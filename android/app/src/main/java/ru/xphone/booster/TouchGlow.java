package ru.xphone.booster;

import android.content.Context;
import android.graphics.*;
import android.hardware.display.DisplayManager;
import android.os.*;
import android.view.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Draws current contacts only. The transparent window never receives game input. */
final class TouchGlow extends View {
    private final WindowManager wm;
    private final Paint glow=new Paint(Paint.ANTI_ALIAS_FLAG), core=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float radius;
    private volatile float[] points=new float[0];
    private volatile boolean drawing,closed;
    private final AtomicBoolean waking=new AtomicBoolean();
    private final Handler main=new Handler(Looper.getMainLooper());
    TouchGlow(Context context){
        super(context);wm=context.getSystemService(WindowManager.class);
        Ui.load(context);radius=Ui.dp(context,17);
        int accent=Ui.GLASS ? Ui.ACCENT : Color.WHITE;
        glow.setShader(new RadialGradient(0,0,radius,new int[]{0xF2FFFFFF,(accent&0xFFFFFF)|0xB0000000,(accent&0xFFFFFF)|0x38000000,0},new float[]{0,0.17f,0.48f,1},Shader.TileMode.CLAMP));
        core.setColor(Color.WHITE);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }
    void show(){
        WindowManager.LayoutParams lp=new WindowManager.LayoutParams(-1,-1,WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE|WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,PixelFormat.TRANSLUCENT);
        lp.gravity=Gravity.TOP|Gravity.START;
        // Android 12+: an untrusted pass-through window must stay below 0.8 opacity.
        lp.alpha=0.65f;
        if(Build.VERSION.SDK_INT>=30) {lp.layoutInDisplayCutoutMode=WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;lp.setFitInsetsTypes(0);}
        else if(Build.VERSION.SDK_INT>=28)lp.layoutInDisplayCutoutMode=WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        wm.addView(this,lp);
    }
    void points(float[] value){
        if(closed)return;
        points=value==null?new float[0]:value;
        if(!drawing && waking.compareAndSet(false,true))main.post(() -> {
            waking.set(false);if(closed||drawing)return;drawing=true;postOnAnimation(frame);
        });
    }
    private final Runnable frame=new Runnable(){public void run(){
        if(closed)return;
        invalidate();
        if(points.length>0)postOnAnimation(this);else drawing=false;
    }};
    @Override protected void onDraw(Canvas canvas){
        super.onDraw(canvas);
        float[] current=points;
        android.view.Display display=getContext().getSystemService(DisplayManager.class).getDisplay(Display.DEFAULT_DISPLAY);
        int rotation=display==null?0:display.getRotation();
        float shimmer=1f+0.045f*(float)Math.sin(SystemClock.uptimeMillis()*0.0045);
        for(int i=0;i+1<current.length;i+=2){
            float[] xy=TouchState.rotate(current[i],current[i+1],rotation);
            canvas.save();canvas.translate(xy[0]*getWidth(),xy[1]*getHeight());
            canvas.scale(shimmer,shimmer);canvas.drawCircle(0,0,radius,glow);
            canvas.drawCircle(0,0,Ui.dp(getContext(),2),core);canvas.restore();
        }
    }
    void close(){
        closed=true;points=new float[0];removeCallbacks(frame);
        try{wm.removeView(this);}catch(Exception ignored){}
    }
}
