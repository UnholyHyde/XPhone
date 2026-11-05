package ru.xphone.booster;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.os.Build;
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

/**
 * Всё, что поверх игры: язычок у края, панель, приветствие, кнопка микрофона, короткие сообщения.
 * Показывает только то, что есть на этом телефоне.
 */
public final class Overlay {
    private final Context ctx;
    private final WindowManager wm;
    private View handle, micBubble, toastView, welcomeView;
    private WindowManager.LayoutParams handleLp, micLp;
    private FrameLayout panelRoot;
    private LinearLayout dndRow;
    private String gamePkg;
    private final Map<String, Tile> tiles = new HashMap<>();
    private TextView infoText;

    /** Что панель просит у службы. */
    public interface Listener {
        /** Панель открыла другое окно (настройки) — не считать это выходом из игры. */
        void openedExternal();
        /** «Скрыть до перезахода». */
        void hideUntilExit();
    }
    private final Listener listener;

    private static final String STREAM = Booster.STREAM, MIC = "mic";
    private static final android.os.Handler MAIN = new android.os.Handler(android.os.Looper.getMainLooper());

    public Overlay(Context ctx, Listener listener) {
        this.ctx = ctx;
        this.listener = listener;
        this.wm = ctx.getSystemService(WindowManager.class);
    }

    public boolean canShow() { return Settings.canDrawOverlays(ctx); }

    private int dp(float v) { return Ui.dp(ctx, v); }

    private Point screen() {
        Point p = new Point();
        if (Build.VERSION.SDK_INT >= 30) {
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

    private boolean left() { return Prefs.handleLeft(ctx); }

    private void remove(View v) {
        if (v == null) return;
        try { wm.removeView(v); } catch (Exception ignored) { }
    }

    // ── Язычок ────────────────────────────────────────────────────────

    @SuppressLint("ClickableViewAccessibility")
    public void showHandle(String pkg) {
        gamePkg = pkg;
        if (!canShow() || handle != null) return;
        Ui.load(ctx);
        boolean left = left();

        FrameLayout h = new FrameLayout(ctx);
        View pill = new View(ctx);
        pill.setBackground(Ui.box(ctx, Ui.GLASS ? 0xCCFFFFFF : 0xB3FFFFFF, 0x66000000, 3));
        FrameLayout.LayoutParams pl = new FrameLayout.LayoutParams(dp(5), dp(64),
                Gravity.CENTER_VERTICAL | (left ? Gravity.START : Gravity.END));
        if (left) pl.leftMargin = dp(3); else pl.rightMargin = dp(3);
        h.addView(pill, pl);

        handleLp = baseLp(dp(22), dp(96),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS);
        handleLp.gravity = Gravity.TOP | (left ? Gravity.START : Gravity.END);
        handleLp.y = Math.round(screen().y * Prefs.handleY(ctx));

        final float[] down = new float[3];
        h.setOnTouchListener((v, e) -> {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    down[0] = e.getRawX(); down[1] = e.getRawY(); down[2] = handleLp.y;
                    return true;
                case MotionEvent.ACTION_MOVE: {
                    // Вертикально — двигаем язычок. Свайп к центру решаем при отпускании:
                    // короткий — открыть панель, через середину экрана — перенести язычок на другую сторону.
                    float dx = e.getRawX() - down[0], dy = e.getRawY() - down[1];
                    if (Math.abs(dy) > dp(12) && Math.abs(dy) > Math.abs(dx)) {
                        handleLp.y = Math.max(0, Math.round(down[2] + dy));
                        wm.updateViewLayout(h, handleLp);
                    }
                    return true;
                }
                case MotionEvent.ACTION_UP: {
                    float dx = e.getRawX() - down[0], dy = e.getRawY() - down[1];
                    float inward = left ? dx : -dx;
                    boolean crossed = left ? e.getRawX() > screen().x / 2f : e.getRawX() < screen().x / 2f;
                    if (crossed && Math.abs(dx) > Math.abs(dy)) {
                        // Протащили через середину экрана — язычок переезжает на другую сторону.
                        setSide(!left);
                    } else if (inward > dp(18) && Math.abs(dx) > Math.abs(dy)) {
                        openPanel();
                    } else if (Math.abs(dx) < dp(8) && Math.abs(dy) < dp(8)) {
                        openPanel();
                    } else if (Math.abs(dy) >= dp(12)) {
                        Prefs.setHandleY(ctx, handleLp.y / (float) screen().y);
                    }
                    return true;
                }
            }
            return false;
        });
        handle = h;
        try { wm.addView(h, handleLp); } catch (Exception e) { handle = null; }
        if (Prefs.get(ctx, Prefs.MIC_BUBBLE, false)) showMicBubble();
    }

    /** Перенести язычок (и панель) на другую сторону экрана. */
    public void setSide(boolean leftSide) {
        Prefs.put(ctx, Prefs.HANDLE_LEFT, leftSide);
        String pkg = gamePkg;
        boolean wasOpen = panelRoot != null;
        closePanel();
        remove(handle);
        handle = null;
        showHandle(pkg);
        toast(leftSide ? "Язычок теперь слева" : "Язычок теперь справа", R.drawable.ic_side);
        if (wasOpen) openPanel();
    }

    public void hideAll() {
        closePanel();
        remove(handle);
        handle = null;
        hideMicBubble();
        remove(welcomeView);
        welcomeView = null;
        gamePkg = null;
    }

    public boolean isPanelOpen() { return panelRoot != null; }

    // ── Приветствие ───────────────────────────────────────────────────

    /** Короткое приветствие у язычка вместо сразу открытой панели. */
    public void welcome() {
        android.util.Log.i("XPhone", "welcome canShow=" + canShow());
        if (!canShow()) return;
        Ui.load(ctx);
        remove(welcomeView);
        boolean left = left();
        LinearLayout pill = new LinearLayout(ctx);
        pill.setGravity(Gravity.CENTER_VERTICAL);
        pill.setPadding(dp(14), dp(10), dp(16), dp(10));
        pill.setBackground(Ui.box(ctx, Ui.GLASS ? 0xE6141820 : Ui.PANEL, Ui.LINE2, 999));
        ImageView logo = Ui.icon(ctx, R.drawable.ic_gamepad, 20, Ui.ACCENT);
        pill.addView(logo);
        LinearLayout texts = new LinearLayout(ctx);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setPadding(dp(10), 0, 0, 0);
        texts.addView(Ui.text(ctx, "XPHONE", 13, Ui.TEXT, 800));
        texts.addView(Ui.text(ctx, "Свайп от края — панель", 11, Ui.MUTED, 500), Ui.lpMargins(ctx, -2, -2, 0, 3, 0, 0));
        pill.addView(texts);

        WindowManager.LayoutParams lp = baseLp(-2, -2,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE);
        lp.gravity = Gravity.TOP | (left ? Gravity.START : Gravity.END);
        lp.x = dp(28);
        lp.y = Math.round(screen().y * Prefs.handleY(ctx)) + dp(22);
        welcomeView = pill;
        try { wm.addView(pill, lp); } catch (Exception e) { android.util.Log.w("XPhone", "welcome failed", e); welcomeView = null; return; }
        float from = (left ? -1 : 1) * dp(80);
        pill.setAlpha(0f);
        pill.setTranslationX(from);
        pill.animate().alpha(1f).translationX(0).setDuration(320).setInterpolator(new DecelerateInterpolator()).start();
        // Время показа — обычным таймером: при выключенных анимациях (в игре) они заканчиваются мгновенно.
        MAIN.postDelayed(() -> pill.animate().alpha(0f).translationX(from).setDuration(260).start(), 2200);
        MAIN.postDelayed(() -> { remove(pill); if (welcomeView == pill) welcomeView = null; }, 2500);
        // Язычок «подмигивает», чтобы было видно, где он.
        if (handle != null) {
            handle.animate().scaleY(1.35f).setDuration(220).withEndAction(() ->
                    handle.animate().scaleY(1f).setDuration(220).start()).start();
        }
    }

    // ── Короткие сообщения ────────────────────────────────────────────

    /** Сообщение сверху по центру на пару секунд («Не беспокоить включено» и т. п.). */
    public void toast(String text, int icon) {
        if (!canShow()) return;
        Ui.load(ctx);
        remove(toastView);
        LinearLayout pill = new LinearLayout(ctx);
        pill.setGravity(Gravity.CENTER_VERTICAL);
        pill.setPadding(dp(14), dp(9), dp(16), dp(9));
        pill.setBackground(Ui.box(ctx, Ui.GLASS ? 0xE6141820 : 0xFFFFFFFF, Ui.GLASS ? Ui.LINE2 : 0, 999));
        int fg = Ui.GLASS ? Ui.TEXT : 0xFF0B0B0E;
        pill.addView(Ui.icon(ctx, icon, 16, Ui.GLASS ? Ui.ACCENT : fg));
        TextView t = Ui.text(ctx, text, 13, fg, 700);
        t.setPadding(dp(8), 0, 0, 0);
        pill.addView(t);
        WindowManager.LayoutParams lp = baseLp(-2, -2,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE);
        lp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        lp.y = dp(18);
        toastView = pill;
        try { wm.addView(pill, lp); } catch (Exception e) { toastView = null; return; }
        pill.setAlpha(0f);
        pill.setTranslationY(-dp(16));
        pill.animate().alpha(1f).translationY(0).setDuration(220).start();
        MAIN.postDelayed(() -> pill.animate().alpha(0f).setDuration(250).start(), 2500);
        MAIN.postDelayed(() -> { remove(pill); if (toastView == pill) toastView = null; }, 2800);
    }

    // ── Кнопка микрофона ──────────────────────────────────────────────

    @SuppressLint("ClickableViewAccessibility")
    public void showMicBubble() {
        if (!canShow() || micBubble != null || !Mic.supported(ctx)) return;
        Ui.load(ctx);
        FrameLayout b = new FrameLayout(ctx);
        ImageView ic = Ui.icon(ctx, Mic.muted() ? R.drawable.ic_mic_off : R.drawable.ic_mic, 22, Ui.TEXT);
        b.addView(ic, new FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER));
        paintMic(b, ic);

        micLp = baseLp(dp(52), dp(52), WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS);
        micLp.gravity = Gravity.TOP | Gravity.START;
        Point s = screen();
        micLp.x = Math.round(Prefs.getFloat(ctx, "mic_x", left() ? 0.9f : 0.03f) * s.x);
        micLp.y = Math.round(Prefs.getFloat(ctx, "mic_y", 0.45f) * s.y);

        final float[] down = new float[4];
        b.setOnTouchListener((v, e) -> {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    down[0] = e.getRawX(); down[1] = e.getRawY(); down[2] = micLp.x; down[3] = micLp.y;
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (Math.abs(e.getRawX() - down[0]) > dp(6) || Math.abs(e.getRawY() - down[1]) > dp(6)) {
                        micLp.x = Math.round(down[2] + e.getRawX() - down[0]);
                        micLp.y = Math.round(down[3] + e.getRawY() - down[1]);
                        wm.updateViewLayout(b, micLp);
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                    if (Math.abs(e.getRawX() - down[0]) < dp(6) && Math.abs(e.getRawY() - down[1]) < dp(6)) {
                        toggleMic(b, ic);
                    } else {
                        Point p = screen();
                        Prefs.putFloat(ctx, "mic_x", micLp.x / (float) p.x);
                        Prefs.putFloat(ctx, "mic_y", micLp.y / (float) p.y);
                    }
                    return true;
            }
            return false;
        });
        micBubble = b;
        try { wm.addView(b, micLp); } catch (Exception e) { micBubble = null; }
    }

    public void hideMicBubble() {
        remove(micBubble);
        micBubble = null;
    }

    private void paintMic(View b, ImageView ic) {
        boolean m = Mic.muted();
        if (Ui.GLASS) b.setBackground(Ui.circle(m ? Ui.ACCENT : 0xB3141820));
        else {
            android.graphics.drawable.GradientDrawable d = Ui.circle(m ? 0xFFFFFFFF : 0xD90B0B0E);
            d.setStroke(dp(1), 0xFF3A3A44);
            b.setBackground(d);
        }
        ic.setImageDrawable(ctx.getDrawable(m ? R.drawable.ic_mic_off : R.drawable.ic_mic).mutate());
        Ui.tint(ic, m ? Ui.ON_ACCENT : Ui.TEXT);
        b.setContentDescription(m ? "Микрофон выключен" : "Микрофон включён");
    }

    private void toggleMic(View b, ImageView ic) {
        final boolean target = !Mic.muted();
        Shell.background(() -> {
            boolean ok = Mic.set(ctx, target);
            Shell.onMain(() -> {
                paintMic(b, ic);
                if (ok) toast(target ? "Микрофон выключен" : "Микрофон включён", target ? R.drawable.ic_mic_off : R.drawable.ic_mic);
                else toast("Не получилось переключить микрофон", R.drawable.ic_alert);
                Tile t = tiles.get(MIC);
                if (t != null) t.paint();
            });
        });
    }

    // ── Панель ────────────────────────────────────────────────────────

    /** Какие плитки показывать: только то, что есть и работает на этом телефоне. */
    private List<String> visibleTiles(int[] lockedOut) {
        List<String> ids = new ArrayList<>();
        int locked = 0;
        ids.add(STREAM);
        if (Mic.supported(ctx)) ids.add(MIC);
        else if (Build.VERSION.SDK_INT >= 31) locked++;
        for (String id : new String[]{Booster.DND, Booster.PERF, Booster.PASS, Booster.ANIM, Booster.BRIGHT, Booster.CLEAN, Booster.RES}) {
            if (!Booster.supported(id)) continue;            // такой функции на телефоне нет — не показываем
            if (Booster.available(ctx, id)) ids.add(id);
            else locked++;                                    // есть, но нужен Shizuku
        }
        lockedOut[0] = locked;
        return ids;
    }

    public void openPanel() {
        if (!canShow() || panelRoot != null) return;
        Ui.load(ctx);
        boolean landscape = ctx.getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
        boolean left = left();
        Point s = screen();

        FrameLayout root = new FrameLayout(ctx);
        root.setBackgroundColor(Ui.SCRIM);
        root.setOnClickListener(v -> closePanel());

        LinearLayout panel = new LinearLayout(ctx);
        panel.setOrientation(LinearLayout.VERTICAL);
        boolean blur = Ui.GLASS && Build.VERSION.SDK_INT >= 31 && wm.isCrossWindowBlurEnabled();
        panel.setBackground(Ui.box(ctx, Ui.GLASS ? (blur ? Ui.PANEL : 0xEB141820) : Ui.PANEL, Ui.GLASS ? Ui.LINE2 : Ui.LINE2, Ui.R_PANEL));
        panel.setPadding(dp(16), dp(14), dp(16), dp(14));
        panel.setClickable(true);
        if (!Ui.GLASS) panel.setElevation(dp(12));

        // Шапка
        LinearLayout head = new LinearLayout(ctx);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(Ui.icon(ctx, R.drawable.ic_gamepad, 20, Ui.GLASS ? Ui.ACCENT : Ui.TEXT));
        LinearLayout titles = new LinearLayout(ctx);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.setPadding(dp(10), 0, dp(8), 0);
        titles.addView(Ui.text(ctx, Ui.GLASS ? "XPhone" : "XPHONE", Ui.GLASS ? 15 : 14, Ui.TEXT, 800));
        TextView game = Ui.text(ctx, appName(gamePkg), 12, Ui.MUTED, 500);
        game.setSingleLine(true);
        titles.addView(game, Ui.lpMargins(ctx, -2, -2, 0, 3, 0, 0));
        head.addView(titles, new LinearLayout.LayoutParams(0, -2, 1));
        FrameLayout close = new FrameLayout(ctx);
        close.setBackground(Ui.GLASS ? Ui.circle(0x1AFFFFFF) : Ui.box(ctx, 0, Ui.LINE2, 9));
        close.addView(Ui.icon(ctx, R.drawable.ic_x, 16, Ui.SILVER), new FrameLayout.LayoutParams(dp(16), dp(16), Gravity.CENTER));
        close.setContentDescription("Закрыть");
        close.setOnClickListener(v -> closePanel());
        head.addView(close, new LinearLayout.LayoutParams(dp(32), dp(32)));
        panel.addView(head);

        // Нагрев и батарея
        infoText = Ui.text(ctx, "…", 12, Ui.SILVER, 600);
        LinearLayout infoRow = new LinearLayout(ctx);
        infoRow.setGravity(Gravity.CENTER_VERTICAL);
        infoRow.setBackground(Ui.box(ctx, Ui.CARD, Ui.LINE, Ui.GLASS ? 999 : 12));
        infoRow.setPadding(dp(12), dp(9), dp(12), dp(9));
        infoRow.addView(Ui.icon(ctx, R.drawable.ic_thermo, 16, Ui.SILVER));
        infoRow.addView(infoText, Ui.lpMargins(ctx, -2, -2, 8, 0, 0, 0));
        panel.addView(infoRow, Ui.lpMargins(ctx, -1, -2, 0, 12, 0, 12));
        updateInfo();

        // Плитки
        int[] locked = new int[1];
        List<String> ids = visibleTiles(locked);
        int cols = Ui.GLASS ? (landscape ? 5 : 4) : (landscape ? 4 : 3);
        tiles.clear();
        LinearLayout grid = new LinearLayout(ctx);
        grid.setOrientation(LinearLayout.VERTICAL);
        LinearLayout row = null;
        int tileH = Ui.GLASS ? 98 : 88;
        for (int i = 0; i < ids.size(); i++) {
            if (i % cols == 0) {
                row = new LinearLayout(ctx);
                grid.addView(row, Ui.lpMargins(ctx, -1, -2, 0, i == 0 ? 0 : (Ui.GLASS ? 6 : 8), 0, 0));
            }
            Tile t = new Tile(ids.get(i));
            tiles.put(t.id, t);
            LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(0, dp(tileH), 1);
            if (i % cols != 0) tl.leftMargin = dp(8);
            row.addView(t.view, tl);
        }
        int rest = ids.size() % cols;
        if (rest != 0 && row != null) for (int i = rest; i < cols; i++) {
            LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(0, dp(tileH), 1);
            tl.leftMargin = dp(8);
            row.addView(new View(ctx), tl);
        }
        panel.addView(grid);

        // Таймер «Не беспокоить» (показывается по нажатию на плитку)
        dndRow = buildDndRow();
        dndRow.setVisibility(View.GONE);
        panel.addView(dndRow, Ui.lpMargins(ctx, -1, -2, 0, 10, 0, 0));

        // Ещё функции — с Shizuku
        if (locked[0] > 0) {
            TextView hint = Ui.text(ctx, "Ещё " + locked[0] + " " + plural(locked[0]) + " — с Shizuku. Нажмите, чтобы узнать как.", 12, Ui.MUTED, 500);
            hint.setPadding(0, dp(12), 0, 0);
            hint.setOnClickListener(v -> openApp());
            panel.addView(hint);
        }

        // Низ: сторона язычка и «Скрыть до перезахода»
        LinearLayout foot = new LinearLayout(ctx);
        TextView side = footButton(left ? "Вправо" : "Влево", R.drawable.ic_side);
        side.setContentDescription(left ? "Перенести язычок вправо" : "Перенести язычок влево");
        side.setOnClickListener(v -> setSide(!left));
        TextView hide = footButton("Скрыть до перезахода", R.drawable.ic_eye_off);
        hide.setOnClickListener(v -> { if (listener != null) listener.hideUntilExit(); });
        foot.addView(side, new LinearLayout.LayoutParams(0, dp(36), 1));
        LinearLayout.LayoutParams hl = new LinearLayout.LayoutParams(0, dp(36), 2f);
        hl.leftMargin = dp(8);
        foot.addView(hide, hl);
        panel.addView(foot, Ui.lpMargins(ctx, -1, -2, 0, 12, 0, 0));

        ScrollView scroll = new ScrollView(ctx);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.addView(panel);
        int width = Math.min(s.x - dp(24), dp(landscape ? (Ui.GLASS ? 440 : 430) : 340));
        FrameLayout.LayoutParams sl = new FrameLayout.LayoutParams(width, -2,
                (left ? Gravity.START : Gravity.END) | Gravity.CENTER_VERTICAL);
        sl.leftMargin = sl.rightMargin = dp(12);
        sl.topMargin = sl.bottomMargin = dp(12);
        root.addView(scroll, sl);

        WindowManager.LayoutParams lp = baseLp(-1, -1,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN);
        lp.gravity = Gravity.TOP | Gravity.START;
        if (blur) {
            // «Стекло»: игра за панелью размывается, пока панель открыта.
            lp.flags |= WindowManager.LayoutParams.FLAG_BLUR_BEHIND;
            lp.setBlurBehindRadius(dp(18));
        }
        panelRoot = root;
        try { wm.addView(root, lp); } catch (Exception e) { panelRoot = null; return; }

        PcLink.addListener(pcListener);
        float from = (left ? -1 : 1) * (width + dp(24));
        scroll.setTranslationX(from);
        scroll.animate().translationX(0).setDuration(220).setInterpolator(new DecelerateInterpolator()).start();
        refreshTiles();
    }

    private static String plural(int n) {
        int m10 = n % 10, m100 = n % 100;
        if (m10 == 1 && m100 != 11) return "функция";
        if (m10 >= 2 && m10 <= 4 && (m100 < 12 || m100 > 14)) return "функции";
        return "функций";
    }

    private TextView footButton(String label, int icon) {
        TextView b = Ui.text(ctx, label, 12, Ui.MUTED, 600);
        b.setGravity(Gravity.CENTER);
        b.setSingleLine(true);
        b.setBackground(Ui.box(ctx, 0, Ui.LINE3, Ui.GLASS ? 999 : 10));
        android.graphics.drawable.Drawable d = ctx.getDrawable(icon).mutate();
        d.setTint(Ui.MUTED);
        d.setBounds(0, 0, dp(14), dp(14));
        b.setCompoundDrawables(d, null, null, null);
        b.setCompoundDrawablePadding(dp(6));
        b.setPadding(dp(10), 0, dp(10), 0);
        return b;
    }

    /** Ряд выбора времени для «Не беспокоить». */
    private LinearLayout buildDndRow() {
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackground(Ui.box(ctx, Ui.CARD, Ui.LINE, Ui.GLASS ? 20 : 12));
        box.setPadding(dp(12), dp(10), dp(12), dp(12));
        box.addView(Ui.text(ctx, "Не беспокоить на…", 12, Ui.TEXT, 700));
        String[] labels = {"15 мин", "30 мин", "1 час", "2 часа", "До конца игры", "Выключить"};
        int[] minutes = {15, 30, 60, 120, Dnd.UNTIL_GAME_END, 0};
        LinearLayout r1 = new LinearLayout(ctx), r2 = new LinearLayout(ctx);
        for (int i = 0; i < labels.length; i++) {
            TextView chip = Ui.text(ctx, labels[i], 12, Ui.TEXT, 600);
            chip.setGravity(Gravity.CENTER);
            chip.setBackground(Ui.box(ctx, Ui.GLASS ? 0x14FFFFFF : Ui.FIELD, Ui.LINE2, Ui.GLASS ? 999 : 8));
            final int min = minutes[i];
            chip.setOnClickListener(v -> {
                dndRow.setVisibility(View.GONE);
                Runnable done = () -> refreshTiles();
                if (min == 0) Dnd.stop(ctx, done); else Dnd.start(ctx, min, done);
            });
            LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(0, dp(34), 1);
            LinearLayout target = i < 3 ? r1 : r2;
            if (target.getChildCount() > 0) cl.leftMargin = dp(6);
            target.addView(chip, cl);
        }
        box.addView(r1, Ui.lpMargins(ctx, -1, -2, 0, 8, 0, 0));
        box.addView(r2, Ui.lpMargins(ctx, -1, -2, 0, 6, 0, 0));
        return box;
    }

    public void closePanel() {
        if (panelRoot == null) return;
        PcLink.removeListener(pcListener);
        remove(panelRoot);
        panelRoot = null;
        dndRow = null;
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

    /** Обновить плитки, если панель открыта. */
    public void refreshIfOpen() {
        if (panelRoot != null) refreshTiles();
    }

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
        if (listener != null) listener.openedExternal();
        ctx.startActivity(new Intent(ctx, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    private final PcLink.Listener pcListener = () -> {
        Tile t = tiles.get(STREAM);
        if (t != null) t.paint();
    };

    // ── Плитка ────────────────────────────────────────────────────────

    private final class Tile {
        final String id;
        final LinearLayout view;
        final FrameLayout iconBox;   // «Стекло»: круг с иконкой
        final ImageView icon;
        final TextView label, state;
        boolean on;
        final boolean action;

        Tile(String id) {
            this.id = id;
            this.action = Booster.CLEAN.equals(id) || Booster.RES.equals(id);
            view = new LinearLayout(ctx);
            view.setOrientation(LinearLayout.VERTICAL);
            icon = Ui.icon(ctx, iconRes(), Ui.GLASS ? 21 : 20, Ui.SILVER);
            label = Ui.text(ctx, title(), Ui.GLASS ? 11 : 12, Ui.TEXT, 700);
            state = Ui.text(ctx, action ? "Нажмите" : "Выкл", 10, Ui.MUTED, 500);
            state.setSingleLine(true);
            if (Ui.GLASS) {
                view.setGravity(Gravity.CENTER_HORIZONTAL);
                iconBox = new FrameLayout(ctx);
                iconBox.addView(icon, new FrameLayout.LayoutParams(dp(21), dp(21), Gravity.CENTER));
                view.addView(iconBox, new LinearLayout.LayoutParams(dp(52), dp(52)));
                label.setGravity(Gravity.CENTER);
                label.setSingleLine(true);
                view.addView(label, Ui.lpMargins(ctx, -2, -2, 0, 7, 0, 0));
                state.setGravity(Gravity.CENTER);
                view.addView(state, Ui.lpMargins(ctx, -2, -2, 0, 2, 0, 0));
            } else {
                iconBox = null;
                view.setPadding(dp(10), dp(10), dp(10), dp(9));
                view.addView(icon);
                view.addView(new View(ctx), new LinearLayout.LayoutParams(1, 0, 1));
                label.setMaxLines(2);
                view.addView(label);
                view.addView(state, Ui.lpMargins(ctx, -2, -2, 0, 3, 0, 0));
            }
            view.setContentDescription(Booster.title(id));
            view.setOnClickListener(v -> click());
            view.setOnLongClickListener(v -> {
                Toast.makeText(ctx, hint(), Toast.LENGTH_SHORT).show();
                return true;
            });
            paint();
        }

        int iconRes() { return MIC.equals(id) ? R.drawable.ic_mic_off : Booster.icon(id); }
        String title() { return MIC.equals(id) ? "Микрофон" : Ui.GLASS ? Booster.tinyTitle(id) : Booster.shortTitle(id); }
        String hint() { return MIC.equals(id) ? "Плавающая кнопка, которая мгновенно выключает микрофон телефона" : Booster.hint(id); }

        void setOn(boolean v) { on = v; paint(); }

        void paint() {
            String st;
            if (STREAM.equals(id)) {
                PcLink.State s = PcLink.state();
                on = s == PcLink.State.STREAMING;
                st = s == PcLink.State.STREAMING ? "Идёт" : s == PcLink.State.CONNECTING ? "Запуск…"
                        : s == PcLink.State.IDLE ? "Выкл" : PcLink.waiting() ? "Ищем ПК…" : "Нет ПК";
            } else if (MIC.equals(id)) {
                on = micBubble != null;
                st = !on ? "Скрыта" : Mic.muted() ? "Выключен" : "На экране";
            } else if (Booster.DND.equals(id)) {
                st = on ? (Dnd.timed() ? Dnd.untilText() : "Вкл") : "Выкл";
            } else if (action) {
                st = state.getText().toString().isEmpty() ? "Нажмите" : state.getText().toString();
            } else {
                st = Booster.stateText(id, on);
            }
            state.setText(st);
            boolean lit = on && !action;
            if (Ui.GLASS) {
                iconBox.setBackground(Ui.circle(lit ? Ui.ACCENT : Ui.TILE_OFF));
                Ui.tint(icon, lit ? Ui.ON_ACCENT : 0xFFFFFFFF);
                label.setTextColor(Ui.TEXT);
                state.setTextColor(lit ? Ui.ACCENT : Ui.MUTED);
            } else {
                view.setBackground(Ui.box(ctx, lit ? Ui.ACCENT : Ui.TILE_OFF, lit ? Ui.ACCENT : Ui.LINE, Ui.R_TILE));
                Ui.tint(icon, lit ? Ui.ON_ACCENT : Ui.SILVER);
                label.setTextColor(lit ? Ui.ON_ACCENT : Ui.TEXT);
                state.setTextColor(lit ? 0xFF3A3A44 : Ui.MUTED);
            }
        }

        void click() {
            if (STREAM.equals(id)) {
                if (PcLink.state() == PcLink.State.STREAMING) PcLink.requestStop();
                else {
                    PcLink.requestStart(ctx);
                    if (PcLink.state() == PcLink.State.NO_PC)
                        toast(PcLink.canEnableWifiDebugging(ctx) ? "Ищем компьютер…" : "Включите «Беспроводную отладку» в шторке", R.drawable.ic_cast);
                }
                paint();
                return;
            }
            if (MIC.equals(id)) {
                boolean show = micBubble == null;
                Prefs.put(ctx, Prefs.MIC_BUBBLE, show);
                if (show) showMicBubble(); else hideMicBubble();
                paint();
                return;
            }
            if (Booster.DND.equals(id)) {
                if (dndRow != null) dndRow.setVisibility(dndRow.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE);
                return;
            }
            if (Booster.RES.equals(id)) {
                closePanel();
                if (listener != null) listener.openedExternal();
                Intent i = new Intent(Settings.ACTION_DISPLAY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                try { ctx.startActivity(i); } catch (Exception ignored) { }
                toast("Выберите «Разрешение экрана»", R.drawable.ic_monitor);
                return;
            }
            if (Booster.CLEAN.equals(id)) {
                state.setText("Закрываем…");
                Shell.background(() -> {
                    Booster.clean(ctx);
                    Shell.onMain(() -> { state.setText("Готово"); toast("Фоновые приложения закрыты", R.drawable.ic_trash); });
                });
                return;
            }
            final boolean target = !on;
            setOn(target);
            final String pkg = gamePkg;
            Shell.background(() -> {
                Booster.set(ctx, id, target, pkg);
                boolean real = Booster.read(ctx, id, pkg);
                Shell.onMain(() -> {
                    setOn(real);
                    if (real != target) toast("Телефон не дал переключить «" + Booster.title(id) + "»", R.drawable.ic_alert);
                });
            });
        }
    }
}
