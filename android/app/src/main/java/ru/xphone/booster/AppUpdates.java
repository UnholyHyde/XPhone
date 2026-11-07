package ru.xphone.booster;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.SystemClock;
import android.provider.Settings;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.net.ssl.HttpsURLConnection;

final class AppUpdates {
    static final int INSTALL_PERMISSION = 7281;
    private static final String RELEASES = "https://api.github.com/repos/UnholyHyde/XPhone/releases?per_page=30";
    private static final String DOWNLOAD = "https://github.com/UnholyHyde/XPhone/releases/download/";
    private static final Pattern APK = Pattern.compile("XPhone-Android-([0-9]+\\.[0-9]+\\.[0-9]+)\\.apk");
    private static final String PENDING_VERSION = "pending_version";
    private static final String PENDING_NOTES = "pending_notes";
    private static boolean busy;
    private static long lastCheck = -1;
    private final MainActivity activity;

    private record Update(String version, String url, String digest, long size, String notes) {}

    AppUpdates(MainActivity activity) {
        this.activity = activity;
    }

    void onResume() {
        var prefs = activity.getSharedPreferences("updates", Context.MODE_PRIVATE);
        String version = prefs.getString(PENDING_VERSION, "");
        if (!version.isEmpty() && compare(BuildConfig.VERSION_NAME, version) >= 0) {
            if (BuildConfig.VERSION_NAME.equals(version)) {
                String notes = prefs.getString(PENDING_NOTES, "");
                new AlertDialog.Builder(activity).setTitle("Что нового в XPhone " + version)
                        .setMessage(notes.trim().isEmpty() ? "В релизе нет списка изменений." : notes)
                        .setPositiveButton("Понятно", null).show();
            }
            prefs.edit().remove(PENDING_VERSION).remove(PENDING_NOTES).apply();
            apkFile(activity).delete();
        }
        if (lastCheck < 0 || SystemClock.elapsedRealtime() - lastCheck >= 6 * 60 * 60 * 1000L)
            check(false);
    }

    void check(boolean manual) {
        if (busy) {
            if (manual) toast("Проверка или загрузка уже идёт.");
            return;
        }
        busy = true;
        lastCheck = SystemClock.elapsedRealtime();
        new Thread(() -> {
            try {
                Update update = findUpdate();
                activity.runOnUiThread(() -> {
                    busy = false;
                    if (!activity.canShowUpdate()) {
                        lastCheck = -1;
                        return;
                    }
                    if (update != null && (!hasDownload() || !update.version.equals(activity
                            .getSharedPreferences("updates", Context.MODE_PRIVATE).getString(PENDING_VERSION, ""))))
                        offer(update);
                    else if (manual) toast("У вас последняя версия XPhone для Android.");
                });
            } catch (Exception ex) {
                activity.runOnUiThread(() -> {
                    busy = false;
                    if (manual && activity.canShowUpdate()) toast("Не удалось проверить обновления. Проверьте доступ к GitHub.");
                });
            }
        }, "XPhone-update-check").start();
    }

    private void offer(Update update) {
        new AlertDialog.Builder(activity).setTitle("Доступно обновление XPhone " + update.version)
                .setMessage("Скачать APK и установить поверх текущей версии? Настройки и библиотека игр сохранятся.")
                .setNegativeButton("Позже", null)
                .setPositiveButton("Скачать", (dialog, which) -> download(update)).show();
    }

    private void download(Update update) {
        if (busy) return;
        busy = true;
        toast("XPhone скачивает обновление…");
        Context context = activity.getApplicationContext();
        new Thread(() -> {
            File partial = new File(context.getCacheDir(), "xphone-update-part.apk");
            try {
                fetch(update, partial);
                verifyApk(context, partial, update.version);
                Files.move(partial.toPath(), apkFile(context).toPath(), StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
                if (!context.getSharedPreferences("updates", Context.MODE_PRIVATE).edit()
                        .putString(PENDING_VERSION, update.version).putString(PENDING_NOTES, update.notes).commit())
                    throw new IOException("Pending update");
                activity.runOnUiThread(() -> {
                    busy = false;
                    if (activity.canShowUpdate()) installDownloaded();
                });
            } catch (Exception ex) {
                activity.runOnUiThread(() -> {
                    busy = false;
                    if (activity.canShowUpdate()) toast("Не удалось скачать или проверить APK. Попробуйте ещё раз.");
                });
            } finally {
                partial.delete();
            }
        }, "XPhone-update-download").start();
    }

    boolean hasDownload() {
        String version = activity.getSharedPreferences("updates", Context.MODE_PRIVATE).getString(PENDING_VERSION, "");
        return !version.isEmpty() && compare(version, BuildConfig.VERSION_NAME) > 0 && apkFile(activity).isFile();
    }

    void installDownloaded() {
        if (busy || !hasDownload()) return;
        try {
            if (!activity.getPackageManager().canRequestPackageInstalls()) {
                new AlertDialog.Builder(activity).setTitle("Разрешение на обновление")
                        .setMessage("Разрешите XPhone устанавливать обновления. Затем Android попросит подтвердить установку.")
                        .setNegativeButton("Позже", null)
                        .setPositiveButton("Открыть настройки", (dialog, which) -> openInstallPermission()).show();
                return;
            }
            Uri uri = Uri.parse("content://" + activity.getPackageName() + ".updates/update.apk");
            activity.startActivity(new Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, UpdateApkProvider.MIME)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION));
        } catch (Exception ex) {
            toast("Не удалось открыть системный установщик Android.");
        }
    }

    private void openInstallPermission() {
        try {
            activity.startActivityForResult(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + activity.getPackageName())), INSTALL_PERMISSION);
        } catch (Exception ex) {
            toast("Не удалось открыть настройки установки Android.");
        }
    }

    static File apkFile(Context context) {
        return new File(context.getCacheDir(), "xphone-update.apk");
    }

    private static Update findUpdate() throws Exception {
        HttpsURLConnection connection = connect(RELEASES);
        try (InputStream stream = connection.getInputStream()) {
            var data = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int count;
            while ((count = stream.read(buffer)) != -1) {
                if (data.size() + count > 8 * 1024 * 1024) throw new IOException("Release list too large");
                data.write(buffer, 0, count);
            }
            JSONArray releases = new JSONArray(new String(data.toByteArray(), StandardCharsets.UTF_8));
            Update newest = null;
            for (int i = 0; i < releases.length(); i++) {
                JSONObject release = releases.getJSONObject(i);
                if (release.optBoolean("draft") || release.optBoolean("prerelease")) continue;
                JSONArray assets = release.getJSONArray("assets");
                for (int j = 0; j < assets.length(); j++) {
                    JSONObject asset = assets.getJSONObject(j);
                    String name = asset.optString("name");
                    Matcher match = APK.matcher(name);
                    if (!match.matches()) continue;
                    String version = match.group(1);
                    if (compare(version, newest == null ? BuildConfig.VERSION_NAME : newest.version) <= 0) continue;
                    String digest = asset.optString("digest");
                    String url = asset.optString("browser_download_url");
                    long size = asset.optLong("size");
                    if (!digest.matches("sha256:[0-9a-fA-F]{64}") || !url.startsWith(DOWNLOAD)
                            || !url.endsWith("/" + name) || size <= 0 || size > 256L * 1024 * 1024)
                        continue;
                    String notes = release.isNull("body") ? "" : release.optString("body").trim();
                    newest = new Update(version, url, digest.substring(7), size, notes);
                }
            }
            return newest;
        } finally {
            connection.disconnect();
        }
    }

    private static int compare(String a, String b) {
        String[] left = a.split("\\."), right = b.split("\\.");
        for (int i = 0; i < 3; i++) {
            int order = Long.compare(Long.parseLong(left[i]), Long.parseLong(right[i]));
            if (order != 0) return order;
        }
        return 0;
    }

    private static HttpsURLConnection connect(String url) throws IOException {
        var connection = (HttpsURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(12000);
        connection.setReadTimeout(15000);
        connection.setRequestProperty("User-Agent", "XPhone-Android");
        return connection;
    }

    private static void fetch(Update update, File partial) throws Exception {
        HttpsURLConnection connection = connect(update.url);
        try (InputStream stream = connection.getInputStream(); FileOutputStream output = new FileOutputStream(partial)) {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            long received = 0;
            byte[] buffer = new byte[65536];
            int count;
            while ((count = stream.read(buffer)) != -1) {
                received += count;
                if (received > update.size) throw new IOException("APK size");
                sha.update(buffer, 0, count);
                output.write(buffer, 0, count);
            }
            var digest = new StringBuilder();
            for (byte value : sha.digest()) digest.append(String.format(Locale.ROOT, "%02x", value & 255));
            if (received != update.size || !update.digest.equalsIgnoreCase(digest.toString()))
                throw new IOException("APK hash");
        } finally {
            connection.disconnect();
        }
    }

    @SuppressWarnings("deprecation")
    private static void verifyApk(Context context, File file, String version) throws Exception {
        PackageManager manager = context.getPackageManager();
        PackageInfo archive = manager.getPackageArchiveInfo(file.getPath(), PackageManager.GET_SIGNATURES);
        PackageInfo installed = manager.getPackageInfo(context.getPackageName(), PackageManager.GET_SIGNATURES);
        if (archive == null || !context.getPackageName().equals(archive.packageName)
                || !version.equals(archive.versionName) || versionCode(archive) <= versionCode(installed)
                || archive.signatures == null || !Arrays.equals(archive.signatures, installed.signatures))
            throw new IOException("APK identity");
    }

    @SuppressWarnings("deprecation")
    private static long versionCode(PackageInfo info) {
        return Build.VERSION.SDK_INT >= 28 ? info.getLongVersionCode() : info.versionCode;
    }

    private void toast(String text) {
        Toast.makeText(activity, text, Toast.LENGTH_LONG).show();
    }
}
