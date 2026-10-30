package com.deh.mobile;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.ImageFormat;
import android.hardware.Camera;
import android.os.Bundle;
import android.view.Gravity;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.PlanarYUVLuminanceSource;
import com.google.zxing.Result;
import com.google.zxing.common.HybridBinarizer;

import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 极简二维码取景器：Camera1 + ZXing core，只解 QR_CODE。
 * 识别成功把原始文本放在 EXTRA_RAW（deh://pair?...），由 MainActivity 解析。
 */
public class QrScanActivity extends Activity implements SurfaceHolder.Callback, Camera.PreviewCallback {
    public static final String EXTRA_RAW = "raw";
    private static final int REQUEST_CAMERA = 9101;

    private SurfaceView previewView;
    private Camera camera;
    private Camera.Size previewSize;
    private int displayOrientation = 90;

    private final ExecutorService decoder = Executors.newSingleThreadExecutor();
    private final AtomicBoolean decoding = new AtomicBoolean(false);
    private final AtomicBoolean finished = new AtomicBoolean(false);
    private MultiFormatReader reader;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setStatusBarColor(Color.BLACK);

        reader = new MultiFormatReader();
        Map<DecodeHintType, Object> hints = new EnumMap<>(DecodeHintType.class);
        hints.put(DecodeHintType.POSSIBLE_FORMATS, Arrays.asList(BarcodeFormat.QR_CODE));
        hints.put(DecodeHintType.TRY_HARDER, Boolean.TRUE);
        hints.put(DecodeHintType.CHARACTER_SET, "UTF-8");
        reader.setHints(hints);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        previewView = new SurfaceView(this);
        previewView.getHolder().addCallback(this);
        root.addView(previewView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        TextView hint = new TextView(this);
        hint.setText("将电脑上的配对二维码放入取景框");
        hint.setTextColor(Color.WHITE);
        hint.setTextSize(14);
        hint.setGravity(Gravity.CENTER);
        hint.setPadding(dp(16), dp(10), dp(16), dp(10));
        hint.setBackgroundColor(0x99000000);
        FrameLayout.LayoutParams hintLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        hintLp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        hintLp.bottomMargin = dp(72);
        root.addView(hint, hintLp);

        setContentView(root);
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] { Manifest.permission.CAMERA }, REQUEST_CAMERA);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQUEST_CAMERA) return;
        if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            if (previewView != null && previewView.getHolder().getSurface().isValid()) {
                openCamera(previewView.getHolder());
            }
        } else {
            Toast.makeText(this, "需要相机权限才能扫码", Toast.LENGTH_LONG).show();
            finish();
        }
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        openCamera(holder);
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        // 预览尺寸已经在 openCamera 里定好；这里不需要重启。
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        releaseCamera();
    }

    private void openCamera(SurfaceHolder holder) {
        if (camera != null || finished.get()) return;
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return;
        try {
            camera = Camera.open();
            if (camera == null) throw new RuntimeException("Camera.open() 返回 null");
            Camera.Parameters params = camera.getParameters();
            List<String> focusModes = params.getSupportedFocusModes();
            if (focusModes != null && focusModes.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE)) {
                params.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE);
            } else if (focusModes != null && focusModes.contains(Camera.Parameters.FOCUS_MODE_AUTO)) {
                params.setFocusMode(Camera.Parameters.FOCUS_MODE_AUTO);
            }
            params.setPreviewFormat(ImageFormat.NV21);
            Camera.Size best = chooseSize(params.getSupportedPreviewSizes());
            if (best != null) params.setPreviewSize(best.width, best.height);
            camera.setParameters(params);
            previewSize = camera.getParameters().getPreviewSize();
            displayOrientation = 90;
            camera.setDisplayOrientation(displayOrientation);
            camera.setPreviewDisplay(holder);
            camera.setPreviewCallbackWithBuffer(this);
            int bufferSize = previewSize.width * previewSize.height * ImageFormat.getBitsPerPixel(ImageFormat.NV21) / 8;
            camera.addCallbackBuffer(new byte[bufferSize]);
            camera.startPreview();
        } catch (Throwable t) {
            Toast.makeText(this, "打开相机失败：" + t.getMessage(), Toast.LENGTH_LONG).show();
            releaseCamera();
            finish();
        }
    }

    private Camera.Size chooseSize(List<Camera.Size> sizes) {
        if (sizes == null || sizes.isEmpty()) return null;
        Camera.Size best = sizes.get(0);
        long target = 1280L * 720L;
        long bestDiff = Math.abs((long) best.width * best.height - target);
        for (Camera.Size size : sizes) {
            long diff = Math.abs((long) size.width * size.height - target);
            if (diff < bestDiff) {
                best = size;
                bestDiff = diff;
            }
        }
        return best;
    }

    @Override
    public void onPreviewFrame(final byte[] data, Camera source) {
        if (finished.get() || previewSize == null) {
            if (source != null) source.addCallbackBuffer(data);
            return;
        }
        if (!decoding.compareAndSet(false, true)) {
            if (source != null) source.addCallbackBuffer(data);
            return;
        }
        final int width = previewSize.width;
        final int height = previewSize.height;
        decoder.execute(new Runnable() {
            @Override
            public void run() {
                Result result = null;
                try {
                    PlanarYUVLuminanceSource luminance = new PlanarYUVLuminanceSource(
                            data, width, height, 0, 0, width, height, false);
                    BinaryBitmap bitmap = new BinaryBitmap(new HybridBinarizer(luminance));
                    result = reader.decodeWithState(bitmap);
                } catch (Throwable ignored) {
                    // NotFoundException 是正常情况，继续下一帧
                } finally {
                    decoding.set(false);
                    Camera current = camera;
                    if (current != null) {
                        try {
                            current.addCallbackBuffer(data);
                        } catch (Throwable ignored) {
                        }
                    }
                }
                if (result != null && !result.getText().isEmpty()) {
                    finishWith(result.getText());
                }
            }
        });
    }

    private void finishWith(final String raw) {
        if (!finished.compareAndSet(false, true)) return;
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                Intent output = new Intent();
                output.putExtra(EXTRA_RAW, raw);
                setResult(RESULT_OK, output);
                finish();
            }
        });
    }

    private void releaseCamera() {
        Camera current = camera;
        camera = null;
        if (current != null) {
            try {
                current.setPreviewCallbackWithBuffer(null);
            } catch (Throwable ignored) {
            }
            try {
                current.stopPreview();
            } catch (Throwable ignored) {
            }
            try {
                current.release();
            } catch (Throwable ignored) {
            }
        }
    }

    @Override
    protected void onPause() {
        releaseCamera();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        finished.set(true);
        releaseCamera();
        decoder.shutdownNow();
        super.onDestroy();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
