package ru.xphone.booster;

import android.app.NotificationManager;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.BatteryManager;
import android.os.Build;
import android.os.PowerManager;
import android.provider.Settings;

/**
 * Функции игровой панели. Всё, что меняется, сначала запоминается — после игры возвращается как было.
 * Методы read/set/restoreAll блокируют поток: вызывать через Shell.background().
 */
public final class Booster {
    private Booster() {}

    public static final String DND = "dnd";       // «Не беспокоить» (только важные — звук игры остаётся)
    public static final String PERF = "perf";     // игровой режим «Производительность»
    public static final String PASS = "pass";     // Samsung: питание напрямую от зарядки
    public static final String ANIM = "anim";     // без анимаций
    public static final String BRIGHT = "bright"; // яркость не меняется сама
    public static final String CLEAN = "clean";   // закрыть фоновые приложения (действие)
    public static final String RES = "res";       // разрешение экрана (открывает настройки)
    public static final String STREAM = "stream"; // трансляция на ПК (через XPhone на компьютере)

    public static final String[] TOGGLES = {DND, PERF, PASS, ANIM, BRIGHT};
    private static final String[] ANIM_KEYS = {
            Settings.Global.WINDOW_ANIMATION_SCALE, Settings.Global.TRANSITION_ANIMATION_SCALE,
            Settings.Global.ANIMATOR_DURATION_SCALE};

    public static boolean isSamsung() {
        return "samsung".equalsIgnoreCase(Build.MANUFACTURER);
    }

    public static String title(String id) {
        switch (id) {
            case DND: return "Не беспокоить";
            case PERF: return "Производительность";
            case PASS: return "Питание USB";
            case ANIM: return "Без анимаций";
            case BRIGHT: return "Яркость: не менять";
            case CLEAN: return "Очистить фон";
            case RES: return "Разрешение";
            case STREAM: return "Стрим на ПК";
            default: return id;
        }
    }

    /** Короткое название для плитки (длинные слова не помещаются). */
    public static String shortTitle(String id) {
        switch (id) {
            case PERF: return "Турбо";
            case BRIGHT: return "Яркость";
            case CLEAN: return "Очистка";
            default: return title(id);
        }
    }

    /** Совсем короткое название — для круглых кнопок темы «Стекло». */
    public static String tinyTitle(String id) {
        switch (id) {
            case DND: return "Тишина";
            case PASS: return "USB";
            case ANIM: return "Анимации";
            case RES: return "Экран";
            case STREAM: return "Стрим";
            default: return shortTitle(id);
        }
    }

    /** Подпись состояния на плитке. */
    public static String stateText(String id, boolean on) {
        switch (id) {
            case BRIGHT: return on ? "Не меняется" : "Авто";
            case ANIM: return on ? "Выключены" : "Обычные";
            default: return on ? "Вкл" : "Выкл";
        }
    }

    public static String hint(String id) {
        switch (id) {
            case DND: return "Уведомления молчат, звук игры остаётся";
            case PERF: return "Игровой режим «Производительность» для этой игры";
            case PASS: return "На зарядке 25 Вт+ телефон питается от неё, батарея не греется";
            case ANIM: return "Интерфейс отзывчивее";
            case BRIGHT: return "Автояркость не дёргает экран во время игры";
            case CLEAN: return "Закрыть приложения в фоне";
            case RES: return "Откроет настройки: выберите FHD+ или WQHD+";
            case STREAM: return "Трансляция экрана и звука на компьютер (нужен XPhone на ПК)";
            default: return "";
        }
    }

    public static int icon(String id) {
        switch (id) {
            case DND: return R.drawable.ic_bell_off;
            case PERF: return R.drawable.ic_zap;
            case PASS: return R.drawable.ic_plug;
            case ANIM: return R.drawable.ic_wind;
            case BRIGHT: return R.drawable.ic_sun_lock;
            case CLEAN: return R.drawable.ic_trash;
            case RES: return R.drawable.ic_monitor;
            case STREAM: return R.drawable.ic_cast;
            default: return R.drawable.ic_settings;
        }
    }

    /** Нужен ли Shizuku (если нет права на системные настройки). */
    public static boolean needsShizuku(String id) {
        return !(DND.equals(id) || RES.equals(id) || STREAM.equals(id));
    }

    /**
     * Право менять системные настройки — выдаёт XPhone на ПК при подключении телефона (или Shizuku).
     * Сохраняется навсегда: с ним телефон сам включает «Отладку по Wi-Fi» и выключает анимации без Shizuku.
     */
    public static boolean canWriteSecure(Context c) {
        return c.checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS") == android.content.pm.PackageManager.PERMISSION_GRANTED;
    }

    /** Записать глобальную настройку: через Shizuku или своим правом. */
    public static void putGlobal(Context c, String key, String value) {
        if (Shell.ready(c)) {
            sh(c, "settings put global " + key + " " + value);
        } else if (canWriteSecure(c)) {
            try { Settings.Global.putString(c.getContentResolver(), key, value); } catch (Exception ignored) { }
        }
    }

    /** Есть ли функция на этом телефоне вообще. */
    public static boolean supported(String id) {
        if (PASS.equals(id)) return isSamsung();
        if (PERF.equals(id)) return Build.VERSION.SDK_INT >= 33;
        return true;
    }

    /** Можно ли сейчас пользоваться (есть права). */
    public static boolean available(Context c, String id) {
        if (!supported(id)) return false;
        if (DND.equals(id)) return Shell.ready(c) || dndAccess(c);
        if (RES.equals(id) || STREAM.equals(id)) return true;
        if (ANIM.equals(id)) return Shell.ready(c) || canWriteSecure(c);
        return Shell.ready(c);
    }

    public static boolean dndAccess(Context c) {
        return c.getSystemService(NotificationManager.class).isNotificationPolicyAccessGranted();
    }

    // ── Чтение текущего состояния ──────────────────────────────────────

    public static boolean read(Context c, String id, String pkg) {
        ContentResolver cr = c.getContentResolver();
        switch (id) {
            case DND:
                return Settings.Global.getInt(cr, "zen_mode", 0) != 0;
            case PERF:
                if (pkg == null) return false;
                String modes = sh(c, "cmd game list-modes " + pkg);
                return modes != null && modes.contains("current mode: performance");
            case PASS:
                return "1".equals(sh(c, "settings get system pass_through"));
            case ANIM:
                return Settings.Global.getFloat(cr, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f;
            case BRIGHT:
                return Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, 1) == 0;
            default:
                return false;
        }
    }

    // ── Включение / выключение ─────────────────────────────────────────

    public static void set(Context c, String id, boolean on, String pkg) {
        remember(c, id, pkg);
        switch (id) {
            case DND:
                setDnd(c, on ? 1 : 0);
                break;
            case PERF:
                if (pkg != null) sh(c, "cmd game mode " + (on ? "performance" : "standard") + " " + pkg);
                break;
            case PASS:
                sh(c, "settings put system pass_through " + (on ? 1 : 0));
                break;
            case ANIM:
                for (String k : ANIM_KEYS) putGlobal(c, k, on ? "0" : "1.0");
                break;
            case BRIGHT:
                sh(c, "settings put system screen_brightness_mode " + (on ? 0 : 1));
                break;
        }
    }

    /** Закрыть фоновые приложения. */
    public static void clean(Context c) {
        sh(c, "am kill-all");
    }

    /** zen: 0 — выкл, 1 — только важные, 2 — полная тишина, 3 — только будильники. */
    private static void setDnd(Context c, int zen) {
        if (Shell.ready(c)) {
            String mode = zen == 1 ? "priority" : zen == 2 ? "none" : zen == 3 ? "alarms" : "off";
            sh(c, "cmd notification set_dnd " + mode);
        } else if (dndAccess(c)) {
            int filter = zen == 1 ? NotificationManager.INTERRUPTION_FILTER_PRIORITY
                    : zen == 2 ? NotificationManager.INTERRUPTION_FILTER_NONE
                    : zen == 3 ? NotificationManager.INTERRUPTION_FILTER_ALARMS
                    : NotificationManager.INTERRUPTION_FILTER_ALL;
            c.getSystemService(NotificationManager.class).setInterruptionFilter(filter);
        }
    }

    // ── Запомнить и вернуть как было ───────────────────────────────────

    private static SharedPreferences orig(Context c) {
        return c.getApplicationContext().getSharedPreferences("originals", Context.MODE_PRIVATE);
    }

    public static boolean hasChanges(Context c) {
        return !orig(c).getAll().isEmpty();
    }

    private static void remember(Context c, String id, String pkg) {
        SharedPreferences o = orig(c);
        if (o.contains(id)) return;
        ContentResolver cr = c.getContentResolver();
        String v = null;
        switch (id) {
            case DND:
                v = String.valueOf(Settings.Global.getInt(cr, "zen_mode", 0));
                break;
            case PERF:
                if (pkg == null) return;
                String modes = sh(c, "cmd game list-modes " + pkg);
                String mode = "standard";
                if (modes != null) {
                    int i = modes.indexOf("current mode: ");
                    if (i >= 0) mode = modes.substring(i + 14).split("[,\\s]")[0];
                }
                if (!mode.matches("standard|performance|battery")) mode = "standard";
                v = mode + "|" + pkg;
                break;
            case PASS:
                String p = sh(c, "settings get system pass_through");
                v = "1".equals(p) ? "1" : "0";
                break;
            case ANIM:
                StringBuilder sb = new StringBuilder();
                for (String k : ANIM_KEYS) {
                    String s = Settings.Global.getString(cr, k);
                    sb.append(s == null ? "1.0" : s).append('|');
                }
                v = sb.toString();
                break;
            case BRIGHT:
                v = String.valueOf(Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, 1));
                break;
        }
        if (v != null) o.edit().putString(id, v).commit();
    }

    /** Вернуть всё, что меняли, как было до игры. */
    public static void restoreAll(Context c) {
        SharedPreferences o = orig(c);
        for (String id : o.getAll().keySet()) {
            String v = o.getString(id, null);
            if (v == null) continue;
            try {
                switch (id) {
                    case DND:
                        setDnd(c, Integer.parseInt(v));
                        break;
                    case PERF:
                        String[] parts = v.split("\\|");
                        if (parts.length == 2) sh(c, "cmd game mode " + parts[0] + " " + parts[1]);
                        break;
                    case PASS:
                        sh(c, "settings put system pass_through " + v);
                        break;
                    case ANIM:
                        String[] vals = v.split("\\|");
                        for (int i = 0; i < ANIM_KEYS.length && i < vals.length; i++) putGlobal(c, ANIM_KEYS[i], vals[i]);
                        break;
                    case BRIGHT:
                        sh(c, "settings put system screen_brightness_mode " + v);
                        break;
                }
            } catch (Exception ignored) { }
        }
        o.edit().clear().commit();
    }

    // ── Нагрев и батарея ───────────────────────────────────────────────

    public static final class Info {
        public int batteryPercent = -1;
        public float batteryTemp = Float.NaN;
        public boolean charging;
        public int thermal; // 0 — норма … 4+ — сильный перегрев
    }

    public static Info info(Context c) {
        Info i = new Info();
        Intent b = c.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (b != null) {
            int level = b.getIntExtra(BatteryManager.EXTRA_LEVEL, -1), scale = b.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
            if (level >= 0 && scale > 0) i.batteryPercent = Math.round(level * 100f / scale);
            int t = b.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Integer.MIN_VALUE);
            if (t != Integer.MIN_VALUE) i.batteryTemp = t / 10f;
            i.charging = b.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0;
        }
        if (Build.VERSION.SDK_INT >= 29) i.thermal = c.getSystemService(PowerManager.class).getCurrentThermalStatus();
        return i;
    }

    public static String heatText(int thermal) {
        switch (thermal) {
            case 0: return "норма";
            case 1: return "тёплый";
            case 2: return "горячий";
            default: return "перегрев — частоты снижены";
        }
    }

    private static String sh(Context c, String cmd) {
        String r = Shell.run(c, cmd);
        return r == null ? null : r.trim();
    }
}
