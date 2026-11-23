package com.duopost.daoche.viewer;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.projection.MediaProjectionManager;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.Toast;

/**
 * 倒车影像查看端：WebView 装载本地页面（自动发现摄像头 + 全屏播放）。
 * 系统级横屏锁定（sensorLandscape）+ 沉浸式全屏。
 * 左下角「录像」按钮：MediaProjection 全屏录制看到的画面（含麦克风说明），MP4 存相册。
 */
public class MainActivity extends Activity {

    private static final int REQ_MIC = 101;
    private static final int REQ_PROJECTION = 102;
    private static MainActivity instance = null;

    private WebView web;
    private Button btnRec;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        instance = this;

        // 沉浸全屏 + 常亮
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        web = new WebView(this);
        FrameLayout root = new FrameLayout(this);
        root.addView(web);

        // 悬浮录像按钮（半透明，不挡画面主体）
        btnRec = new Button(this);
        btnRec.setText("● 录像");
        btnRec.setTextColor(0xFFFFFFFF);
        btnRec.setBackgroundColor(0x66000000);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.LEFT);
        lp.leftMargin = dp(14);
        lp.bottomMargin = dp(14);
        btnRec.setLayoutParams(lp);
        btnRec.setOnClickListener(v -> toggleRecord());
        root.addView(btnRec);

        setContentView(root);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);          // localStorage 记住地址
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setCacheMode(WebSettings.LOAD_NO_CACHE);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        s.setLoadsImagesAutomatically(true);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);

        web.setWebViewClient(new WebViewClient());
        web.setWebChromeClient(new android.webkit.WebChromeClient());

        if (savedInstanceState == null) {
            web.loadUrl("file:///android_asset/daocheyx.html");
        } else {
            web.restoreState(savedInstanceState);
        }
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    // ---------------- 录像 ----------------

    private void toggleRecord() {
        if (RecordingService.recording) {
            startService(new Intent(this, RecordingService.class).setAction("stop"));
            return;
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC);
            return;
        }
        requestProjection();
    }

    private void requestProjection() {
        MediaProjectionManager pm = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        startActivityForResult(pm.createScreenCaptureIntent(), REQ_PROJECTION);
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        if (code == REQ_MIC) {
            if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
                requestProjection();
            } else {
                Toast.makeText(this, "没有麦克风权限就录不了带说明的视频", Toast.LENGTH_SHORT).show();
            }
        }
    }

    @Override
    protected void onActivityResult(int code, int res, Intent data) {
        super.onActivityResult(code, res, data);
        if (code == REQ_PROJECTION) {
            if (res == RESULT_OK && data != null) {
                Intent i = new Intent(this, RecordingService.class).setAction("start");
                i.putExtra("code", res);
                i.putExtra("data", data);  // 投影授权结果原样传给服务
                startForegroundService(i);
            } else {
                Toast.makeText(this, "未授权录屏", Toast.LENGTH_SHORT).show();
            }
        }
    }

    // ---- Service 回调（切回主线程）----

    public static void onRecordStateChanged(boolean rec) {
        final boolean r = rec;
        if (instance != null) instance.runOnUiThread(() -> {
            if (instance.btnRec != null) instance.btnRec.setText(r ? "■ 停止" : "● 录像");
        });
    }

    public static void onRecordError(String msg) {
        final String m = msg;
        if (instance != null) instance.runOnUiThread(() ->
                Toast.makeText(instance, "录像失败：" + m, Toast.LENGTH_LONG).show());
    }

    public static void onRecordSaved(String msg) {
        final String m = msg;
        if (instance != null) instance.runOnUiThread(() ->
                Toast.makeText(instance, m, Toast.LENGTH_LONG).show());
    }

    // ---------------- 生命周期 ----------------

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        web.saveState(out);
    }

    @Override
    public void onBackPressed() {
        if (RecordingService.recording) {
            Toast.makeText(this, "正在录像，先点 ■ 停止", Toast.LENGTH_SHORT).show();
            return;
        }
        finish();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                            | View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        }
    }

    @Override
    protected void onDestroy() {
        instance = null;
        if (RecordingService.recording) {
            startService(new Intent(this, RecordingService.class).setAction("stop"));
        }
        if (web != null) {
            web.loadUrl("about:blank");
            web.destroy();
        }
        super.onDestroy();
    }
}
