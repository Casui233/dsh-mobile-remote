package com.deh.mobile;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 文本剪贴板同步。Android 10+ 只允许前台应用读剪贴板，所以自动同步只在
 * 这个页面可见时运行；离开页面就停止，不做后台偷读。
 */
public class ClipboardActivity extends Activity {
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private EditText textView;
    private TextView statusView;
    private CheckBox autoBox;
    private ClipboardManager clipboard;
    private ClipboardManager.OnPrimaryClipChangedListener clipListener;

    private String origin;
    private String token;
    private String hash = "";
    private String lastApplied = "";
    private String lastPushed = "";
    private boolean requesting;
    private boolean destroyed;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        origin = Prefs.remoteOrigin(this);
        token = Prefs.remoteToken(this);
        if (origin == null || origin.isEmpty() || token == null || token.isEmpty()) {
            Toast.makeText(this, "还没有配对电脑", Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        buildUi();
        clipListener = new ClipboardManager.OnPrimaryClipChangedListener() {
            @Override
            public void onPrimaryClipChanged() {
                if (destroyed || !autoBox.isChecked() || !hasWindowFocus()) return;
                String current = readPhoneClipboard();
                if (current == null || current.isEmpty()) return;
                if (current.equals(lastApplied) || current.equals(lastPushed)) return;
                textView.setText(current);
                lastApplied = current;
                lastPushed = current;
                push(current);
            }
        };
        clipboard.addPrimaryClipChangedListener(clipListener);
        String initial = readPhoneClipboard();
        if (initial != null && !initial.isEmpty()) {
            textView.setText(initial);
            lastApplied = initial;
            lastPushed = initial;
        }
        status("当前是手机剪贴板；需要电脑内容请点「从电脑取」");
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(14), dp(10), dp(14), dp(10));
        root.setBackgroundColor(0xFF0F1115);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = new TextView(this);
        title.setText("剪贴板同步");
        title.setTextColor(Color.WHITE);
        title.setTextSize(18);
        header.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Button close = new Button(this);
        close.setAllCaps(false);
        close.setText("关闭");
        close.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        header.addView(close);
        root.addView(header);

        TextView hint = new TextView(this);
        hint.setText("PC ↔ 手机，仅文本；自动同步只在当前页面打开时运行。");
        hint.setTextColor(0xFF8B93A3);
        hint.setTextSize(11);
        hint.setPadding(0, dp(2), 0, dp(6));
        root.addView(hint);

        textView = new EditText(this);
        textView.setTextColor(0xFFE8EAED);
        textView.setTextSize(14);
        textView.setGravity(Gravity.TOP | Gravity.START);
        textView.setBackgroundColor(0xFF171A21);
        textView.setPadding(dp(10), dp(10), dp(10), dp(10));
        textView.setMinLines(8);
        textView.setHint("剪贴板内容");
        textView.setHintTextColor(0xFF5F6675);
        root.addView(textView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setPadding(0, dp(8), 0, 0);
        buttons.addView(button("从电脑取", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pull(true);
            }
        }), weight());
        buttons.addView(button("发到电脑", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                push(textView.getText().toString());
            }
        }), weight());
        buttons.addView(button("复制到手机", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String value = textView.getText().toString();
                setPhoneClipboard(value);
                lastApplied = value;
                status("已复制到手机");
            }
        }), weight());
        root.addView(buttons);

        autoBox = new CheckBox(this);
        autoBox.setText("自动发送手机 → 电脑（仅本页前台）");
        autoBox.setTextColor(0xFFE8EAED);
        autoBox.setTextSize(13);
        autoBox.setChecked(true);
        autoBox.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                status(autoBox.isChecked() ? "手机复制后会自动发到电脑" : "已暂停自动发送");
            }
        });
        root.addView(autoBox);

        statusView = new TextView(this);
        statusView.setTextColor(0xFF8B93A3);
        statusView.setTextSize(11);
        statusView.setPadding(0, dp(2), 0, 0);
        statusView.setText("准备同步");
        root.addView(statusView);

        setContentView(root);
    }

    private Button button(String label, View.OnClickListener listener) {
        Button button = new Button(this);
        button.setAllCaps(false);
        button.setText(label);
        button.setTextSize(12);
        button.setOnClickListener(listener);
        return button;
    }

    private LinearLayout.LayoutParams weight() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        params.setMargins(dp(2), 0, dp(2), 0);
        return params;
    }

    private void pull(final boolean force) {
        if (destroyed) return;
        if (requesting) return;
        requesting = true;
        final String since = force ? "" : hash;
        if (force) status("正在读取电脑剪贴板…");
        io.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    JSONObject json = fetchClipboard(since);
                    final boolean changed = json.optBoolean("changed", true);
                    final String text = json.optString("text", "");
                    final String newHash = json.optString("hash", "");
                    if (!changed) {
                        requesting = false;
                        return;
                    }
                    main.post(new Runnable() {
                        @Override
                        public void run() {
                            requesting = false;
                            applyFromPc(text, newHash, force);
                        }
                    });
                } catch (final Throwable error) {
                    requesting = false;
                    main.post(new Runnable() {
                        @Override
                        public void run() {
                            status("读取失败：" + message(error));
                            toast("读取失败：" + message(error));
                        }
                    });
                }
            }
        });
    }

    private void applyFromPc(String value, String newHash, boolean force) {
        if (destroyed) return;
        String text = value == null ? "" : value;
        hash = newHash == null ? "" : newHash;
        if (!force && textView.hasFocus() && !text.equals(readPhoneClipboard())) {
            status("电脑有新内容，点「从电脑取」覆盖");
            return;
        }
        if (text.isEmpty()) {
            if (force) status("电脑剪贴板为空");
            return;
        }
        String current = readPhoneClipboard();
        lastApplied = text;
        if (!text.equals(current)) setPhoneClipboard(text);
        if (!text.equals(textView.getText().toString())) textView.setText(text);
        status("已从电脑同步 · " + time());
        if (force) toast("已从电脑取到剪贴板");
    }

    private void push(final String value) {
        final String text = value == null ? "" : value;
        if (text.isEmpty()) {
            status("没有可发送的文本");
            toast("没有可发送的文本");
            return;
        }
        if (text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 512 * 1024) {
            status("文本超过 512 KiB");
            toast("文本超过 512 KiB");
            return;
        }
        status("正在发送到电脑…");
        io.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    JSONObject json = postClipboard(text);
                    final String newHash = json.optString("hash", "");
                    main.post(new Runnable() {
                        @Override
                        public void run() {
                            hash = newHash;
                            lastPushed = text;
                            lastApplied = text;
                            status("已发送到电脑 · " + time());
                            toast("已发送到电脑");
                        }
                    });
                } catch (final Throwable error) {
                    main.post(new Runnable() {
                        @Override
                        public void run() {
                            status("发送失败：" + message(error));
                            toast("发送失败：" + message(error));
                        }
                    });
                }
            }
        });
    }

    private JSONObject fetchClipboard(String since) throws Exception {
        String url = trimSlash(origin) + "/clipboard";
        if (since != null && !since.isEmpty()) {
            url += "?since=" + URLEncoder.encode(since, "UTF-8");
        }
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        try {
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(8000);
            conn.setRequestMethod("GET");
            conn.setRequestProperty("Authorization", "Bearer " + token);
            return readJson(conn);
        } finally {
            conn.disconnect();
        }
    }

    private JSONObject postClipboard(String text) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(trimSlash(origin) + "/clipboard").openConnection();
        try {
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(10000);
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setRequestProperty("Authorization", "Bearer " + token);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            JSONObject body = new JSONObject();
            body.put("text", text);
            OutputStream out = conn.getOutputStream();
            out.write(body.toString().getBytes("UTF-8"));
            out.close();
            return readJson(conn);
        } finally {
            conn.disconnect();
        }
    }

    private JSONObject readJson(HttpURLConnection conn) throws Exception {
        int code = conn.getResponseCode();
        InputStream stream = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
        String raw = stream == null ? "" : readAll(stream);
        JSONObject json = raw.isEmpty() ? new JSONObject() : new JSONObject(raw);
        if (code != 200 || !json.optBoolean("ok", false)) {
            String error = json.optString("error", "HTTP " + code);
            throw new IllegalStateException(error);
        }
        return json;
    }

    private String readAll(InputStream input) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int count;
        while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
        input.close();
        return output.toString("UTF-8");
    }

    private String readPhoneClipboard() {
        try {
            ClipData clip = clipboard.getPrimaryClip();
            if (clip == null || clip.getItemCount() == 0) return "";
            CharSequence value = clip.getItemAt(0).coerceToText(this);
            return value == null ? "" : value.toString();
        } catch (Throwable ignored) {
            return "";
        }
    }

    private void setPhoneClipboard(String text) {
        try {
            clipboard.setPrimaryClip(ClipData.newPlainText("DSH", text));
        } catch (Throwable ignored) {
        }
    }

    private String trimSlash(String value) {
        String result = value;
        while (result.endsWith("/")) result = result.substring(0, result.length() - 1);
        return result;
    }

    private String message(Throwable error) {
        String message = error == null ? "" : error.getMessage();
        return message == null || message.isEmpty() ? "网络错误" : message;
    }

    private String time() {
        return new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(new java.util.Date());
    }

    private void status(String text) {
        if (statusView != null) statusView.setText(text);
    }

    private void toast(String text) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        if (clipboard != null && clipListener != null) {
            try {
                clipboard.removePrimaryClipChangedListener(clipListener);
            } catch (Throwable ignored) {
            }
        }
        io.shutdownNow();
        super.onDestroy();
    }
}
