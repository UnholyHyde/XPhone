package ru.xphone.booster;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Настройки игровой панели. */
public final class Prefs {
    private Prefs() {}

    public static final String ENABLED = "enabled";
    public static final String AUTO_PANEL = "auto_panel";
    public static final String RESTORE = "restore";
    public static final String HANDLE_Y = "handle_y";
    /** Начинать трансляцию на ПК, когда открывается игра. */
    public static final String AUTO_STREAM = "auto_stream";
    /** Останавливать трансляцию, когда игра закрыта (если её начала игра). */
    public static final String AUTO_STREAM_STOP = "auto_stream_stop";
    private static final String ADDED = "games_added";
    private static final String EXCLUDED = "games_excluded";

    private static final Map<String, Boolean> GAME_CACHE = new HashMap<>();

    public static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences("xphone", Context.MODE_PRIVATE);
    }

    public static boolean get(Context c, String key, boolean def) { return sp(c).getBoolean(key, def); }
    public static void put(Context c, String key, boolean v) { sp(c).edit().putBoolean(key, v).apply(); }

    public static boolean enabled(Context c) { return get(c, ENABLED, true); }
    public static boolean autoPanel(Context c) { return get(c, AUTO_PANEL, true); }
    public static boolean restore(Context c) { return get(c, RESTORE, true); }

    /** Включать функцию сама при запуске игры. По умолчанию — «Не беспокоить» и «Производительность». */
    public static boolean auto(Context c, String feature) {
        boolean def = Booster.DND.equals(feature) || Booster.PERF.equals(feature);
        return get(c, "auto_" + feature, def);
    }
    public static void setAuto(Context c, String feature, boolean v) { put(c, "auto_" + feature, v); }

    public static float handleY(Context c) { return sp(c).getFloat(HANDLE_Y, 0.38f); }
    public static void setHandleY(Context c, float v) { sp(c).edit().putFloat(HANDLE_Y, v).apply(); }

    private static Set<String> set(Context c, String key) { return new HashSet<>(sp(c).getStringSet(key, new HashSet<>())); }

    /** Игра ли это: отмечено вручную, либо Android считает приложение игрой. */
    public static boolean isGame(Context c, String pkg) {
        if (pkg == null) return false;
        if (set(c, EXCLUDED).contains(pkg)) return false;
        if (set(c, ADDED).contains(pkg)) return true;
        return isGameByCategory(c, pkg);
    }

    public static boolean isGameByCategory(Context c, String pkg) {
        Boolean cached = GAME_CACHE.get(pkg);
        if (cached != null) return cached;
        boolean game = false;
        try {
            ApplicationInfo ai = c.getPackageManager().getApplicationInfo(pkg, 0);
            game = ai.category == ApplicationInfo.CATEGORY_GAME || (ai.flags & ApplicationInfo.FLAG_IS_GAME) != 0;
        } catch (PackageManager.NameNotFoundException ignored) { }
        GAME_CACHE.put(pkg, game);
        return game;
    }

    public static void setGame(Context c, String pkg, boolean game) {
        Set<String> added = set(c, ADDED), excluded = set(c, EXCLUDED);
        boolean byCategory = isGameByCategory(c, pkg);
        added.remove(pkg);
        excluded.remove(pkg);
        if (game && !byCategory) added.add(pkg);
        if (!game && byCategory) excluded.add(pkg);
        sp(c).edit().putStringSet(ADDED, added).putStringSet(EXCLUDED, excluded).apply();
    }
}
