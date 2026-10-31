package ru.xphone.booster;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.provider.Settings;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Язычок у правого края и выезжающая панель поверх игры. */
public final class Overlay {
    private final Context ctx;
    private final WindowManager wm;
    private View handle;
    private WindowManager.LayoutParams handleLp;
    private FrameLayout panelRoot;
    private String gamePkg;
    private final Map<String, Tile> tiles = new HashMap<>();
    private TextView infoText;

    /** Панель открыла другое окно (настройки) — не считать это выходом из игры. */
    public interface Listener { void openedExternal(); }
    private final Listener listener;

    public Overlay(Context ctx, Listener listener) {
        this.ctx = ctx;
        this.listener = listener;
        this.wm = ctx.getSystemService(WindowManager.class);
    }

    public boolean canShow() {
        return Settings.canDrawOverlays(ctx);
    }

    private int dp(float v) { return Ui.dp(ctx, v); }

    private Point screen() {
        Point p = new Point();
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            android.graphics.Rect b = wm.getCurrentWindowMetrics().getBounds();
            p.set(b.width(), b.height());
        } else {
            wm.getDefaultDisplay().getRealSize(p);
        }
        return p;
    }

    private static WindowManager.LayoutParams baseLp(int w, int h, int flags) {
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(w, h,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, flags, PixelFormat.TRANSLUCENT);
        lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        return lp;
    }

    // ── Язычок ────────────────────────────────────────────────────────

    @SuppressLint("ClickableViewAccessibility")
    public void showHandle(String pkg) {
        gamePkg = pkg;
        if (!canShow()) return;
        if (handle != null) return;

        FrameLayout h = new FrameLayout(ctx);
        View pill = new View(ctx);
        pill.setBackground(Ui.box(ctx, 0xB3FFFFFF, 0x66000000, 3));
        FrameLayout.LayoutParams pl = new FrameLayout.LayoutParams(dp(5), dp(64), Gravity.CENTER_VERTICAL | Gravity.END);
        pl.rightMargin = dp(3);
        h.addView(pill, pl);

        handleLp = baseLp(dp(22), dp(96),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS);
        handleLp.gravity = Gravity.TOP | Gravity.END;
        handleLp.y = Math.round(screen().y * Prefs.handleY(ctx));

        final float[] down = new float[3];
        h.setOnTouchListener((v, e) -> {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    down[0] = e.getRawX(); down[1] = e.getRawY(); down[2] = handleLp.y;
                    return true;
                case MotionEvent.ACTION_MOVE:
                    float dx = e.getRawX() - down[0], dy = e.getRawY() - down[1];
                    if (dx < -dp(18) && Math.abs(dx) > Math.abs(dy)) {
                        down[0] = Float.MAX_VALUE; // уже открыли
                        openPanel();
                    } else if (down[0] != Float.MAX_VALUE && Math.abs(dy) > dp(12)) {
                        // Тянем вверх-вниз — переставить язычок.
                        handleLp.y = Math.max(0, Math.round(down[2] + dy));
                        wm.updateViewLayout(h, handleLp);
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                    float tx = Math.abs(e.getRawX() - down[0]), ty = Math.abs(e.getRawY() - down[1]);
                    if (down[0] != Float.MAX_VALUE && tx < dp(8) && ty < dp(8)) openPanel();
                    else if (ty >= dp(12)) Prefs.setHandleY(ctx, handleLp.y / (float) screen().y);
                    return true;
            }
            return false;
        });
        handle = h;
        try { wm.addView(h, handleLp); } catch (Exception e) { handle = null; }
    }

    public void hideAll() {
        closePanel();
        if (handle != null) {
            try { wm.removeView(handle); } catch (Exception ignored) { }
            handle = null;
        }
        gamePkg = null;
    }

    public boolean isPanelOpen() { return panelRoot != null; }

    // ── Панель ────────────────────────────────────────────────────────

    public void openPanel() {
        if (!canShow() || panelRoot != null) return;
        boolean landscape = ctx.getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
        Point s = screen();

        FrameLayout root = new FrameLayout(ctx);
        root.setBackgroundColor(0x59000000);
        root.setOnClickListener(v -> closePanel());

        LinearLayout panel = new LinearLayout(ctx);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackground(Ui.box(ctx, Ui.PANEL, Ui.LINE2, 24));
        panel.setPadding(dp(16), dp(14), dp(16), dp(16));
        panel.setClickable(true); // нажатия внутри панели не закрывают её
        panel.setElevation(dp(12));

        // Шапка: значок, название, игра, закрыть
        LinearLayout head = new LinearLayout(ctx);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        ImageView logo = Ui.icon(ctx, R.drawable.ic_gamepad, 20, Ui.TEXT);
        head.addView(logo);
        LinearLayout titles = new LinearLayout(ctx);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.setPadding(dp(10), 0, dp(8), 0);
        titles.addView(Ui.text(ctx, "XPHONE", 14, Ui.TEXT, 800));
        TextView game = Ui.text(ctx, appName(gamePkg), 12, Ui.MUTED, 500);
        game.setSingleLine(true);
        titles.addView(game, Ui.lpMargins(ctx, -2, -2, 0, 3, 0, 0));
        head.addView(titles, new LinearLayout.LayoutParams(0, -2, 1));
        ImageView close = Ui.icon(ctx, R.drawable.ic_x, 20, Ui.MUTED);
        close.setPadding(dp(4), dp(4), dp(4), dp(4));
        close.setLayoutParams(new LinearLayout.LayoutParams(dp(32), dp(32)));
        close.setOnClickListener(v -> closePanel());
        head.addView(close);
        panel.addView(head);

        // Нагрев и батарея
        infoText = Ui.text(ctx, "…", 12, Ui.SILVER, 600);
        LinearLayout infoRow = new LinearLayout(ctx);
        infoRow.setGravity(Gravity.CENTER_VERTICAL);
        infoRow.setBackground(Ui.box(ctx, Ui.CARD, Ui.LINE, 12));
        infoRow.setPadding(dp(12), dp(9), dp(12), dp(9));
        infoRow.addView(Ui.icon(ctx, R.drawable.ic_thermo, 16, Ui.SILVER));
        infoRow.addView(infoText, Ui.lpMargins(ctx, -2, -2, 8, 0, 0, 0));
        panel.addView(infoRow, Ui.lpMargins(ctx, -1, -2, 0, 12, 0, 12));
        updateInfo();

        // Плитки
        List<String> ids = new ArrayList<>();
        ids.add(STREAM);
        for (String id : new String[]{Booster.DND, Booster.PERF, Booster.PASS, Booster.ANIM,
                Booster.BRIGHT, Booster.CLEAN, Booster.RES})
            if (Booster.supported(id)) ids.add(id);
        int cols = landscape ? 4 : 3;
        tiles.clear();
        LinearLayout grid = new LinearLayout(ctx);
        grid.setOrientation(LinearLayout.VERTICAL);
        LinearLayout row = null;
        for (int i = 0; i < ids.size(); i++) {
            if (i % cols == 0) {
                row = new LinearLayout(ctx);
                row.setOrientation(LinearLayout.HORIZONTAL);
                grid.addView(row, Ui.lpMargins(ctx, -1, -2, 0, i == 0 ? 0 : 8, 0, 0));
            }
            Tile t = new Tile(ids.get(i));
            tiles.put(t.id, t);
            LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(0, dp(92), 1);
            if (i % cols != 0) tl.leftMargin = dp(8);
            row.addView(t.view, tl);
        }
        // Пустые места в последнем ряду, чтобы плитки были одного размера.
        int rest = ids.size() % cols;
        if (rest != 0 && row != null) for (int i = rest; i < cols; i++) {
            LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(0, dp(92), 1);
            tl.leftMargin = dp(8);
            row.addView(new View(ctx), tl);
        }
        panel.addView(grid);

        // Подсказка, если Shizuku не работает
        if (!Shell.ready(ctx)) {
            TextView hint = Ui.text(ctx, "Плитки с замком откроются, когда запущен Shizuku. Нажмите здесь, чтобы открыть настройки XPhone.",
                    12, Ui.MUTED, 500);
            hint.setPadding(0, dp(12), 0, 0);
            hint.setOnClickListener(v -> openApp());
            panel.addView(hint);
        }

        ScrollView scroll = new ScrollView(ctx);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.addView(panel);
        int width = Math.min(s.x - dp(24), dp(landscape ? 430 : 340));
        FrameLayout.LayoutParams sl = new FrameLayout.LayoutParams(width, -2, Gravity.END | Gravity.CENTER_VERTICAL);
        sl.rightMargin = dp(12);
        sl.topMargin = dp(12);
        sl.bottomMargin = dp(12);
        root.addView(scroll, sl);

        WindowManager.LayoutParams lp = baseLp(-1, -1,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN);
        lp.gravity = Gravity.TOP | Gravity.START;
        panelRoot = root;
        try { wm.addView(root, lp); } catch (Exception e) { panelRoot = null; return; }

        PcLink.addListener(pcListener);
        scroll.setTranslationX(width + dp(24));
        scroll.animate().translationX(0).setDuration(200).setInterpolator(new DecelerateInterpolator()).start();
        refreshTiles();
    }

    public void closePanel() {
        if (panelRoot == null) return;
        PcLink.removeListener(pcListener);
        try { wm.removeView(panelRoot); } catch (Exception ignored) { }
        panelRoot = null;
        tiles.clear();
    }

    private void updateInfo() {
        if (infoText == null) return;
        Booster.Info i = Booster.info(ctx);
        StringBuilder sb = new StringBuilder();
        if (i.batteryPercent >= 0) sb.append("Батарея ").append(i.batteryPercent).append('%');
        if (i.charging) sb.append(" (заряжается)");
        if (!Float.isNaN(i.batteryTemp)) sb.append(" · ").append(Math.round(i.batteryTemp)).append(" °C");
        sb.append(" · Нагрев: ").append(Booster.heatText(i.thermal));
        infoText.setText(sb);
        infoText.setTextColor(i.thermal >= 2 ? Ui.ACCENT : Ui.SILVER);
    }

    /** Обновить плитки, если панель открыта (например, после автоматического включения функций). */
    public void refreshIfOpen() {
        if (panelRoot != null) refreshTiles();
    }

    /** Узнать текущие состояния в фоне и обновить плитки. */
    private void refreshTiles() {
        final String pkg = gamePkg;
        Shell.background(() -> {
            Map<String, Boolean> states = new HashMap<>();
            for (String id : Booster.TOGGLES)
                if (Booster.available(ctx, id)) states.put(id, Booster.read(ctx, id, pkg));
            Shell.onMain(() -> {
                for (Map.Entry<String, Boolean> e : states.entrySet()) {
                    Tile t = tiles.get(e.getKey());
                    if (t != null) t.setOn(e.getValue());
                }
            });
        });
    }

    private String appName(String pkg) {
        if (pkg == null) return "Игровая панель";
        try {
            PackageManager pm = ctx.getPackageManager();
            ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
            return pm.getApplicationLabel(ai).toString();
        } catch (Exception e) {
            return pkg;
        }
    }

    private void openApp() {
        closePanel();
        Intent i = new Intent(ctx, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (listener != null) listener.openedExternal();
        ctx.startActivity(i);
    }

    // ── Плитка ────────────────────────────────────────────────────────

    /** Плитка «Стрим на ПК». */
    private static final String STREAM = "stream";

    private final PcLink.Listener pcListener = () -> {
        Tile t = tiles.get(STREAM);
        if (t != null) t.paint();
    };

    private final class Tile {
        final String id;
        final LinearLayout view;
        final ImageView icon;
        final TextView label, state;
        boolean on;
        final boolean available, action;

        Tile(String id) {
            this.id = id;
            this.available = Booster.available(ctx, id);
            this.action = Booster.CLEAN.equals(id) || Booster.RES.equals(id);
            view = new LinearLayout(ctx);
            view.setOrientation(LinearLayout.VERTICAL);
            view.setPadding(dp(10), dp(10), dp(10), dp(9));
            LinearLayout top = new LinearLayout(ctx);
            top.setOrientation(LinearLayout.HORIZONTAL);
            icon = Ui.icon(ctx, Booster.icon(id), 20, Ui.SILVER);
            top.addView(icon);
            if (!available) {
                View spacer = new View(ctx);
                top.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1));
                top.addView(Ui.icon(ctx, R.drawable.ic_lock, 13, Ui.DIM));
            }
            view.addView(top, new LinearLayout.LayoutParams(-1, -2));
            View fill = new View(ctx);
            view.addView(fill, new LinearLayout.LayoutParams(1, 0, 1));
            label = Ui.text(ctx, Booster.shortTitle(id), 12, Ui.TEXT, 700);
            label.setMaxLines(2);
            view.addView(label);
            state = Ui.text(ctx, available ? (action ? "Нажмите" : "Выкл") : "Shizuku", 10, Ui.MUTED, 500);
            state.setSingleLine(true);
            view.addView(state, Ui.lpMargins(ctx, -2, -2, 0, 3, 0, 0));
            view.setOnClickListener(v -> click());
            view.setOnLongClickListener(v -> {
                Toast.makeText(ctx, Booster.hint(id), Toast.LENGTH_SHORT).show();
                return true;
            });
            paint();
        }

        void setOn(boolean v) {
            on = v;
            paint();
        }

        void paint() {
            if (STREAM.equals(id)) {
                PcLink.State s = PcLink.state();
                on = s == PcLink.State.STREAMING;
                state.setText(s == PcLink.State.STREAMING ? "Идёт"
                        : s == PcLink.State.CONNECTING ? "Запуск…"
                        : s == PcLink.State.IDLE ? "Выкл"
                        : PcLink.waiting() ? "Ищем ПК…" : "Нет ПК");
            }
            boolean lit = on && available && !action;
            view.setBackground(Ui.box(ctx, lit ? Ui.ACCENT : Ui.TILE_OFF, lit ? Ui.ACCENT : Ui.LINE, 16));
            Ui.tint(icon, lit ? Ui.ON_ACCENT : Ui.SILVER);
            label.setTextColor(lit ? Ui.ON_ACCENT : Ui.TEXT);
            state.setTextColor(lit ? 0xFF3A3A44 : Ui.MUTED);
            if (available && !action && !STREAM.equals(id)) state.setText(Booster.stateText(id, on));
            view.setAlpha(available ? 1f : 0.5f);
        }

        void click() {
            if (!available) {
                Toast.makeText(ctx, Booster.supported(id)
                        ? "Нужен Shizuku: откройте XPhone на телефоне — там подсказка, как его запустить."
                        : "На этом телефоне такой функции нет.", Toast.LENGTH_LONG).show();
                return;
            }
            if (STREAM.equals(id)) {
                if (PcLink.state() == PcLink.State.STREAMING) {
                    PcLink.requestStop();
                } else {
                    PcLink.requestStart(ctx);
                    if (PcLink.state() == PcLink.State.NO_PC)
                        Toast.makeText(ctx, PcLink.canEnableWifiDebugging(ctx)
                                ? "Ищем компьютер… Проверьте, что XPhone на ПК открыт."
                                : "Включите «Беспроводную отладку» плиткой в шторке и откройте XPhone на ПК.", Toast.LENGTH_LONG).show();
                }
                paint();
                return;
            }
            if (Booster.RES.equals(id)) {
                closePanel();
                if (listener != null) listener.openedExternal();
                Intent i = new Intent(Settings.ACTION_DISPLAY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                try { ctx.startActivity(i); } catch (Exception ignored) { }
                Toast.makeText(ctx, "Выберите «Разрешение экрана» → FHD+ или WQHD+", Toast.LENGTH_LONG).show();
                return;
            }
            if (Booster.CLEAN.equals(id)) {
                state.setText("Закрываем…");
                Shell.background(() -> {
                    Booster.clean(ctx);
                    Shell.onMain(() -> state.setText("Готово"));
                });
                return;
            }
            final boolean target = !on;
            setOn(target); // сразу, чтобы отклик был мгновенным
            final String pkg = gamePkg;
            Shell.background(() -> {
                Booster.set(ctx, id, target, pkg);
                boolean real = Booster.read(ctx, id, pkg);
                Shell.onMain(() -> {
                    setOn(real);
                    if (real != target)
                        Toast.makeText(ctx, "Телефон не дал переключить «" + Booster.title(id) + "»", Toast.LENGTH_SHORT).show();
                });
            });
        }
    }
}
