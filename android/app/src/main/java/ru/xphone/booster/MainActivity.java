package ru.xphone.booster;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.AppOpsManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Process;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import rikka.shizuku.Shizuku;

/** XPhone на телефоне: вкладки «Игра», «Стрим», «Настройки». */
public class MainActivity extends Activity {

    private static final int TAB_GAME = 0, TAB_STREAM = 1, TAB_SETTINGS = 2;

    private ScrollView scroll;
    private LinearLayout content;
    private int tab = TAB_GAME;
    private PcLink.State lastPc = PcLink.State.NO_PC;

    private final Shizuku.OnRequestPermissionResultListener permListener = (code, result) -> runOnUiThread(this::render);
    private final Shizuku.OnBinderReceivedListener binderListener = () -> runOnUiThread(this::render);
    private final Shizuku.OnBinderDeadListener deadListener = () -> runOnUiThread(this::render);

    /** Телефон связался с ПК — сразу показываем вкладку «Стрим». */
    private final PcLink.Listener pcListener = () -> {
        PcLink.State now = PcLink.state();
        if (lastPc == PcLink.State.NO_PC && now != PcLink.State.NO_PC) tab = TAB_STREAM;
        lastPc = now;
        render();
    };

    // ── Права ─────────────────────────────────────────────────────────

    public static boolean overlayOk(Context c) { return Settings.canDrawOverlays(c); }

    public static boolean usageOk(Context c) {
        AppOpsManager ops = c.getSystemService(AppOpsManager.class);
        int mode = Build.VERSION.SDK_INT >= 29
                ? ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), c.getPackageName())
                : ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), c.getPackageName());
        return mode == AppOpsManager.MODE_ALLOWED;
    }

    public static boolean notifOk(Context c) {
        return Build.VERSION.SDK_INT < 33 || c.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
    }

    public static boolean permissionsOk(Context c) { return overlayOk(c) && usageOk(c); }

    // ── Жизненный цикл ────────────────────────────────────────────────

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        int p = Ui.dp(this, 18);
        content.setPadding(p, Ui.dp(this, 22), p, Ui.dp(this, 28));
        scroll.addView(content);
        setContentView(scroll);
        // Android 15+ рисует приложение под строкой состояния — отодвигаем содержимое.
        final int padTop = content.getPaddingTop(), padBottom = content.getPaddingBottom();
        scroll.setOnApplyWindowInsetsListener((v, insets) -> {
            int top, bottom;
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(android.view.WindowInsets.Type.systemBars());
                top = bars.top; bottom = bars.bottom;
            } else {
                top = insets.getSystemWindowInsetTop(); bottom = insets.getSystemWindowInsetBottom();
            }
            content.setPadding(content.getPaddingLeft(), padTop + top, content.getPaddingRight(), padBottom + bottom);
            return insets;
        });
        Shizuku.addRequestPermissionResultListener(permListener);
        Shizuku.addBinderReceivedListenerSticky(binderListener);
        Shizuku.addBinderDeadListener(deadListener);
        if (PcLink.state() != PcLink.State.NO_PC) tab = TAB_STREAM;
    }

    @Override
    protected void onDestroy() {
        Shizuku.removeRequestPermissionResultListener(permListener);
        Shizuku.removeBinderReceivedListener(binderListener);
        Shizuku.removeBinderDeadListener(deadListener);
        super.onDestroy();
    }

    @Override
    protected void onResume() {
        super.onResume();
        PcLink.start(this);
        lastPc = PcLink.state();
        PcLink.addListener(pcListener);
        render();
        if (Prefs.enabled(this) && permissionsOk(this)) BoostService.start(this);
    }

    @Override
    protected void onPause() {
        PcLink.removeListener(pcListener);
        super.onPause();
    }

    // ── Экран ─────────────────────────────────────────────────────────

    private void render() {
        Ui.load(this);
        getWindow().getDecorView().setBackground(Ui.screenBackground(this));
        scroll.setBackgroundColor(0);
        content.removeAllViews();

        // Шапка
        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        ImageView logo = new ImageView(this);
        logo.setImageResource(R.mipmap.ic_launcher);
        head.addView(logo, new LinearLayout.LayoutParams(Ui.dp(this, 40), Ui.dp(this, 40)));
        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.setPadding(Ui.dp(this, 12), 0, 0, 0);
        titles.addView(Ui.text(this, Ui.GLASS ? "XPhone" : "XPHONE", Ui.GLASS ? 21 : 18, Ui.TEXT, 800));
        titles.addView(Ui.text(this, Build.MODEL, 12, Ui.MUTED, 500), Ui.lpMargins(this, -2, -2, 0, 4, 0, 0));
        head.addView(titles, new LinearLayout.LayoutParams(0, -2, 1));
        if (PcLink.state() == PcLink.State.STREAMING) {
            TextView live = Ui.text(this, "В эфире", 12, Ui.GLASS ? Ui.TEXT : Ui.ON_ACCENT, 700);
            live.setGravity(Gravity.CENTER);
            live.setPadding(Ui.dp(this, 12), 0, Ui.dp(this, 12), 0);
            live.setBackground(Ui.box(this, Ui.GLASS ? 0x14FFFFFF : Ui.ACCENT, Ui.GLASS ? Ui.LINE2 : 0, 999));
            head.addView(live, new LinearLayout.LayoutParams(-2, Ui.dp(this, 28)));
        }
        content.addView(head, Ui.lpMargins(this, -1, -2, 0, 0, 0, 16));

        // Вкладки
        content.addView(Ui.segmented(this, new String[]{"Игра", "Стрим", "Настройки"}, tab, i -> { tab = i; render(); }),
                Ui.lpMargins(this, -1, -2, 0, 0, 0, 6));

        if (tab == TAB_GAME) renderGame();
        else if (tab == TAB_STREAM) renderStream();
        else renderSettings();

        TextView foot = Ui.text(this, "XPhone " + BuildConfig.VERSION_NAME, 11, Ui.DIM, 500);
        foot.setGravity(Gravity.CENTER);
        content.addView(foot, Ui.lpMargins(this, -1, -2, 0, 24, 0, 0));
    }

    // ── Вкладка «Игра» ────────────────────────────────────────────────

    private void renderGame() {
        LinearLayout master = card();
        master.addView(plainSwitch("Игровая панель включена", "В игре — свайп от края экрана", Prefs.enabled(this), on -> {
            Prefs.put(this, Prefs.ENABLED, on);
            if (on && permissionsOk(this)) BoostService.start(this);
            if (!on) BoostService.stop(this);
        }));
        content.addView(master, Ui.lpMargins(this, -1, -2, 0, 12, 0, 0));
        if (!permissionsOk(this)) {
            TextView warn = Ui.text(this, "Нужны разрешения — откройте вкладку «Настройки».", 12, Ui.SILVER, 600);
            warn.setOnClickListener(v -> { tab = TAB_SETTINGS; render(); });
            content.addView(warn, Ui.lpMargins(this, -1, -2, 4, 10, 0, 0));
        }

        if (Prefs.get(this, "dnd_reverted", false)) {
            LinearLayout warn = card();
            warn.addView(Ui.text(this, "«Не беспокоить» сразу выключается", 14, Ui.TEXT, 700));
            warn.addView(Ui.text(this, "Его выключает служба Galaxy Watch: она синхронизирует режим со своим. "
                    + "Откройте Galaxy Wearable → настройки часов → Уведомления (или «Не беспокоить») и выключите синхронизацию с телефоном. "
                    + "Либо включайте режим на самих часах.", 12, Ui.MUTED, 500), Ui.lpMargins(this, -1, -2, 0, 6, 0, 0));
            TextView ok = Ui.button(this, "Понятно", false);
            ok.setOnClickListener(v -> { Prefs.put(this, "dnd_reverted", false); render(); });
            warn.addView(ok, Ui.lpMargins(this, -1, -2, 0, 12, 0, 0));
            content.addView(warn, Ui.lpMargins(this, -1, -2, 0, 12, 0, 0));
        }

        section("ПАНЕЛЬ В ИГРЕ");
        LinearLayout panel = card();
        panel.addView(plainSwitch("Приветствие при запуске игры", "Короткая анимация у края — вместо сразу открытой панели",
                Prefs.welcome(this), on -> Prefs.put(this, Prefs.WELCOME, on)));
        divider(panel);
        panel.addView(plainSwitch("Сразу открывать панель", null, Prefs.autoPanel(this),
                on -> Prefs.put(this, Prefs.AUTO_PANEL, on)));
        divider(panel);
        panel.addView(Ui.text(this, "Язычок панели", 14, Ui.TEXT, 600));
        panel.addView(Ui.segmented(this, new String[]{"Слева", "Справа"}, Prefs.handleLeft(this) ? 0 : 1, i -> {
            Prefs.put(this, Prefs.HANDLE_LEFT, i == 0);
            render();
        }), Ui.lpMargins(this, -1, -2, 0, 8, 0, 0));
        panel.addView(Ui.text(this, "Можно и прямо в игре: протащите язычок через середину экрана.", 12, Ui.MUTED, 500),
                Ui.lpMargins(this, -1, -2, 0, 6, 0, 0));
        if (Mic.supported(this)) {
            divider(panel);
            panel.addView(plainSwitch("Кнопка микрофона", "Плавающая кнопка в игре: нажатие мгновенно выключает микрофон",
                    Prefs.get(this, Prefs.MIC_BUBBLE, false), on -> Prefs.put(this, Prefs.MIC_BUBBLE, on)));
        }
        TextView test = Ui.button(this, "Показать панель сейчас", true);
        test.setOnClickListener(v -> {
            if (!overlayOk(this)) { Toast.makeText(this, "Сначала разрешите «Поверх других приложений»", Toast.LENGTH_LONG).show(); return; }
            startForegroundService(new Intent(this, BoostService.class).setAction(BoostService.ACTION_SHOW_PANEL));
        });
        panel.addView(test, Ui.lpMargins(this, -1, -2, 0, 14, 0, 0));
        content.addView(panel);

        section("ПРИ ЗАПУСКЕ ИГРЫ");
        LinearLayout autos = card();
        int locked = 0, shown = 0;
        for (String id : Booster.TOGGLES) {
            if (!Booster.supported(id)) continue;
            if (!Booster.available(this, id)) { locked++; continue; }
            if (shown++ > 0) divider(autos);
            autos.addView(plainSwitch("Включать «" + Booster.title(id) + "»", Booster.hint(id), Prefs.auto(this, id),
                    on -> Prefs.setAuto(this, id, on)));
        }
        if (Booster.available(this, Booster.PASS)) {
            divider(autos);
            autos.addView(Ui.text(this, "Питание USB от заряда", 14, Ui.TEXT, 600));
            autos.addView(Ui.text(this, "Когда заряд дошёл до порога — телефон питается от зарядки, батарея не греется", 12, Ui.MUTED, 500),
                    Ui.lpMargins(this, -1, -2, 0, 4, 0, 0));
            int[] levels = {0, 50, 70, 80, 90};
            int cur = Prefs.getInt(this, Prefs.PASS_LEVEL, 0), sel = 0;
            for (int i = 0; i < levels.length; i++) if (levels[i] == cur) sel = i;
            autos.addView(Ui.segmented(this, new String[]{"Выкл", "50%", "70%", "80%", "90%"}, sel, i -> {
                Prefs.putInt(this, Prefs.PASS_LEVEL, levels[i]);
                render();
            }), Ui.lpMargins(this, -1, -2, 0, 8, 0, 0));
        }
        if (shown > 0 || Booster.available(this, Booster.PASS)) divider(autos);
        autos.addView(plainSwitch("После игры возвращать как было", "Всё, что включила панель, выключится само",
                Prefs.restore(this), on -> Prefs.put(this, Prefs.RESTORE, on)));
        if (locked > 0) {
            TextView more = Ui.text(this, "Ещё " + locked + " — с Shizuku (вкладка «Настройки»).", 12, Ui.MUTED, 500);
            more.setOnClickListener(v -> { tab = TAB_SETTINGS; render(); });
            autos.addView(more, Ui.lpMargins(this, -1, -2, 0, 12, 0, 0));
        }
        content.addView(autos);

        section("ИГРЫ");
        LinearLayout games = card();
        List<App> apps = installedApps();
        int count = 0;
        for (App a : apps) {
            if (!Prefs.isGame(this, a.pkg)) continue;
            if (count++ > 0) divider(games);
            games.addView(gameRow(a));
        }
        if (count == 0) games.addView(Ui.text(this, "Игр пока нет. Добавьте их кнопкой ниже.", 13, Ui.MUTED, 500));
        TextView add = Ui.button(this, "Добавить приложение как игру", false);
        add.setOnClickListener(v -> pickApps(apps));
        games.addView(add, Ui.lpMargins(this, -1, -2, 0, 14, 0, 0));
        content.addView(games);
    }

    // ── Вкладка «Стрим» ───────────────────────────────────────────────

    private void renderStream() {
        content.addView(pcCard(), Ui.lpMargins(this, -1, -2, 0, 12, 0, 0));
    }

    /** Связь с XPhone на компьютере: состояние, кнопки, трансляция вместе с игрой. */
    private LinearLayout pcCard() {
        LinearLayout card = card();
        PcLink.State ps = PcLink.state();
        String name = PcLink.pcName() != null ? PcLink.pcName() : "компьютеру";
        boolean ever = Prefs.get(this, "pc_ever_connected", false);
        String title, sub;
        switch (ps) {
            case STREAMING:
                title = "Идёт трансляция";
                sub = "Экран и звук телефона — на " + name;
                break;
            case CONNECTING:
                title = "Запускаем трансляцию…";
                sub = "Окно появится на компьютере через пару секунд";
                break;
            case IDLE:
                title = "Подключено к " + name;
                sub = "Можно начинать трансляцию";
                break;
            default:
                title = PcLink.waiting() ? "Ищем компьютер…" : "Компьютер не подключён";
                sub = ever
                        ? "Откройте XPhone на ПК. Телефон и компьютер — в одной сети Wi-Fi (или подключите кабель)."
                        : "Первый раз: подключите телефон кабелем к XPhone на ПК (или «Сопряжение по Wi-Fi» → QR-код). Дальше всё само.";
        }
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        if (Ui.GLASS) {
            android.widget.FrameLayout circle = new android.widget.FrameLayout(this);
            boolean live = ps == PcLink.State.STREAMING;
            circle.setBackground(Ui.circle(live ? Ui.ACCENT : Ui.TILE_OFF));
            circle.addView(Ui.icon(this, R.drawable.ic_cast, 20, live ? Ui.ON_ACCENT : 0xFFFFFFFF),
                    new android.widget.FrameLayout.LayoutParams(Ui.dp(this, 20), Ui.dp(this, 20), Gravity.CENTER));
            row.addView(circle, new LinearLayout.LayoutParams(Ui.dp(this, 44), Ui.dp(this, 44)));
        } else {
            row.addView(Ui.icon(this, R.drawable.ic_cast, 22, ps == PcLink.State.STREAMING ? Ui.ACCENT : Ui.SILVER));
        }
        LinearLayout tb = textBlock(title, sub);
        tb.setPadding(Ui.dp(this, 12), 0, 0, 0);
        row.addView(tb, new LinearLayout.LayoutParams(0, -2, 1));
        card.addView(row);

        if (PcLink.message() != null) {
            TextView m = Ui.text(this, PcLink.message(), 12, Ui.SILVER, 600);
            card.addView(m, Ui.lpMargins(this, -1, -2, 0, 8, 0, 0));
        }

        LinearLayout buttons = new LinearLayout(this);
        if (ps == PcLink.State.STREAMING) {
            TextView stop = Ui.button(this, "Остановить трансляцию", false);
            stop.setOnClickListener(v -> PcLink.requestStop());
            buttons.addView(stop, new LinearLayout.LayoutParams(0, -2, 1));
        } else if (ps != PcLink.State.CONNECTING) {
            if (ps == PcLink.State.NO_PC) {
                TextView find = Ui.button(this, "Подключиться", false);
                find.setOnClickListener(v -> {
                    if (!PcLink.canEnableWifiDebugging(this))
                        Toast.makeText(this, "Включите «Беспроводную отладку» плиткой в шторке. Чтобы телефон делал это сам — один раз подключите его кабелем к XPhone на ПК.", Toast.LENGTH_LONG).show();
                    PcLink.findPc(this);
                });
                buttons.addView(find, new LinearLayout.LayoutParams(0, -2, 1));
            }
            TextView start = Ui.button(this, "Начать трансляцию", true);
            start.setOnClickListener(v -> PcLink.requestStart(this));
            LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(0, -2, 1);
            if (ps == PcLink.State.NO_PC) sl.leftMargin = Ui.dp(this, 8);
            buttons.addView(start, sl);
        }
        if (buttons.getChildCount() > 0) card.addView(buttons, Ui.lpMargins(this, -1, -2, 0, 14, 0, 0));

        divider(card);
        card.addView(plainSwitch("Начинать трансляцию вместе с игрой", "Открыли игру — экран сразу на ПК",
                Prefs.get(this, Prefs.AUTO_STREAM, false), on -> Prefs.put(this, Prefs.AUTO_STREAM, on)));
        divider(card);
        card.addView(plainSwitch("Останавливать после игры", "Экономит заряд и трафик",
                Prefs.get(this, Prefs.AUTO_STREAM_STOP, true), on -> Prefs.put(this, Prefs.AUTO_STREAM_STOP, on)));
        return card;
    }

    // ── Вкладка «Настройки» ───────────────────────────────────────────

    private void renderSettings() {
        section("ОФОРМЛЕНИЕ");
        content.addView(Ui.segmented(this, new String[]{"Графит", "Стекло"},
                Ui.THEME_GLASS.equals(Ui.theme(this)) ? 1 : 0, i -> {
                    Ui.setTheme(this, i == 1 ? Ui.THEME_GLASS : Ui.THEME_GRAPHITE);
                    render();
                }));
        content.addView(Ui.text(this, "Тема меняет и приложение, и панель в игре.", 12, Ui.MUTED, 500),
                Ui.lpMargins(this, -1, -2, 4, 8, 0, 0));

        section("ЧТО НУЖНО");
        LinearLayout perms = card();
        Shell.State st = Shell.state(this);
        perms.addView(shizukuRow(st));
        divider(perms);
        perms.addView(statusRow("Поверх других приложений", "Чтобы показывать язычок и панель", overlayOk(this),
                () -> startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName())))));
        divider(perms);
        perms.addView(statusRow("Доступ к истории использования", "Чтобы понять, что открыта игра", usageOk(this),
                () -> startActivity(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))));
        if (Build.VERSION.SDK_INT >= 33) {
            divider(perms);
            perms.addView(statusRow("Уведомления", "Значок «XPhone следит за играми»", notifOk(this),
                    () -> requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 2)));
        }
        divider(perms);
        boolean wifiOk = Booster.canWriteSecure(this) || st == Shell.State.READY;
        perms.addView(statusRow("Сам включать «Отладку по Wi-Fi»",
                wifiOk ? "Телефон включит её сам, когда нужна связь с ПК"
                        : "Подключите телефон кабелем к XPhone на ПК один раз — он выдаст это разрешение",
                wifiOk, () -> {
                    if (st == Shell.State.READY) grantAllViaShizuku();
                    else Toast.makeText(this, "Подключите телефон кабелем к компьютеру с открытым XPhone — разрешение выдастся само.", Toast.LENGTH_LONG).show();
                }));
        if (st != Shell.State.READY) {
            divider(perms);
            perms.addView(statusRow("«Не беспокоить» без Shizuku", "Доступ к режиму «Не беспокоить»", Booster.dndAccess(this),
                    () -> startActivity(new Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))));
        }
        if (st == Shell.State.READY && !(overlayOk(this) && usageOk(this) && notifOk(this) && Booster.canWriteSecure(this))) {
            TextView all = Ui.button(this, "Выдать всё через Shizuku", true);
            all.setOnClickListener(v -> grantAllViaShizuku());
            perms.addView(all, Ui.lpMargins(this, -1, -2, 0, 14, 0, 0));
        }
        content.addView(perms);

        TextView credits = Ui.text(this, "Иконки Lucide (ISC), шрифты Manrope и Unbounded (OFL), Shizuku API (Apache 2.0)", 11, Ui.DIM, 500);
        credits.setGravity(Gravity.CENTER);
        content.addView(credits, Ui.lpMargins(this, -1, -2, 0, 20, 0, 0));
    }

    // ── Общие элементы ────────────────────────────────────────────────

    private LinearLayout card() { return Ui.card(this); }

    private void section(String title) {
        content.addView(Ui.text(this, Ui.GLASS ? title.charAt(0) + title.substring(1).toLowerCase() : title,
                Ui.GLASS ? 13 : 11, Ui.MUTED, 700), Ui.lpMargins(this, -1, -2, 4, 22, 0, 10));
    }

    private void divider(LinearLayout l) {
        View d = new View(this);
        d.setBackgroundColor(Ui.LINE);
        l.addView(d, Ui.lpMargins(this, -1, 1, 0, 12, 0, 12));
    }

    private LinearLayout textBlock(String title, String sub) {
        LinearLayout t = new LinearLayout(this);
        t.setOrientation(LinearLayout.VERTICAL);
        t.addView(Ui.text(this, title, 14, Ui.TEXT, Ui.GLASS ? 700 : 600));
        if (sub != null) t.addView(Ui.text(this, sub, 12, Ui.MUTED, 500), Ui.lpMargins(this, -2, -2, 0, 4, 0, 0));
        return t;
    }

    private LinearLayout plainSwitch(String title, String sub, boolean on, Ui.Toggle.Listener l) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(textBlock(title, sub), new LinearLayout.LayoutParams(0, -2, 1));
        Ui.Toggle t = new Ui.Toggle(this);
        t.setOn(on);
        t.setListener(l);
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(Ui.dp(this, Ui.GLASS ? 48 : 44), Ui.dp(this, Ui.GLASS ? 28 : 26));
        tl.leftMargin = Ui.dp(this, 12);
        row.addView(t, tl);
        row.setOnClickListener(v -> t.performClick());
        return row;
    }

    private LinearLayout statusRow(String title, String sub, boolean ok, Runnable fix) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(Ui.icon(this, ok ? R.drawable.ic_check : R.drawable.ic_alert, 18, ok ? (Ui.GLASS ? Ui.ACCENT : Ui.TEXT) : Ui.MUTED));
        LinearLayout tb = textBlock(title, sub);
        tb.setPadding(Ui.dp(this, 12), 0, Ui.dp(this, 8), 0);
        row.addView(tb, new LinearLayout.LayoutParams(0, -2, 1));
        if (!ok) {
            TextView b = Ui.button(this, "Выдать", false);
            b.setOnClickListener(v -> fix.run());
            row.addView(b);
        }
        return row;
    }

    private LinearLayout shizukuRow(Shell.State st) {
        String sub, btn = null;
        Runnable act = null;
        switch (st) {
            case READY:
                sub = "Работает — открыты все функции";
                break;
            case NO_PERMISSION:
                sub = "Shizuku запущен, но XPhone ещё не разрешён";
                btn = "Разрешить";
                act = Shell::requestPermission;
                break;
            case NOT_RUNNING:
                sub = "Не запущен. Откройте Shizuku → «Запустить» (через отладку по Wi-Fi). Или подключите телефон к XPhone на ПК — он запустит Shizuku сам.";
                btn = "Открыть";
                act = () -> {
                    Intent i = getPackageManager().getLaunchIntentForPackage(Shell.SHIZUKU_PKG);
                    if (i != null) startActivity(i);
                };
                break;
            default:
                sub = "Не обязателен. С ним появятся: «Турбо», «Питание USB», «Яркость», «Очистка», кнопка микрофона.";
                btn = "Скачать";
                act = () -> startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://shizuku.rikka.app/download/")));
        }
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        boolean ok = st == Shell.State.READY;
        row.addView(Ui.icon(this, ok ? R.drawable.ic_check : R.drawable.ic_alert, 18, ok ? (Ui.GLASS ? Ui.ACCENT : Ui.TEXT) : Ui.MUTED));
        LinearLayout tb = textBlock("Shizuku", sub);
        tb.setPadding(Ui.dp(this, 12), 0, Ui.dp(this, 8), 0);
        row.addView(tb, new LinearLayout.LayoutParams(0, -2, 1));
        if (btn != null) {
            TextView b = Ui.button(this, btn, st == Shell.State.NO_PERMISSION);
            Runnable a = act;
            b.setOnClickListener(v -> a.run());
            row.addView(b);
        }
        return row;
    }

    private void grantAllViaShizuku() {
        String p = getPackageName();
        String cmd = "appops set " + p + " SYSTEM_ALERT_WINDOW allow; appops set " + p + " GET_USAGE_STATS allow; "
                + "cmd notification allow_dnd " + p + "; pm grant " + p + " android.permission.WRITE_SECURE_SETTINGS; "
                + (Build.VERSION.SDK_INT >= 33 ? "pm grant " + p + " android.permission.POST_NOTIFICATIONS; " : "");
        Shell.runAsync(this, cmd, r -> {
            render();
            if (Prefs.enabled(this) && permissionsOk(this)) BoostService.start(this);
            Toast.makeText(this, permissionsOk(this) ? "Готово" : "Не всё получилось — выдайте вручную", Toast.LENGTH_SHORT).show();
        });
    }

    // ── Игры ──────────────────────────────────────────────────────────

    private static final class App {
        String pkg, label;
    }

    private List<App> installedApps() {
        PackageManager pm = getPackageManager();
        Intent main = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<App> list = new ArrayList<>();
        for (ResolveInfo ri : pm.queryIntentActivities(main, 0)) {
            String pkg = ri.activityInfo.packageName;
            if (pkg.equals(getPackageName()) || list.stream().anyMatch(a -> a.pkg.equals(pkg))) continue;
            App a = new App();
            a.pkg = pkg;
            a.label = ri.loadLabel(pm).toString();
            list.add(a);
        }
        Collections.sort(list, (x, y) -> x.label.compareToIgnoreCase(y.label));
        return list;
    }

    private LinearLayout gameRow(App a) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        ImageView ic = new ImageView(this);
        try { ic.setImageDrawable(getPackageManager().getApplicationIcon(a.pkg)); } catch (Exception ignored) { }
        row.addView(ic, new LinearLayout.LayoutParams(Ui.dp(this, 32), Ui.dp(this, 32)));
        TextView name = Ui.text(this, a.label, 14, Ui.TEXT, 600);
        name.setPadding(Ui.dp(this, 12), 0, Ui.dp(this, 8), 0);
        name.setSingleLine(true);
        row.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
        Ui.Toggle t = new Ui.Toggle(this);
        t.setOn(true);
        t.setListener(on -> Prefs.setGame(this, a.pkg, on));
        row.addView(t);
        return row;
    }

    private void pickApps(List<App> apps) {
        List<App> others = new ArrayList<>();
        for (App a : apps) if (!Prefs.isGame(this, a.pkg)) others.add(a);
        String[] names = new String[others.size()];
        for (int i = 0; i < names.length; i++) names[i] = others.get(i).label;
        boolean[] checked = new boolean[names.length];
        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("Какие приложения считать играми?")
                .setMultiChoiceItems(names, checked, (d, i, v) -> checked[i] = v)
                .setPositiveButton("Добавить", (d, w) -> {
                    for (int i = 0; i < checked.length; i++) if (checked[i]) Prefs.setGame(this, others.get(i).pkg, true);
                    render();
                })
                .setNegativeButton("Отмена", null)
                .show();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        render();
    }
}
