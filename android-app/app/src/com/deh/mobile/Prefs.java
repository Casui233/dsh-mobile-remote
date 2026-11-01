package com.deh.mobile;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** 轻量偏好设置：工作区根、桌面视图。 */
public final class Prefs {
    private static final String FILE = "dsh_mobile";

    private Prefs() {
    }

    /** 工作区根模式：internal（内部存储）或 private（应用私有目录）。 */
    public static String workRootMode(Context context) {
        return sp(context).getString("work_root", "internal");
    }

    public static void setWorkRootMode(Context context, String mode) {
        sp(context).edit().putString("work_root", mode).apply();
    }

    public static boolean deskView(Context context) {
        return sp(context).getBoolean("desk_view", false);
    }

    public static void setDeskView(Context context, boolean enabled) {
        sp(context).edit().putBoolean("desk_view", enabled).apply();
    }

    public static int deskScale(Context context) {
        return sp(context).getInt("desk_scale", 80);
    }

    public static void setDeskScale(Context context, int scale) {
        sp(context).edit().putInt("desk_scale", scale).apply();
    }

    public static String defaultOpenApp(Context context, String mime) {
        return sp(context).getString("open_default_" + mime, "");
    }

    public static void setDefaultOpenApp(Context context, String mime, String flat) {
        sp(context).edit().putString("open_default_" + mime, flat).apply();
    }

    public static void clearDefaultOpenApp(Context context) {
        android.content.SharedPreferences.Editor editor = sp(context).edit();
        for (String key : sp(context).getAll().keySet()) {
            if (key.startsWith("open_default_")) editor.remove(key);
        }
        editor.apply();
    }

    public static String shizukuToken(Context context) {
        return sp(context).getString("shizuku_token", "");
    }

    public static void setShizukuToken(Context context, String token) {
        sp(context).edit().putString("shizuku_token", token).apply();
    }

    /** 连接方式：local = 手机本地跑 DSH；remote = 连电脑上的 DSH。 */
    public static String connMode(Context context) {
        return sp(context).getString("conn_mode", "local");
    }

    public static void setConnMode(Context context, String mode) {
        sp(context).edit().putString("conn_mode", mode).apply();
    }

    /** 远程电脑的地址，例如 http://192.168.10.101:8765 或 MagicDNS 名。 */
    public static String remoteOrigin(Context context) {
        return sp(context).getString("remote_origin", "");
    }

    public static void setRemoteOrigin(Context context, String origin) {
        sp(context).edit().putString("remote_origin", origin).apply();
    }

    /** 桥接插件签发的设备 token（只存在 App 私有目录）。 */
    public static String remoteToken(Context context) {
        return sp(context).getString("remote_token", "");
    }

    public static String remoteDeviceId(Context context) {
        return sp(context).getString("remote_device_id", "");
    }

    public static String remoteName(Context context) {
        return sp(context).getString("remote_name", "");
    }

    public static void setRemoteDevice(Context context, String origin, String deviceId, String token, String name) {
        String safeName = name == null || name.trim().isEmpty() ? hostLabel(origin) : name.trim();
        sp(context).edit()
                .putString("remote_origin", origin)
                .putString("remote_device_id", deviceId)
                .putString("remote_token", token)
                .putString("remote_name", safeName)
                .apply();
        upsertProfile(context, origin, deviceId, token, safeName);
    }

    /** 已配对电脑列表（最近使用的在前）；旧版本只有单台时自动迁移。 */
    public static List<Profile> profiles(Context context) {
        List<Profile> result = new ArrayList<>();
        SharedPreferences preferences = sp(context);
        try {
            String raw = preferences.getString("remote_profiles", "[]");
            JSONArray array = new JSONArray(raw == null ? "[]" : raw);
            for (int i = 0; i < array.length(); i++) {
                JSONObject item = array.optJSONObject(i);
                if (item == null) continue;
                String origin = item.optString("origin", "");
                String token = item.optString("token", "");
                if (origin.isEmpty() || token.isEmpty()) continue;
                String name = item.optString("name", "");
                if (name.isEmpty()) name = hostLabel(origin);
                result.add(new Profile(origin, item.optString("deviceId", ""), token, name));
            }
        } catch (Throwable ignored) {
        }
        if (result.isEmpty()) {
            String origin = preferences.getString("remote_origin", "");
            String token = preferences.getString("remote_token", "");
            if (!origin.isEmpty() && !token.isEmpty()) {
                String name = preferences.getString("remote_name", "");
                result.add(new Profile(origin, preferences.getString("remote_device_id", ""), token,
                        name == null || name.isEmpty() ? hostLabel(origin) : name));
            }
        }
        return result;
    }

    private static void upsertProfile(Context context, String origin, String deviceId, String token, String name) {
        List<Profile> list = profiles(context);
        List<Profile> next = new ArrayList<>();
        next.add(new Profile(origin, deviceId == null ? "" : deviceId, token, name));
        for (Profile profile : list) {
            if (!profile.origin.equals(origin)) next.add(profile);
        }
        writeProfiles(context, next);
    }

    private static void writeProfiles(Context context, List<Profile> profiles) {
        JSONArray array = new JSONArray();
        for (Profile profile : profiles) {
            try {
                JSONObject item = new JSONObject();
                item.put("origin", profile.origin);
                item.put("deviceId", profile.deviceId);
                item.put("token", profile.token);
                item.put("name", profile.name);
                array.put(item);
            } catch (Throwable ignored) {
            }
        }
        sp(context).edit().putString("remote_profiles", array.toString()).apply();
    }

    /** 解除当前配对：只删当前电脑，保留其他已配对电脑。 */
    public static void clearRemote(Context context) {
        String origin = sp(context).getString("remote_origin", "");
        List<Profile> list = profiles(context);
        List<Profile> next = new ArrayList<>();
        for (Profile profile : list) {
            if (!profile.origin.equals(origin)) next.add(profile);
        }
        writeProfiles(context, next);
        sp(context).edit()
                .remove("remote_origin")
                .remove("remote_device_id")
                .remove("remote_token")
                .remove("remote_name")
                .remove("notify_channel")
                .remove("notify_endpoint")
                .remove("notify_topic")
                .remove("notify_since")
                .remove("notify_last_id")
                .apply();
    }

    public static String hostLabel(String origin) {
        if (origin == null) return "DSH";
        String value = origin.replaceFirst("^https?://", "");
        int slash = value.indexOf('/');
        if (slash >= 0) value = value.substring(0, slash);
        return value.isEmpty() ? "DSH" : value;
    }

    public static final class Profile {
        public final String origin;
        public final String deviceId;
        public final String token;
        public final String name;

        public Profile(String origin, String deviceId, String token, String name) {
            this.origin = origin;
            this.deviceId = deviceId;
            this.token = token;
            this.name = name;
        }
    }

    // ── 通知订阅（App 直接订阅 ntfy，不依赖 ntfy App） ─────────────────────

    public static String notifyChannel(Context context) {
        return sp(context).getString("notify_channel", "");
    }

    public static String notifyEndpoint(Context context) {
        return sp(context).getString("notify_endpoint", "");
    }

    public static String notifyTopic(Context context) {
        return sp(context).getString("notify_topic", "");
    }

    public static long notifySince(Context context) {
        return sp(context).getLong("notify_since", 0L);
    }

    public static String notifyLastId(Context context) {
        return sp(context).getString("notify_last_id", "");
    }

    /**
     * 保存通知配置。topic 变了才重置 since/last_id，避免把同一 topic 的历史消息重放。
     */
    public static void setNotifyConfig(Context context, String channel, String endpoint, String topic, long since) {
        String oldTopic = sp(context).getString("notify_topic", "");
        SharedPreferences.Editor editor = sp(context).edit()
                .putString("notify_channel", channel == null ? "" : channel)
                .putString("notify_endpoint", endpoint == null ? "" : endpoint)
                .putString("notify_topic", topic == null ? "" : topic);
        if (topic == null || !topic.equals(oldTopic)) {
            editor.putLong("notify_since", since);
            editor.remove("notify_last_id");
        }
        editor.apply();
    }

    public static void setNotifyLastId(Context context, String id) {
        sp(context).edit().putString("notify_last_id", id == null ? "" : id).apply();
    }

    public static void clearNotify(Context context) {
        sp(context).edit()
                .remove("notify_channel")
                .remove("notify_endpoint")
                .remove("notify_topic")
                .remove("notify_since")
                .remove("notify_last_id")
                .apply();
    }

    public static void clear(Context context) {
        sp(context).edit().clear().apply();
    }

    private static SharedPreferences sp(Context context) {
        return context.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }
}
