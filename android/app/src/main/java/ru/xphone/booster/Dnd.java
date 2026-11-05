package ru.xphone.booster;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * «Не беспокоить» на время: 15 минут, час, до конца игры… По окончании выключается само
 * и показывает сообщение поверх игры.
 */
public final class Dnd {
    private Dnd() {}

    /** До конца игры (выключится при выходе из игры вместе с остальными настройками). */
    public static final int UNTIL_GAME_END = -1;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static long until;          // время окончания; -1 — до конца игры; 0 — без таймера
    private static Runnable pending;

    public static boolean timed() { return until != 0; }

    /** «до 23:40», «до конца игры» или пусто. */
    public static String untilText() {
        if (until == UNTIL_GAME_END) return "до конца игры";
        if (until > 0) return "до " + new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date(until));
        return "";
    }

    /** Включить на minutes минут (или UNTIL_GAME_END). */
    public static void start(Context c, int minutes, Runnable changed) {
        cancelTimer();
        until = minutes == UNTIL_GAME_END ? UNTIL_GAME_END : System.currentTimeMillis() + minutes * 60_000L;
        Shell.background(() -> {
            Booster.set(c, Booster.DND, true, null);
            Shell.onMain(() -> {
                BoostService.toast("Не беспокоить включено · " + untilText(), R.drawable.ic_bell_off);
                if (changed != null) changed.run();
            });
            // Проверка: на Samsung с Galaxy Watch служба часов выключает режим через секунду
            // (синхронизирует «Не беспокоить» со своим). Не делаем вид, что режим работает.
            try { Thread.sleep(2500); } catch (InterruptedException ignored) { }
            if (until != 0 && !Booster.read(c, Booster.DND, null)) {
                Shell.onMain(() -> {
                    cancelTimer();
                    until = 0;
                    BoostService.toast("«Не беспокоить» выключил телефон — см. подсказку в XPhone", R.drawable.ic_alert);
                    Prefs.put(c, "dnd_reverted", true);
                    if (changed != null) changed.run();
                });
            }
        });
        if (minutes > 0) {
            pending = () -> stop(c, null);
            MAIN.postDelayed(pending, minutes * 60_000L);
        }
    }

    public static void stop(Context c, Runnable changed) {
        cancelTimer();
        until = 0;
        Shell.background(() -> {
            Booster.set(c, Booster.DND, false, null);
            Shell.onMain(() -> {
                BoostService.toast("Не беспокоить выключено", R.drawable.ic_bell);
                if (changed != null) changed.run();
            });
        });
    }

    /** Игра закрылась — таймер больше не нужен (настройки вернёт Booster.restoreAll). */
    public static void reset() {
        cancelTimer();
        until = 0;
    }

    private static void cancelTimer() {
        if (pending != null) MAIN.removeCallbacks(pending);
        pending = null;
    }
}
