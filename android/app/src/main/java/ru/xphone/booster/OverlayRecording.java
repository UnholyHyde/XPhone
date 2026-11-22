package ru.xphone.booster;

import android.app.*;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.*;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.*;
import android.provider.MediaStore;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.ImageView;
import android.widget.Toast;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

@android.annotation.TargetApi(29)
public final class OverlayRecording extends Service {
    private static final String STOP = "stop";
    private static final String CHANNEL = "xphone_recording";
    private static final int NOTICE = 49;
    private static volatile boolean active;
    private static OverlayRecording instance;
    private final Handler main = new Handler(Looper.getMainLooper());
    private Handler worker;
    private MediaProjection projection;
    private VirtualDisplay display;
    private MediaRecorder video;
    private RecordingAudio audio;
    private RecordingFrames frames;
    private android.view.Surface encoderInput;
    private File videoFile, audioFile, mergedFile;
    private View controls;
    private View opacityWindow;
    private boolean started, paused, finishing, mic, changedTouches, previousTouches;
    private boolean destroyed;

    static boolean available() { return Build.VERSION.SDK_INT >= 29; }
    static boolean active() { return active; }

    static void request(Context context) {
        if (active) {
            context.startService(new Intent(context, OverlayRecording.class).setAction(STOP));
        } else {
            Point size = outputSize(context, Prefs.getInt(context, "record_quality", 1));
            context.startActivity(new Intent(context, RecordingConsent.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra("width", size.x).putExtra("height", size.y));
        }
    }

    @SuppressWarnings("deprecation")
    static Point outputSize(Context context, int quality) {
        Point size = new Point();
        context.getSystemService(WindowManager.class).getDefaultDisplay().getRealSize(size);
        int[] limits = {720, 1080, 1440};
        int limit = limits[Math.max(0, Math.min(limits.length - 1, quality))];
        double scale = Math.min(1.0, (double) limit / Math.max(1, Math.min(size.x, size.y)));
        return new Point(Math.max(2, ((int) (size.x * scale)) & ~1),
                Math.max(2, ((int) (size.y * scale)) & ~1));
    }

    @Override public void onCreate() {
        super.onCreate();
        instance = this;
        HandlerThread thread = new HandlerThread("XPhone-record-video");
        thread.start();
        worker = new Handler(thread.getLooper());
    }

    @Override public int onStartCommand(Intent intent, int flags, int id) {
        if (intent == null || STOP.equals(intent.getAction())) {
            worker.post(() -> finishRecording(true));
            return START_NOT_STICKY;
        }
        if (active) return START_NOT_STICKY;
        mic = intent.getBooleanExtra("mic", false);
        try {
            startForeground(NOTICE, notification("Подготовка записи"),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                            | (mic && Build.VERSION.SDK_INT >= 30 ? ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE : 0));
        } catch (RuntimeException error) {
            android.util.Log.w("XPhoneRecording", "Cannot enter foreground", error);
            Diagnostics.failure(this, "record.foreground.failed", error);
            toast("Не удалось запустить запись экрана");
            stopSelf();
            return START_NOT_STICKY;
        }
        active = true;
        // Let the game return to its original orientation after the permission screen closes.
        worker.postDelayed(() -> { if (!finishing) begin(intent); }, 850);
        return START_NOT_STICKY;
    }

    @SuppressWarnings("deprecation")
    private void begin(Intent intent) {
        try {
            Intent consent = intent.getParcelableExtra("consent");
            projection = getSystemService(MediaProjectionManager.class).getMediaProjection(Activity.RESULT_OK, consent);
            if (projection == null) throw new IOException("Projection unavailable");
            projection.registerCallback(new MediaProjection.Callback() {
                @Override public void onStop() { finishRecording(true); }
            }, worker);

            previousTouches = TouchTrace.enabled(this);
            boolean touches = intent.getBooleanExtra("touches", false);
            changedTouches = touches != previousTouches;
            if (changedTouches && !TouchTrace.set(this, touches) && touches)
                main.post(() -> toast("Касания недоступны. Запись продолжится без них."));

            videoFile = File.createTempFile("xphone-video-", ".mp4", getCacheDir());
            int quality = Math.max(0, Math.min(2, intent.getIntExtra("quality", 1)));
            int width = intent.getIntExtra("width", 0);
            int height = intent.getIntExtra("height", 0);
            if (width < 2 || height < 2) throw new IOException("Invalid recording size");
            video = new MediaRecorder();
            video.setVideoSource(MediaRecorder.VideoSource.SURFACE);
            video.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            video.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
            video.setVideoSize(width, height);
            video.setVideoFrameRate(30);
            video.setVideoEncodingBitRate(new int[]{5000000, 9000000, 16000000}[quality]);
            video.setOutputFile(videoFile.getPath());
            video.setOnErrorListener((recorder, what, extra) -> worker.post(() -> finishRecording(true)));
            video.prepare();
            encoderInput = video.getSurface();
            if (touches && Build.VERSION.SDK_INT >= 34) {
                Ui.load(this);
                frames = new RecordingFrames(encoderInput, worker, width, height,
                        Ui.GLASS ? Ui.ACCENT : android.graphics.Color.WHITE,
                        2 * Ui.dp(this, 17) * Math.min(width, height) /
                                (float) Math.min(getResources().getDisplayMetrics().widthPixels,
                                        getResources().getDisplayMetrics().heightPixels),
                        getSystemService(DisplayManager.class).getDisplay(android.view.Display.DEFAULT_DISPLAY));
            }
            display = projection.createVirtualDisplay("XPhone recording", width, height,
                    getResources().getDisplayMetrics().densityDpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    frames == null ? encoderInput : frames.input(), null, worker);
            if (display == null) throw new IOException("Virtual display unavailable");
            boolean playback = intent.getBooleanExtra("playback", false);
            if (mic || playback) {
                audioFile = File.createTempFile("xphone-audio-", ".m4a", getCacheDir());
                audio = new RecordingAudio(audioFile, projection, mic, playback);
            }
            video.start();
            started = true;
            if (audio != null) audio.start();
            Diagnostics.event(this, "record.started " + width + "x" + height + " mic=" + mic + " playback=" + playback);
            main.post(() -> {
                if (destroyed || !active) return;
                getSystemService(NotificationManager.class).notify(NOTICE, notification("Идёт запись экрана"));
                showControls();
            });
        } catch (Exception error) {
            android.util.Log.w("XPhoneRecording", "Cannot begin capture", error);
            Diagnostics.failure(this, "record.start.failed", error);
            main.post(() -> toast("Не удалось начать запись. Попробуйте снизить качество или отключить звук."));
            finishRecording(false);
        }
    }

    private Notification notification(String title) {
        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel(CHANNEL, "Запись экрана XPhone", NotificationManager.IMPORTANCE_LOW));
        PendingIntent stop = PendingIntent.getService(this, 49, new Intent(this, OverlayRecording.class).setAction(STOP),
                PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_video).setContentTitle(title)
                .setOngoing(true).addAction(new Notification.Action.Builder(null, "Стоп", stop).build()).build();
    }

    private void showControls() {
        if (!MainActivity.overlayOk(this)) return;
        Ui.load(this);
        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER);
        bar.setPadding(Ui.dp(this, 4), 0, Ui.dp(this, 4), 0);
        bar.setBackground(Ui.box(this, Ui.BG, Ui.LINE2, Ui.R_CARD));
        bar.setAlpha(controlsAlpha(this));
        bar.setOnLongClickListener(v -> { showOpacity(); return true; });
        if (mic) {
            ImageView mute = button(bar, R.drawable.ic_mic, "Выключить микрофон записи");
            mute.setOnClickListener(v -> worker.post(() -> {
                if (audio == null || finishing) return;
                audio.muted = !audio.muted;
                boolean muted = audio.muted;
                main.post(() -> {
                    mute.setImageResource(muted ? R.drawable.ic_mic_off : R.drawable.ic_mic);
                    mute.setContentDescription(muted ? "Включить микрофон записи" : "Выключить микрофон записи");
                });
            }));
        }
        ImageView pause = button(bar, R.drawable.ic_pause, "Приостановить запись");
        pause.setOnClickListener(v -> worker.post(() -> togglePause(pause)));
        button(bar, R.drawable.ic_stop, "Остановить запись").setOnClickListener(v -> worker.post(() -> finishRecording(true)));
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(-2, -2,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_SECURE,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.END;
        params.x = Ui.dp(this, 8);
        params.y = Ui.dp(this, 8);
        params.setTitle("XPhone recording controls");
        try {
            getSystemService(WindowManager.class).addView(bar, params);
            controls = bar;
        } catch (RuntimeException error) {
            Diagnostics.failure(this, "record.controls.failed", error);
        }
    }

    private ImageView button(LinearLayout parent, int icon, String text) {
        ImageView button = Ui.icon(this, icon, 20, Ui.TEXT);
        button.setPadding(Ui.dp(this, 12), Ui.dp(this, 12), Ui.dp(this, 12), Ui.dp(this, 12));
        button.setBackground(Ui.box(this, Ui.FIELD, Ui.LINE, Ui.R_BUTTON));
        button.setContentDescription(text);
        button.setFocusable(true);
        button.setOnLongClickListener(v -> { showOpacity(); return true; });
        parent.addView(button, Ui.lpMargins(this, Ui.dp(this, 44), Ui.dp(this, 44), 2, 2, 2, 2));
        return button;
    }

    private static float controlsAlpha(Context context) {
        int transparency = Math.max(0, Math.min(90, Prefs.getInt(context, "record_controls_transparency", 30)));
        return 1f - transparency / 100f;
    }

    static View opacityControls(Context context) {
        LinearLayout body = new LinearLayout(context);
        body.setOrientation(LinearLayout.VERTICAL);
        int transparency = Math.round((1 - controlsAlpha(context)) * 100);
        android.widget.TextView label = Ui.text(context, "Прозрачность значков: " + transparency + "%", 12, Ui.TEXT, 600);
        body.addView(label, Ui.lpMargins(context, -1, -2, 0, 8, 0, 0));
        android.widget.SeekBar slider = new android.widget.SeekBar(context);
        slider.setMax(90);
        slider.setProgress(transparency);
        slider.setProgressTintList(android.content.res.ColorStateList.valueOf(Ui.ACCENT));
        slider.setThumbTintList(android.content.res.ColorStateList.valueOf(Ui.ACCENT));
        slider.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(android.widget.SeekBar seekBar, int value, boolean user) {
                if (!user) return;
                Prefs.putInt(context, "record_controls_transparency", value);
                label.setText("Прозрачность значков: " + value + "%");
                OverlayRecording current = instance;
                if (current != null) current.main.post(() -> {
                    if (current.controls != null) current.controls.setAlpha(controlsAlpha(current));
                });
            }
            @Override public void onStartTrackingTouch(android.widget.SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(android.widget.SeekBar seekBar) {}
        });
        body.addView(slider, new LinearLayout.LayoutParams(-1, Ui.dp(context, 44)));
        return body;
    }

    private void showOpacity() {
        if (opacityWindow != null || controls == null) return;
        Ui.load(this);
        LinearLayout body = Ui.card(this);
        body.setBackground(Ui.box(this, Ui.BG, Ui.LINE2, Ui.R_CARD));
        body.addView(opacityControls(this));
        android.widget.TextView done = Ui.button(this, "Готово", true);
        done.setOnClickListener(v -> hideOpacity());
        body.addView(done, Ui.lpMargins(this, -1, -2, 0, 8, 0, 0));
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                Math.min(getResources().getDisplayMetrics().widthPixels - Ui.dp(this, 32), Ui.dp(this, 340)), -2,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_SECURE,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.CENTER;
        params.setTitle("XPhone recording opacity");
        try {
            getSystemService(WindowManager.class).addView(body, params);
            opacityWindow = body;
        } catch (RuntimeException error) {
            Diagnostics.failure(this, "record.opacity.failed", error);
        }
    }

    private void hideOpacity() {
        if (opacityWindow == null) return;
        try { getSystemService(WindowManager.class).removeViewImmediate(opacityWindow); }
        catch (IllegalArgumentException ignored) {}
        opacityWindow = null;
    }

    private void togglePause(ImageView button) {
        if (!started || finishing) return;
        try {
            if (paused) video.resume(); else video.pause();
            paused = !paused;
            if (audio != null) audio.pause(paused);
            boolean nowPaused = paused;
            main.post(() -> {
                button.setImageResource(nowPaused ? R.drawable.ic_play : R.drawable.ic_pause);
                button.setContentDescription(nowPaused ? "Продолжить запись" : "Приостановить запись");
            });
        } catch (RuntimeException error) {
            Diagnostics.failure(this, "record.pause.failed", error);
        }
    }

    private void finishRecording(boolean save) {
        if (finishing) return;
        finishing = true;
        main.post(this::hideControls);
        boolean valid = false;
        try {
            if (started) {
                video.stop();
                valid = save;
            }
        } catch (RuntimeException error) {
            Diagnostics.failure(this, "record.stop.failed", error);
        }
        boolean audioValid = false;
        if (audio != null) {
            try {
                audio.finish();
                audioValid = true;
            } catch (Exception error) {
                Diagnostics.failure(this, "record.audio.failed", error);
            } finally { audio.close(); }
        }
        if (display != null) display.release();
        if (frames != null) frames.close();
        if (encoderInput != null) encoderInput.release();
        if (video != null) video.release();
        if (projection != null) projection.stop();
        if (changedTouches) {
            if (previousTouches && !BoostService.hubOnScreen()) TouchTrace.set(this, true);
            else if (!previousTouches) TouchTrace.restore(this);
        }
        if (save) {
            String status = "Не удалось сохранить запись";
            try {
                if (valid) {
                    File output = videoFile;
                    if (audioValid) {
                        try {
                            mergedFile = File.createTempFile("xphone-merged-", ".mp4", getCacheDir());
                            merge(videoFile, audioFile, mergedFile);
                            output = mergedFile;
                        } catch (Exception error) { Diagnostics.failure(this, "record.merge.failed", error); }
                    }
                    publish(output);
                    status = audio != null && output == videoFile ? "Видео сохранено без звука" : "Запись сохранена в Movies/XPhone";
                }
            } catch (Exception error) { Diagnostics.failure(this, "record.save.failed", error); }
            String message = status;
            main.post(() -> toast(message));
        }
        for (File file : new File[]{videoFile, audioFile, mergedFile}) if (file != null) file.delete();
        main.post(() -> {
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
        });
        worker.getLooper().quitSafely();
    }

    private void publish(File file) throws IOException {
        ContentValues values = new ContentValues();
        values.put(MediaStore.Video.Media.DISPLAY_NAME, "XPhone-" + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(new Date()) + ".mp4");
        values.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
        values.put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/XPhone");
        values.put(MediaStore.Video.Media.IS_PENDING, 1);
        var resolver = getContentResolver();
        var uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values);
        if (uri == null) throw new IOException("MediaStore unavailable");
        boolean saved = false;
        try {
            try (var output = resolver.openOutputStream(uri)) {
                if (output == null) throw new IOException("MediaStore stream unavailable");
                Files.copy(file.toPath(), output);
            }
            values.clear();
            values.put(MediaStore.Video.Media.IS_PENDING, 0);
            saved = resolver.update(uri, values, null, null) > 0;
            if (!saved) throw new IOException("MediaStore publication failed");
        } finally {
            if (!saved) resolver.delete(uri, null, null);
        }
    }

    private static void merge(File video, File audio, File output) throws IOException {
        MediaExtractor v = new MediaExtractor(), a = new MediaExtractor();
        MediaMuxer muxer = null;
        try {
            v.setDataSource(video.getPath());
            a.setDataSource(audio.getPath());
            if (v.getTrackCount() == 0 || a.getTrackCount() == 0) throw new IOException("Missing track");
            muxer = new MediaMuxer(output.getPath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            int videoTrack = muxer.addTrack(v.getTrackFormat(0));
            int audioTrack = muxer.addTrack(a.getTrackFormat(0));
            v.selectTrack(0);
            a.selectTrack(0);
            muxer.start();
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            ByteBuffer buffer = ByteBuffer.allocateDirect(1024 * 1024);
            while (v.getSampleTime() >= 0 || a.getSampleTime() >= 0) {
                boolean useVideo = a.getSampleTime() < 0 || (v.getSampleTime() >= 0 && v.getSampleTime() <= a.getSampleTime());
                MediaExtractor extractor = useVideo ? v : a;
                long size = extractor.getSampleSize();
                if (size > 16L * 1024 * 1024) throw new IOException("Sample too large");
                if (size > buffer.capacity()) buffer = ByteBuffer.allocateDirect((int) size);
                buffer.clear();
                int read = extractor.readSampleData(buffer, 0);
                if (read < 0) throw new IOException("Invalid sample");
                int flags = (extractor.getSampleFlags() & MediaExtractor.SAMPLE_FLAG_SYNC) != 0
                        ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0;
                info.set(0, read, extractor.getSampleTime(), flags);
                muxer.writeSampleData(useVideo ? videoTrack : audioTrack, buffer, info);
                extractor.advance();
            }
            muxer.stop();
        } finally {
            if (muxer != null) muxer.release();
            v.release();
            a.release();
        }
    }

    private void hideControls() {
        hideOpacity();
        if (controls == null) return;
        try { getSystemService(WindowManager.class).removeViewImmediate(controls); }
        catch (IllegalArgumentException ignored) {}
        controls = null;
    }

    private void toast(String message) { Toast.makeText(this, message, Toast.LENGTH_LONG).show(); }
    @Override public IBinder onBind(Intent intent) { return null; }
    @Override public void onDestroy() {
        destroyed = true;
        if (instance == this) instance = null;
        active = false;
        hideControls();
        worker.post(() -> finishRecording(true));
        super.onDestroy();
    }
}
