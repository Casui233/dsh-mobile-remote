package com.deh.mobile;

import android.app.Activity;
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
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** 手机端回答 ask_user_question；从通知 token 拉取题目并回传结构化答案。 */
public class QuestionAnswerActivity extends Activity {
    public static final String EXTRA_TOKEN = "token";

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final List<QuestionBlock> blocks = new ArrayList<>();
    private LinearLayout container;
    private TextView statusView;
    private Button submitButton;
    private String token;
    private boolean submitting;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        token = getIntent() == null ? null : getIntent().getStringExtra(EXTRA_TOKEN);
        buildUi();
        if (token == null || token.isEmpty()) {
            status("动作已过期");
            return;
        }
        load();
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
        title.setText("回答问题");
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

        statusView = new TextView(this);
        statusView.setTextColor(0xFF8B93A3);
        statusView.setTextSize(12);
        statusView.setText("正在读取问题…");
        statusView.setPadding(0, dp(2), 0, dp(6));
        root.addView(statusView);

        ScrollView scroll = new ScrollView(this);
        container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(container);
        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        submitButton = new Button(this);
        submitButton.setAllCaps(false);
        submitButton.setText("提交回答");
        submitButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                submit();
            }
        });
        root.addView(submitButton);

        setContentView(root);
    }

    private void load() {
        io.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    JSONObject json = RemoteActionClient.get(QuestionAnswerActivity.this, token);
                    main.post(new Runnable() {
                        @Override
                        public void run() {
                            render(json);
                        }
                    });
                } catch (final Throwable error) {
                    main.post(new Runnable() {
                        @Override
                        public void run() {
                            status("读取失败：" + message(error));
                        }
                    });
                }
            }
        });
    }

    private void render(JSONObject json) {
        String body = json.optString("body", "");
        if (!body.isEmpty()) {
            TextView intro = new TextView(this);
            intro.setText(body);
            intro.setTextColor(0xFF8B93A3);
            intro.setTextSize(12);
            intro.setPadding(0, 0, 0, dp(8));
            container.addView(intro);
        }
        JSONArray questions = json.optJSONArray("questions");
        if (questions == null || questions.length() == 0) {
            status("没有可回答的问题");
            submitButton.setEnabled(false);
            return;
        }
        for (int i = 0; i < questions.length(); i++) {
            JSONObject question = questions.optJSONObject(i);
            if (question == null) continue;
            QuestionBlock block = new QuestionBlock(question);
            blocks.add(block);
            container.addView(block.view);
        }
        status("选择选项或填写回答");
    }

    private void submit() {
        if (submitting) return;
        JSONArray answers = new JSONArray();
        try {
            for (QuestionBlock block : blocks) {
                JSONObject answer = new JSONObject();
                answer.put("id", block.id);
                JSONArray selected = new JSONArray();
                String custom = block.custom.getText().toString().trim();
                if (block.multi || custom.isEmpty()) {
                    for (String label : block.selectedLabels()) selected.put(label);
                }
                answer.put("selected", selected);
                if (!custom.isEmpty()) answer.put("custom", custom);
                answers.put(answer);
            }
        } catch (Throwable error) {
            status("答案格式错误：" + message(error));
            return;
        }
        submitting = true;
        submitButton.setEnabled(false);
        status("正在提交…");
        io.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    JSONObject body = new JSONObject();
                    body.put("answers", answers);
                    RemoteActionClient.post(QuestionAnswerActivity.this, token, body);
                    main.post(new Runnable() {
                        @Override
                        public void run() {
                            Toast.makeText(QuestionAnswerActivity.this, "已提交回答", Toast.LENGTH_LONG).show();
                            finish();
                        }
                    });
                } catch (final Throwable error) {
                    main.post(new Runnable() {
                        @Override
                        public void run() {
                            submitting = false;
                            submitButton.setEnabled(true);
                            status("提交失败：" + message(error));
                        }
                    });
                }
            }
        });
    }

    private class QuestionBlock {
        final String id;
        final LinearLayout view;
        final EditText custom;
        final List<CompoundButton> choices = new ArrayList<>();
        final boolean multi;

        QuestionBlock(JSONObject question) {
            id = question.optString("id", "");
            multi = question.optBoolean("multiSelect", false);
            view = new LinearLayout(QuestionAnswerActivity.this);
            view.setOrientation(LinearLayout.VERTICAL);
            view.setPadding(0, dp(6), 0, dp(6));

            String header = question.optString("header", "");
            if (!header.isEmpty()) {
                TextView headerView = new TextView(QuestionAnswerActivity.this);
                headerView.setText(header);
                headerView.setTextColor(0xFF8AB4F8);
                headerView.setTextSize(13);
                view.addView(headerView);
            }
            TextView questionView = new TextView(QuestionAnswerActivity.this);
            questionView.setText(question.optString("question", ""));
            questionView.setTextColor(0xFFE8EAED);
            questionView.setTextSize(15);
            questionView.setPadding(0, dp(2), 0, dp(4));
            view.addView(questionView);

            JSONArray options = question.optJSONArray("options");
            if (options != null && options.length() > 0) {
                if (multi) {
                    for (int i = 0; i < options.length(); i++) {
                        addChoice(options.optJSONObject(i), null);
                    }
                } else {
                    RadioGroup group = new RadioGroup(QuestionAnswerActivity.this);
                    group.setOrientation(RadioGroup.VERTICAL);
                    view.addView(group);
                    for (int i = 0; i < options.length(); i++) {
                        addChoice(options.optJSONObject(i), group);
                    }
                }
            }

            custom = new EditText(QuestionAnswerActivity.this);
            custom.setHint(options == null || options.length() == 0 ? "输入回答" : "其他/补充回答（可选）");
            custom.setHintTextColor(0xFF5F6675);
            custom.setTextColor(0xFFE8EAED);
            custom.setTextSize(14);
            custom.setBackgroundColor(0xFF171A21);
            custom.setPadding(dp(8), dp(8), dp(8), dp(8));
            view.addView(custom);

            View divider = new View(QuestionAnswerActivity.this);
            divider.setBackgroundColor(0xFF232833);
            LinearLayout.LayoutParams dividerLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
            dividerLp.topMargin = dp(8);
            view.addView(divider, dividerLp);
        }

        private void addChoice(JSONObject option, RadioGroup group) {
            if (option == null) return;
            String label = option.optString("label", "");
            String description = option.optString("description", "");
            String text = description.isEmpty() ? label : label + " — " + description;
            CompoundButton button;
            if (group == null) {
                CheckBox checkBox = new CheckBox(QuestionAnswerActivity.this);
                checkBox.setText(text);
                checkBox.setTextColor(0xFFE8EAED);
                checkBox.setTextSize(14);
                button = checkBox;
                view.addView(checkBox);
            } else {
                RadioButton radio = new RadioButton(QuestionAnswerActivity.this);
                radio.setText(text);
                radio.setTextColor(0xFFE8EAED);
                radio.setTextSize(14);
                button = radio;
                group.addView(radio);
            }
            button.setTag(label);
            choices.add(button);
        }

        List<String> selectedLabels() {
            List<String> labels = new ArrayList<>();
            for (CompoundButton button : choices) {
                if (button.isChecked()) labels.add(String.valueOf(button.getTag()));
            }
            return labels;
        }
    }

    private String message(Throwable error) {
        String message = error == null ? "" : error.getMessage();
        return message == null || message.isEmpty() ? "网络错误" : message;
    }

    private void status(String text) {
        if (statusView != null) statusView.setText(text);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onDestroy() {
        io.shutdownNow();
        super.onDestroy();
    }
}
