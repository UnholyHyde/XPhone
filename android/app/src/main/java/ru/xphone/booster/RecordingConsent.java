package ru.xphone.booster;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.projection.MediaProjectionManager;
import android.media.projection.MediaProjectionConfig;
import android.os.Build;
import android.os.Bundle;
import android.widget.Toast;

public final class RecordingConsent extends Activity {
    private Intent granted;
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (state != null) return;
        if ((Prefs.get(this, "record_mic", false) || Prefs.get(this, "record_playback", false))
                && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 1);
        } else requestCapture();
    }

    private void requestCapture() {
        MediaProjectionManager manager = getSystemService(MediaProjectionManager.class);
        Intent capture = Build.VERSION.SDK_INT >= 34 && !Prefs.get(this, "record_choose_app", true)
                ? manager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
                : manager.createScreenCaptureIntent();
        startActivityForResult(capture, 2);
    }

    @Override public void onRequestPermissionsResult(int code, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(code, permissions, results);
        if (code != 1) return;
        if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) requestCapture();
        else {
            Toast.makeText(this, "Для записи со звуком нужен доступ к аудио.", Toast.LENGTH_LONG).show();
            finish();
        }
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != 2) return;
        if (result == RESULT_OK && data != null) granted = data;
        else finish();
    }

    @Override protected void onPostResume() {
        super.onPostResume();
        if (granted != null) {
            Intent data = granted;
            granted = null;
            try {
                startForegroundService(new Intent(this, OverlayRecording.class)
                        .putExtra("consent", data)
                        .putExtra("width", getIntent().getIntExtra("width", 0))
                        .putExtra("height", getIntent().getIntExtra("height", 0))
                        .putExtra("mic", Prefs.get(this, "record_mic", false))
                        .putExtra("playback", Prefs.get(this, "record_playback", false))
                        .putExtra("touches", Prefs.get(this, "record_touches", false))
                        .putExtra("quality", Prefs.getInt(this, "record_quality", 1)));
                getWindow().getDecorView().postDelayed(this::finish, 350);
                return;
            } catch (RuntimeException error) {
                android.util.Log.w("XPhoneRecording", "Cannot start recording service", error);
                Diagnostics.failure(this, "record.consent.failed", error);
                Toast.makeText(this, "Не удалось запустить запись.", Toast.LENGTH_LONG).show();
            }
            finish();
        }
    }
}
