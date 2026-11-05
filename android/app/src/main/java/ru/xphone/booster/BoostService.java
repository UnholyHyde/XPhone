package ru.xphone.booster;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;

/**
 * Следит, какая программа на экране. Открылась игра — показывает язычок, приветствие (или панель)
 * и включает выбранные функции. Вышли из игры — убирает всё и возвращает настройки.
 */
public class BoostService extends Service {
    public static final String ACTION_SHOW_PANEL = "ru.xphone.booster.SHOW_PANEL";
    private static final String CHANNEL = "boost";
    private static final long TICK_MS = 1000, EXIT_DELAY_MS = 2500, EXTERNAL_GRACE_MS = 120_000;

    private static BoostService instance;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private Overlay overlay;
    private String currentGame;
    private String hiddenGame;      // «Скрыть до перезахода»
    private String lastForeground;
    private long exitSince;
    private long externalUntil;
    private String launcher;
    private boolean streamByGame;
    private boolean passByLevel;    // «Питание USB» включили мы по проценту заряда
    private BroadcastReceiver batteryReceiver;

    public static void start(Context c) {
        Intent i = new Intent(c, BoostService.class);
        try { c.startForegroundService(i); } catch (Exception ignored) { }
    }

    public static void stop(Context c) {
        c.stopService(new Intent(c, BoostService.class));
    }

    /** Короткое сообщение поверх игры (если служба работает). */
    public static void toast(String text, int icon) {
        BoostService s = instance;
        if (s != null && s.overlay != null) s.overlay.toast(text, icon);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        startForegroundNotice();
        overlay = new Overlay(this, new Overlay.Listener() {
            @Override public void openedExternal() { externalUntil = System.currentTimeMillis() + EXTERNAL_GRACE_MS; }
            @Override public void hideUntilExit() {
                hiddenGame = currentGame;
                overlay.hideAll();
                overlay.toast("Оверлей скрыт до перезахода в игру", R.drawable.ic_eye_off);
            }
        });
        launcher = homePackage();
        // Если прошлый раз что-то поменяли и не вернули (например, телефон перезагрузился) — вернуть.
        if (Prefs.restore(this) && Booster.hasChanges(this)) Shell.background(() -> Booster.restoreAll(this));
        handler.post(tick);
        PcLink.start(this);
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
        stopBatteryWatch();
        PcLink.stop();
        if (overlay != null) overlay.hideAll();
        if (currentGame != null) afterGame();
        instance = null;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            try { check(); } catch (Exception ignored) { }
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
        if (now < externalUntil && !fg.equals(launcher)) return; // открыли настройки из панели
        if (fg.equals("com.android.systemui")) return;
        if (exitSince == 0) exitSince = now;
        else if (now - exitSince > EXIT_DELAY_MS) onGameExit();
    }

    private void onGameStart(String pkg) {
        boolean switched = currentGame != null;
        android.util.Log.i("XPhone", "game start: " + pkg + " switched=" + switched + " welcome=" + Prefs.welcome(this) + " autoPanel=" + Prefs.autoPanel(this));
        currentGame = pkg;
        overlay.hideAll();
        if (!pkg.equals(hiddenGame)) {
            overlay.showHandle(pkg);
            if (!switched) {
                if (Prefs.autoPanel(this))
                    handler.postDelayed(() -> { if (pkg.equals(currentGame)) overlay.openPanel(); }, 1500);
                else if (Prefs.welcome(this))
                    handler.postDelayed(() -> { if (pkg.equals(currentGame)) overlay.welcome(); }, 1200);
            }
        }
        Shell.background(() -> {
            boolean dnd = false;
            for (String id : Booster.TOGGLES)
                if (Prefs.auto(this, id) && Booster.available(this, id)) {
                    Booster.set(this, id, true, pkg);
                    if (Booster.DND.equals(id)) dnd = true;
                }
            Shell.onMain(overlay::refreshIfOpen);
            if (dnd) {
                // Galaxy Watch может сразу выключить «Не беспокоить» — проверим и подскажем.
                try { Thread.sleep(2500); } catch (InterruptedException ignored) { }
                if (pkg.equals(currentGame) && !Booster.read(this, Booster.DND, pkg)) {
                    Prefs.put(this, "dnd_reverted", true);
                    Shell.onMain(() -> {
                        overlay.toast("«Не беспокоить» выключил телефон — см. подсказку в XPhone", R.drawable.ic_alert);
                        overlay.refreshIfOpen();
                    });
                }
            }
        });
        if (!switched && Prefs.get(this, Prefs.AUTO_STREAM, false) && PcLink.state() != PcLink.State.STREAMING) {
            streamByGame = true;
            PcLink.requestStart(this);
        }
        startBatteryWatch();
    }

    private void onGameExit() {
        android.util.Log.i("XPhone", "game exit: " + currentGame);
        currentGame = null;
        hiddenGame = null;
        exitSince = 0;
        overlay.hideAll();
        afterGame();
        if (streamByGame && Prefs.get(this, Prefs.AUTO_STREAM_STOP, true)) PcLink.requestStop();
        streamByGame = false;
    }

    /** Вернуть всё как было до игры. */
    private void afterGame() {
        stopBatteryWatch();
        Dnd.reset();
        passByLevel = false;
        Shell.background(() -> {
            Mic.restore(this);
            if (Prefs.restore(this)) Booster.restoreAll(this);
        });
    }

    // ── «Питание USB» по проценту заряда ──────────────────────────────

    private void startBatteryWatch() {
        int level = Prefs.getInt(this, Prefs.PASS_LEVEL, 0);
        if (level <= 0 || batteryReceiver != null || !Booster.available(this, Booster.PASS)) return;
        batteryReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent i) {
                int l = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1), s = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
                if (l < 0 || s <= 0) return;
                int pct = Math.round(l * 100f / s);
                int target = Prefs.getInt(c, Prefs.PASS_LEVEL, 0);
                if (target <= 0) return;
                if (pct >= target && !passByLevel) {
                    passByLevel = true;
                    Shell.background(() -> Booster.set(c, Booster.PASS, true, null));
                    overlay.toast("Заряд " + pct + "% — питание напрямую от зарядки", R.drawable.ic_plug);
                } else if (pct <= target - 5 && passByLevel) {
                    passByLevel = false;
                    Shell.background(() -> Booster.set(c, Booster.PASS, false, null));
                    overlay.toast("Заряд " + pct + "% — снова заряжаем батарею", R.drawable.ic_plug);
                }
            }
        };
        registerReceiver(batteryReceiver, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
    }

    private void stopBatteryWatch() {
        if (batteryReceiver == null) return;
        try { unregisterReceiver(batteryReceiver); } catch (Exception ignored) { }
        batteryReceiver = null;
    }

    // ── Служебное ─────────────────────────────────────────────────────

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
                .setContentText("В игре — свайп от края экрана открывает панель")
                .setContentIntent(open)
                .setOngoing(true)
                .build();
        if (Build.VERSION.SDK_INT >= 34)
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        else
            startForeground(1, n);
    }
}
