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
    private static volatile boolean hubForeground;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private Overlay overlay;
    private String currentGame;
    private final ReentryGate reentry=new ReentryGate();
    private long restartingUntil;
    private String hiddenGame;      // «Скрыть до перезахода»
    private final ForegroundActivities resumedActivities = new ForegroundActivities();
    private boolean foregroundInitialized;
    private long exitSince;
    private long externalUntil;
    private String launcher;
    private boolean streamByGame;
    private boolean manualUsbRule;
    private boolean passByLevel;    // «Питание USB» включили мы по проценту заряда
    private BroadcastReceiver batteryReceiver;

    public static void start(Context c) {
        Intent i = new Intent(c, BoostService.class);
        try { c.startForegroundService(i); } catch (Exception ignored) { }
    }

    public static boolean hubOnScreen(){return hubForeground;}
    public static void hubVisible(){hubForeground=true;BoostService s=instance;if(s!=null){if(s.hiddenGame!=null)s.reentry.visitHubOrHome(System.currentTimeMillis());if(s.overlay!=null)s.overlay.suspendForHub(true);}}
    public static void hubHidden(){hubForeground=false;}
    static String activeGame() {return instance==null?null:instance.currentGame;}

    public static void refreshNotice(){BoostService s=instance;if(s!=null)s.handler.post(s::startForegroundNotice);}
    public static void refreshUsbRule(){BoostService s=instance;if(s!=null)s.handler.post(()->{s.manualUsbRule=true;s.stopBatteryWatch();if(s.currentGame!=null)s.startBatteryWatch();});}
    @Override public void onConfigurationChanged(android.content.res.Configuration configuration){super.onConfigurationChanged(configuration);if(overlay!=null)overlay.requestDisplayReflow();}
    public static void refreshHandle(){BoostService s=instance;if(s!=null&&s.overlay!=null)s.handler.post(s.overlay::requestDisplayReflow);}
    public static void refreshAppearance() {
        BoostService s=instance;if(s!=null&&s.overlay!=null)s.handler.post(s.overlay::reloadAppearance);
    }

    public static void refreshHud() {
        BoostService s=instance;
        if(s!=null && s.currentGame!=null && s.hiddenGame==null) s.overlay.applyHudPreference();
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
            @Override public void restartGame(){restartCurrentGame();}
            @Override public void hideUntilExit() {
                hiddenGame = currentGame;reentry.hide(System.currentTimeMillis());
                overlay.hideAll();
                overlay.toast("Оверлей скрыт до перезахода в игру", R.drawable.ic_eye_off);
            }
        });
        overlay.suspendForHub(hubForeground);
        launcher = homePackage();
        // Если прошлый раз что-то поменяли и не вернули (например, телефон перезагрузился) — вернуть.
        Shell.background(() -> { FullFocus.stop(this);if(Prefs.restore(this)&&Booster.hasChanges(this))Booster.restoreAll(this); TouchTrace.restore(this); TouchTrace.probe(this); Telemetry.probe(this); ScreenFrames.probe(this);TouchSensitivity.probe(this);OemBooster.restore(this);TouchSensitivity.restore(this);Prefs.put(this,"privacy_available",Shell.privacyAvailable(this)); GamePower.probe(this); NativeRecorder.probe(this); CompanionApps.probe(this); });
        handler.post(tick);
        PcLink.start(this);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if(intent!=null&&"ru.xphone.booster.STOP_FOCUS".equals(intent.getAction()))Shell.background(()->{boolean restored=FullFocus.stop(this);Shell.onMain(()->{overlay.refreshIfOpen();toast(restored?"Full Focus выключен":"Не все настройки восстановлены — проверьте Shizuku",R.drawable.ic_info);});});
        if(intent!=null&&"ru.xphone.booster.STOP_RECORD".equals(intent.getAction()))Shell.background(()->{String message=QuickRecorder.stop(this);Shell.onMain(()->toast(message,R.drawable.ic_video));});
        if (intent != null && ACTION_SHOW_PANEL.equals(intent.getAction())) {
            hiddenGame=null;reentry.clear();
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
        StreamPrivacy.stop(this);PcLink.stop();
        if (overlay != null) overlay.hideAll();
        if (currentGame != null) afterGame();
        else if(QuickRecorder.active())Shell.background(()->QuickRecorder.stop(this));
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
            if (!interactive && TouchTrace.enabled(BoostService.this)) TouchTrace.stop();
            handler.postDelayed(this, interactive ? TICK_MS : TICK_MS * 5);
        }
    };

    private void check() {
        GameHistory.tick(this);
        StreamPrivacy.tick(this);
        if(FullFocus.active()&&(!Shell.ready(this)||!FullFocus.active(currentGame)||!FullFocus.stillActive(this)))Shell.background(()->{if(FullFocus.active()){FullFocus.stop(this);Shell.onMain(()->{overlay.refreshIfOpen();overlay.toast("Full Focus завершён: проверьте режим и Shizuku",R.drawable.ic_info);});}});
        if(System.currentTimeMillis()<restartingUntil)return;
        if (!getSystemService(PowerManager.class).isInteractive()) return;
        String fg = foregroundPackage();
        if (fg == null) return;
        long now = System.currentTimeMillis();
        if (hubForeground||fg.equals(getPackageName())) { overlay.suspendForHub(true);reentry.visitHubOrHome(now);exitSince=0; return; } // наши настройки
        if (!Prefs.isGame(this, fg) && TouchTrace.enabled(this)) TouchTrace.stop();
        if (Prefs.isGame(this, fg)) {
            overlay.suspendForHub(false);
            if(fg.equals(hiddenGame)&&reentry.shouldRestore()){hiddenGame=null;reentry.clear();overlay.showHandle(fg);}
            exitSince = 0;
            externalUntil = 0;
            if (!fg.equals(currentGame)) onGameStart(fg);
            if(hiddenGame==null)overlay.syncHud();
            return;
        }
        if (currentGame == null) return;
        // In multi-window the game can stay resumed while a companion gains focus.
        // Closing that companion need not emit another RESUMED event for the game.
        if (!fg.equals(launcher) && resumedActivities.contains(currentGame)) {exitSince=0;return;}
        if (hiddenGame == null && overlay.companionForeground(fg)) {exitSince=0;return;}
        if (now < externalUntil && !fg.equals(launcher)) {exitSince=0;return;} // открыли настройки из панели
        if (fg.equals("com.android.systemui")) {exitSince=0;return;}
        if (exitSince == 0) exitSince = now;
        else if (now - exitSince > EXIT_DELAY_MS) onGameExit();
    }

    private void restartCurrentGame(){
        final String pkg=currentGame;
        if(pkg==null||!Prefs.isGame(this,pkg)||pkg.equals(getPackageName())||pkg.equals(Shell.SHIZUKU_PKG)||!Shell.ready(this)){
            overlay.toast("Для перезапуска откройте игру и подключите Shizuku",R.drawable.ic_alert);return;
        }
        Intent launch=getPackageManager().getLaunchIntentForPackage(pkg);
        if(launch==null||launch.getComponent()==null){overlay.toast("Не удалось найти запуск этой игры",R.drawable.ic_alert);return;}
        final String component=launch.getComponent().flattenToString();
        restartingUntil=System.currentTimeMillis()+60000;currentGame=null;hiddenGame=null;reentry.clear();exitSince=0;
        overlay.hideAll();afterGame();overlay.toast("Перезапускаем игру…",R.drawable.ic_gamepad);
        Shell.background(()->{
            String result=Shell.restartApp(this,component);
            Shell.onMain(()->{
                if(instance!=this)return;
                restartingUntil=0;foregroundInitialized=false;
                if("STARTED".equals(result)){onGameStart(pkg);overlay.toast("Игра запускается заново",R.drawable.ic_gamepad);}
                else{if(streamByGame&&Prefs.get(this,Prefs.AUTO_STREAM_STOP,true))PcLink.requestStop();streamByGame=false;overlay.toast("Не удалось перезапустить игру. Проверьте Shizuku",R.drawable.ic_alert);}
            });
        });
    }

    private void onGameStart(String pkg) {
        if(hiddenGame!=null&&!hiddenGame.equals(pkg)){hiddenGame=null;reentry.clear();}
        boolean switched = currentGame != null;
        android.util.Log.i("XPhone", "game start: " + pkg + " switched=" + switched + " welcome=" + Prefs.welcome(this) + " autoPanel=" + Prefs.autoPanel(this));
        currentGame = pkg;manualUsbRule=false;passByLevel=false;stopBatteryWatch();
        overlay.hideAll();
        if (!pkg.equals(hiddenGame)) {
            overlay.showHandle(pkg);
            if (!switched) {
                if (Prefs.autoPanel(this))
                    handler.postDelayed(() -> { if (pkg.equals(currentGame)&&!hubForeground) overlay.openPanel(); }, 1500);
                else if (Prefs.welcome(this))
                    handler.postDelayed(() -> { if (pkg.equals(currentGame)&&!hubForeground) overlay.welcome(); }, 1200);
            }
        }
        Shell.background(() -> {
            if(switched)FullFocus.stop(this);
            if(switched && Prefs.restore(this))Booster.restoreAll(this);
            boolean dnd = false;
            for (String id : Booster.TOGGLES)
                if (GameProfiles.auto(this, pkg, id) && Booster.available(this, id)) {
                    if(Booster.PERF.equals(id)) {
                        String error=GamePower.select(this,pkg,GameProfiles.mode(this,pkg));
                        if(error!=null)Shell.onMain(()->overlay.toast(error,R.drawable.ic_alert));
                    }else if(Booster.BRIGHT.equals(id)&&GameProfiles.brightness(this,pkg)>=0)Booster.setBrightness(this,GameProfiles.brightness(this,pkg));
                    else Booster.set(this, id, true, pkg);
                    if (Booster.DND.equals(id)) dnd = true;
                }
            if(pkg.equals(currentGame)&&GameProfiles.extra(this,pkg,"touch")&&TouchTrace.available(this))TouchTrace.set(this,true);
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
        hiddenGame = null;reentry.clear();
        exitSince = 0;
        overlay.hideAll();
        afterGame();
        if (streamByGame && Prefs.get(this, Prefs.AUTO_STREAM_STOP, true)) PcLink.requestStop();
        streamByGame = false;
    }

    /** Вернуть всё как было до игры. */
    private void afterGame() {
        stopBatteryWatch();
        if(QuickRecorder.active())Shell.background(()->{String message=QuickRecorder.stop(this);Shell.onMain(()->toast(message,R.drawable.ic_video));});
        Dnd.reset();
        passByLevel = false;manualUsbRule=false;
        Shell.background(() -> {
            FullFocus.stop(this);TouchSensitivity.restore(this);
            Mic.restore(this);
            TouchTrace.restore(this);
            if (Prefs.restore(this)) Booster.restoreAll(this);
        });
    }

    // ── «Питание USB» по проценту заряда ──────────────────────────────

    private void startBatteryWatch() {
        int level = GameProfiles.custom(this,currentGame)?GameProfiles.usbLevel(this,currentGame):manualUsbRule?Prefs.getInt(this,Prefs.PASS_LEVEL,0):0;
        if (level <= 0 || batteryReceiver != null || !Booster.available(this, Booster.PASS)) return;
        batteryReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent i) {
                int l = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1), s = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
                if (l < 0 || s <= 0) return;
                int pct = Math.round(l * 100f / s);
                int target = GameProfiles.custom(c,currentGame)?GameProfiles.usbLevel(c,currentGame):manualUsbRule?Prefs.getInt(c,Prefs.PASS_LEVEL,0):0;
                if (target <= 0) return;
                if (pct >= target && !passByLevel) {
                    passByLevel = true;
                    Shell.background(() -> {Booster.set(c,Booster.PASS,true,null);boolean actual=Booster.read(c,Booster.PASS,null);Shell.onMain(()->{passByLevel=actual;overlay.toast(actual?"Заряд "+pct+"% — питание напрямую от зарядки":"Телефон не подтвердил питание USB. Проверьте поддержку зарядкой",R.drawable.ic_plug);});});
                } else if (pct <= target - 5 && passByLevel) {
                    passByLevel = false;manualUsbRule=false;
                    Shell.background(() -> {Booster.set(c,Booster.PASS,false,null);boolean actual=Booster.read(c,Booster.PASS,null);Shell.onMain(()->{passByLevel=actual;overlay.toast(!actual?"Заряд "+pct+"% — зарядка батареи возобновлена":"Телефон не подтвердил выключение питания USB",R.drawable.ic_plug);});});
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
        // After an app update the game may already have been open for a while.
        // Reconstruct the latest resumed activity once, then use short polling.
        long since = now - (foregroundInitialized ? 20_000 : 86_400_000L);
        foregroundInitialized = true;
        UsageEvents events = usm.queryEvents(since, now);
        UsageEvents.Event e = new UsageEvents.Event();
        while (events.hasNextEvent()) {
            events.getNextEvent(e);
            int type=e.getEventType();
            if(type==UsageEvents.Event.ACTIVITY_RESUMED)
                {resumedActivities.resume(e.getPackageName(),e.getClassName());
                if(e.getPackageName().equals(getPackageName())||e.getPackageName().equals(launcher))reentry.visitHubOrHome(e.getTimeStamp());}
            else if(type==UsageEvents.Event.ACTIVITY_PAUSED || type==UsageEvents.Event.ACTIVITY_STOPPED)
                resumedActivities.pause(e.getPackageName(),e.getClassName());
        }
        return resumedActivities.top();
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
        Notification.Builder builder = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_gamepad)
                .setContentTitle(FullFocus.active()?"Full Focus включён":QuickRecorder.active()?"Идёт запись экрана · без звука":"XPhone следит за играми")
                .setContentText("Двойное нажатие на язычок открывает панель")
                .setContentIntent(open)
                .setOngoing(true);
        if(QuickRecorder.active()){
            PendingIntent stop=PendingIntent.getService(this,91,new Intent(this,BoostService.class).setAction("ru.xphone.booster.STOP_RECORD"),PendingIntent.FLAG_IMMUTABLE);
            builder.addAction(new Notification.Action.Builder(null,"Остановить запись",stop).build());
        }
        if(FullFocus.active()){
            PendingIntent stop=PendingIntent.getService(this,92,new Intent(this,BoostService.class).setAction("ru.xphone.booster.STOP_FOCUS"),PendingIntent.FLAG_IMMUTABLE);
            builder.addAction(new Notification.Action.Builder(null,"Выключить Full Focus",stop).build());
        }
        Notification n=builder.build();
        if (Build.VERSION.SDK_INT >= 34)
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        else
            startForeground(1, n);
    }
}
