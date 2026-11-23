package com.duopost.daoche.viewer;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.MediaRecorder;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.Display;
import android.view.WindowManager;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 全屏录像服务：MediaProjection 录制查看端屏幕（= 倒车影像画面），
 * 带麦克风录音，输出 MP4 到相册「Movies/倒车影像」目录。
 */
public class RecordingService extends Service {

    public static volatile boolean recording = false;

    private MediaProjection projection;
    private VirtualDisplay virtualDisplay;
    private MediaRecorder recorder;
    private Uri savedUri;      // Android 10+ 写相册用
    private File legacyFile;   // Android 9- 直写文件用
    private String savePath;

    private final Handler main = new Handler(Looper.getMainLooper());

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForegroundWithType();

        if (intent == null || intent.getAction() == null) {
            stopSelf();
            return START_NOT_STICKY;
        }

        if ("start".equals(intent.getAction())) {
            startRecording(intent);
        } else if ("stop".equals(intent.getAction())) {
            stopRecording();
        }
        return START_NOT_STICKY;
    }

    private void startForegroundWithType() {
        String id = "daoche_rec_channel";
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(id, "录像服务",
                NotificationManager.IMPORTANCE_LOW));
        PendingIntent pi = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification n = new Notification.Builder(this, id)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle("正在录制倒车影像")
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        } else {
            startForeground(1, n);
        }
    }

    private void startRecording(Intent intent) {
        try {
            android.media.projection.MediaProjectionManager pm =
                    (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
            Intent proj = intent.getParcelableExtra("data");
            projection = pm.getMediaProjection(intent.getIntExtra("code", 0), proj);

            WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
            Display d = wm.getDefaultDisplay();
            DisplayMetrics metrics = new DisplayMetrics();
            d.getRealMetrics(metrics);
            int w = Math.min(metrics.widthPixels, 1280) / 2 * 2;   // 偶数
            int h = Math.min(metrics.heightPixels, 720) / 2 * 2;
            if (w > h) { /* 保持原比例，只限制最大边 */ }

            recorder = new MediaRecorder();
            // 麦克风录音（做说明用）；无权限时 exception 会走 catch
            recorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            recorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            recorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            recorder.setVideoEncodingBitRate(6_000_000);
            recorder.setVideoFrameRate(30);
            recorder.setVideoSize(w, h);
            savePath = "倒车影像-" + new SimpleDateFormat("MMdd-HHmmss", Locale.CHINA).format(new Date());
            setupOutput();

            recorder.prepare();
            android.util.Log.d("DaoCheRec", "prepare ok, w=" + w + " h=" + h);

            // Android 14 要求：必须先注册回调，再 createVirtualDisplay
            projection.registerCallback(new MediaProjection.Callback() {
                @Override
                public void onStop() {
                    main.post(() -> stopRecording());
                }
            }, main);

            virtualDisplay = projection.createVirtualDisplay("daoche-rec",
                    w, h, metrics.densityDpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    recorder.getSurface(), null, main);
            android.util.Log.d("DaoCheRec", "virtual display created");

            recorder.start();
            recording = true;
            android.util.Log.d("DaoCheRec", "recording started");
            MainActivity.onRecordStateChanged(true);
        } catch (Throwable e) {
            android.util.Log.e("DaoCheRec", "start failed", e);
            recording = false;
            cleanup();
            String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            MainActivity.onRecordError("录音需要麦克风权限，或设备不支持：" + msg);
            stopSelf();
        }
    }

    /** Android 10+ 写入公共 Movies 目录（MediaStore，无需存储权限）；旧系统直写文件 */
    private void setupOutput() throws Exception {
        if (Build.VERSION.SDK_INT >= 29) {
            android.content.ContentValues cv = new android.content.ContentValues();
            cv.put(android.provider.MediaStore.Video.Media.RELATIVE_PATH,
                    Environment.DIRECTORY_MOVIES + "/倒车影像");
            cv.put(android.provider.MediaStore.Video.Media.DISPLAY_NAME, savePath + ".mp4");
            cv.put(android.provider.MediaStore.Video.Media.MIME_TYPE, "video/mp4");
            savedUri = getContentResolver().insert(
                    android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI, cv);
            recorder.setOutputFile(getContentResolver().openFileDescriptor(savedUri, "rw")
                    .getFileDescriptor());
        } else {
            File dir = new File(Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_MOVIES), "倒车影像");
            if (!dir.exists()) dir.mkdirs();
            legacyFile = new File(dir, savePath + ".mp4");
            recorder.setOutputFile(legacyFile.getAbsolutePath());
        }
    }

    private void stopRecording() {
        if (!recording) return;
        recording = false;
        try {
            recorder.stop();
            // 让相册立刻索引到
            if (savedUri != null) {
                android.content.ContentValues cv = new android.content.ContentValues();
                cv.put(android.provider.MediaStore.Video.Media.IS_PENDING, 0);
                getContentResolver().update(savedUri, cv, null, null);
            }
            savePath = null;
            MainActivity.onRecordStateChanged(false);
            String where = Build.VERSION.SDK_INT >= 29
                    ? "相册 → Movies → 倒车影像"
                    : "SD卡 → Movies → 倒车影像";
            MainActivity.onRecordSaved("已保存到 " + where);
        } catch (Exception e) {
            recording = false;
            MainActivity.onRecordStateChanged(false);
            MainActivity.onRecordError("录像太短或异常：" + e.getMessage());
        }
        cleanup();
        stopSelf();
    }

    private void cleanup() {
        try {
            if (virtualDisplay != null) virtualDisplay.release();
        } catch (Exception ignored) {
        }
        try {
            if (projection != null) projection.stop();
        } catch (Exception ignored) {
        }
        try {
            if (recorder != null) recorder.release();
        } catch (Exception ignored) {
        }
        virtualDisplay = null;
        projection = null;
        recorder = null;
    }

    @Override
    public void onDestroy() {
        if (recording) {
            recording = false;
            try {
                recorder.stop();
            } catch (Exception ignored) {
            }
            cleanup();
            MainActivity.onRecordStateChanged(false);
        }
        super.onDestroy();
    }
}
