package ru.xphone.booster;

import android.content.Context;
import android.os.Build;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Связь с XPhone на компьютере. XPhone на ПК через adb пробрасывает к телефону порт 47190
 * (adb reverse) — телефон подключается к 127.0.0.1:47190 и попадает в программу на ПК.
 * Если связи нет — раз в несколько секунд пробуем снова (это почти ничего не тратит).
 */
public final class PcLink {
    private PcLink() {}

    public static final int PORT = 47190;

    public enum State { NO_PC, IDLE, CONNECTING, STREAMING }

    private static volatile State state = State.NO_PC;
    private static volatile String pcName;
    private static volatile String message;
    private static volatile long pendingStartUntil;
    private static volatile Writer out;
    private static volatile Thread thread;
    private static volatile boolean running;
    private static Context app;

    public interface Listener { void changed(); }
    private static final CopyOnWriteArrayList<Listener> LISTENERS = new CopyOnWriteArrayList<>();

    public static void addListener(Listener l) { LISTENERS.add(l); }
    public static void removeListener(Listener l) { LISTENERS.remove(l); }

    public static State state() { return state; }
    public static String pcName() { return pcName; }
    public static String message() { return message; }
    public static boolean waiting() { return state == State.NO_PC && System.currentTimeMillis() < pendingStartUntil; }

    private static void changed() {
        if(app!=null)Shell.onMain(()->StreamPrivacy.tick(app));
        Shell.onMain(() -> { for (Listener l : LISTENERS) l.changed(); });
    }

    /** Запустить фоновое подключение (вызывается службой). */
    public static synchronized void start(Context c) {
        app = c.getApplicationContext();
        if (thread != null && thread.isAlive()) return;
        running = true;
        thread = new Thread(PcLink::loop, "xphone-pc-link");
        thread.setDaemon(true);
        thread.start();
    }

    public static synchronized void stop() {
        running = false;
        if (thread != null) thread.interrupt();
        thread = null;
    }

    private static void loop() {
        while (running) {
            try (Socket s = new Socket()) {
                s.connect(new InetSocketAddress("127.0.0.1", PORT), 1500);
                s.setTcpNoDelay(true);
                Writer w = new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8);
                out = w;
                send("HELLO " + Build.MODEL+" XPHONE2");
                state = State.IDLE;
                changed();
                if (System.currentTimeMillis() < pendingStartUntil) {
                    pendingStartUntil = 0;
                    if(!Prefs.get(app,"stream_privacy",true)||!Prefs.get(app,"privacy_available",false)||"PROTECTED".equals(Shell.streamPrivacy(app,true)))send("START");
                    else{message="Не удалось скрыть уведомления — запуск отменён";changed();}
                }
                BufferedReader r = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
                String line;
                while (running && (line = r.readLine()) != null) handle(line.trim());
            } catch (Exception ignored) {
            }
            out = null;
            if (state != State.NO_PC) {
                state = State.NO_PC;
                changed();
            }
            try {
                // Ждём быстрее, если человек только что нажал «Подключиться».
                Thread.sleep(System.currentTimeMillis() < pendingStartUntil ? 1500 : 4000);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    private static void handle(String line) {
        if(line.startsWith("PREPARE_PRIVACY ")){
            String id=line.substring(16).trim();if(!id.matches("[a-f0-9]{32}"))return;
            Shell.background(()->{
                String status="disabled";
                if(Prefs.get(app,"stream_privacy",true)&&Prefs.get(app,"privacy_available",false)){
                    status=Shell.ready(app)&&"PROTECTED".equals(Shell.streamPrivacy(app,true))?"protected":"denied";
                }
                send("PRIVACY "+id+" "+status);
            });return;
        }

        if (line.startsWith("PC ")) {
            pcName = line.substring(3);
            Prefs.put(app, "pc_ever_connected", true);
        } else if (line.startsWith("STATE ")) {
            String v = line.substring(6);
            state = "streaming".equals(v) ? State.STREAMING : "connecting".equals(v) ? State.CONNECTING : State.IDLE;
            if (state == State.STREAMING) message = null;
        } else if (line.startsWith("MSG ")) {
            message = line.substring(4);
        }
        changed();
    }

    private static synchronized boolean send(String line) {
        Writer w = out;
        if (w == null) return false;
        try {
            w.write(line + "\n");
            w.flush();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Начать трансляцию на ПК. Если связи ещё нет — включаем «Отладку по Wi-Fi» (через Shizuku)
     * и ждём, пока XPhone на ПК найдёт телефон (до полутора минут).
     */
    public static void requestStart(Context c) {
        message = null;
        if (state != State.NO_PC) {
            Shell.background(() -> {
                if(Prefs.get(c,"stream_privacy",true)&&Prefs.get(c,"privacy_available",false)){
                    String protection=Shell.streamPrivacy(c,true);
                    if(!"PROTECTED".equals(protection)){state=State.IDLE;message="Не удалось скрыть уведомления. Проверьте Shizuku или выключите защиту в настройках.";changed();return;}
                }
                send("START");
            });
            state = State.CONNECTING;
            changed();
            return;
        }
        pendingStartUntil = System.currentTimeMillis() + 90_000;
        changed();
        enableWifiDebugging(c);
    }

    public static void requestStop() {
        pendingStartUntil = 0;
        Shell.background(() -> send("STOP"));
        changed();
    }

    /** Только найти компьютер (без трансляции). */
    public static void findPc(Context c) {
        enableWifiDebugging(c);
    }

    /** Включить «Отладку по Wi-Fi» и не давать телефону «забывать» компьютер через неделю. */
    public static void enableWifiDebugging(Context c) {
        if (!PhoneNetwork.lanAddresses().isEmpty() && state == State.NO_PC) {
            message="LAN обнаружен. Если ПК не находится: откройте на нём «Сеть / LAN» и введите LAN-адрес. Для некоторых прошивок нужна предварительная подготовка через USB.";
            changed();
        }
        if (!canEnableWifiDebugging(c)) return;
        Shell.background(() -> {
            Booster.putGlobal(c, "adb_allowed_connection_time", "0");
            Booster.putGlobal(c, "adb_wifi_enabled", "1");
        });
    }

    /** Может ли приложение само включить «Отладку по Wi-Fi» (через Shizuku или своё право). */
    public static boolean canEnableWifiDebugging(Context c) {
        return Shell.ready(c) || Booster.canWriteSecure(c);
    }

    public static boolean wifiDebuggingOn(Context c) {
        try {
            return android.provider.Settings.Global.getInt(c.getContentResolver(), "adb_wifi_enabled", 0) == 1;
        } catch (Exception e) {
            return false;
        }
    }
}
