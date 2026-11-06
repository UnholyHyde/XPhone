package ru.xphone.booster;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Оформление XPhone. Две темы:
 * «Графит» — чёрно-белая, как программа на ПК;
 * «Стекло» — полупрозрачные панели, мягкий акцент, круглые кнопки.
 * Цвета — поля класса: после смены темы экран просто перестраивается.
 */
public final class Ui {
    private Ui() {}

    public static final String THEME_GRAPHITE = "graphite", THEME_GLASS = "glass";

    public static int BG, PANEL, CARD, FIELD, SEL, LINE, LINE2, LINE3, TEXT, MUTED, SILVER, DIM,
            ACCENT, ON_ACCENT, TILE_OFF, TOGGLE_OFF, THUMB_OFF, THUMB_ON, SCRIM;
    /** Скругления (dp). */
    public static float R_CARD, R_PANEL, R_BUTTON, R_TILE;
    public static boolean GLASS;
    private static String current;

    static { apply(THEME_GRAPHITE); }

    public static String theme(Context c) { return Prefs.sp(c).getString("theme", THEME_GRAPHITE); }

    public static void setTheme(Context c, String t) {
        Prefs.sp(c).edit().putString("theme", t).apply();
        load(c);
    }

    /** Вызывать перед построением экрана или панели. */
    public static void load(Context c) {
        apply(theme(c));
        if(Prefs.sp(c).contains("accent_color")) {
            ACCENT=0xFF000000|Prefs.getInt(c,"accent_color",0xFFFFFF);
            ON_ACCENT=AccentColor.foreground(ACCENT);
            THUMB_ON=ON_ACCENT;
        }
    }
    public static void setAccent(Context c,int color){Prefs.putInt(c,"accent_color",color|0xFF000000);load(c);}
    public static void resetAccent(Context c){Prefs.sp(c).edit().remove("accent_color").apply();load(c);}
    public static int overlayFill(Context c){return (Prefs.getInt(c,"panel_opacity",35)*255/100<<24)|0x0017191E;}


    private static void apply(String t) {
        current = t;
        if (THEME_GLASS.equals(t)) {
            GLASS = true;
            BG = 0xFF0E1116; PANEL = 0xA6141820; CARD = 0x12FFFFFF; FIELD = 0x14FFFFFF; SEL = 0x29FFFFFF;
            LINE = 0x1AFFFFFF; LINE2 = 0x24FFFFFF; LINE3 = 0x33FFFFFF;
            TEXT = 0xFFF2F4F8; MUTED = 0xFFA3AAB8; SILVER = 0xFFC8CDD8; DIM = 0xFF6B7280;
            ACCENT = 0xFFA99BFF; ON_ACCENT = 0xFF0E1116; TILE_OFF = 0x1FFFFFFF;
            TOGGLE_OFF = 0x24FFFFFF; THUMB_OFF = 0xFFFFFFFF; THUMB_ON = 0xFFFFFFFF; SCRIM = 0x33000000;
            R_CARD = 22; R_PANEL = 28; R_BUTTON = 999; R_TILE = 999;
        } else {
            GLASS = false;
            BG = 0xFF0B0B0E; PANEL = 0xFF111116; CARD = 0xFF141419; FIELD = 0xFF16161B; SEL = 0xFF1C1C23;
            LINE = 0xFF23232B; LINE2 = 0xFF2A2A33; LINE3 = 0xFF3A3A44;
            TEXT = 0xFFE6E7EB; MUTED = 0xFF9A9DA8; SILVER = 0xFFC9CBD1; DIM = 0xFF5C5F69;
            ACCENT = 0xFFFFFFFF; ON_ACCENT = 0xFF0B0B0E; TILE_OFF = 0xFF1A1A20;
            TOGGLE_OFF = 0xFF34343E; THUMB_OFF = 0xFFC9CBD1; THUMB_ON = 0xFF0B0B0E; SCRIM = 0x59000000;
            R_CARD = 14; R_PANEL = 22; R_BUTTON = 10; R_TILE = 14;
        }
    }

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
        if (weight >= 800) return GLASS ? bold : logo; // в «Стекле» заголовки — жирный Manrope
        if (weight >= 700) return bold;
        if (weight >= 600) return semibold;
        return regular;
    }

    public static boolean hintsEnabled(Context c){return Prefs.get(c,"show_hints",true);}

    /** Optional explanations only; never use for controls, values or error messages. */
    public static TextView hint(Context c,CharSequence value,float sp,int color,int weight){
        TextView view=text(c,value,sp,color,weight);
        if(!hintsEnabled(c))view.setVisibility(View.GONE);
        return view;
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
        d.setCornerRadius(dp(c, Math.min(radiusDp, 400)));
        if (stroke != 0) d.setStroke(Math.max(1, dp(c, 1)), stroke);
        return d;
    }

    public static GradientDrawable circle(int fill) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(fill);
        return d;
    }

    /** Фон экрана приложения: в «Стекле» — с мягким пятном акцента сверху. */
    public static Drawable screenBackground(Context c) {
        GradientDrawable base = new GradientDrawable();
        base.setColor(BG);
        if (!GLASS) return base;
        GradientDrawable glow = new GradientDrawable();
        glow.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        glow.setGradientRadius(dp(c, 340));
        glow.setGradientCenter(0.1f, 0.05f);
        glow.setColors(new int[]{(ACCENT & 0x00FFFFFF) | 0x38000000, 0x00000000});
        return new LayerDrawable(new Drawable[]{base, glow});
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
        l.setBackground(box(c, CARD, LINE, R_CARD));
        int p = dp(c, 16);
        l.setPadding(p, dp(c, 14), p, dp(c, 14));
        return l;
    }

    /** Кнопка: главная (акцент) или обычная. */
    public static TextView button(Context c, String s, boolean primary) {
        TextView b = text(c, s, 13, primary ? ON_ACCENT : (GLASS ? TEXT : SILVER), 700);
        b.setGravity(Gravity.CENTER);
        b.setBackground(box(c, primary ? ACCENT : FIELD, primary ? ACCENT : LINE2, R_BUTTON));
        b.setPadding(dp(c, 16), 0, dp(c, 16), 0);
        b.setMinHeight(dp(c, GLASS ? 42 : 38));
        b.setClickable(true);
        b.setFocusable(true);
        return b;
    }

    /** Переключатель из нескольких вариантов (вкладки, тема, сторона). */
    public static LinearLayout segmented(Context c, String[] labels, int selected, java.util.function.IntConsumer onPick) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(dp(c, 4), dp(c, 4), dp(c, 4), dp(c, 4));
        row.setBackground(box(c, GLASS ? 0x0FFFFFFF : CARD, LINE, GLASS ? 999 : R_CARD));
        for (int i = 0; i < labels.length; i++) {
            boolean on = i == selected;
            TextView t = text(c, labels[i], 13, on ? (GLASS ? 0xFFFFFFFF : ON_ACCENT) : MUTED, on ? 700 : 600);
            t.setGravity(Gravity.CENTER);
            t.setMinHeight(dp(c, 36));
            if (on) t.setBackground(box(c, GLASS ? 0x2EFFFFFF : ACCENT, 0, GLASS ? 999 : 10));
            final int idx = i;
            t.setSelected(on);
            t.setOnClickListener(v -> {
                for(int j=0;j<row.getChildCount();j++){
                    TextView item=(TextView)row.getChildAt(j);boolean active=j==idx;
                    item.setSelected(active);item.setTextColor(active?(GLASS?0xFFFFFFFF:ON_ACCENT):MUTED);
                    item.setTypeface(font(c,active?700:600));
                    item.setBackground(active?box(c,GLASS?0x2EFFFFFF:ACCENT,0,GLASS?999:10):null);
                }
                onPick.accept(idx);
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1);
            if (i > 0) lp.leftMargin = dp(c, 4);
            row.addView(t, lp);
        }
        return row;
    }

    public static LinearLayout.LayoutParams lp(int w, int h) {
        return new LinearLayout.LayoutParams(w, h);
    }

    public static LinearLayout.LayoutParams lpMargins(Context c, int w, int h, int l, int t, int r, int b) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h);
        p.setMargins(dp(c, l), dp(c, t), dp(c, r), dp(c, b));
        return p;
    }

    /** Переключатель: в «Графите» — 44×26 белый, в «Стекле» — 48×28 с акцентом. */
    public static final class Toggle extends View {
        private boolean on;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();
        public interface Listener { void changed(boolean on); }
        private Listener listener;

        public Toggle(Context c) {
            super(c);
            setLayoutParams(new LinearLayout.LayoutParams(dp(c, GLASS ? 48 : 44), dp(c, GLASS ? 28 : 26)));
            setClickable(true);
            setOnClickListener(v -> { setOn(!on); if (listener != null) listener.changed(on); });
        }

        public void setListener(Listener l) { listener = l; }
        public boolean isOn() { return on; }
        public void setOn(boolean v) { on = v; invalidate(); }

        @Override
        protected void onDraw(Canvas canvas) {
            float w = getWidth(), h = getHeight(), pad = dp(getContext(), 3);
            p.setColor(on ? ACCENT : TOGGLE_OFF);
            r.set(0, 0, w, h);
            canvas.drawRoundRect(r, h / 2, h / 2, p);
            float d = h - pad * 2;
            float cx = on ? w - pad - d / 2 : pad + d / 2;
            p.setColor(on ? THUMB_ON : THUMB_OFF);
            canvas.drawCircle(cx, h / 2, d / 2, p);
        }
    }
}
