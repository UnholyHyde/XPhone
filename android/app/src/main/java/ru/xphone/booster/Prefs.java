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
    /** Приветствие при запуске игры (вместо сразу открытой панели). */
    public static final String WELCOME = "welcome";
    /** Язычок у левого края (иначе — у правого). */
    public static final String HANDLE_LEFT = "handle_left";
    /** Плавающая кнопка микрофона в игре. */
    public static final String MIC_BUBBLE = "mic_bubble";
    /** Включать «Питание USB», когда заряд не меньше этого (0 — не включать само). */
    public static final String PASS_LEVEL = "pass_level";
    private static final String ADDED = "games_added";
    private static final String EXCLUDED = "games_excluded";

    private static final Map<String, Boolean> GAME_CACHE = new HashMap<>();

    public static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences("xphone", Context.MODE_PRIVATE);
    }

    public static boolean get(Context c, String key, boolean def) { return sp(c).getBoolean(key, def); }
    public static void put(Context c, String key, boolean v) { sp(c).edit().putBoolean(key, v).apply(); }

    public static boolean enabled(Context c) { return get(c, ENABLED, true); }
    public static boolean autoPanel(Context c) { return get(c, AUTO_PANEL, false); }
    public static boolean welcome(Context c) { return get(c, WELCOME, true); }
    public static int handleEdge(Context c){int edge=getInt(c,"handle_edge",handleLeft(c)?0:1);return edge>=0&&edge<=3?edge:1;}
    public static void setHandleEdge(Context c,int edge){if(edge<0||edge>3)return;SharedPreferences.Editor edit=sp(c).edit().putInt("handle_edge",edge);if(edge<2)edit.putBoolean(HANDLE_LEFT,edge==0);edit.apply();}
    public static boolean handleLeft(Context c) { return get(c, HANDLE_LEFT, false); }
    public static int getInt(Context c, String key, int def) { return sp(c).getInt(key, def); }
    public static void putInt(Context c, String key, int v) { sp(c).edit().putInt(key, v).apply(); }
    public static float getFloat(Context c, String key, float def) { return sp(c).getFloat(key, def); }
    public static void putFloat(Context c, String key, float v) { sp(c).edit().putFloat(key, v).apply(); }
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
