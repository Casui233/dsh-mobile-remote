package com.deh.mobile;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.Uri;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;

/**
 * 远程模式的后台通知服务：
 * 直接用设备 token 订阅 PC 插件配置的 ntfy topic，不依赖 ntfy App。
 * 通知走公网 ntfy.sh，所以 Tailscale 断开时仍然能收到任务提醒；
 * 但 /notify 配置刷新需要能连到 PC。
 */
public class NotifyService extends Service {
    private static final String TAG = "DshNotify";
    private static final String ACTION_REFRESH = "com.deh.mobile.REFRESH_NOTIFY";
    private static final String CHANNEL_EVENTS = "dsh_events";
    private static final String CHANNEL_SERVICE = "dsh_notify_service";
    private static final int SERVICE_NOTIFICATION_ID = 1002;
    private static final long REFRESH_INTERVAL_MS = 30L * 60L * 1000L;
    private static final long STREAM_MAX_MS = 25L * 60L * 1000L;

    private static final int REFRESH_OK = 0;
    private static final int REFRESH_UNAUTHORIZED = 1;
    private static final int REFRESH_OTHER = 2;
    private static final int REFRESH_FAILED = 3;

    private static volatile boolean running = false;
    private static volatile String lastError = "";

    private volatile boolean stopping = false;
    private volatile boolean refreshRequested = false;
    private volatile HttpURLConnection activeConnection = null;
    private volatile Thread worker = null;
    private long lastRefreshAt = 0L;

    public static boolean isRunning() {
        return running;
    }

    public static String lastError() {
        return lastError;
    }

    /** 启动（已运行则复用）。 */
    public static void start(Context context) {
        if (context == null) return;
        Intent intent = new Intent(context, NotifyService.class);
        try {
            context.startForegroundService(intent);
        } catch (Throwable t) {
            try {
                context.startService(intent);
            } catch (Throwable ignored) {
            }
        }
    }

    /** 停止后台通知。 */
    public static void stop(Context context) {
        if (context == null) return;
        try {
            context.stopService(new Intent(context, NotifyService.class));
        } catch (Throwable ignored) {
        }
    }

    /** 请求立即刷新 /notify（设置页点击 / 配对后）。 */
    public static void requestRefresh(Context context) {
        if (context == null) return;
        Intent intent = new Intent(context, NotifyService.class);
        intent.setAction(ACTION_REFRESH);
        try {
            context.startForegroundService(intent);
        } catch (Throwable t) {
            try {
                context.startService(intent);
            } catch (Throwable ignored) {
            }
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        running = true;
        lastError = "";
        createChannels();
        Notification notification = buildServiceNotification();
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(SERVICE_NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            } else {
                startForeground(SERVICE_NOTIFICATION_ID, notification);
            }
        } catch (Throwable t) {
            lastError = "前台服务启动失败：" + t;
            Log.w(TAG, lastError);
            stopSelf();
            return;
        }
        startWorker();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_REFRESH.equals(intent.getAction())) {
            refreshRequested = true;
            disconnectActive();
            Thread thread = worker;
            if (thread != null) thread.interrupt();
        }
        if (worker == null || !worker.isAlive()) startWorker();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        stopping = true;
        disconnectActive();
        Thread thread = worker;
        if (thread != null) thread.interrupt();
        worker = null;
        running = false;
        try {
            stopForeground(true);
        } catch (Throwable ignored) {
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // ── 工作线程 ───────────────────────────────────────────────────────────

    private void startWorker() {
        if (worker != null && worker.isAlive()) return;
        worker = new Thread(new Runnable() {
            @Override
            public void run() {
                loop();
            }
        }, "dsh-notify");
        worker.start();
    }

    private void loop() {
        long backoff = 1000L;
        while (!stopping) {
            if (refreshRequested || System.currentTimeMillis() - lastRefreshAt > REFRESH_INTERVAL_MS) {
                refreshRequested = false;
                int result = refreshConfig();
                lastRefreshAt = System.currentTimeMillis();
                if (result == REFRESH_UNAUTHORIZED) {
                    lastError = "设备已撤销，请重新配对";
                    stopSelf();
                    break;
                }
                if (result == REFRESH_OTHER) {
                    lastError = "通知渠道不是 ntfy";
                    stopSelf();
                    break;
                }
            }

            String channel = Prefs.notifyChannel(this);
            String topic = Prefs.notifyTopic(this);
            String endpoint = Prefs.notifyEndpoint(this);
            if (!"ntfy".equals(channel) || topic.isEmpty()) {
                lastError = "等待电脑下发通知配置";
                sleep(30000L);
                continue;
            }

            try {
                connectAndRead(endpoint, topic);
                backoff = 1000L;
            } catch (Throwable t) {
                lastError = t.getMessage() == null ? t.toString() : t.getMessage();
                Log.w(TAG, "通知订阅断开：" + lastError);
            }

            if (stopping) break;
            sleep(backoff);
            backoff = Math.min(backoff * 2L, 60000L);
        }
        running = false;
    }

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
        }
    }

    private void disconnectActive() {
        HttpURLConnection connection = activeConnection;
        activeConnection = null;
        if (connection != null) {
            try {
                connection.disconnect();
            } catch (Throwable ignored) {
            }
        }
    }

    // ── /notify 配置刷新 ───────────────────────────────────────────────────

    private int refreshConfig() {
        String origin = Prefs.remoteOrigin(this);
        String token = Prefs.remoteToken(this);
        if (origin == null || origin.isEmpty() || token == null || token.isEmpty()) {
            return REFRESH_FAILED;
        }
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(origin + "/notify").openConnection();
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(5000);
            connection.setRequestMethod("GET");
            connection.setRequestProperty("Authorization", "Bearer " + token);
            connection.setRequestProperty("Accept", "application/json");
            int code = connection.getResponseCode();
            if (code == 401) return REFRESH_UNAUTHORIZED;
            if (code != 200) return REFRESH_FAILED;
            String text = readAll(connection.getInputStream());
            JSONObject json = new JSONObject(text);
            JSONObject notify = json.optJSONObject("notify");
            if (notify == null) return REFRESH_FAILED;
            String channel = notify.optString("channel", "off");
            if (!"ntfy".equals(channel)) {
                Prefs.clearNotify(this);
                return REFRESH_OTHER;
            }
            String topic = notify.optString("topic", "").trim();
            String endpoint = notify.optString("endpoint", "").trim();
            if (topic.isEmpty()) {
                Prefs.clearNotify(this);
                return REFRESH_OTHER;
            }
            Prefs.setNotifyConfig(this, channel, endpoint, topic, System.currentTimeMillis() / 1000L);
            return REFRESH_OK;
        } catch (Throwable t) {
            Log.w(TAG, "刷新通知配置失败：" + t);
            return REFRESH_FAILED;
        } finally {
            if (connection != null) {
                try {
                    connection.disconnect();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    // ── SSE 订阅 ───────────────────────────────────────────────────────────

    private void connectAndRead(String endpointRaw, String topic) throws Exception {
        String endpoint = endpointRaw == null ? "" : endpointRaw.trim();
        if (endpoint.isEmpty()) endpoint = "https://ntfy.sh";
        while (endpoint.endsWith("/")) endpoint = endpoint.substring(0, endpoint.length() - 1);

        StringBuilder url = new StringBuilder();
        url.append(endpoint).append('/').append(URLEncoder.encode(topic, "UTF-8")).append("/sse");
        String lastId = Prefs.notifyLastId(this);
        long since = Prefs.notifySince(this);
        if (lastId != null && !lastId.isEmpty()) {
            url.append("?since=").append(URLEncoder.encode(lastId, "UTF-8"));
        } else if (since > 0) {
            url.append("?since=").append(since);
        }

        HttpURLConnection connection = (HttpURLConnection) new URL(url.toString()).openConnection();
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(90000);
        connection.setRequestProperty("Accept", "text/event-stream");
        connection.setRequestProperty("Cache-Control", "no-cache");
        connection.setRequestProperty("User-Agent", "DSH-Android");
        int code = connection.getResponseCode();
        if (code != 200) {
            try {
                connection.disconnect();
            } catch (Throwable ignored) {
            }
            throw new IOException("ntfy HTTP " + code);
        }

        activeConnection = connection;
        lastError = "";
        BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream(), "UTF-8"));
        StringBuilder data = new StringBuilder();
        long startedAt = System.currentTimeMillis();
        try {
            String line;
            while (!stopping) {
                line = reader.readLine();
                if (line == null) break;
                if (System.currentTimeMillis() - startedAt > STREAM_MAX_MS) break;
                if (line.isEmpty()) {
                    if (data.length() > 0) {
                        handleSseData(data.toString());
                        data.setLength(0);
                    }
                    continue;
                }
                if (line.startsWith("data:")) {
                    if (data.length() > 0) data.append('\n');
                    data.append(line.substring(5).trim());
                }
            }
        } finally {
            activeConnection = null;
            try {
                reader.close();
            } catch (Throwable ignored) {
            }
            try {
                connection.disconnect();
            } catch (Throwable ignored) {
            }
        }
    }

    private void handleSseData(String payload) {
        try {
            JSONObject json = new JSONObject(payload);
            String event = json.optString("event", "message");
            if (!"message".equals(event)) return;
            String id = json.optString("id", "");
            if (!id.isEmpty() && id.equals(Prefs.notifyLastId(this))) return;
            String title = json.optString("title", "DSH");
            String message = json.optString("message", "");
            if (message.isEmpty()) message = title;
            postEvent(id, title, message);
            if (!id.isEmpty()) Prefs.setNotifyLastId(this, id);
        } catch (Throwable t) {
            Log.w(TAG, "忽略无法解析的 ntfy 消息：" + t);
        }
    }

    // ── 通知 ───────────────────────────────────────────────────────────────

    private void createChannels() {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager == null) return;
        NotificationChannel events = new NotificationChannel(CHANNEL_EVENTS, "DSH 任务提醒", NotificationManager.IMPORTANCE_HIGH);
        events.setDescription("任务完成、出错、等待批准或回答");
        events.setShowBadge(true);
        NotificationChannel service = new NotificationChannel(CHANNEL_SERVICE, "DSH 通知服务", NotificationManager.IMPORTANCE_LOW);
        service.setDescription("保持远程任务提醒在后台接收");
        service.setShowBadge(false);
        manager.createNotificationChannel(events);
        manager.createNotificationChannel(service);
    }

    private Notification buildServiceNotification() {
        Notification.Builder builder = new Notification.Builder(this, CHANNEL_SERVICE)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle("DSH 通知已连接")
                .setContentText("任务提醒会在后台接收")
                .setOngoing(true)
                .setContentIntent(contentIntent(100));
        return builder.build();
    }

    private void postEvent(String messageId, String title, String message) {
        String safeTitle = title == null || title.isEmpty() ? "DSH" : title;
        String safeMessage = message == null ? "" : message;
        if (safeMessage.length() > 300) safeMessage = safeMessage.substring(0, 300) + "…";
        int notificationId = 20000 + (Math.abs((messageId == null || messageId.isEmpty()
                ? String.valueOf(System.currentTimeMillis())
                : messageId).hashCode()) % 100000);

        Notification.Builder builder = new Notification.Builder(this, CHANNEL_EVENTS)
                .setSmallIcon(android.R.drawable.stat_notify_chat)
                .setContentTitle(safeTitle)
                .setContentText(safeMessage)
                .setStyle(new Notification.BigTextStyle().bigText(safeMessage))
                .setAutoCancel(true)
                .setVisibility(Notification.VISIBILITY_PRIVATE)
                .setContentIntent(contentIntent(notificationId));

        Notification publicVersion = new Notification.Builder(this, CHANNEL_EVENTS)
                .setSmallIcon(android.R.drawable.stat_notify_chat)
                .setContentTitle("DSH")
                .setContentText("有新通知")
                .build();
        builder.setPublicVersion(publicVersion);

        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) manager.notify(notificationId, builder.build());
    }

    private PendingIntent contentIntent(int requestCode) {
        Intent intent = new Intent(this, MainActivity.class);
        intent.setAction(Intent.ACTION_VIEW);
        intent.setData(Uri.parse("deh://open"));
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return PendingIntent.getActivity(this, requestCode, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private String readAll(InputStream input) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int count;
        while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
        input.close();
        return output.toString("UTF-8");
    }
}
