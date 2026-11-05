package ru.xphone.booster;

import android.content.Context;
import android.os.Build;

/**
 * Мгновенно выключить микрофон телефона для всех приложений — системным переключателем
 * «Доступ к микрофону» (Android 12+, через Shizuku). Игра и голосовой чат получают тишину.
 * После игры микрофон включается обратно, если выключили его мы.
 */
public final class Mic {
    private Mic() {}

    private static volatile boolean muted;

    public static boolean muted() { return muted; }

    /** Есть ли функция на этом телефоне сейчас. */
    public static boolean supported(Context c) {
        return Build.VERSION.SDK_INT >= 31 && Shell.ready(c);
    }

    private static int userId() {
        return android.os.Process.myUid() / 100000;
    }

    /** Выключить или включить. Блокирует поток. true — получилось. */
    public static boolean set(Context c, boolean mute) {
        String r = Shell.run(c, "cmd sensor_privacy " + (mute ? "enable " : "disable ") + userId() + " microphone");
        boolean ok = r != null && !r.toLowerCase().contains("exception") && !r.toLowerCase().contains("unknown")
                && !r.toLowerCase().contains("error");
        if (ok) muted = mute;
        return ok;
    }

    /** После игры вернуть микрофон, если выключили его мы. */
    public static void restore(Context c) {
        if (muted) set(c, false);
    }
}
