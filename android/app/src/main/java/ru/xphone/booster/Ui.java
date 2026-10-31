package ru.xphone.booster;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Оформление в стиле XPhone на ПК: только оттенки серого, шрифт Manrope, иконки Lucide. */
public final class Ui {
    private Ui() {}

    public static final int BG = 0xFF0B0B0E, PANEL = 0xFF111116, CARD = 0xFF141419, FIELD = 0xFF16161B,
            SEL = 0xFF1C1C23, LINE = 0xFF23232B, LINE2 = 0xFF2A2A33, LINE3 = 0xFF3A3A44,
            TEXT = 0xFFE6E7EB, MUTED = 0xFF9A9DA8, SILVER = 0xFFC9CBD1, DIM = 0xFF5C5F69,
            ACCENT = 0xFFFFFFFF, ON_ACCENT = 0xFF0B0B0E, TILE_OFF = 0xFF1A1A20;

    private static Typeface regular, semibold, bold, logo;

    public static int dp(Context c, float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, c.getResources().getDisplayMetrics()));
    }

    public static Typeface font(Context c, int weight) {
        if (regular == null) {
            regular = c.getResources().getFont(R.font.manrope_regular);
            semibold = c.getResources().getFont(R.font.manrope_semibold);
            bold = c.getResources().getFont(R.font.manrope_bold);
            logo = c.getResources().getFont(R.font.unbounded_semibold);
        }
        if (weight >= 800) return logo;
        if (weight >= 700) return bold;
        if (weight >= 600) return semibold;
        return regular;
    }

    public static TextView text(Context c, CharSequence s, float sp, int color, int weight) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setTypeface(font(c, weight));
        t.setIncludeFontPadding(false);
        t.setLineSpacing(0, 1.15f);
        return t;
    }

    public static GradientDrawable box(Context c, int fill, int stroke, float radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fill);
        d.setCornerRadius(dp(c, radiusDp));
        if (stroke != 0) d.setStroke(Math.max(1, dp(c, 1)), stroke);
        return d;
    }

    public static ImageView icon(Context c, int res, int sizeDp, int color) {
        ImageView v = new ImageView(c);
        Drawable d = c.getDrawable(res).mutate();
        d.setTintList(ColorStateList.valueOf(color));
        v.setImageDrawable(d);
        v.setLayoutParams(new LinearLayout.LayoutParams(dp(c, sizeDp), dp(c, sizeDp)));
        return v;
    }

    public static void tint(ImageView v, int color) {
        if (v.getDrawable() != null) v.getDrawable().setTintList(ColorStateList.valueOf(color));
    }

    /** Карточка-секция. */
    public static LinearLayout card(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setBackground(box(c, CARD, LINE, 14));
        int p = dp(c, 16);
        l.setPadding(p, dp(c, 14), p, dp(c, 14));
        return l;
    }

    /** Кнопка как .btn в пульте: белая (главная) или серая. */
    public static TextView button(Context c, String s, boolean primary) {
        TextView b = text(c, s, 13, primary ? ON_ACCENT : SILVER, 700);
        b.setGravity(Gravity.CENTER);
        b.setBackground(box(c, primary ? ACCENT : FIELD, primary ? ACCENT : LINE2, 10));
        b.setPadding(dp(c, 14), 0, dp(c, 14), 0);
        b.setMinHeight(dp(c, 38));
        b.setClickable(true);
        b.setFocusable(true);
        return b;
    }

    public static LinearLayout.LayoutParams lp(int w, int h) {
        return new LinearLayout.LayoutParams(w, h);
    }

    public static LinearLayout.LayoutParams lpMargins(Context c, int w, int h, int l, int t, int r, int b) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h);
        p.setMargins(dp(c, l), dp(c, t), dp(c, r), dp(c, b));
        return p;
    }

    /** Переключатель как в пульте: 44×26, белый — включено. */
    public static final class Toggle extends View {
        private boolean on;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();
        public interface Listener { void changed(boolean on); }
        private Listener listener;

        public Toggle(Context c) {
            super(c);
            setLayoutParams(new LinearLayout.LayoutParams(dp(c, 44), dp(c, 26)));
            setClickable(true);
            setOnClickListener(v -> { setOn(!on); if (listener != null) listener.changed(on); });
        }

        public void setListener(Listener l) { listener = l; }
        public boolean isOn() { return on; }
        public void setOn(boolean v) { on = v; invalidate(); }

        @Override
        protected void onDraw(Canvas canvas) {
            float w = getWidth(), h = getHeight(), pad = dp(getContext(), 3);
            p.setColor(on ? ACCENT : 0xFF34343E);
            r.set(0, 0, w, h);
            canvas.drawRoundRect(r, h / 2, h / 2, p);
            float d = h - pad * 2;
            float cx = on ? w - pad - d / 2 : pad + d / 2;
            p.setColor(on ? ON_ACCENT : SILVER);
            canvas.drawCircle(cx, h / 2, d / 2, p);
        }
    }
}
