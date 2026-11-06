package ru.xphone.booster;
import android.content.Context;
import android.graphics.PixelFormat;
import android.view.*;
import android.widget.*;
import java.util.*;
import java.util.function.Consumer;
/** Independent FPS/Hz readout, not part of the general mini HUD. */
final class FpsHud {
    static final String PINNED="fps_pinned",OPACITY="fps_opacity",BACKGROUND="fps_background";
    private String style;
    private int opacity(){return HudFormat.opacity(Prefs.getInt(c,OPACITY,90),Prefs.get(c,PINNED,false),Prefs.get(c,MiniHud.PINNED,false)&&Prefs.get(c,MiniHud.ENABLED,false));}
    void refreshAppearance(){if(view==null)return;Ui.load(c);boolean pinned=Prefs.get(c,PINNED,false);
        if(pinned)lp.flags|=WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;else lp.flags&=~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        lp.alpha=opacity()/100f;String next=Ui.theme(c)+":"+Prefs.get(c,BACKGROUND,true)+":"+Ui.TEXT;
        if(!next.equals(style)){style=next;view.setTextColor(Ui.TEXT);view.setBackground(Prefs.get(c,BACKGROUND,true)?Ui.box(c,Ui.GLASS?0xE6141820:Ui.PANEL,Ui.LINE2,Ui.GLASS?20:10):null);}
        try{wm.updateViewLayout(view,lp);}catch(Exception ignored){}
    }
    private final Context c;private final WindowManager wm;private TextView view;private WindowManager.LayoutParams lp;
    private Consumer<List<Telemetry.Metric>> subscription;
    FpsHud(Context c){this.c=c;wm=c.getSystemService(WindowManager.class);}
    @android.annotation.SuppressLint("ClickableViewAccessibility")
    void show(){if(view!=null||!android.provider.Settings.canDrawOverlays(c))return;
        TextView text=Ui.text(c,"— FPS · — Гц",12,Ui.TEXT,700);text.setPadding(Ui.dp(c,7),Ui.dp(c,4),Ui.dp(c,7),Ui.dp(c,4));text.setBackground(Ui.box(c,0xE617191F,Ui.LINE2,8));text.setSingleLine(true);text.setFontFeatureSettings("tnum");text.setMinWidth(Ui.dp(c,115));
        lp=new WindowManager.LayoutParams(-2,-2,WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,PixelFormat.TRANSLUCENT);lp.gravity=Gravity.TOP|Gravity.START;
        android.graphics.Rect initial=DisplayGeometryBounds();lp.x=Math.round(Prefs.getFloat(c,"fps_rx",.04f)*initial.width());lp.y=Math.round(Prefs.getFloat(c,"fps_ry",.12f)*initial.height());
        float[] drag=new float[4];text.setOnTouchListener((v,e)->{if(Prefs.get(c,PINNED,false))return false;switch(e.getActionMasked()){
            case MotionEvent.ACTION_DOWN:drag[0]=e.getRawX();drag[1]=e.getRawY();drag[2]=lp.x;drag[3]=lp.y;return true;
            case MotionEvent.ACTION_MOVE:android.graphics.Rect bounds=DisplayGeometryBounds();lp.x=Math.max(0,Math.min(bounds.width()-text.getWidth(),Math.round(drag[2]+e.getRawX()-drag[0])));lp.y=Math.max(0,Math.min(bounds.height()-text.getHeight(),Math.round(drag[3]+e.getRawY()-drag[1])));try{wm.updateViewLayout(text,lp);}catch(Exception ignored){}return true;
            case MotionEvent.ACTION_UP:android.graphics.Rect size=DisplayGeometryBounds();Prefs.putFloat(c,"fps_rx",lp.x/(float)Math.max(1,size.width()));Prefs.putFloat(c,"fps_ry",lp.y/(float)Math.max(1,size.height()));return true;
        }return true;});
        view=text;style=null;lp.alpha=opacity()/100f;if(Prefs.get(c,PINNED,false))lp.flags|=WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;try{wm.addView(text,lp);refreshAppearance();}catch(Exception e){view=null;return;}
        text.post(()->{if(view!=text)return;android.graphics.Rect size=DisplayGeometryBounds();lp.x=Math.max(0,Math.min(lp.x,size.width()-text.getWidth()));lp.y=Math.max(0,Math.min(lp.y,size.height()-text.getHeight()));try{wm.updateViewLayout(text,lp);}catch(Exception ignored){}});
        subscription=values->{if(view!=text)return;String fps="—",hz="—";for(Telemetry.Metric m:values){if(m.key.equals("fps"))fps=m.value;if(m.key.equals("hz"))hz=m.value;}text.setText(fps+" FPS · "+hz+" Гц");};TelemetryFeed.listen(c,subscription);
    }
    static LinearLayout controls(Context c,Runnable changed){
        LinearLayout body=new LinearLayout(c);body.setOrientation(LinearLayout.VERTICAL);
        body.addView(MiniHud.row(c,"Окно FPS / Гц",R.drawable.ic_monitor,Prefs.get(c,"fps_hud_enabled",false),on->{Prefs.put(c,"fps_hud_enabled",on);changed.run();}));
        TextView value=Ui.text(c,"",12,Ui.MUTED,500);SeekBar slider=new SeekBar(c);slider.setContentDescription("Непрозрачность окна FPS");
        Runnable update=()->{boolean pinned=Prefs.get(c,PINNED,false);int max=pinned?40:100;if(pinned&&Prefs.get(c,MiniHud.PINNED,false)&&Prefs.get(c,MiniHud.ENABLED,false))max=20;
            int opacity=Math.min(max,HudFormat.opacity(Prefs.getInt(c,OPACITY,90),pinned));slider.setMax(max-5);slider.setProgress(opacity-5);value.setText("Непрозрачность: "+opacity+"%");};
        body.addView(MiniHud.row(c,"Закрепить окно",R.drawable.ic_lock,Prefs.get(c,PINNED,false),on->{Prefs.put(c,PINNED,on);update.run();changed.run();}));
        body.addView(MiniHud.row(c,"Подложка окна",R.drawable.ic_layers,Prefs.get(c,BACKGROUND,true),on->{Prefs.put(c,BACKGROUND,on);changed.run();}));
        body.addView(value);body.addView(slider);update.run();
        slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){public void onProgressChanged(SeekBar bar,int progress,boolean user){if(user){Prefs.putInt(c,OPACITY,progress+5);value.setText("Непрозрачность: "+(progress+5)+"%");changed.run();}}public void onStartTrackingTouch(SeekBar bar){}public void onStopTrackingTouch(SeekBar bar){}});
        body.addView(Ui.hint(c,"Перетащите окно и закрепите: касания будут проходить в игру. Снять закрепление можно здесь. Удержание плитки FPS / Гц открывает настройки.",11,Ui.MUTED,500));return body;
    }
    private android.graphics.Rect DisplayGeometryBounds(){android.util.DisplayMetrics m=c.getResources().getDisplayMetrics();return new android.graphics.Rect(0,0,m.widthPixels,m.heightPixels);}
    void hide(){if(subscription!=null){TelemetryFeed.stop(subscription);subscription=null;}TextView old=view;view=null;if(old!=null)try{wm.removeView(old);}catch(Exception ignored){}}
}
