package ru.xphone.booster;
import android.os.*;
import java.lang.reflect.*;
import java.util.concurrent.*;
/** Own Binder token and expiring lease; never changes shared shell disable flags. */
final class StreamPrivacyBridge {
    private final IBinder token=new Binder();private Object service;private Method disable;private int flags, expandFlag;private long renewed;private ScheduledExecutorService watchdog;
    synchronized boolean available(){try{
        if(service!=null&&disable!=null&&flags!=0)return true;
        IBinder binder=(IBinder)Class.forName("android.os.ServiceManager").getMethod("getService",String.class).invoke(null,"statusbar");
        Class<?> type=Class.forName("com.android.internal.statusbar.IStatusBarService");
        service=Class.forName("com.android.internal.statusbar.IStatusBarService$Stub").getMethod("asInterface",IBinder.class).invoke(null,binder);
        disable=type.getMethod("disableForUser",int.class,IBinder.class,String.class,int.class);
        Class<?> manager=Class.forName("android.app.StatusBarManager");
        expandFlag=manager.getField("DISABLE_EXPAND").getInt(null);
        flags=manager.getField("DISABLE_NOTIFICATION_ALERTS").getInt(null)|manager.getField("DISABLE_NOTIFICATION_ICONS").getInt(null);
        return service!=null;
    }catch(Exception e){service=null;return false;}}
    synchronized String set(boolean active,int user,boolean lockShade){
        if(user<0||user>9999||!available())return "UNSUPPORTED";
        try{disable.invoke(service,active?flags|(lockShade?expandFlag:0):0,token,"com.android.shell",user);
            renewed=SystemClock.elapsedRealtime();
            if(active&&watchdog==null){watchdog=Executors.newSingleThreadScheduledExecutor();watchdog.scheduleAtFixedRate(()->{synchronized(this){if(SystemClock.elapsedRealtime()-renewed>8000)set(false,user,false);}},2,2,TimeUnit.SECONDS);}
            if(!active&&watchdog!=null){watchdog.shutdownNow();watchdog=null;}
            return active?"PROTECTED":"RESTORED";
        }catch(Exception e){return "DENIED";}
    }
}
