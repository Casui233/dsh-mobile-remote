package com.deh.mobile;

import android.app.AlarmManager;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** 通知上的“稍后 10 分钟”动作：取消原通知并安排一次延时提醒。 */
public class NotificationActionReceiver extends BroadcastReceiver {
    public static final String ACTION_SNOOZE = "com.deh.mobile.NOTIFY_SNOOZE";
    public static final String EXTRA_NOTIFICATION_ID = "notification_id";
    public static final String EXTRA_TITLE = "title";
    public static final String EXTRA_MESSAGE = "message";
    private static final long SNOOZE_MS = 10L * 60L * 1000L;

    @Override
    public void onReceive(Context context, Intent intent) {
        if (context == null || intent == null) return;
        if (!ACTION_SNOOZE.equals(intent.getAction())) return;
        int id = intent.getIntExtra(EXTRA_NOTIFICATION_ID, 0);
        String title = intent.getStringExtra(EXTRA_TITLE);
        String message = intent.getStringExtra(EXTRA_MESSAGE);
        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null && id != 0) manager.cancel(id);
        Intent fire = new Intent(context, SnoozeReceiver.class);
        fire.setAction(SnoozeReceiver.ACTION_FIRE);
        fire.putExtra(EXTRA_TITLE, title);
        fire.putExtra(EXTRA_MESSAGE, message);
        PendingIntent pending = PendingIntent.getBroadcast(context, id + 300000, fire,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        AlarmManager alarm = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (alarm != null) {
            alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + SNOOZE_MS, pending);
        }
    }
}
