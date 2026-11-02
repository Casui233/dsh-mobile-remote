package com.deh.mobile;

import android.content.Context;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;

/** 远程审批/回答的 HTTP 客户端，复用已配对电脑的 origin/token。 */
final class RemoteActionClient {
    private RemoteActionClient() {
    }

    static JSONObject get(Context context, String token) throws Exception {
        return request(context, "GET", token, null);
    }

    static JSONObject post(Context context, String token, JSONObject body) throws Exception {
        return request(context, "POST", token, body);
    }

    private static JSONObject request(Context context, String method, String token, JSONObject body) throws Exception {
        String origin = Prefs.remoteOrigin(context);
        String device = Prefs.remoteToken(context);
        if (origin == null || origin.isEmpty() || device == null || device.isEmpty()) {
            throw new IllegalStateException("还没有配对电脑");
        }
        while (origin.endsWith("/")) origin = origin.substring(0, origin.length() - 1);
        String url = origin + "/remote-action?token=" + URLEncoder.encode(token == null ? "" : token, "UTF-8");
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        try {
            conn.setConnectTimeout(6000);
            conn.setReadTimeout(12000);
            conn.setRequestMethod(method);
            conn.setRequestProperty("Authorization", "Bearer " + device);
            if (body != null) {
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                OutputStream output = conn.getOutputStream();
                output.write(body.toString().getBytes("UTF-8"));
                output.close();
            }
            int code = conn.getResponseCode();
            InputStream stream = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
            String raw = stream == null ? "" : readAll(stream);
            JSONObject json = raw.isEmpty() ? new JSONObject() : new JSONObject(raw);
            if (code != 200 || !json.optBoolean("ok", false)) {
                String error = json.optString("error", "HTTP " + code);
                throw new IllegalStateException(error);
            }
            return json;
        } finally {
            conn.disconnect();
        }
    }

    private static String readAll(InputStream input) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int count;
        while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
        input.close();
        return output.toString("UTF-8");
    }
}
