package com.deh.mobile;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.Manifest;
import android.content.DialogInterface;
import android.content.ClipData;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.PackageInfo;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import android.content.res.Configuration;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.Parcelable;
import android.provider.Settings;
import android.text.InputType;
import android.text.method.ScrollingMovementMethod;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.DownloadListener;
import android.webkit.JavascriptInterface;
import android.webkit.URLUtil;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.GridView;
import android.widget.PopupMenu;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.io.FileOutputStream;

import org.json.JSONObject;

/**
 * DeepSeek Harness 手机端主界面：
 * 本机解压 Linux 运行时、安装 DSH、启动本地 dsh web，用 WebView 打开。
 * 支持横竖屏自适应、会话内文件上传、内部存储/应用私有目录双工作区根。
 */
public class MainActivity extends Activity {
    private static final int REQUEST_FILE = 1001;
    private static final int REQUEST_SCAN_DIALOG = 2001;
    private static final int REQUEST_SCAN_SIDEBAR = 2002;

    private Runtime runtime;
    private FrameLayout root;
    private LinearLayout setupView;
    private TextView statusText;
    private TextView workText;
    private TextView logText;
    private ScrollView logScroll;
    private WebView webView;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private boolean busy;
    private ValueCallback<Uri[]> fileCallback;
    private String lastChooserInfo = "";
    private boolean resumed;
    private AlertDialog settingsDialog;
    private Runnable settingsTicker;
    private String pendingUrl;
    private String hostUrl;
    private TextView floatingSettings;
    private android.widget.EditText remoteOriginField;
    private android.widget.EditText remoteCodeField;
    private AlertDialog pickerDialog;
    private static MainActivity current;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        runtime = ((DshApp) getApplication()).runtime;
        diag("=== app start, uid=" + android.os.Process.myUid() + " pid=" + android.os.Process.myPid() + " ===");
        if (Build.VERSION.SDK_INT >= 33) {
            try {
                requestPermissions(new String[] { android.Manifest.permission.POST_NOTIFICATIONS }, 1002);
            } catch (Exception ignored) {
            }
        }
        requestStoragePermission();
        buildUi();
        loadHistoryLog();
        ShizukuBridge.init(this);
        DhizukuBridge.init(this);
        LocalBridge.start(this);
        writeShizukuFiles();
        boolean handledIntent = handleIntent(getIntent());
        if (isRemoteMode()) {
            status("远程模式");
            workText.setText("连接方式：远程\n电脑上的 DSH（本机运行时不会启动）");
            if (!handledIntent) {
                enterRemote();
            }
            startNotifyIfPaired();
        } else {
            try {
                startForegroundService(new Intent(this, DshService.class));
            } catch (Exception ignored) {
            }
            bootstrap();
        }
    }

    private boolean isRemoteMode() {
        return "remote".equals(Prefs.connMode(this));
    }

    /** 远程模式且已配对时，启动 App 自己的 ntfy 订阅服务（服务内部会刷新 /notify）。 */
    private void startNotifyIfPaired() {
        if (!isRemoteMode()) return;
        if (Prefs.remoteToken(this).isEmpty()) return;
        NotifyService.start(this);
    }

    private String notifyStatusLabel() {
        if (!isRemoteMode()) return "未启用（远程模式才需要）";
        String channel = Prefs.notifyChannel(this);
        if (channel == null || channel.isEmpty() || "off".equals(channel)) return "未配置";
        if (!"ntfy".equals(channel)) return "由 " + channel + " 渠道发送";
        String topic = Prefs.notifyTopic(this);
        String masked = topic == null || topic.isEmpty()
                ? "未配置"
                : (topic.length() > 4 ? "…" + topic.substring(topic.length() - 4) : topic);
        if (NotifyService.isRunning()) return "ntfy · " + masked + " · 接收中";
        String error = NotifyService.lastError();
        return "ntfy · " + masked + (error == null || error.isEmpty() ? " · 未连接" : " · " + error);
    }

    private void openNotificationSettings() {
        try {
            Intent intent = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS);
            intent.putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName());
            startActivity(intent);
        } catch (Throwable t) {
            Toast.makeText(this, "无法打开通知设置：" + t.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private boolean hasNotificationPermission() {
        if (Build.VERSION.SDK_INT < 33) return true;
        return checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean isLandscape() {
        return getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
    }

    private void buildUi() {
        root = new FrameLayout(this);
        setupView = new LinearLayout(this);
        setupView.setOrientation(isLandscape() ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        setupView.setBackgroundColor(0xFF0F1115);
        int pad = isLandscape() ? dp(14) : dp(18);
        setupView.setPadding(pad, pad, pad, pad);

        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        info.setBackgroundResource(R.drawable.card_bg);
        info.setPadding(dp(18), dp(16), dp(18), dp(16));

        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        ImageView icon = new ImageView(this);
        icon.setImageResource(R.mipmap.ic_launcher);
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(dp(30), dp(30));
        iconLp.rightMargin = dp(10);
        titleRow.addView(icon, iconLp);

        LinearLayout titleCol = new LinearLayout(this);
        titleCol.setOrientation(LinearLayout.VERTICAL);
        TextView title = new TextView(this);
        title.setText("DeepSeek Harness");
        title.setTextSize(20);
        title.setTextColor(0xFFE8EAED);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        titleCol.addView(title);
        TextView subtitle = new TextView(this);
        subtitle.setText("手机本地运行 · 独立版");
        subtitle.setTextSize(12);
        subtitle.setTextColor(0xFF9AA3B2);
        titleCol.addView(subtitle);
        titleRow.addView(titleCol);
        info.addView(titleRow);

        statusText = new TextView(this);
        statusText.setTextSize(13);
        statusText.setTextColor(0xFF8AB4F8);
        statusText.setPadding(0, dp(12), 0, 0);
        info.addView(statusText);

        workText = new TextView(this);
        workText.setTextSize(11);
        workText.setTextColor(0xFF6B7280);
        workText.setPadding(0, dp(6), 0, 0);
        workText.setText("工作区：" + runtime.workModeLabel() + "\n" + runtime.workRoot.getAbsolutePath());
        info.addView(workText);

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        btnLp.topMargin = dp(12);
        btnLp.rightMargin = dp(8);

        Button reset = new Button(this);
        reset.setText("重置");
        reset.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                confirmReset();
            }
        });
        buttons.addView(reset, btnLp);

        Button settings = new Button(this);
        settings.setText("设置");
        settings.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showSettings();
            }
        });
        LinearLayout.LayoutParams btnLp2 = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        btnLp2.topMargin = dp(12);
        buttons.addView(settings, btnLp2);
        info.addView(buttons);

        logScroll = new ScrollView(this);
        logScroll.setBackgroundColor(0xFF0B0D11);
        logText = new TextView(this);
        logText.setTextSize(10.5f);
        logText.setTextColor(0xFFB0B6C0);
        logText.setTypeface(android.graphics.Typeface.MONOSPACE);
        logText.setTextIsSelectable(true);
        logText.setMovementMethod(new ScrollingMovementMethod());
        logText.setPadding(dp(10), dp(8), dp(10), dp(8));
        logScroll.addView(logText);

        if (isLandscape()) {
            LinearLayout.LayoutParams infoLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f);
            infoLp.rightMargin = dp(12);
            setupView.addView(info, infoLp);
            setupView.addView(logScroll, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1.4f));
        } else {
            setupView.addView(info, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
            LinearLayout.LayoutParams logLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
            logLp.topMargin = dp(12);
            setupView.addView(logScroll, logLp);
        }

        root.addView(setupView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        webView = new WebView(this);
        webView.setVisibility(View.GONE);
        root.addView(webView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        setContentView(root);
    }

    private void diag(String message) {
        try {
            File dir = new File("/storage/emulated/0/DEH");
            if (!dir.exists()) dir.mkdirs();
            FileOutputStream out = new FileOutputStream(new File(dir, "diag.log"), true);
            String line = new java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US).format(new java.util.Date())
                    + " " + message + "\n";
            out.write(line.getBytes("UTF-8"));
            out.close();
        } catch (Throwable ignored) {
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void bootstrap() {
        if (busy) return;
        if (runtime.isHostRunning() && runtime.lastUrl != null) {
            hostUrl = runtime.lastUrl;
            showWeb(runtime.lastUrl);
            return;
        }
        if (runtime.isInstalled()) {
            startHostWork();
        } else if (runtime.isPrepared()) {
            runInstall();
        } else {
            File backup = new File("/storage/emulated/0/DEH/备份/dsh-restore.tar.gz");
            if (backup.isFile()) {
                runRestore(backup);
            } else {
                runPrepare();
            }
        }
    }

    private void runRestore(final File backup) {
        busy = true;
        status("检测到运行时备份，正在恢复（" + (backup.length() / 1024 / 1024) + " MB）...");
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    runtime.restoreFromBackup(backup, logger);
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            busy = false;
                            startHostWork();
                        }
                    });
                } catch (final Exception e) {
                    fail("恢复备份失败", e);
                }
            }
        }, "dsh-restore").start();
    }

    private void runPrepare() {
        busy = true;
        status("正在准备运行环境（首次约 1-3 分钟）...");
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    runtime.prepare(logger);
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            busy = false;
                            runInstall();
                        }
                    });
                } catch (final Exception e) {
                    fail("准备运行环境失败", e);
                }
            }
        }, "dsh-prepare").start();
    }

    private void runInstall() {
        busy = true;
        status("正在安装依赖（需要联网，约 5-20 分钟）...");
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    runtime.install(logger);
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            busy = false;
                            startHostWork();
                        }
                    });
                } catch (final Exception e) {
                    fail("安装失败", e);
                }
            }
        }, "dsh-install").start();
    }

    private void startHostWork() {
        busy = true;
        status("正在启动 DeepSeek Harness...");
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    runtime.startHost(logger, new Runtime.UrlListener() {
                        @Override
                        public void onUrl(final String url) {
                            mainHandler.post(new Runnable() {
                                @Override
                                public void run() {
                                    showWeb(url);
                                }
                            });
                        }
                    });
                } catch (final Exception e) {
                    fail("启动失败", e);
                }
            }
        }, "dsh-start").start();
    }

    private void showWeb(String url) {
        if (!resumed) {
            pendingUrl = url;
            return;
        }
        busy = false;
        status("已启动");
        setupView.setVisibility(View.GONE);
        webView.setVisibility(View.VISIBLE);
        configureWebView();
        hostUrl = url;
        runtime.lastUrl = url;
        diag("showWeb url=" + url + " resumed=" + resumed + " w=" + webView.getWidth() + " h=" + webView.getHeight());
        webView.loadUrl(url);
        applyZoom();
        try {
            webView.onResume();
            webView.requestLayout();
            webView.postInvalidate();
        } catch (Throwable ignored) {
        }
        webView.postDelayed(new Runnable() {
            @Override
            public void run() {
                try {
                    webView.onPause();
                    webView.onResume();
                    webView.postInvalidate();
                } catch (Throwable ignored) {
                }
                diag("render-reset done, w=" + webView.getWidth() + " h=" + webView.getHeight());
                checkWebBlank();
            }
        }, 2500);
        webView.postDelayed(new Runnable() {
            @Override
            public void run() {
                checkWebBlank();
            }
        }, 7000);
        try {
            startForegroundService(new Intent(this, DshService.class));
        } catch (Exception ignored) {
        }
        Toast.makeText(this, "DeepSeek Harness 已启动", Toast.LENGTH_SHORT).show();
    }

    // ── 远程模式：连电脑上的 DSH ─────────────────────────────────────────────

    /** 补全 scheme/端口，去掉末尾斜杠；不合法返回 null。 */
    private static String normalizeOrigin(String raw) {
        if (raw == null) {
            return null;
        }
        String value = raw.trim();
        if (value.isEmpty()) {
            return null;
        }
        if (!value.startsWith("http://") && !value.startsWith("https://")) {
            value = "http://" + value;
        }
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        try {
            URL url = new URL(value);
            String host = url.getHost();
            if (host == null || host.isEmpty()) {
                return null;
            }
            int port = url.getPort();
            if (port <= 0) {
                port = "https".equals(url.getProtocol()) ? 443 : 80;
            }
            return url.getProtocol() + "://" + host + ":" + port;
        } catch (Exception e) {
            return null;
        }
    }

    private String[] parsePairPayload(String raw) {
        if (raw == null) return null;
        try {
            Uri uri = Uri.parse(raw.trim());
            if (!"deh".equals(uri.getScheme()) || !"pair".equals(uri.getHost())) return null;
            String origin = normalizeOrigin(uri.getQueryParameter("origin"));
            String code = uri.getQueryParameter("code");
            if (origin == null || code == null) return null;
            code = code.replaceAll("\\D", "");
            if (code.length() != 6) return null;
            return new String[] { origin, code };
        } catch (Throwable ignored) {
            return null;
        }
    }

    private void enterRemote() {
        final String origin = normalizeOrigin(Prefs.remoteOrigin(this));
        final String token = Prefs.remoteToken(this);
        if (origin == null || token == null || token.isEmpty()) {
            showRemoteDialog();
            return;
        }
        status("远程模式：正在连接 " + origin);
        new Thread(new Runnable() {
            @Override
            public void run() {
                final String error = probeRemote(origin);
                mainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (error == null) {
                            applyRemoteCookie(origin, token);
                            showRemote(origin);
                        } else {
                            showRemoteFailure(origin, error);
                        }
                    }
                });
            }
        }, "dsh-remote-probe").start();
    }

    private String probeRemote(String origin) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(origin + "/health").openConnection();
            conn.setConnectTimeout(4000);
            conn.setReadTimeout(4000);
            conn.setRequestMethod("GET");
            int code = conn.getResponseCode();
            if (code != 200) {
                return "电脑返回 HTTP " + code;
            }
            String text = readStream(conn.getInputStream());
            JSONObject json = new JSONObject(text);
            return json.optBoolean("ok") ? null : "电脑上的桥接还没就绪";
        } catch (Exception e) {
            String message = e.getMessage();
            return message == null ? e.toString() : message;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private void applyRemoteCookie(String origin, String token) {
        try {
            CookieManager manager = CookieManager.getInstance();
            manager.setAcceptCookie(true);
            manager.setCookie(origin, "dshmb=" + token + "; Path=/; Max-Age=31536000");
            manager.flush();
        } catch (Throwable ignored) {
        }
    }

    private void showRemote(String origin) {
        busy = false;
        String token = Prefs.remoteToken(this);
        if (token != null && !token.isEmpty()) {
            applyRemoteCookie(origin, token);
        }
        setupView.setVisibility(View.GONE);
        webView.setVisibility(View.VISIBLE);
        configureWebView();
        hostUrl = origin;
        status("远程模式：" + origin);
        diag("showRemote url=" + origin);
        webView.loadUrl(origin);
        applyZoom();
        try {
            webView.onResume();
            webView.requestLayout();
            webView.postInvalidate();
        } catch (Throwable ignored) {
        }
        Toast.makeText(this, "已连接电脑上的 DSH", Toast.LENGTH_SHORT).show();
    }

    private void showRemoteFailure(final String origin, String error) {
        status("连不上电脑：" + error);
        new AlertDialog.Builder(this)
                .setTitle("连不上电脑")
                .setMessage("地址：" + origin + "\n原因：" + error
                        + "\n\n检查：1) 电脑上的 DSH 正在运行；2) 手机和电脑在同一 Wi-Fi 或 Tailscale；"
                        + "3) 设置里的地址和端口对；4) 电脑防火墙放行该端口。")
                .setPositiveButton("重试", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        enterRemote();
                    }
                })
                .setNeutralButton("仍然加载", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        showRemote(origin);
                    }
                })
                .setNegativeButton("设置", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        showSettings();
                    }
                })
                .show();
    }

    private void showRemoteDialog() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(8), dp(20), dp(4));

        TextView hint = new TextView(this);
        hint.setTextSize(12);
        hint.setTextColor(0xFF9AA3B2);
        hint.setText("在电脑上的 DSH「设置 → 手机连接」里生成配对码，填到下面。");
        box.addView(hint);

        final android.widget.EditText originInput = new android.widget.EditText(this);
        originInput.setHint("http://192.168.10.101:8765");
        originInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        originInput.setText(Prefs.remoteOrigin(this));
        box.addView(originInput);
        remoteOriginField = originInput;

        final android.widget.EditText codeInput = new android.widget.EditText(this);
        codeInput.setHint("6 位配对码");
        codeInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        box.addView(codeInput);
        remoteCodeField = codeInput;

        ImageButton scan = new ImageButton(this);
        scan.setImageResource(R.drawable.ic_scan);
        scan.setBackgroundResource(R.drawable.scan_fab_bg);
        scan.setPadding(dp(10), dp(10), dp(10), dp(10));
        scan.setContentDescription("扫码填入地址和配对码");
        scan.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivityForResult(new Intent(MainActivity.this, QrScanActivity.class), REQUEST_SCAN_DIALOG);
            }
        });
        LinearLayout.LayoutParams scanLp = new LinearLayout.LayoutParams(dp(44), dp(44));
        scanLp.gravity = Gravity.CENTER_HORIZONTAL;
        scanLp.topMargin = dp(8);
        box.addView(scan, scanLp);

        TextView scanNote = new TextView(this);
        scanNote.setText("扫码自动填入");
        scanNote.setTextSize(11);
        scanNote.setTextColor(0xFF9AA3B2);
        scanNote.setGravity(Gravity.CENTER_HORIZONTAL);
        scanNote.setPadding(0, dp(4), 0, dp(2));
        box.addView(scanNote);

        new AlertDialog.Builder(this)
                .setTitle("连接电脑上的 DSH")
                .setView(box)
                .setPositiveButton("配对", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        String origin = normalizeOrigin(originInput.getText().toString());
                        String code = codeInput.getText().toString().replaceAll("\\D", "");
                        if (origin == null) {
                            Toast.makeText(MainActivity.this, "地址不对，示例：192.168.10.101:8765", Toast.LENGTH_LONG).show();
                            showRemoteDialog();
                            return;
                        }
                        if (code.length() != 6) {
                            Toast.makeText(MainActivity.this, "配对码是 6 位数字", Toast.LENGTH_LONG).show();
                            showRemoteDialog();
                            return;
                        }
                        Prefs.setConnMode(MainActivity.this, "remote");
                        Prefs.setRemoteOrigin(MainActivity.this, origin);
                        pairRemote(origin, code);
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void pairRemote(final String origin, final String code) {
        status("正在配对 " + origin + " …");
        new Thread(new Runnable() {
            @Override
            public void run() {
                String error = null;
                try {
                    HttpURLConnection conn = (HttpURLConnection) new URL(origin + "/pair").openConnection();
                    conn.setConnectTimeout(5000);
                    conn.setReadTimeout(10000);
                    conn.setRequestMethod("POST");
                    conn.setDoOutput(true);
                    conn.setRequestProperty("Content-Type", "application/json");
                    JSONObject body = new JSONObject();
                    body.put("code", code);
                    body.put("name", deviceName());
                    body.put("platform", "android");
                    OutputStream out = conn.getOutputStream();
                    out.write(body.toString().getBytes("UTF-8"));
                    out.close();
                    int status = conn.getResponseCode();
                    InputStream stream = status >= 400 ? conn.getErrorStream() : conn.getInputStream();
                    String text = stream == null ? "" : readStream(stream);
                    conn.disconnect();
                    JSONObject json = new JSONObject(text);
                    if (status == 200 && json.optBoolean("ok")) {
                        final String token = json.optString("token", "");
                        final String deviceId = json.optString("deviceId", "");
                        String returned = normalizeOrigin(json.optString("origin", origin));
                        final String useOrigin = returned == null ? origin : returned;
                        JSONObject notify = json.optJSONObject("notify");
                        final String notifyChannel = notify == null ? "" : notify.optString("channel", "");
                        final String notifyEndpoint = notify == null ? "" : notify.optString("endpoint", "");
                        final String notifyTopic = notify == null ? "" : notify.optString("topic", "");
                        if (token.isEmpty()) {
                            throw new Exception("电脑没有返回设备 token");
                        }
                        mainHandler.post(new Runnable() {
                            @Override
                            public void run() {
                                Prefs.setRemoteDevice(MainActivity.this, useOrigin, deviceId, token, deviceName());
                                if ("ntfy".equals(notifyChannel) && !notifyTopic.isEmpty()) {
                                    Prefs.setNotifyConfig(MainActivity.this, notifyChannel, notifyEndpoint, notifyTopic,
                                            System.currentTimeMillis() / 1000L);
                                } else {
                                    Prefs.clearNotify(MainActivity.this);
                                }
                                applyRemoteCookie(useOrigin, token);
                                Toast.makeText(MainActivity.this, "配对成功", Toast.LENGTH_SHORT).show();
                                enterRemote();
                                startNotifyIfPaired();
                            }
                        });
                        return;
                    }
                    error = json.optString("error", "HTTP " + status);
                } catch (Exception e) {
                    error = e.getMessage() == null ? e.toString() : e.getMessage();
                }
                final String message = error;
                mainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        status("配对失败：" + message);
                        new AlertDialog.Builder(MainActivity.this)
                                .setTitle("配对失败")
                                .setMessage(message)
                                .setPositiveButton("重试", new DialogInterface.OnClickListener() {
                                    @Override
                                    public void onClick(DialogInterface dialog, int which) {
                                        showRemoteDialog();
                                    }
                                })
                                .setNegativeButton("关闭", null)
                                .show();
                    }
                });
            }
        }, "dsh-remote-pair").start();
    }

    private static String deviceName() {
        String model = Build.MODEL;
        return model == null || model.isEmpty() ? "Android" : model;
    }

    private String readStream(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int count;
        while ((count = in.read(buffer)) != -1) {
            out.write(buffer, 0, count);
        }
        in.close();
        return out.toString("UTF-8");
    }

    private boolean isRemoteHost(String host) {
        String origin = Prefs.remoteOrigin(this);
        if (origin == null || origin.isEmpty()) {
            return false;
        }
        try {
            return host.equalsIgnoreCase(new URL(origin).getHost());
        } catch (Exception e) {
            return false;
        }
    }

    private void switchConnMode() {
        if (settingsDialog != null && settingsDialog.isShowing()) {
            settingsDialog.dismiss();
        }
        if (isRemoteMode()) {
            NotifyService.stop(this);
            Prefs.setConnMode(this, "local");
            recreate();
        } else {
            Prefs.setConnMode(this, "remote");
            if (Prefs.remoteToken(this).isEmpty()) {
                showRemoteDialog();
            } else {
                enterRemote();
                startNotifyIfPaired();
            }
        }
    }

    private void applyZoom() {
        if (webView == null) return;
        int scale = Prefs.deskScale(this);
        try {
            webView.setInitialScale(scale);
        } catch (Throwable ignored) {
        }
        try {
            webView.evaluateJavascript("document.documentElement.style.zoom='" + scale + "%'", null);
        } catch (Throwable ignored) {
        }
    }

    private void configureWebView() {
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setSupportZoom(true);
        settings.setBuiltInZoomControls(true);
        settings.setDisplayZoomControls(false);
        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(true);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        webView.setInitialScale(Prefs.deskScale(this));

        CookieManager cookieManager = CookieManager.getInstance();
        cookieManager.setAcceptCookie(true);
        cookieManager.setAcceptThirdPartyCookies(webView, true);
        webView.addJavascriptInterface(new ScanBridge(), "DSHMobile");

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                injectScanUi(view);
            }

            @Override
            public boolean onRenderProcessGone(WebView view, android.webkit.RenderProcessGoneDetail detail) {
                root.removeView(view);
                view.destroy();
                webView = new WebView(MainActivity.this);
                root.addView(webView, new FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
                configureWebView();
                if (hostUrl != null) webView.loadUrl(hostUrl);
                return true;
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String host = request.getUrl().getHost();
                if (host != null && (host.equals("127.0.0.1") || host.equals("localhost") || isRemoteHost(host))) {
                    return false;
                }
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, request.getUrl()));
                    return true;
                } catch (Exception e) {
                    return false;
                }
            }
        });

        webView.setDownloadListener(new DownloadListener() {
            @Override
            public void onDownloadStart(String url, String userAgent, String contentDisposition, String mimetype, long contentLength) {
                try {
                    String fileName = downloadFileName(url, contentDisposition, mimetype);
                    fileName = fileName.replaceAll("[\\\\/:*?\"<>|]", "_");
                    DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url));
                    if (mimetype != null && !mimetype.isEmpty()) request.setMimeType(mimetype);
                    String cookie = CookieManager.getInstance().getCookie(url);
                    if (cookie != null && !cookie.isEmpty()) request.addRequestHeader("Cookie", cookie);
                    if (userAgent != null && !userAgent.isEmpty()) request.addRequestHeader("User-Agent", userAgent);
                    request.setTitle(fileName);
                    request.setDescription("DSH 文件传输");
                    request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                    request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "DSH/" + fileName);
                    DownloadManager manager = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
                    if (manager == null) throw new IllegalStateException("DownloadManager 不可用");
                    manager.enqueue(request);
                    Toast.makeText(MainActivity.this, "开始下载：" + fileName, Toast.LENGTH_SHORT).show();
                } catch (Throwable t) {
                    Toast.makeText(MainActivity.this, "下载失败：" + t.getMessage(), Toast.LENGTH_LONG).show();
                }
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (fileCallback != null) {
                    fileCallback.onReceiveValue(null);
                }
                fileCallback = callback;
                try {
                    String[] accept = params.getAcceptTypes();
                    String acceptText = accept == null ? "" : String.join(",", accept);
                    lastChooserInfo = "mode=" + params.getMode() + " accept=" + acceptText;
                    diag("fileChooser mode=" + params.getMode() + " accept=" + acceptText);
                    Intent intent = new Intent(MainActivity.this, FilePickerActivity.class);
                    intent.putExtra(FilePickerActivity.EXTRA_MULTIPLE,
                            params.getMode() == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE);
                    try {
                        startActivityForResult(intent, REQUEST_FILE);
                    } catch (Throwable t) {
                        diag("custom file picker failed: " + t);
                        Intent fallback = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                        fallback.addCategory(Intent.CATEGORY_OPENABLE);
                        fallback.setType("*/*");
                        fallback.putExtra(Intent.EXTRA_ALLOW_MULTIPLE,
                                params.getMode() == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE);
                        startActivityForResult(fallback, REQUEST_FILE);
                    }
                } catch (Exception e) {
                    fileCallback = null;
                    Toast.makeText(MainActivity.this, "无法打开文件选择器：" + e.getMessage(), Toast.LENGTH_LONG).show();
                }
                return true;
            }
        });
    }

    private class ScanBridge {
        @JavascriptInterface
        public void scanPair() {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    startActivityForResult(new Intent(MainActivity.this, QrScanActivity.class), REQUEST_SCAN_SIDEBAR);
                }
            });
        }

        @JavascriptInterface
        public boolean isRemote() {
            return isRemoteMode();
        }
    }

    /** 在 DSH 网页左侧栏注入扫码按钮；浏览器里没有 DSHMobile，不会显示。 */
    private void injectScanUi(final WebView view) {
        if (view == null) return;
        try (InputStream input = getAssets().open("floating-menu.js")) {
            String js = readStream(input);
            if (!js.isEmpty()) view.evaluateJavascript(js, null);
        } catch (Throwable t) {
            diag("floating menu load failed: " + t);
        }
    }

    private String appVersionName() {
        try {
            PackageInfo info = getPackageManager().getPackageInfo(getPackageName(), 0);
            return info == null ? "" : String.valueOf(info.versionName);
        } catch (Throwable ignored) {
            return "";
        }
    }

    /** 把文件选择结果上传到 PC 共享目录，方便在没有 adb 的情况下诊断。 */
    private void uploadPickerDiag(final String text) {
        final String origin = Prefs.remoteOrigin(this);
        final String token = Prefs.remoteToken(this);
        if (origin == null || origin.isEmpty() || token == null || token.isEmpty()) return;
        new Thread(new Runnable() {
            @Override
            public void run() {
                HttpURLConnection connection = null;
                try {
                    byte[] body = text.getBytes("UTF-8");
                    connection = (HttpURLConnection) new URL(origin + "/files/api/upload?dir=&name=file-picker-diag.txt").openConnection();
                    connection.setConnectTimeout(5000);
                    connection.setReadTimeout(10000);
                    connection.setRequestMethod("POST");
                    connection.setDoOutput(true);
                    connection.setFixedLengthStreamingMode(body.length);
                    connection.setRequestProperty("Authorization", "Bearer " + token);
                    connection.setRequestProperty("X-DSH-Files", "1");
                    connection.setRequestProperty("Content-Type", "text/plain; charset=utf-8");
                    OutputStream out = connection.getOutputStream();
                    out.write(body);
                    out.close();
                    int code = connection.getResponseCode();
                    diag("picker diag upload HTTP " + code);
                } catch (Throwable t) {
                    diag("picker diag upload failed: " + t);
                } finally {
                    if (connection != null) connection.disconnect();
                }
            }
        }, "picker-diag").start();
    }

    private String downloadFileName(String url, String contentDisposition, String mimeType) {
        String queryName = null;
        try {
            String path = Uri.parse(url).getQueryParameter("path");
            if (path != null) {
                int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
                queryName = slash >= 0 ? path.substring(slash + 1) : path;
            }
        } catch (Throwable ignored) {
        }
        String name = fileNameFromDisposition(contentDisposition);
        String lower = name == null ? "" : name.toLowerCase(Locale.US);
        boolean missingExt = lower.isEmpty() || lower.endsWith(".bin") || !lower.contains(".");
        if ((name == null || name.isEmpty() || missingExt)
                && queryName != null && !queryName.isEmpty()
                && (name == null || name.isEmpty() || !lower.endsWith(".bin") || queryName.contains("."))) {
            if (missingExt && queryName.contains(".")) name = queryName;
        }
        if (name == null || name.isEmpty()) name = URLUtil.guessFileName(url, contentDisposition, mimeType);
        if (name == null || name.isEmpty()) name = "dsh-download";
        lower = name.toLowerCase(Locale.US);
        if (lower.endsWith(".bin") || !lower.contains(".")) {
            String ext = extensionForMime(mimeType);
            if (ext != null) {
                if (lower.endsWith(".bin")) name = name.substring(0, name.length() - 4) + ext;
                else name += ext;
            }
        }
        return name;
    }

    private String fileNameFromDisposition(String contentDisposition) {
        if (contentDisposition == null || contentDisposition.isEmpty()) return null;
        String lower = contentDisposition.toLowerCase(Locale.US);
        int star = lower.indexOf("filename*=");
        if (star >= 0) {
            String value = contentDisposition.substring(star + 10).trim();
            int semi = value.indexOf(';');
            if (semi >= 0) value = value.substring(0, semi);
            int quote = value.indexOf("''");
            if (quote >= 0) value = value.substring(quote + 2);
            try {
                return URLDecoder.decode(value.trim(), "UTF-8");
            } catch (Throwable ignored) {
            }
        }
        int plain = lower.indexOf("filename=");
        if (plain >= 0) {
            String value = contentDisposition.substring(plain + 9).trim();
            int semi = value.indexOf(';');
            if (semi >= 0) value = value.substring(0, semi);
            return value.replace("\"", "").trim();
        }
        return null;
    }

    private String extensionForMime(String mimeType) {
        if (mimeType == null) return null;
        String mime = mimeType.toLowerCase(Locale.US);
        if (mime.contains("android.package-archive")) return ".apk";
        if (mime.contains("application/pdf")) return ".pdf";
        if (mime.contains("wordprocessingml.document")) return ".docx";
        if (mime.contains("msword")) return ".doc";
        if (mime.contains("spreadsheetml.sheet")) return ".xlsx";
        if (mime.contains("ms-excel")) return ".xls";
        if (mime.contains("presentationml.presentation")) return ".pptx";
        if (mime.contains("ms-powerpoint")) return ".ppt";
        if (mime.contains("image/png")) return ".png";
        if (mime.contains("image/jpeg")) return ".jpg";
        if (mime.contains("image/webp")) return ".webp";
        if (mime.contains("video/mp4")) return ".mp4";
        if (mime.contains("audio/mpeg")) return ".mp3";
        if (mime.contains("application/zip")) return ".zip";
        if (mime.contains("text/plain")) return ".txt";
        if (mime.contains("application/json")) return ".json";
        return null;
    }

    private void checkWebBlank() {
        if (webView == null || webView.getVisibility() != View.VISIBLE) return;
        String current = webView.getUrl();
        if (current == null || current.isEmpty()) {
            if (hostUrl != null) webView.loadUrl(hostUrl);
            return;
        }
        try {
            webView.evaluateJavascript(
                    "(function(){try{return (document.body&&document.body.innerText?document.body.innerText.length:0)}catch(e){return -1}})()",
                    new ValueCallback<String>() {
                        @Override
                        public void onReceiveValue(String value) {
                            int length = -1;
                            try {
                                length = Integer.parseInt(value == null ? "-1" : value.trim());
                            } catch (Throwable ignored) {
                            }
                            int w = webView.getWidth();
                            int h = webView.getHeight();
                            int ch = webView.getContentHeight();
                            diag("checkBlank text=" + length + " w=" + w + " h=" + h + " contentH=" + ch);
                            if (hostUrl != null && (length >= 0 && length < 80) || (w <= 0 || h <= 0)) {
                                diag("reload triggered");
                                webView.loadUrl(hostUrl);
                            }
                        }
                    });
        } catch (Throwable ignored) {
        }
    }

    private void status(final String text) {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                if (statusText != null) statusText.setText(text);
            }
        });
    }

    private final Runtime.LogListener logger = new Runtime.LogListener() {
        @Override
        public void onLog(final String line) {
            appendLog(line);
            mainHandler.post(new Runnable() {
                @Override
                public void run() {
                    if (logText == null) return;
                    logText.append(line + "\n");
                    if (logText.getLineCount() > 500) {
                        CharSequence all = logText.getText();
                        int cut = all.length() / 2;
                        logText.setText(all.subSequence(cut, all.length()));
                    }
                    if (logScroll != null) {
                        logScroll.post(new Runnable() {
                            @Override
                            public void run() {
                                logScroll.fullScroll(View.FOCUS_DOWN);
                            }
                        });
                    }
                }
            });
        }
    };

    private void appendLog(String line) {
        try {
            File dir = new File(getFilesDir(), "dsh");
            if (!dir.exists()) dir.mkdirs();
            File file = new File(dir, "app.log");
            if (file.length() > 262144) file.delete();
            FileOutputStream out = new FileOutputStream(file, true);
            out.write((line + "\n").getBytes("UTF-8"));
            out.close();
        } catch (Exception ignored) {
        }
    }

    private void loadHistoryLog() {
        try {
            File file = new File(new File(getFilesDir(), "dsh"), "app.log");
            if (!file.isFile()) return;
            java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(new java.io.FileInputStream(file), "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) sb.append(line).append("\n");
            reader.close();
            logText.setText(sb.toString());
            logScroll.post(new Runnable() {
                @Override
                public void run() {
                    logScroll.fullScroll(View.FOCUS_DOWN);
                }
            });
        } catch (Exception ignored) {
        }
    }

    private void fail(String prefix, Exception e) {
        busy = false;
        final String message = prefix + "：" + (e.getMessage() == null ? e.toString() : e.getMessage());
        status(message);
        logger.onLog("[ERROR] " + message);
    }

    private void showSettings() {
        if (settingsDialog != null && settingsDialog.isShowing()) {
            settingsDialog.dismiss();
        }
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(10), dp(20), dp(6));

        final TextView statusView = new TextView(this);
        statusView.setTextSize(13);
        statusView.setTextColor(0xFF8AB4F8);
        statusView.setPadding(0, dp(4), 0, dp(10));
        statusView.setText("Shizuku：" + shizukuLabel());
        box.addView(statusView);

        addSettingsButton(box, "连接方式：" + (isRemoteMode() ? "远程（连电脑）" : "本机（手机上跑）") + "（点击切换）", new Runnable() {
            @Override
            public void run() {
                switchConnMode();
            }
        });
        addSettingsButton(box, "连接电脑 / 修改地址…", new Runnable() {
            @Override
            public void run() {
                if (settingsDialog != null) {
                    settingsDialog.dismiss();
                }
                showRemoteDialog();
            }
        });
        addSettingsButton(box, "通知：" + notifyStatusLabel() + "（点击刷新）", new Runnable() {
            @Override
            public void run() {
                if (!isRemoteMode()) {
                    Toast.makeText(MainActivity.this, "先切到远程模式", Toast.LENGTH_SHORT).show();
                    return;
                }
                if (Prefs.remoteToken(MainActivity.this).isEmpty()) {
                    Toast.makeText(MainActivity.this, "先连接电脑完成配对", Toast.LENGTH_SHORT).show();
                    return;
                }
                if (!hasNotificationPermission()) {
                    openNotificationSettings();
                    return;
                }
                NotifyService.requestRefresh(MainActivity.this);
                Toast.makeText(MainActivity.this, "正在刷新通知配置", Toast.LENGTH_SHORT).show();
                if (settingsDialog != null) settingsDialog.dismiss();
            }
        });
        if (!Prefs.remoteToken(this).isEmpty()) {
            final String pairedName = Prefs.remoteName(this);
            final String pairedOrigin = Prefs.remoteOrigin(this);
            addSettingsButton(box, "解除配对：" + pairedName + " @ " + pairedOrigin, new Runnable() {
                @Override
                public void run() {
                    new AlertDialog.Builder(MainActivity.this)
                            .setTitle("解除配对")
                            .setMessage("清除这台电脑的配对凭证？\n" + pairedName + " @ " + pairedOrigin)
                            .setPositiveButton("解除", new DialogInterface.OnClickListener() {
                                @Override
                                public void onClick(DialogInterface dialog, int which) {
                                    if (settingsDialog != null) {
                                        settingsDialog.dismiss();
                                    }
                                    NotifyService.stop(MainActivity.this);
                                    Prefs.clearRemote(MainActivity.this);
                                    Prefs.setConnMode(MainActivity.this, "local");
                                    recreate();
                                }
                            })
                            .setNegativeButton("取消", null)
                            .show();
                }
            });
        }

        addSettingsButton(box, "工作区：" + runtime.workModeLabel() + "（点击切换）", new Runnable() {
            @Override
            public void run() {
                toggleWorkRoot();
            }
        });
        addSettingsButton(box, "Shizuku 权限（点击授权）", new Runnable() {
            @Override
            public void run() {
                handleShizukuAction();
            }
        });
        addSettingsButton(box, "Dhizuku 权限：" + DhizukuBridge.statusLabel() + "（点击授权）", new Runnable() {
            @Override
            public void run() {
                DhizukuBridge.requestPermission(MainActivity.this);
            }
        });
        LinearLayout zoomRow = new LinearLayout(this);
        zoomRow.setOrientation(LinearLayout.VERTICAL);
        zoomRow.setPadding(0, dp(8), 0, dp(8));
        final TextView zoomLabel = new TextView(this);
        zoomLabel.setTextSize(13);
        zoomLabel.setTextColor(0xFFE8EAED);
        zoomLabel.setText("桌面缩放：" + Prefs.deskScale(this) + "%");
        zoomRow.addView(zoomLabel);
        android.widget.SeekBar seekBar = new android.widget.SeekBar(this);
        seekBar.setMax(100);
        seekBar.setProgress(Prefs.deskScale(this) - 50);
        seekBar.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(android.widget.SeekBar bar, int progress, boolean fromUser) {
                int scale = 50 + progress;
                zoomLabel.setText("桌面缩放：" + scale + "%");
                Prefs.setDeskScale(MainActivity.this, scale);
                applyZoom();
            }

            @Override
            public void onStartTrackingTouch(android.widget.SeekBar bar) {
            }

            @Override
            public void onStopTrackingTouch(android.widget.SeekBar bar) {
            }
        });
        zoomRow.addView(seekBar, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        box.addView(zoomRow);
        addSettingsButton(box, "重置运行时", new Runnable() {
            @Override
            public void run() {
                confirmReset();
            }
        });
        addSettingsButton(box, "退出", new Runnable() {
            @Override
            public void run() {
                runtime.stopHost();
                finish();
            }
        });

        settingsDialog = new AlertDialog.Builder(this)
                .setTitle("设置")
                .setView(box)
                .setNegativeButton("关闭", null)
                .setOnDismissListener(new DialogInterface.OnDismissListener() {
                    @Override
                    public void onDismiss(DialogInterface dialog) {
                        stopSettingsTicker();
                    }
                })
                .show();

        stopSettingsTicker();
        lastShizukuLabel = "";
        settingsTicker = new Runnable() {
            @Override
            public void run() {
                if (settingsDialog != null && settingsDialog.isShowing()) {
                    String label = shizukuLabel();
                    if (!label.equals(lastShizukuLabel)) {
                        lastShizukuLabel = label;
                        statusView.setText("Shizuku：" + label);
                    }
                    mainHandler.postDelayed(this, 200);
                }
            }
        };
        mainHandler.postDelayed(settingsTicker, 100);
    }

    public static void pickOpenForBridge(final String path) {
        final MainActivity activity = current;
        if (activity == null) return;
        activity.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                activity.pickOpen(path);
            }
        });
    }

    public void pickOpen(final String path) {
        try {
            final File file = new File(path);
            final Uri contentUri = SimpleFileProvider.uriFor(file);
            final Uri fileUri = Uri.fromFile(file);
            final PackageManager pm = getPackageManager();

            LinearLayout box = new LinearLayout(this);
            box.setOrientation(LinearLayout.VERTICAL);
            box.setPadding(dp(18), dp(8), dp(18), dp(4));

            // 标题行：打开方式… + 类型筛选
            LinearLayout titleRow = new LinearLayout(this);
            titleRow.setOrientation(LinearLayout.HORIZONTAL);
            titleRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
            TextView title = new TextView(this);
            title.setText("打开方式…");
            title.setTextSize(20);
            title.setTypeface(null, android.graphics.Typeface.BOLD);
            title.setTextColor(0xFFE8EAED);
            titleRow.addView(title, new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

            final TextView typeButton = new TextView(this);
            typeButton.setText("全部 ▾");
            typeButton.setTextSize(13);
            typeButton.setTextColor(0xFF8AB4F8);
            typeButton.setPadding(dp(10), dp(6), dp(10), dp(6));
            titleRow.addView(typeButton);
            box.addView(titleRow);

            final GridView grid = new GridView(this);
            grid.setNumColumns(4);
            grid.setPadding(dp(2), dp(10), dp(2), dp(2));
            box.addView(grid, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(400)));

            final List<ResolveInfo> appsList = new ArrayList<>();
            final BaseAdapter adapter = new BaseAdapter() {
                @Override
                public int getCount() {
                    return appsList.size();
                }

                @Override
                public Object getItem(int position) {
                    return appsList.get(position);
                }

                @Override
                public long getItemId(int position) {
                    return position;
                }

                @Override
                public View getView(int position, View convertView, android.view.ViewGroup parent) {
                    LinearLayout item = new LinearLayout(MainActivity.this);
                    item.setOrientation(LinearLayout.VERTICAL);
                    item.setGravity(android.view.Gravity.CENTER);
                    item.setPadding(dp(4), dp(10), dp(4), dp(10));
                    ImageView icon = new ImageView(MainActivity.this);
                    try {
                        icon.setImageDrawable(appsList.get(position).loadIcon(pm));
                    } catch (Throwable ignored) {
                    }
                    item.addView(icon, new LinearLayout.LayoutParams(dp(52), dp(52)));
                    TextView label = new TextView(MainActivity.this);
                    try {
                        label.setText(appsList.get(position).loadLabel(pm));
                    } catch (Throwable ignored) {
                    }
                    label.setTextSize(11);
                    label.setTextColor(0xFFE8EAED);
                    label.setGravity(android.view.Gravity.CENTER);
                    label.setMaxLines(2);
                    label.setPadding(0, dp(5), 0, 0);
                    item.addView(label);
                    return item;
                }
            };
            grid.setAdapter(adapter);

            final String[] categoryNames = new String[] { "全部", "系统建议", "文本", "图片", "视频", "音频" };
            final String[] categoryMimes = new String[] { null, "", "text/*", "image/*", "video/*", "audio/*" };
            final int[] currentCategory = new int[] { 0 };

            final Runnable reload = new Runnable() {
                @Override
                public void run() {
                    appsList.clear();
                    appsList.addAll(loadAppsForCategory(fileUri, contentUri, categoryMimes[currentCategory[0]], pm));
                    adapter.notifyDataSetChanged();
                    typeButton.setText(categoryNames[currentCategory[0]] + " ▾");
                }
            };

            typeButton.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    PopupMenu popup = new PopupMenu(MainActivity.this, typeButton);
                    for (int i = 0; i < categoryNames.length; i++) {
                        popup.getMenu().add(0, i, i, categoryNames[i]);
                    }
                    popup.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
                        @Override
                        public boolean onMenuItemClick(android.view.MenuItem item) {
                            currentCategory[0] = item.getItemId();
                            reload.run();
                            return true;
                        }
                    });
                    popup.show();
                }
            });

            grid.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
                @Override
                public boolean onItemLongClick(AdapterView<?> parent, View view, int position, long id) {
                    ResolveInfo ri = appsList.get(position);
                    String mime = guessMimeOf(path);
                    String flat = ri.activityInfo.packageName + "/" + ri.activityInfo.name;
                    Prefs.setDefaultOpenApp(MainActivity.this, mime, flat);
                    Toast.makeText(MainActivity.this, "已设为该类型文件的默认打开方式", Toast.LENGTH_SHORT).show();
                    if (pickerDialog != null) pickerDialog.dismiss();
                    return true;
                }
            });

            grid.setOnItemClickListener(new AdapterView.OnItemClickListener() {
                @Override
                public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                    try {
                        ResolveInfo ri = appsList.get(position);
                        Intent intent = new Intent(Intent.ACTION_VIEW);
                        intent.setDataAndType(fileUri, guessMimeOf(path));
                        intent.setComponent(new ComponentName(ri.activityInfo.packageName, ri.activityInfo.name));
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        startActivity(intent);
                        diag("pick open " + ri.activityInfo.packageName);
                    } catch (Throwable t) {
                        diag("pick open error: " + t);
                        Toast.makeText(MainActivity.this, "打开失败：" + t, Toast.LENGTH_SHORT).show();
                    }
                    if (pickerDialog != null) pickerDialog.dismiss();
                }
            });

            reload.run();

            pickerDialog = new AlertDialog.Builder(this)
                    .setView(box)
                    .setNegativeButton("关闭", null)
                    .show();
            diag("pickOpen entries=" + appsList.size());
        } catch (Throwable t) {
            diag("pickOpen error: " + t);
        }
    }

    private List<ResolveInfo> loadAppsForCategory(Uri fileUri, Uri contentUri, String mime, PackageManager pm) {
        List<ResolveInfo> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        boolean defaultOnly = mime != null && mime.isEmpty();
        String[] probes = (mime == null || mime.isEmpty()) ? new String[] { "*/*" } : new String[] { mime };
        for (String probe : probes) {
            for (Uri probeUri : new Uri[] { fileUri, contentUri }) {
                try {
                    Intent intent = new Intent(Intent.ACTION_VIEW);
                    intent.setDataAndType(probeUri, probe);
                    int flags = defaultOnly ? PackageManager.MATCH_DEFAULT_ONLY : 0;
                    for (ResolveInfo ri : pm.queryIntentActivities(intent, flags)) {
                        String pkg = ri.activityInfo.packageName;
                        if ("com.deh.mobile".equals(pkg)) continue;
                        if (seen.add(pkg)) result.add(ri);
                    }
                } catch (Throwable ignored) {
                }
            }
        }
        return result;
    }

    private String guessMimeOf(String path) {
        String lower = path.toLowerCase();
        if (lower.endsWith(".txt") || lower.endsWith(".log") || lower.endsWith(".json")
                || lower.endsWith(".md") || lower.endsWith(".xml") || lower.endsWith(".java")
                || lower.endsWith(".js") || lower.endsWith(".py") || lower.endsWith(".sh")) {
            return "text/plain";
        }
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".gif")) return "image/gif";
        if (lower.endsWith(".webp")) return "image/webp";
        if (lower.endsWith(".mp4")) return "video/mp4";
        if (lower.endsWith(".mp3")) return "audio/mpeg";
        if (lower.endsWith(".pdf")) return "application/pdf";
        if (lower.endsWith(".zip")) return "application/zip";
        if (lower.endsWith(".apk")) return "application/vnd.android.package-archive";
        return "application/octet-stream";
    }

    private void addSettingsButton(LinearLayout parent, String text, final Runnable action) {
        Button button = new Button(this);
        button.setText(text);
        button.setAllCaps(false);
        button.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                action.run();
            }
        });
        parent.addView(button, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
    }

    private String lastShizukuLabel = "";

    private void startSettingsTicker() {
        stopSettingsTicker();
        lastShizukuLabel = "";
        settingsTicker = new Runnable() {
            @Override
            public void run() {
                if (settingsDialog != null && settingsDialog.isShowing()) {
                    String label = shizukuLabel();
                    if (!label.equals(lastShizukuLabel)) {
                        lastShizukuLabel = label;
                        settingsDialog.setMessage("Shizuku：" + label);
                    }
                    mainHandler.postDelayed(this, 200);
                }
            }
        };
        mainHandler.postDelayed(settingsTicker, 100);
    }

    private void stopSettingsTicker() {
        if (settingsTicker != null) {
            mainHandler.removeCallbacks(settingsTicker);
            settingsTicker = null;
        }
    }

    private void handleShizukuAction() {
        String status = ShizukuBridge.status();
        ShizukuBridge.requestPermission(1003);
        if ("granted".equals(status)) {
            Toast.makeText(this, "已授权。如需重新授权，请先在 Shizuku 中撤销，再点此请求", Toast.LENGTH_LONG).show();
        } else if ("denied".equals(status)) {
            Toast.makeText(this, "已申请权限，请在弹出的 Shizuku 授权窗口点「允许」", Toast.LENGTH_LONG).show();
        } else {
            Toast.makeText(this, "Shizuku 服务未就绪，正在后台重试；稍等几秒后再点一次", Toast.LENGTH_LONG).show();
        }
    }

    private void toggleWorkRoot() {
        final boolean toPrivate = !"private".equals(Prefs.workRootMode(this));
        new AlertDialog.Builder(this)
                .setTitle("切换工作区")
                .setMessage("工作区根将切换到：" + (toPrivate ? "应用私有目录（/data/user/0/com.deh.mobile/files/work/DEH）" : "内部存储（/storage/emulated/0/DEH）")
                        + "\n\n切换后需要重启 DSH。已安装的运行时保留，不会重新安装。是否切换？")
                .setPositiveButton("切换并重启", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        Prefs.setWorkRootMode(MainActivity.this, toPrivate ? "private" : "internal");
                        runtime.stopHost();
                        recreate();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void confirmReset() {
        new AlertDialog.Builder(this)
                .setTitle("重置运行时")
                .setMessage("将删除已解压的 rootfs、Node 和已安装的 DSH，下次启动会重新初始化（工作区文件不受影响）。确定吗？")
                .setPositiveButton("重置", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        runtime.stopHost();
                        Runtime.deleteRecursively(runtime.base);
                        recreate();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void writeShizukuFiles() {
        try {
            File dir = new File("/storage/emulated/0/DEH");
            dir.mkdirs();
            File tokenFile = new File(dir, ".shizuku-token");
            FileOutputStream tokenOut = new FileOutputStream(tokenFile);
            tokenOut.write(LocalBridge.token().getBytes("UTF-8"));
            tokenOut.close();

            String shizukuScript = "#!/bin/sh\n"
                    + "TOKEN=$(cat /storage/emulated/0/DEH/.shizuku-token 2>/dev/null)\n"
                    + "if [ -z \"$1\" ]; then echo '\u7528\u6cd5: shizuku.sh <command>'; exit 1; fi\n"
                    + "ENC=$(printf '%s' \"$1\" | python3 -c 'import sys,urllib.parse;print(urllib.parse.quote(sys.stdin.read()))' 2>/dev/null)\n"
                    + "curl -s \"http://127.0.0.1:38620/run?token=$TOKEN&cmd=$ENC\"\n";
            writeExecutable(new File(dir, "shizuku.sh"), shizukuScript);

            String openScript = "#!/bin/sh\n"
                    + "TARGET=\"$1\"\n"
                    + "TOKEN=$(cat /storage/emulated/0/DEH/.shizuku-token 2>/dev/null)\n"
                    + "ENC=$(printf '%s' \"$TARGET\" | python3 -c 'import sys,urllib.parse;print(urllib.parse.quote(sys.stdin.read()))' 2>/dev/null)\n"
                    + "[ -z \"$ENC\" ] && ENC=\"$TARGET\"\n"
                    + "curl -s \"http://127.0.0.1:38620/open?token=$TOKEN&path=$ENC\"\n";
            writeExecutable(new File(runtime.rootfs, "usr/local/bin/xdg-open"), openScript);
            writeExecutable(new File(runtime.rootfs, "usr/local/bin/open"), openScript);
        } catch (Exception ignored) {
        }
    }

    private static void writeExecutable(File file, String content) throws Exception {
        file.getParentFile().mkdirs();
        FileOutputStream out = new FileOutputStream(file);
        out.write(content.getBytes("UTF-8"));
        out.close();
        file.setExecutable(true, false);
    }

    private String shizukuLabel() {
        String status = ShizukuBridge.status();
        if ("granted".equals(status)) return "已授权";
        if ("denied".equals(status)) return "未授权";
        if ("connecting".equals(status)) return "连接中…";
        return "连接中…（等待 Shizuku）";
    }

    private void requestStoragePermission() {
        if (Build.VERSION.SDK_INT >= 30) {
            if (!Environment.isExternalStorageManager()) {
                try {
                    Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                    intent.setData(Uri.parse("package:" + getPackageName()));
                    startActivity(intent);
                } catch (Exception e) {
                    try {
                        startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
                    } catch (Exception ignored) {
                    }
                }
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQUEST_SCAN_DIALOG || requestCode == REQUEST_SCAN_SIDEBAR) {
            if (resultCode == RESULT_OK && data != null) {
                String[] pair = parsePairPayload(data.getStringExtra(QrScanActivity.EXTRA_RAW));
                if (pair == null) {
                    Toast.makeText(this, "不是有效的 DSH 配对二维码", Toast.LENGTH_LONG).show();
                } else if (requestCode == REQUEST_SCAN_DIALOG) {
                    if (remoteOriginField != null) remoteOriginField.setText(pair[0]);
                    if (remoteCodeField != null) remoteCodeField.setText(pair[1]);
                    Toast.makeText(this, "已扫码填入，点「配对」继续", Toast.LENGTH_SHORT).show();
                } else {
                    Prefs.setConnMode(this, "remote");
                    Prefs.setRemoteOrigin(this, pair[0]);
                    pairRemote(pair[0], pair[1]);
                }
            }
            return;
        }
        if (requestCode == REQUEST_FILE) {
            LinkedHashSet<Uri> merged = new LinkedHashSet<>();
            if (resultCode == RESULT_OK) {
                try {
                    Uri[] parsed = WebChromeClient.FileChooserParams.parseResult(resultCode, data);
                    if (parsed != null) {
                        for (Uri uri : parsed) if (uri != null) merged.add(uri);
                    }
                } catch (Exception ignored) {
                }
                if (data != null) {
                    Parcelable[] picked = data.getParcelableArrayExtra(FilePickerActivity.EXTRA_RESULT_URIS);
                    if (picked != null) {
                        for (Parcelable parcelable : picked) {
                            if (parcelable instanceof Uri) merged.add((Uri) parcelable);
                        }
                    }
                    ClipData clip = data.getClipData();
                    if (clip != null) {
                        for (int i = 0; i < clip.getItemCount(); i++) {
                            Uri uri = clip.getItemAt(i).getUri();
                            if (uri != null) merged.add(uri);
                        }
                    }
                    if (data.getData() != null) merged.add(data.getData());
                }
            }
            Uri[] results = merged.isEmpty() ? null : merged.toArray(new Uri[0]);
            if (results != null) {
                for (Uri uri : results) {
                    try {
                        getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    } catch (Throwable ignored) {
                    }
                }
            }
            diag("fileResult code=" + resultCode + " results=" + (results == null ? "null" : results.length)
                    + " data=" + (data == null ? "null" : String.valueOf(data.getData())));
            StringBuilder diagText = new StringBuilder();
            diagText.append("version=").append(appVersionName()).append('\n');
            diagText.append("chooser=").append(lastChooserInfo).append('\n');
            diagText.append("resultCode=").append(resultCode).append('\n');
            diagText.append("clipCount=").append(data == null || data.getClipData() == null ? 0 : data.getClipData().getItemCount()).append('\n');
            diagText.append("resultCount=").append(results == null ? 0 : results.length).append('\n');
            if (results != null) {
                for (Uri uri : results) diagText.append("uri=").append(uri).append('\n');
            }
            uploadPickerDiag(diagText.toString());
            if (fileCallback != null) {
                fileCallback.onReceiveValue(results);
                fileCallback = null;
                if (results != null && results.length > 0) {
                    Toast.makeText(this, "已选择 " + results.length + " 个文件", Toast.LENGTH_SHORT).show();
                }
            } else {
                diag("fileResult ignored: fileCallback=null");
            }
            super.onActivityResult(requestCode, resultCode, data);
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (webView != null && webView.getVisibility() == View.VISIBLE) {
            return;
        }
        CharSequence logs = logText == null ? "" : logText.getText();
        CharSequence status = statusText == null ? "" : statusText.getText();
        buildUi();
        logText.setText(logs);
        if (statusText != null && status.length() > 0) statusText.setText(status);
        if (logScroll != null) {
            logScroll.post(new Runnable() {
                @Override
                public void run() {
                    logScroll.fullScroll(View.FOCUS_DOWN);
                }
            });
        }
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.getVisibility() == View.VISIBLE && webView.canGoBack()) {
            webView.goBack();
            return;
        }
        super.onBackPressed();
    }

    /**
     * 处理启动/新到的 Intent。返回 true 表示这个 Intent 已经驱动了界面流程，
     * onCreate 就不要再按本地模式 bootstrap。
     */
    private boolean handleIntent(Intent intent) {
        if (intent == null) {
            return false;
        }
        Uri data = intent.getData();
        if (data != null && "deh".equals(data.getScheme())) {
            String host = data.getHost();
            if ("pair".equals(host)) {
                String origin = normalizeOrigin(data.getQueryParameter("origin"));
                String code = data.getQueryParameter("code");
                Prefs.setConnMode(this, "remote");
                if (origin != null && code != null && !code.isEmpty()) {
                    Prefs.setRemoteOrigin(this, origin);
                    pairRemote(origin, code);
                } else {
                    showRemoteDialog();
                }
                return true;
            }
            if ("open".equals(host)) {
                Prefs.setConnMode(this, "remote");
                enterRemote();
                return true;
            }
        }
        if ("com.deh.mobile.OPEN_SETTINGS".equals(intent.getAction())) {
            intent.setAction(null);
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    showSettings();
                }
            });
            return true;
        }
        return false;
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleIntent(intent);
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        current = this;
        diag("onResume pending=" + pendingUrl);
        if (pendingUrl != null) {
            String url = pendingUrl;
            pendingUrl = null;
            showWeb(url);
            return;
        }
        if (isRemoteMode()) {
            startNotifyIfPaired();
        }
        if (webView != null && webView.getVisibility() == View.VISIBLE) {
            webView.onResume();
            checkWebBlank();
        }
    }

    @Override
    protected void onPause() {
        resumed = false;
        if (current == this) current = null;
        diag("onPause");
        CookieManager.getInstance().flush();
        if (webView != null) webView.onPause();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (isFinishing()) {
            if (runtime != null) runtime.stopHost();
            stopService(new Intent(this, DshService.class));
        }
        if (webView != null) webView.destroy();
        super.onDestroy();
    }
}
