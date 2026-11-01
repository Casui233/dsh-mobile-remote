package com.deh.mobile;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 接收 Android 分享（文本 / 单个或多个文件），上传到 PC 共享目录的 inbox/。
 * 不做当前会话附件注入，保证跨 App 分享路径简单可靠。
 */
public class ShareReceiverActivity extends Activity {
    private TextView status;
    private String origin;
    private String token;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setPadding(dp(24), dp(20), dp(24), dp(20));
        root.setBackgroundColor(0xFF171A21);

        TextView title = new TextView(this);
        title.setText("发送到 DSH");
        title.setTextColor(Color.WHITE);
        title.setTextSize(17);
        root.addView(title);

        status = new TextView(this);
        status.setText("正在发送到 PC…");
        status.setTextColor(0xFF9AA3B2);
        status.setTextSize(13);
        status.setPadding(0, dp(8), 0, dp(10));
        root.addView(status);

        ProgressBar progress = new ProgressBar(this);
        root.addView(progress, new LinearLayout.LayoutParams(dp(36), dp(36)));
        setContentView(root, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        origin = Prefs.remoteOrigin(this);
        token = Prefs.remoteToken(this);
        if (origin == null || origin.isEmpty() || token == null || token.isEmpty()) {
            Toast.makeText(this, "还没有配对电脑，先打开 App 完成配对", Toast.LENGTH_LONG).show();
            startActivity(new Intent(this, MainActivity.class));
            finish();
            return;
        }
        final Intent intent = getIntent();
        if (intent == null) {
            finish();
            return;
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    uploadFromIntent(intent);
                } catch (Throwable t) {
                    final String message = t.getMessage() == null ? t.toString() : t.getMessage();
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            status.setText("发送失败");
                            Toast.makeText(ShareReceiverActivity.this, message, Toast.LENGTH_LONG).show();
                            finish();
                        }
                    });
                }
            }
        }, "dsh-share").start();
    }

    private void uploadFromIntent(Intent intent) throws Exception {
        ensureInbox();
        String stamp = new SimpleDateFormat("MMdd-HHmmss", Locale.US).format(new Date());
        List<Uri> uris = new ArrayList<>();
        String action = intent.getAction();
        if (Intent.ACTION_SEND_MULTIPLE.equals(action)) {
            ArrayList<Uri> list = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
            if (list != null) uris.addAll(list);
        } else {
            Uri single = intent.getParcelableExtra(Intent.EXTRA_STREAM);
            if (single != null) uris.add(single);
        }

        if (!uris.isEmpty()) {
            int uploaded = 0;
            for (Uri uri : uris) {
                final String label = "正在发送…";
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        status.setText(label);
                    }
                });
                uploaded += uploadOne(uri, stamp);
            }
            final int count = uploaded;
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    status.setText("已发送 " + count + " 个文件");
                    Toast.makeText(ShareReceiverActivity.this,
                            "已发送到 PC 共享目录 inbox/", Toast.LENGTH_LONG).show();
                    finish();
                }
            });
            return;
        }

        String text = intent.getStringExtra(Intent.EXTRA_TEXT);
        if (text == null || text.isEmpty()) {
            throw new IllegalArgumentException("分享内容为空");
        }
        String subject = intent.getStringExtra(Intent.EXTRA_SUBJECT);
        String body = subject == null || subject.isEmpty() ? text : subject + "\n\n" + text;
        uploadBytes(stamp + "-share.txt", body.getBytes("UTF-8"), "text/plain; charset=utf-8");
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                status.setText("已发送文本");
                Toast.makeText(ShareReceiverActivity.this,
                        "已发送到 PC 共享目录 inbox/", Toast.LENGTH_LONG).show();
                finish();
            }
        });
    }

    private int uploadOne(Uri uri, String stamp) throws Exception {
        String base = safeName(displayName(uri));
        String mime = getContentResolver().getType(uri);
        if (isZip(base, mime)) return uploadZip(uri, base, mime, stamp);
        InputStream input = getContentResolver().openInputStream(uri);
        if (input == null) throw new IllegalStateException("无法读取： " + uri);
        uploadStream(stamp + "-" + base, input, sizeOf(uri),
                mime == null ? "application/octet-stream" : mime);
        return 1;
    }

    private boolean isZip(String name, String mime) {
        String lower = name == null ? "" : name.toLowerCase(Locale.US);
        String type = mime == null ? "" : mime.toLowerCase(Locale.US);
        return lower.endsWith(".zip") || type.contains("zip");
    }

    /**
     * 微信「分享聊天记录」会给一个 zip（聊天记录.txt + 附件目录）。
     * 识别到这种 zip 就解包，把附件直接放进 inbox；普通 zip 原样上传。
     */
    private int uploadZip(Uri uri, String base, String mime, String stamp) throws Exception {
        File temp = File.createTempFile("dsh-share-", ".zip", getCacheDir());
        try {
            try (InputStream input = getContentResolver().openInputStream(uri);
                 FileOutputStream output = new FileOutputStream(temp)) {
                if (input == null) throw new IllegalStateException("无法读取： " + uri);
                byte[] buffer = new byte[64 * 1024];
                int count;
                while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            }
            ZipFile zip = openChatRecordZip(temp);
            if (zip == null) {
                try (InputStream input = new FileInputStream(temp)) {
                    uploadStream(stamp + "-" + base, input, temp.length(),
                            mime == null ? "application/zip" : mime);
                }
                return 1;
            }
            try {
                long total = 0;
                Enumeration<? extends ZipEntry> scan = zip.entries();
                while (scan.hasMoreElements()) {
                    ZipEntry entry = scan.nextElement();
                    if (entry.isDirectory()) continue;
                    if (entry.getSize() > 0) total += entry.getSize();
                }
                if (total > 2L * 1024 * 1024 * 1024) {
                    try (InputStream input = new FileInputStream(temp)) {
                        uploadStream(stamp + "-" + base, input, temp.length(), "application/zip");
                    }
                    return 1;
                }
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        status.setText("正在解压微信聊天记录…");
                    }
                });
                String zipBase = base.toLowerCase(Locale.US).endsWith(".zip")
                        ? base.substring(0, base.length() - 4) : base;
                int extracted = 0;
                int index = 0;
                Enumeration<? extends ZipEntry> entries = zip.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry entry = entries.nextElement();
                    if (entry.isDirectory()) continue;
                    String entryName = entry.getName().replace('\\', '/');
                    int slash = entryName.lastIndexOf('/');
                    String leaf = slash >= 0 ? entryName.substring(slash + 1) : entryName;
                    if (leaf.isEmpty()) continue;
                    String out = stamp + "-" + safeName(zipBase) + "-" + (++index) + "-" + safeName(leaf);
                    InputStream input = zip.getInputStream(entry);
                    if (input == null) continue;
                    uploadStream(out, input, entry.getSize(), "application/octet-stream");
                    extracted++;
                }
                if (extracted == 0) {
                    try (InputStream input = new FileInputStream(temp)) {
                        uploadStream(stamp + "-" + base, input, temp.length(), "application/zip");
                    }
                    return 1;
                }
                return extracted;
            } finally {
                zip.close();
            }
        } finally {
            //noinspection ResultOfMethodCallIgnored
            temp.delete();
        }
    }

    private ZipFile openChatRecordZip(File file) {
        Charset[] charsets = new Charset[] { StandardCharsets.UTF_8, Charset.forName("GBK") };
        for (Charset charset : charsets) {
            ZipFile zip = null;
            try {
                zip = new ZipFile(file, charset);
                if (isChatRecord(zip)) return zip;
                zip.close();
            } catch (Throwable ignored) {
                if (zip != null) {
                    try {
                        zip.close();
                    } catch (Throwable ignored2) {
                    }
                }
            }
        }
        return null;
    }

    private boolean isChatRecord(ZipFile zip) {
        Enumeration<? extends ZipEntry> entries = zip.entries();
        while (entries.hasMoreElements()) {
            String name = entries.nextElement().getName();
            if (name.equals("聊天记录.txt") || name.startsWith("聊天记录内的")) return true;
        }
        return false;
    }

    private void ensureInbox() throws Exception {
        HttpURLConnection connection = null;
        try {
            String url = trimSlash(origin) + "/files/api/mkdir?dir=&name=inbox";
            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setConnectTimeout(8000);
            connection.setReadTimeout(15000);
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Authorization", "Bearer " + token);
            connection.setRequestProperty("X-DSH-Files", "1");
            int code = connection.getResponseCode();
            if (code != 200 && code != 409) {
                throw new IllegalStateException("创建 inbox 失败：HTTP " + code);
            }
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private void uploadBytes(String name, byte[] data, String mime) throws Exception {
        HttpURLConnection connection = null;
        try {
            String url = trimSlash(origin) + "/files/api/upload?dir=inbox&name="
                    + URLEncoder.encode(name, "UTF-8");
            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(120000);
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setFixedLengthStreamingMode(data.length);
            connection.setRequestProperty("Authorization", "Bearer " + token);
            connection.setRequestProperty("X-DSH-Files", "1");
            connection.setRequestProperty("Content-Type", mime);
            OutputStream out = connection.getOutputStream();
            out.write(data);
            out.close();
            checkResponse(connection);
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private void uploadStream(String name, InputStream input, long size, String mime) throws Exception {
        HttpURLConnection connection = null;
        try {
            String url = trimSlash(origin) + "/files/api/upload?dir=inbox&name="
                    + URLEncoder.encode(name, "UTF-8");
            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(300000);
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            if (size > 0) connection.setFixedLengthStreamingMode(size);
            else connection.setChunkedStreamingMode(64 * 1024);
            connection.setRequestProperty("Authorization", "Bearer " + token);
            connection.setRequestProperty("X-DSH-Files", "1");
            connection.setRequestProperty("Content-Type", mime);
            OutputStream out = connection.getOutputStream();
            byte[] buffer = new byte[64 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1) out.write(buffer, 0, count);
            out.close();
            input.close();
            checkResponse(connection);
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private void checkResponse(HttpURLConnection connection) throws Exception {
        int code = connection.getResponseCode();
        if (code >= 200 && code < 300) return;
        InputStream error = connection.getErrorStream();
        String body = error == null ? "" : readText(error);
        if (body.length() > 200) body = body.substring(0, 200);
        throw new IllegalStateException("上传失败：HTTP " + code + (body.isEmpty() ? "" : " " + body));
    }

    private String displayName(Uri uri) {
        try (android.database.Cursor cursor = getContentResolver().query(uri,
                new String[] { OpenableColumns.DISPLAY_NAME }, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                String name = cursor.getString(0);
                if (name != null && !name.isEmpty()) return name;
            }
        } catch (Throwable ignored) {
        }
        String last = uri.getLastPathSegment();
        return last == null || last.isEmpty() ? "shared-file" : last;
    }

    private long sizeOf(Uri uri) {
        try (android.database.Cursor cursor = getContentResolver().query(uri,
                new String[] { OpenableColumns.SIZE }, null, null, null)) {
            if (cursor != null && cursor.moveToFirst() && !cursor.isNull(0)) return cursor.getLong(0);
        } catch (Throwable ignored) {
        }
        return -1;
    }

    private String safeName(String name) {
        return name.replaceAll("[\\\\/:*?\"<>|\\r\\n]", "_").trim();
    }

    private String trimSlash(String value) {
        String result = value;
        while (result.endsWith("/")) result = result.substring(0, result.length() - 1);
        return result;
    }

    private String readText(InputStream input) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int count;
        while ((count = input.read(buffer)) != -1) out.write(buffer, 0, count);
        input.close();
        return out.toString("UTF-8");
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
