package ru.xphone.booster;

import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import rikka.shizuku.Shizuku;

/** Команды с правами adb через Shizuku. Без Shizuku часть функций недоступна. */
public final class Shell {
    private Shell() {}

    public static final String SHIZUKU_PKG = "moe.shizuku.privileged.api";

    public enum State { NOT_INSTALLED, NOT_RUNNING, NO_PERMISSION, READY }

    private static final Object LOCK = new Object();
    private static final ExecutorService BG = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static volatile IShellService service;
    private static ServiceConnection connection;

    public static boolean isInstalled(Context c) {
        try {
            c.getPackageManager().getPackageInfo(SHIZUKU_PKG, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    public static State state(Context c) {
        try {
            if (!Shizuku.pingBinder()) return isInstalled(c) ? State.NOT_RUNNING : State.NOT_INSTALLED;
            if (Shizuku.isPreV11()) return State.NOT_RUNNING;
            return Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED ? State.READY : State.NO_PERMISSION;
        } catch (Throwable t) {
            return isInstalled(c) ? State.NOT_RUNNING : State.NOT_INSTALLED;
        }
    }

    public static boolean ready(Context c) {
        return state(c) == State.READY;
    }

    public static void requestPermission() {
        try { Shizuku.requestPermission(1); } catch (Throwable ignored) { }
    }

    /** Выполнить команду. Блокирует поток — вызывать не из главного. null — Shizuku недоступен. */
    public static String run(Context c, String command) {
        IShellService s = bind(c.getApplicationContext());
        if (s == null) return null;
        try {
            return s.exec(command);
        } catch (Exception e) {
            service = null;
            return null;
        }
    }

    /** Выполнить в фоне, результат — в главном потоке. */
    public static boolean touchAvailable(Context c) {
        IShellService s=bind(c.getApplicationContext());
        try { return s!=null && s.touchAvailable(); } catch(Exception e){return false;}
    }
    public static boolean startTouch(Context c, ITouchListener listener) {
        IShellService s=bind(c.getApplicationContext());
        try { return s!=null && s.startTouch(listener); } catch(Exception e){return false;}
    }
    public static void stopTouch() {
        IShellService s=service;
        try { if(s!=null)s.stopTouch(); } catch(Exception ignored){}
    }

    public static boolean privacyAvailable(Context c){IShellService s=bind(c.getApplicationContext());try{return s!=null&&s.privacyAvailable();}catch(Exception e){return false;}}
    public static String streamPrivacy(Context c,boolean active){IShellService s=bind(c.getApplicationContext());try{return s==null?"DENIED":s.streamPrivacy(active,android.os.Process.myUid()/100000);}catch(Exception e){return "ERROR";}}
    public static String resumeGame(Context c,String component){IShellService s=bind(c.getApplicationContext());try{return s==null?"UNAVAILABLE":s.resumeGame(component,android.os.Process.myUid()/100000);}catch(Exception e){return "UNAVAILABLE";}}
    public static String restartApp(Context c,String component){IShellService s=bind(c.getApplicationContext());try{return s==null?"DENIED":s.restartApp(component,android.os.Process.myUid()/100000);}catch(Exception e){return "ERROR";}}
    public static String startRecord(Context c,String name,int mbps){IShellService s=bind(c.getApplicationContext());try{return s==null?"ERROR":s.startRecord(name,mbps);}catch(Exception e){return "ERROR";}}
    public static String stopRecord(Context c){IShellService s=service;try{return s==null?"ERROR":s.stopRecord();}catch(Exception e){return "ERROR";}}
    public static String recordState(Context c){IShellService s=service;try{return s==null?"ERROR":s.recordState();}catch(Exception e){return "ERROR";}}
    public static boolean windowAvailable(Context c) {
        IShellService s=bind(c.getApplicationContext());try{return s!=null&&s.windowAvailable();}catch(Exception e){return false;}
    }
    public static String openWindow(Context c,String component,int[] bounds) {
        IShellService s=bind(c.getApplicationContext());try{return s==null?"DENIED":s.openWindow(component,bounds,android.os.Process.myUid()/100000);}catch(Exception e){return "DENIED";}
    }
    public static String minimizeWindow(Context c,String pkg) {
        IShellService s=bind(c.getApplicationContext());try{return s==null?"DENIED":s.minimizeWindow(pkg,android.os.Process.myUid()/100000);}catch(Exception e){return "DENIED";}
    }
    public static String windowState(Context c,String pkg) {
        IShellService s=bind(c.getApplicationContext());try{return s==null?"UNKNOWN":s.windowState(pkg,android.os.Process.myUid()/100000);}catch(Exception e){return "UNKNOWN";}
    }
    public static void endWindows() {IShellService s=service;try{if(s!=null)s.endWindows();}catch(Exception ignored){}}

    public static void runAsync(Context c, String command, Consumer<String> done) {
        BG.execute(() -> {
            String r = run(c, command);
            if (done != null) MAIN.post(() -> done.accept(r));
        });
    }

    /** Любая работа в фоне по очереди (команды не перемешиваются). */
    public static void background(Runnable r) {
        BG.execute(r);
    }

    public static void onMain(Runnable r) {
        MAIN.post(r);
    }

    private static IShellService bind(Context c) {
        synchronized (LOCK) {
            IShellService s = service;
            if (s != null && s.asBinder().pingBinder()) return s;
            if (!ready(c)) return null;
            CountDownLatch latch = new CountDownLatch(1);
            Shizuku.UserServiceArgs args = new Shizuku.UserServiceArgs(
                    new ComponentName(c.getPackageName(), ShellService.class.getName()))
                    .daemon(false)
                    .processNameSuffix("shell")
                    .debuggable(BuildConfig.DEBUG)
                    .version(BuildConfig.VERSION_CODE);
            connection = new ServiceConnection() {
                @Override
                public void onServiceConnected(ComponentName name, IBinder binder) {
                    if (binder != null && binder.pingBinder()) service = IShellService.Stub.asInterface(binder);
                    latch.countDown();
                }

                @Override
                public void onServiceDisconnected(ComponentName name) {
                    service = null;
                }
            };
            MAIN.post(() -> {
                try { Shizuku.bindUserService(args, connection); } catch (Throwable t) { latch.countDown(); }
            });
            try { latch.await(5, TimeUnit.SECONDS); } catch (InterruptedException ignored) { }
            return service;
        }
    }
}
