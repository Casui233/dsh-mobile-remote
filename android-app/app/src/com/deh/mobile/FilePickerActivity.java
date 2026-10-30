package com.deh.mobile;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * App 内多选文件浏览器。系统 DocumentsUI 在混合 MIME 多选时会只返回一个 URI，
 * 这个 Activity 直接遍历共享存储，允许任意格式多选，再通过 SimpleFileProvider 返回 content://。
 */
public class FilePickerActivity extends Activity {
    public static final String EXTRA_MULTIPLE = "multiple";
    public static final String EXTRA_RESULT_URIS = "uris";
    private static final int REQUEST_STORAGE = 9301;

    private boolean multiple = true;
    private File currentDir;
    private final List<File> items = new ArrayList<>();
    private final Set<String> selected = new LinkedHashSet<>();
    private TextView pathView;
    private Button doneButton;
    private ListView listView;
    private FileAdapter adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        multiple = getIntent() == null || getIntent().getBooleanExtra(EXTRA_MULTIPLE, true);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF0F1115);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(8), dp(8), dp(8), dp(8));
        header.setBackgroundColor(0xFF12151C);

        Button up = new Button(this);
        up.setText("↑");
        up.setAllCaps(false);
        up.setTextSize(18);
        up.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                goUp();
            }
        });
        header.addView(up, new LinearLayout.LayoutParams(dp(48), dp(44)));

        pathView = new TextView(this);
        pathView.setTextColor(0xFFE8EAED);
        pathView.setTextSize(12);
        pathView.setSingleLine(true);
        pathView.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        LinearLayout.LayoutParams pathLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        pathLp.leftMargin = dp(8);
        pathLp.rightMargin = dp(8);
        header.addView(pathView, pathLp);

        doneButton = new Button(this);
        doneButton.setAllCaps(false);
        doneButton.setTextColor(Color.WHITE);
        doneButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finishWithResult();
            }
        });
        header.addView(doneButton);
        root.addView(header);

        listView = new ListView(this);
        listView.setBackgroundColor(0xFF0F1115);
        listView.setDivider(null);
        adapter = new FileAdapter();
        listView.setAdapter(adapter);
        root.addView(listView, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);
        updateDone();
        ensurePermissionThenLoad();
    }

    private void ensurePermissionThenLoad() {
        if (Build.VERSION.SDK_INT >= 30) {
            if (!Environment.isExternalStorageManager()) {
                new AlertDialog.Builder(this)
                        .setTitle("需要文件访问权限")
                        .setMessage("App 内多选文件需要「所有文件访问权限」。授权后返回即可继续。")
                        .setPositiveButton("去授权", new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
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
                        })
                        .setNegativeButton("取消", new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                finish();
                            }
                        })
                        .show();
            }
        } else if (checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] { Manifest.permission.READ_EXTERNAL_STORAGE }, REQUEST_STORAGE);
        }
        loadStartDir();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_STORAGE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                loadStartDir();
            } else {
                Toast.makeText(this, "需要存储权限才能选择文件", Toast.LENGTH_LONG).show();
                finish();
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (currentDir != null) load(currentDir);
    }

    private void loadStartDir() {
        File start = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        if (start == null || !start.isDirectory()) start = Environment.getExternalStorageDirectory();
        if (start == null || !start.isDirectory()) {
            Toast.makeText(this, "无法访问共享存储", Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        currentDir = start;
        load(currentDir);
    }

    private void load(File dir) {
        currentDir = dir;
        items.clear();
        File[] files = dir.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.getName().startsWith(".")) continue;
                items.add(file);
            }
        }
        Collections.sort(items, new Comparator<File>() {
            @Override
            public int compare(File a, File b) {
                if (a.isDirectory() != b.isDirectory()) return a.isDirectory() ? -1 : 1;
                return a.getName().toLowerCase(Locale.US).compareTo(b.getName().toLowerCase(Locale.US));
            }
        });
        pathView.setText(dir.getAbsolutePath());
        adapter.notifyDataSetChanged();
    }

    private void goUp() {
        File parent = currentDir == null ? null : currentDir.getParentFile();
        if (parent != null && parent.canRead()) {
            load(parent);
        } else {
            finish();
        }
    }

    @Override
    public void onBackPressed() {
        File parent = currentDir == null ? null : currentDir.getParentFile();
        if (parent != null && parent.canRead()) {
            load(parent);
            return;
        }
        super.onBackPressed();
    }

    private void toggle(File file) {
        if (file.isDirectory()) {
            load(file);
            return;
        }
        if (!multiple) {
            selected.clear();
            selected.add(file.getAbsolutePath());
            finishWithResult();
            return;
        }
        String path = file.getAbsolutePath();
        if (selected.contains(path)) selected.remove(path);
        else selected.add(path);
        adapter.notifyDataSetChanged();
        updateDone();
    }

    private void updateDone() {
        doneButton.setText("完成 (" + selected.size() + ")");
    }

    private void finishWithResult() {
        if (selected.isEmpty()) {
            Toast.makeText(this, "还没有选择文件", Toast.LENGTH_SHORT).show();
            return;
        }
        List<Uri> uris = new ArrayList<>();
        for (String path : selected) {
            File file = new File(path);
            if (!file.isFile()) continue;
            uris.add(SimpleFileProvider.uriFor(file));
        }
        if (uris.isEmpty()) {
            Toast.makeText(this, "选中的文件已不存在", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent output = new Intent();
        output.putExtra(EXTRA_RESULT_URIS, uris.toArray(new Uri[0]));
        setResult(RESULT_OK, output);
        finish();
    }

    private String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(Locale.US, "%.1f KiB", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024) return String.format(Locale.US, "%.1f MiB", bytes / (1024.0 * 1024));
        return String.format(Locale.US, "%.2f GiB", bytes / (1024.0 * 1024 * 1024));
    }

    private class FileAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return items.size();
        }

        @Override
        public Object getItem(int position) {
            return items.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            final File file = items.get(position);
            boolean dir = file.isDirectory();
            boolean picked = selected.contains(file.getAbsolutePath());

            LinearLayout row = new LinearLayout(FilePickerActivity.this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(10), dp(8), dp(10), dp(8));
            row.setBackgroundColor(picked ? 0x223B82F6 : 0x00000000);

            CheckBox check = new CheckBox(FilePickerActivity.this);
            check.setClickable(false);
            check.setFocusable(false);
            check.setChecked(picked);
            if (dir) check.setVisibility(View.INVISIBLE);
            row.addView(check, new LinearLayout.LayoutParams(dp(38), dp(38)));

            TextView icon = new TextView(FilePickerActivity.this);
            icon.setText(dir ? "📁" : "📄");
            icon.setTextSize(18);
            row.addView(icon, new LinearLayout.LayoutParams(dp(30), ViewGroup.LayoutParams.WRAP_CONTENT));

            LinearLayout col = new LinearLayout(FilePickerActivity.this);
            col.setOrientation(LinearLayout.VERTICAL);
            TextView name = new TextView(FilePickerActivity.this);
            name.setText(file.getName());
            name.setTextColor(0xFFE8EAED);
            name.setTextSize(14);
            TextView meta = new TextView(FilePickerActivity.this);
            meta.setText(dir ? "目录" : formatSize(file.length()));
            meta.setTextColor(0xFF7F8797);
            meta.setTextSize(11);
            col.addView(name);
            col.addView(meta);
            LinearLayout.LayoutParams colLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            colLp.leftMargin = dp(8);
            row.addView(col, colLp);

            row.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    toggle(file);
                }
            });
            return row;
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
