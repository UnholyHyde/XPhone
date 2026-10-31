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
                send("HELLO " + Build.MODEL);
                state = State.IDLE;
                changed();
                if (System.currentTimeMillis() < pendingStartUntil) {
                    pendingStartUntil = 0;
                    send("START");
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
            Shell.background(() -> send("START"));
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
        if (!Shell.ready(c)) return;
        Shell.background(() -> Shell.run(c,
                "settings put global adb_allowed_connection_time 0; settings put global adb_wifi_enabled 1"));
    }

    public static boolean wifiDebuggingOn(Context c) {
        try {
            return android.provider.Settings.Global.getInt(c.getContentResolver(), "adb_wifi_enabled", 0) == 1;
        } catch (Exception e) {
            return false;
        }
    }
}
