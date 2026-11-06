package ru.xphone.booster;

import android.content.*;
import android.graphics.*;
import android.hardware.display.DisplayManager;
import android.os.*;
import android.provider.Settings;
import android.view.*;
import android.widget.*;
import java.util.*;
import java.util.function.BiConsumer;

/** Значки управляют настоящими системными окнами, не копиями чужого экрана. */
final class CompanionWindows {
    private final Context ctx;
    private final WindowManager wm;
    private final BiConsumer<String,Integer> notice;
    private final Runnable beforeOpen;
    private final LinkedHashMap<String,Entry> entries=new LinkedHashMap<>();
    private int generation;
    private boolean busy,checking,suspended;
    private long checkedAt;
    private String game;
    private static final int ICON=R.drawable.ic_monitor;
    private static final class Entry {
        String pkg,state="OPEN";
        long verifiedAt=SystemClock.elapsedRealtime();
        FrameLayout view; WindowManager.LayoutParams lp;
    }
    CompanionWindows(Context c,BiConsumer<String,Integer> n,Runnable close){ctx=c;wm=c.getSystemService(WindowManager.class);notice=n;beforeOpen=close;}
    private int dp(float n){return Ui.dp(ctx,n);}
    private Point screen(){Point p=new Point();ctx.getSystemService(DisplayManager.class).getDisplay(Display.DEFAULT_DISPLAY).getRealSize(p);return p;}
    void suspend(boolean value){suspended=value;for(Entry e:entries.values())if(e.view!=null)e.view.setVisibility(value?View.INVISIBLE:View.VISIBLE);}
    void begin(String pkg){game=pkg;}
    boolean ownsForeground(String pkg) {
        Entry e=entries.get(pkg);
        return game!=null&&e!=null&&SystemClock.elapsedRealtime()-e.verifiedAt<8000&&("OPEN".equals(e.state)||"MINIMIZED".equals(e.state));
    }
    boolean openLast() {
        String pkg=Prefs.sp(ctx).getString("companion_last",null);
        if(pkg==null||!CompanionApps.selected(ctx).contains(pkg))return false;
        open(pkg);return true;
    }
    View controls() {
        LinearLayout body=new LinearLayout(ctx);body.setOrientation(LinearLayout.VERTICAL);
        List<String> packages=new ArrayList<>(CompanionApps.selected(ctx));
        for(int i=0;i<packages.size();i+=2){LinearLayout row=new LinearLayout(ctx);
            for(int j=i;j<Math.min(packages.size(),i+2);j++){
                String pkg=packages.get(j);LinearLayout button=new LinearLayout(ctx);button.setOrientation(LinearLayout.VERTICAL);button.setGravity(Gravity.CENTER);
                button.setPadding(dp(8),dp(10),dp(8),dp(10));button.setBackground(Ui.box(ctx,Ui.FIELD,Ui.LINE2,10));
                ImageView icon=new ImageView(ctx);try{icon.setImageDrawable(ctx.getPackageManager().getApplicationIcon(pkg));}catch(Exception ignored){}
                button.addView(icon,new LinearLayout.LayoutParams(dp(36),dp(36)));
                TextView label=Ui.text(ctx,CompanionApps.label(ctx,pkg),12,Ui.TEXT,600);label.setGravity(Gravity.CENTER);label.setMaxLines(2);
                button.addView(label,Ui.lpMargins(ctx,-1,-2,0,8,0,0));button.setOnClickListener(v->open(pkg));button.setContentDescription("Открыть "+CompanionApps.label(ctx,pkg));
                LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,-2,1);if(j>i)lp.leftMargin=dp(8);row.addView(button,lp);
            }body.addView(row,Ui.lpMargins(ctx,-1,-2,0,i==0?0:8,0,0));
        }
        if(packages.isEmpty())body.addView(Ui.hint(ctx,"Добавьте приложения: XPhone → Панель → Приложения поверх игры.",13,Ui.MUTED,500));
        return body;
    }
    private void open(String pkg) {
        if(game==null){notice.accept("Сначала откройте игру",ICON);return;}
        if(!CompanionApps.available(ctx)||!Settings.canDrawOverlays(ctx)){notice.accept("Проверьте Shizuku и разрешение поверх приложений",ICON);return;}
        if(busy)return;
        if(pkg.equals(game)||Prefs.isGame(ctx,pkg)||!CompanionApps.selected(ctx).contains(pkg))return;
        Intent launch=ctx.getPackageManager().getLaunchIntentForPackage(pkg);
        if(launch==null||launch.getComponent()==null){notice.accept("Приложение удалено или недоступно",ICON);return;}
        beforeOpen.run();
        int token=generation;Point s=screen();int[] bounds=CompanionGeometry.window(s.x,s.y);
        busy=true;
        Shell.background(()->{
            String result=Shell.openWindow(ctx,launch.getComponent().flattenToString(),bounds);
            Shell.onMain(()->{
                if(token!=generation)return;
                busy=false;
                if("OPEN".equals(result)) {
                    Prefs.sp(ctx).edit().putString("companion_last",pkg).apply();
                    Entry e=entries.get(pkg);
                    if(e==null){e=new Entry();e.pkg=pkg;entries.put(pkg,e);}
                    e.state="OPEN";e.verifiedAt=SystemClock.elapsedRealtime();showBubble(e);paint(e);
                    notice.accept("Окно открыто. Нажмите его значок XPhone, чтобы свернуть",ICON);
                }else notice.accept("NOT_FREEFORM".equals(result)?"Телефон не открыл приложение в окне. Эта прошивка или приложение ограничивает оконный режим":"Не удалось открыть окно — проверьте Shizuku",ICON);
            });
        });
    }
    private void toggle(Entry e) {
        if(busy)return;
        if("MINIMIZED".equals(e.state)){open(e.pkg);return;}
        busy=true;int token=generation;
        Shell.background(()->{
            String state=Shell.minimizeWindow(ctx,e.pkg);
            Shell.onMain(()->{
                if(token!=generation)return;
                busy=false;
                if("MINIMIZED".equals(state)){e.state=state;e.verifiedAt=SystemClock.elapsedRealtime();paint(e);}
                else if("GONE".equals(state)||"FULLSCREEN".equals(state)){remove(e);entries.remove(e.pkg);}
                else notice.accept("Телефон не дал свернуть окно. Используйте системную кнопку окна",ICON);
            });
        });
    }
    private void showBubble(Entry e) {
        if(e.view!=null)return;
        Ui.load(ctx);Point s=screen();int size=dp(48);
        FrameLayout v=new FrameLayout(ctx);
        ImageView image=new ImageView(ctx);
        try{image.setImageDrawable(ctx.getPackageManager().getApplicationIcon(e.pkg));}catch(Exception ignored){}
        v.addView(image,new FrameLayout.LayoutParams(dp(28),dp(28),Gravity.CENTER));
        WindowManager.LayoutParams lp=new WindowManager.LayoutParams(size,size,WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,PixelFormat.TRANSLUCENT);
        lp.gravity=Gravity.TOP|Gravity.LEFT;
        lp.x=CompanionGeometry.clamp(Prefs.getInt(ctx,"companion_x_"+e.pkg,s.x-size-dp(28)),s.x,size);
        int step=Math.min(dp(54),Math.max(size,(s.y-size-dp(32))/Math.max(1,CompanionApps.selected(ctx).size()-1)));
        lp.y=CompanionGeometry.clamp(Prefs.getInt(ctx,"companion_y_"+e.pkg,dp(16)+(entries.size()-1)*step),s.y,size);
        e.view=v;e.lp=lp;
        v.setOnTouchListener(new View.OnTouchListener(){
            float x,y;int ox,oy;boolean moved;
            public boolean onTouch(View view,MotionEvent ev) {
                if(ev.getActionMasked()==MotionEvent.ACTION_DOWN){x=ev.getRawX();y=ev.getRawY();ox=lp.x;oy=lp.y;moved=false;return true;}
                if(ev.getActionMasked()==MotionEvent.ACTION_MOVE){
                    float dx=ev.getRawX()-x,dy=ev.getRawY()-y;
                    if(Math.abs(dx)>dp(5)||Math.abs(dy)>dp(5))moved=true;
                    if(moved){Point now=screen();lp.x=CompanionGeometry.clamp(ox+(int)dx,now.x,size);lp.y=CompanionGeometry.clamp(oy+(int)dy,now.y,size);try{wm.updateViewLayout(v,lp);}catch(Exception ignored){}}
                    return true;
                }
                if(ev.getActionMasked()==MotionEvent.ACTION_UP){
                    if(moved){Prefs.putInt(ctx,"companion_x_"+e.pkg,lp.x);Prefs.putInt(ctx,"companion_y_"+e.pkg,lp.y);}else view.performClick();return true;
                }
                return true;
            }
        });
        v.setOnClickListener(view->toggle(e));
        try{v.setVisibility(suspended?View.INVISIBLE:View.VISIBLE);wm.addView(v,lp);paint(e);}catch(Exception ex){e.view=null;notice.accept("Не удалось показать значок окна",ICON);}
    }
    private void paint(Entry e){if(e.view!=null){boolean small="MINIMIZED".equals(e.state);e.view.setBackground(Ui.box(ctx,Ui.CARD,small?Ui.LINE2:Ui.ACCENT,24));e.view.setContentDescription((small?"Развернуть ":"Свернуть ")+CompanionApps.label(ctx,e.pkg));}}
    private void remove(Entry e){if(e.view!=null)try{wm.removeView(e.view);}catch(Exception ignored){}e.view=null;}
    void tick(boolean interactive) {
        if(suspended)return;
        if(entries.isEmpty())return;
        if(!interactive){for(Entry e:entries.values())remove(e);return;}
        if(!Shell.ready(ctx)){end();return;}
        Point s=screen();
        for(Entry e:entries.values()) {
            showBubble(e);
            if(e.view!=null){e.lp.x=CompanionGeometry.clamp(e.lp.x,s.x,dp(48));e.lp.y=CompanionGeometry.clamp(e.lp.y,s.y,dp(48));try{wm.updateViewLayout(e.view,e.lp);}catch(Exception ignored){}}
        }
        long now=SystemClock.elapsedRealtime();
        if(checking||busy||now-checkedAt<2000)return;
        checking=true;checkedAt=now;int token=generation;
        List<String> keys=new ArrayList<>(entries.keySet());
        Shell.background(()->{
            Map<String,String> states=new HashMap<>();for(String pkg:keys)states.put(pkg,Shell.windowState(ctx,pkg));
            Shell.onMain(()->{
                if(token!=generation)return;
                checking=false;
                for(String pkg:keys){Entry e=entries.get(pkg);if(e==null)continue;String state=states.get(pkg);
                    if("GONE".equals(state)||"FULLSCREEN".equals(state)||!CompanionApps.selected(ctx).contains(pkg)){remove(e);entries.remove(pkg);}
                    else if("OPEN".equals(state)||"MINIMIZED".equals(state)){e.state=state;e.verifiedAt=SystemClock.elapsedRealtime();paint(e);}
                }
            });
        });
    }
    void reflow(){for(Entry e:entries.values()){remove(e);showBubble(e);}}
    void end() {
        generation++;game=null;busy=false;checking=false;
        for(Entry e:entries.values())remove(e);entries.clear();
        Shell.background(Shell::endWindows);
    }
}
