package com.deh.mobile;

import android.app.Activity;
import android.app.NotificationManager;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Toast;

import org.json.JSONObject;

/** 通知动作入口：批准/拒绝直接提交；回答打开问题页。 */
public class RemoteActionActivity extends Activity {
    public static final String EXTRA_NOTIFICATION_ID = "notification_id";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Intent source = getIntent();
        if (source != null) {
            int notificationId = source.getIntExtra(EXTRA_NOTIFICATION_ID, 0);
            if (notificationId != 0) {
                NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
                if (manager != null) manager.cancel(notificationId);
            }
        }
        Uri data = source == null ? null : source.getData();
        if (data == null) {
            finish();
            return;
        }
        String host = data.getHost();
        String token = data.getQueryParameter("token");
        if (token == null || token.isEmpty()) {
            Toast.makeText(this, "动作已过期", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        if ("remote-question".equals(host)) {
            Intent intent = new Intent(this, QuestionAnswerActivity.class);
            intent.putExtra(QuestionAnswerActivity.EXTRA_TOKEN, token);
            startActivity(intent);
            finish();
            return;
        }
        final String outcome = data.getQueryParameter("outcome");
        if (!"allowed-once".equals(outcome) && !"rejected".equals(outcome)) {
            finish();
            return;
        }
        final String approvalToken = token;
        final String approvalOutcome = outcome;
        Toast.makeText(this, "正在提交…", Toast.LENGTH_SHORT).show();
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    JSONObject body = new JSONObject();
                    body.put("outcome", approvalOutcome);
                    RemoteActionClient.post(RemoteActionActivity.this, approvalToken, body);
                    toastOnUi("allowed-once".equals(approvalOutcome) ? "已批准" : "已拒绝");
                } catch (Throwable error) {
                    toastOnUi("提交失败：" + (error.getMessage() == null ? "网络错误" : error.getMessage()));
                } finally {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            finish();
                        }
                    });
                }
            }
        }, "dsh-remote-approval").start();
    }

    private void toastOnUi(final String text) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(RemoteActionActivity.this, text, Toast.LENGTH_LONG).show();
            }
        });
    }
}
