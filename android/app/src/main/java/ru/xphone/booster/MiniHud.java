package ru.xphone.booster;

import android.content.Context;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.os.*;
import android.view.*;
import android.widget.*;
import java.util.*;
import java.util.concurrent.*;

/** Compact independent HUD; pinning makes it pass touches through to the game. */
public final class MiniHud {
    public static final String ENABLED="hud_enabled",OPACITY="hud_opacity",PINNED="hud_pinned",BACKGROUND="hud_background";
    private final Context ctx;
    private final WindowManager wm;
    private final Handler main=new Handler(Looper.getMainLooper());
    private HudStrip view;
    private WindowManager.LayoutParams lp;
    private java.util.function.Consumer<List<Telemetry.Metric>> subscription;
    private String lastStyle;
    public MiniHud(Context c){ctx=c;wm=c.getSystemService(WindowManager.class);}
    private Rect bounds(){
        android.graphics.Point p=new android.graphics.Point();
        android.view.Display display=ctx.getSystemService(android.hardware.display.DisplayManager.class).getDisplay(Display.DEFAULT_DISPLAY);
        if(display!=null)display.getRealSize(p);else wm.getDefaultDisplay().getRealSize(p);
        return new Rect(0,0,p.x,p.y);
    }
    public boolean visible(){return view!=null;}
    @android.annotation.SuppressLint("ClickableViewAccessibility")
    public void show(){
        if(view!=null||!android.provider.Settings.canDrawOverlays(ctx))return;
        Ui.load(ctx);HudStrip strip=new HudStrip(ctx);
        lp=new WindowManager.LayoutParams(-2,-2,WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,PixelFormat.TRANSLUCENT);
        lp.gravity=Gravity.TOP|Gravity.START;
        if(Build.VERSION.SDK_INT>=30){lp.setFitInsetsTypes(0);lp.layoutInDisplayCutoutMode=WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;}
        Rect b=bounds();strip.maxWidth(b.width()-Ui.dp(ctx,16));
        lp.x=Math.round(Prefs.getFloat(ctx,"hud_x",0.04f)*b.width());
        lp.y=Math.round(Prefs.getFloat(ctx,"hud_y",0.08f)*b.height());
        float[] down=new float[4];
        strip.setOnTouchListener((v,e) -> {
            if(Prefs.get(ctx,PINNED,false))return false;
            switch(e.getActionMasked()){
                case MotionEvent.ACTION_DOWN:down[0]=e.getRawX();down[1]=e.getRawY();down[2]=lp.x;down[3]=lp.y;return true;
                case MotionEvent.ACTION_MOVE:
                    lp.x=Math.round(down[2]+e.getRawX()-down[0]);lp.y=Math.round(down[3]+e.getRawY()-down[1]);clamp(strip);return true;
                case MotionEvent.ACTION_UP:case MotionEvent.ACTION_CANCEL:
                    Rect r=bounds();Prefs.putFloat(ctx,"hud_x",lp.x/(float)r.width());Prefs.putFloat(ctx,"hud_y",lp.y/(float)r.height());return true;
            }return true;
        });
        view=strip;lastStyle=null;
        // Configure pass-through before the first frame to avoid briefly intercepting the game.
        applyFlags();
        try{wm.addView(strip,lp);}catch(Exception e){view=null;return;}
        refreshAppearance();
        subscription=values->{if(view==strip){strip.update(Telemetry.selectedValues(ctx,values));refreshAppearance();strip.post(()->{if(view==strip)clamp(strip);});}};
        TelemetryFeed.listen(ctx,subscription);
    }

    private void applyFlags(){
        boolean pinned=Prefs.get(ctx,PINNED,false);
        if(pinned)lp.flags|=WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        else lp.flags&=~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        // With the touch glow's alpha=0.65, HUD alpha<=0.4 keeps the combined opacity below Android's 0.8 limit.
        int value=HudFormat.opacity(Prefs.getInt(ctx,OPACITY,90),pinned,Prefs.get(ctx,FpsHud.PINNED,false)&&Prefs.get(ctx,"fps_hud_enabled",false));
        lp.alpha=value/100f;
    }
    private void clamp(HudStrip strip){
        if(view!=strip)return;
        Rect r=bounds();strip.maxWidth(r.width()-Ui.dp(ctx,16));
        lp.x=Math.max(0,Math.min(lp.x,r.width()-strip.getWidth()));lp.y=Math.max(0,Math.min(lp.y,r.height()-strip.getHeight()));
        try{wm.updateViewLayout(strip,lp);}catch(Exception ignored){}
    }
    public void refreshAppearance(){
        if(view==null)return;
        Ui.load(ctx);applyFlags();
        boolean background=Prefs.get(ctx,BACKGROUND,true);
        String style=Ui.theme(ctx)+":"+background;
        if(!style.equals(lastStyle)){
            view.setBackground(background?Ui.box(ctx,Ui.GLASS?0xE6141820:Ui.PANEL,Ui.LINE2,Ui.GLASS?20:10):null);
            lastStyle=style;view.invalidate();
        }
        try{wm.updateViewLayout(view,lp);}catch(Exception ignored){}
    }
    public void hide(){
        if(subscription!=null){TelemetryFeed.stop(subscription);subscription=null;}
        HudStrip old=view;view=null;if(old!=null)try{wm.removeView(old);}catch(Exception ignored){}
    }
    public static LinearLayout controls(Context c,Runnable changed){
        LinearLayout box=new LinearLayout(c);box.setOrientation(LinearLayout.VERTICAL);
        box.addView(Ui.text(c,"МИНИ-ОКНО",12,Ui.TEXT,700));
        box.addView(row(c,"Плавающая строка в игре",R.drawable.ic_info,Prefs.get(c,ENABLED,false),v->{Prefs.put(c,ENABLED,v);changed.run();}));
        TextView opacity=Ui.text(c,"",12,Ui.MUTED,500);
        SeekBar slider=new SeekBar(c);slider.setContentDescription("Непрозрачность строки показателей");
        Runnable updateSlider=() -> {
            boolean pinned=Prefs.get(c,PINNED,false);int value=HudFormat.opacity(Prefs.getInt(c,OPACITY,90),pinned);
            int max=pinned?40:100;if(pinned&&Prefs.get(c,FpsHud.PINNED,false)&&Prefs.get(c,"fps_hud_enabled",false))max=20;value=Math.min(max,value);slider.setMax(max-5);slider.setProgress(value-5);opacity.setText("Непрозрачность: "+value+"%");
        };
        box.addView(row(c,"Закрепить строку",R.drawable.ic_lock,Prefs.get(c,PINNED,false),v->{
            Prefs.put(c,PINNED,v);
            Prefs.putInt(c,OPACITY,HudFormat.opacity(Prefs.getInt(c,OPACITY,90),v));
            updateSlider.run();changed.run();
        }));
        box.addView(Ui.hint(c,"При открытой панели показатели встроены в её шапку. После закрытия появляется плавающая строка. Перетащите её в нужное место, затем закрепите. Касания будут проходить в игру. Снять закрепление можно здесь.",11,Ui.MUTED,500));
        box.addView(row(c,"Подложка строки",R.drawable.ic_layers,Prefs.get(c,BACKGROUND,true),v->{Prefs.put(c,BACKGROUND,v);changed.run();}));
        for(int i=0;i<Telemetry.KEYS.length;i++){
            final String key=Telemetry.KEYS[i];if(!Telemetry.supported(c,key))continue;
            box.addView(row(c,Telemetry.TITLES[i],HudStrip.icon(key),Telemetry.selected(c,key),v->{Prefs.put(c,"hud_"+key,v);changed.run();}));
        }
        box.addView(opacity);updateSlider.run();
        slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            public void onProgressChanged(SeekBar b,int value,boolean user){if(user){Prefs.putInt(c,OPACITY,value+5);opacity.setText("Непрозрачность: "+(value+5)+"%");changed.run();}}
            public void onStartTrackingTouch(SeekBar b){}public void onStopTrackingTouch(SeekBar b){}
        });box.addView(slider);
        box.addView(Ui.hint(c,"Закреплённая строка остаётся полупрозрачной (до 40%), чтобы не мешать касаниям. Скорость: К / М / Гбит/с. Задержка — до роутера. Температура — батареи. CPU/GPU появляются при доступе к данным.",11,Ui.MUTED,500));
        return box;
    }
    static LinearLayout row(Context c,String label,int icon,boolean on,Ui.Toggle.Listener listener){
        LinearLayout r=new LinearLayout(c);r.setGravity(Gravity.CENTER_VERTICAL);r.setPadding(0,Ui.dp(c,7),0,Ui.dp(c,7));
        r.addView(Ui.icon(c,icon,16,Ui.SILVER));
        TextView title=Ui.text(c,label,12,Ui.TEXT,500);title.setPadding(Ui.dp(c,8),0,Ui.dp(c,6),0);
        r.addView(title,new LinearLayout.LayoutParams(0,-2,1));
        Ui.Toggle toggle=new Ui.Toggle(c);toggle.setOn(on);toggle.setListener(listener);
        r.addView(toggle,new LinearLayout.LayoutParams(Ui.dp(c,44),Ui.dp(c,26)));r.setOnClickListener(v->toggle.performClick());return r;
    }
}
