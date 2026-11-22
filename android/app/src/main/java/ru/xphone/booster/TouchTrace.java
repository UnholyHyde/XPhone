package ru.xphone.booster;

import android.content.Context;
import android.content.SharedPreferences;
import android.provider.Settings;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Custom glow dots; system pointer diagnostics stay off while this mode is active. */
public final class TouchTrace {
    private TouchTrace() {}
    private static final String[] KEYS={"show_touches","pointer_location"};
    private static volatile TouchGlow glow;
    private static volatile boolean running;
    private static final java.util.concurrent.atomic.AtomicInteger generation=new java.util.concurrent.atomic.AtomicInteger();
    public static boolean available(Context c){return Shell.ready(c) && Prefs.get(c,"glow_supported",true);}
    public static boolean enabled(Context c){return running;}
    static float[] recordingPoints() {
        TouchGlow view = glow;
        return view == null ? NO_POINTS : view.currentPoints();
    }
    private static final float[] NO_POINTS = new float[0];
    public static void probe(Context c){if(Shell.ready(c))Prefs.put(c,"glow_supported",Shell.touchAvailable(c));}
    /** Called on Shell.background, never on the UI thread. */
    public static boolean set(Context c,boolean on){
        if(!on)return restore(c);
        if(BoostService.hubOnScreen())return false;
        stop();
        final int token=generation.get();
        if(!available(c))return false;
        SharedPreferences p=c.getSharedPreferences("touch_originals",Context.MODE_PRIVATE);
        if(!p.contains(KEYS[0])){
            SharedPreferences.Editor e=p.edit();
            for(String k:KEYS)e.putInt(k,Settings.System.getInt(c.getContentResolver(),k,0));
            if(!e.commit())return false;
        }
        // Remove Android's top coordinate bar, lines and large touch indicators.
        for(String k:KEYS)write(c,k,0);
        for(String k:KEYS)if(Settings.System.getInt(c.getContentResolver(),k,0)!=0)return false;
        CountDownLatch created=new CountDownLatch(1);
        AtomicBoolean cancelled=new AtomicBoolean();
        AtomicReference<TouchGlow> result=new AtomicReference<>();
        Shell.onMain(() -> {
            try {if(!cancelled.get() && generation.get()==token){TouchGlow v=new TouchGlow(c.getApplicationContext());v.show();if(cancelled.get()||generation.get()!=token)v.close();else result.set(v);}}
            catch(Exception e){android.util.Log.w("XPhone","Cannot show touch glow",e);}
            finally{created.countDown();}
        });
        try{if(!created.await(3,TimeUnit.SECONDS))cancelled.set(true);}catch(InterruptedException e){cancelled.set(true);Thread.currentThread().interrupt();}
        final TouchGlow v=result.get();
        if(cancelled.get()||generation.get()!=token||v==null){if(v!=null)Shell.onMain(v::close);return false;}
        synchronized(TouchTrace.class){
            if(generation.get()!=token){Shell.onMain(v::close);return false;}
            glow=v;
        }
        boolean ok=Shell.startTouch(c,new ITouchListener.Stub(){
            @Override public void onPoints(float[] points){if(glow==v)v.points(points);}
            @Override public void onStopped(){if(glow==v){running=false;glow=null;Shell.onMain(v::close);}}
        });
        synchronized(TouchTrace.class){
            if(ok && generation.get()==token && glow==v){running=true;return true;}
        }
        stop();return false;
    }
    /** Stops input monitoring immediately, including when the game/overlay is hidden. */
    public static void stop(){
        TouchGlow old;
        synchronized(TouchTrace.class){generation.incrementAndGet();running=false;old=glow;glow=null;}
        Shell.stopTouch();
        if(old!=null)Shell.onMain(old::close);
    }
    public static boolean restore(Context c){
        stop();
        SharedPreferences p=c.getSharedPreferences("touch_originals",Context.MODE_PRIVATE);
        if(!p.contains(KEYS[0]))return true;
        boolean ok=true;
        for(String k:KEYS){int v=p.getInt(k,0);write(c,k,v);ok &= Settings.System.getInt(c.getContentResolver(),k,-1)==v;}
        if(ok)p.edit().clear().commit();
        return ok;
    }
    private static void write(Context c,String k,int v){
        try{
            if(Shell.ready(c))Shell.run(c,"settings put system "+k+" "+v);
            else if(Settings.System.canWrite(c))Settings.System.putInt(c.getContentResolver(),k,v);
        }catch(Exception e){android.util.Log.w("XPhone","Cannot restore "+k,e);}
    }
}
