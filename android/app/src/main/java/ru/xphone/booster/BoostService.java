package ru.xphone.booster;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;

/**
 * Следит, какая программа на экране. Открылась игра — показывает язычок (и по желанию панель)
 * и включает выбранные функции. Вышли из игры — убирает язычок и возвращает настройки.
 */
public class BoostService extends Service {
    public static final String ACTION_SHOW_PANEL = "ru.xphone.booster.SHOW_PANEL";
    private static final String CHANNEL = "boost";
    private static final long TICK_MS = 1000, EXIT_DELAY_MS = 2500, EXTERNAL_GRACE_MS = 120_000;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private Overlay overlay;
    private String currentGame;
    private String lastForeground;
    private long exitSince;
    private long externalUntil;
    private String launcher;

    public static void start(Context c) {
        Intent i = new Intent(c, BoostService.class);
        try { c.startForegroundService(i); } catch (Exception ignored) { }
    }

    public static void stop(Context c) {
        c.stopService(new Intent(c, BoostService.class));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        startForegroundNotice();
        overlay = new Overlay(this, () -> externalUntil = System.currentTimeMillis() + EXTERNAL_GRACE_MS);
        launcher = homePackage();
        // Если прошлый раз что-то поменяли и не вернули (например, телефон перезагрузился) — вернуть.
        if (Prefs.restore(this) && Booster.hasChanges(this)) Shell.background(() -> Booster.restoreAll(this));
        handler.post(tick);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_SHOW_PANEL.equals(intent.getAction())) {
            // Проверка панели из настроек: показать без игры.
            if (currentGame != null) overlay.showHandle(currentGame);
            overlay.openPanel();
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacks(tick);
        if (overlay != null) overlay.hideAll();
        if (currentGame != null && Prefs.restore(this)) Shell.background(() -> Booster.restoreAll(this));
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            try { check(); } catch (Exception ignored) { }
            // Экран выключен — проверяем реже, чтобы не тратить батарею.
            boolean interactive = getSystemService(PowerManager.class).isInteractive();
            handler.postDelayed(this, interactive ? TICK_MS : TICK_MS * 5);
        }
    };

    private void check() {
        String fg = foregroundPackage();
        if (fg == null) return;
        long now = System.currentTimeMillis();
        if (fg.equals(getPackageName())) return; // наши настройки
        if (Prefs.isGame(this, fg)) {
            exitSince = 0;
            externalUntil = 0;
            if (!fg.equals(currentGame)) onGameStart(fg);
            return;
        }
        if (currentGame == null) return;
        // Открыли настройки из панели — подождём, пока человек вернётся в игру.
        if (now < externalUntil && !fg.equals(launcher)) return;
        if (fg.equals("com.android.systemui")) return;
        if (exitSince == 0) exitSince = now;
        else if (now - exitSince > EXIT_DELAY_MS) onGameExit();
    }

    private void onGameStart(String pkg) {
        boolean switched = currentGame != null;
        currentGame = pkg;
        overlay.closePanel();
        overlay.hideAll();
        overlay.showHandle(pkg);
        Shell.background(() -> {
            for (String id : Booster.TOGGLES)
                if (Prefs.auto(this, id) && Booster.available(this, id)) Booster.set(this, id, true, pkg);
            Shell.onMain(overlay::refreshIfOpen);
        });
        if (Prefs.autoPanel(this) && !switched)
            handler.postDelayed(() -> { if (pkg.equals(currentGame)) overlay.openPanel(); }, 1500);
    }

    private void onGameExit() {
        currentGame = null;
        exitSince = 0;
        overlay.hideAll();
        if (Prefs.restore(this)) Shell.background(() -> Booster.restoreAll(this));
    }

    /** Какая программа сейчас на экране (по событиям «приложение открыто»). */
    private String foregroundPackage() {
        UsageStatsManager usm = getSystemService(UsageStatsManager.class);
        long now = System.currentTimeMillis();
        UsageEvents events = usm.queryEvents(now - 20_000, now);
        UsageEvents.Event e = new UsageEvents.Event();
        String last = null;
        while (events.hasNextEvent()) {
            events.getNextEvent(e);
            if (e.getEventType() == UsageEvents.Event.ACTIVITY_RESUMED) last = e.getPackageName();
        }
        if (last != null) lastForeground = last;
        return lastForeground;
    }

    private String homePackage() {
        Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
        ResolveInfo ri = getPackageManager().resolveActivity(home, 0);
        return ri != null && ri.activityInfo != null ? ri.activityInfo.packageName : "";
    }

    private void startForegroundNotice() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        NotificationChannel ch = new NotificationChannel(CHANNEL, "Игровая панель", NotificationManager.IMPORTANCE_MIN);
        ch.setShowBadge(false);
        nm.createNotificationChannel(ch);
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE);
        Notification n = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_gamepad)
                .setContentTitle("XPhone следит за играми")
                .setContentText("Свайп от правого края в игре — игровая панель")
                .setContentIntent(open)
                .setOngoing(true)
                .build();
        if (Build.VERSION.SDK_INT >= 34)
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        else
            startForeground(1, n);
    }
}
