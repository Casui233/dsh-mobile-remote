package com.deh.mobile;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** AlarmManager 到点后重新弹出被“稍后”的通知。 */
public class SnoozeReceiver extends BroadcastReceiver {
    public static final String ACTION_FIRE = "com.deh.mobile.NOTIFY_SNOOZE_FIRE";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (context == null || intent == null) return;
        String title = intent.getStringExtra(NotificationActionReceiver.EXTRA_TITLE);
        String message = intent.getStringExtra(NotificationActionReceiver.EXTRA_MESSAGE);
        NotifyService.postSnoozed(context, title, message);
    }
}
