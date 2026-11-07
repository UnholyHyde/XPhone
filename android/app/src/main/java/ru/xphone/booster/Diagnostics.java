package ru.xphone.booster;

import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.widget.Toast;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.concurrent.Executors;

final class Diagnostics {
    private static final long LIMIT = 128 * 1024;
    private static final Object FILE_LOCK = new Object();
    private static final java.util.concurrent.Executor IO = Executors.newSingleThreadExecutor();
    private static boolean initialized;

    private Diagnostics() {}

    static synchronized void init(Context context) {
        if (initialized) return;
        initialized = true;
        Context app = context.getApplicationContext();
        Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        if (previous != null) {
            Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
                try { append(app, Instant.now() + " v" + BuildConfig.VERSION_NAME + " crash " + errorDetails(error)); }
                finally { previous.uncaughtException(thread, error); }
            });
        }
        event(app, "process.start");
    }

    static void event(Context context, String event) {
        Context app = context.getApplicationContext();
        String line = Instant.now() + " v" + BuildConfig.VERSION_NAME + " " + event;
        IO.execute(() -> append(app, line));
    }

    static void failure(Context context, String event, Throwable error) {
        event(context, event + " " + errorDetails(error));
    }

    private static String errorDetails(Throwable error) {
        StringBuilder detail = new StringBuilder();
        for (int cause = 0; error != null && cause < 3; cause++, error = error.getCause()) {
            detail.append(' ').append(error.getClass().getName());
            int frames = 0;
            for (StackTraceElement frame : error.getStackTrace()) {
                if (!frame.getClassName().startsWith("ru.xphone.booster.")) continue;
                detail.append(" at ").append(frame);
                if (++frames == 8) break;
            }
        }
        return detail.toString().trim();
    }

    private static File log(Context context, boolean previous) {
        return new File(context.getFilesDir(), previous ? "diagnostics.previous.log" : "diagnostics.log");
    }

    private static void append(Context context, String line) {
        synchronized (FILE_LOCK) {
            File current = log(context, false);
            byte[] bytes = (line + "\n").getBytes(StandardCharsets.UTF_8);
            try {
                if (current.length() + bytes.length > LIMIT)
                    Files.move(current.toPath(), log(context, true).toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                Files.write(current.toPath(), bytes, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException ignored) {
                // Diagnostics must never interrupt the hub or overlay.
            }
        }
    }

    static File exportFile(Context context) {
        return new File(context.getCacheDir(), "XPhone-diagnostics.txt");
    }

    static void share(MainActivity activity) {
        Context app = activity.getApplicationContext();
        IO.execute(() -> {
            try {
                synchronized (FILE_LOCK) {
                    try (var out = Files.newOutputStream(exportFile(app).toPath())) {
                        String header = "XPhone " + BuildConfig.VERSION_NAME + "\nAndroid " + Build.VERSION.RELEASE
                                + " (API " + Build.VERSION.SDK_INT + ")\nDevice: " + Build.MANUFACTURER + " " + Build.MODEL + "\n\n";
                        out.write(header.getBytes(StandardCharsets.UTF_8));
                        for (boolean previous : new boolean[]{true, false}) {
                            File file = log(app, previous);
                            if (file.isFile()) Files.copy(file.toPath(), out);
                        }
                    }
                }
                activity.runOnUiThread(() -> {
                    if (!activity.canShowUpdate()) return;
                    Uri uri = Uri.parse("content://" + app.getPackageName() + ".updates/diagnostics.txt");
                    Intent intent = new Intent(Intent.ACTION_SEND).setType("text/plain")
                            .putExtra(Intent.EXTRA_STREAM, uri)
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    intent.setClipData(ClipData.newRawUri("XPhone diagnostics", uri));
                    activity.startActivity(Intent.createChooser(intent, "Поделиться журналом XPhone"));
                });
            } catch (IOException error) {
                activity.runOnUiThread(() -> Toast.makeText(app, "Не удалось подготовить журнал.", Toast.LENGTH_LONG).show());
            }
        });
    }
}
