package ru.xphone.booster;

import android.app.ActivityManager;
import android.content.ComponentName;
import android.graphics.Rect;
import android.os.Build;
import java.lang.reflect.*;
import java.util.*;
import java.io.*;

/** Вызывается только в Shizuku UserService. Меняет лишь задачи, открытые этим экземпляром. */
final class WindowTaskBridge {
    private final Map<String,Integer> owned=new HashMap<>();
    private Object manager() throws Exception {return Class.forName("android.app.ActivityTaskManager").getMethod("getService").invoke(null);}
    private Method getter() throws Exception {
        for(Method m:Class.forName("android.app.IActivityTaskManager").getMethods()) {
            if(!m.getName().equals("getTasks"))continue;
            Class<?>[] p=m.getParameterTypes();
            if(p.length<1||p.length>4||p[0]!=int.class)continue;
            boolean ok=true;for(int i=1;i<p.length;i++)if(p[i]!=(i==3?int.class:boolean.class))ok=false;
            if(ok)return m;
        }
        throw new NoSuchMethodException("getTasks");
    }
    @SuppressWarnings("unchecked") private List<ActivityManager.RunningTaskInfo> tasks() throws Exception {
        Method m=getter();Object[] args=new Object[m.getParameterCount()];args[0]=100;
        for(int i=1;i<args.length;i++)args[i]=i==3?Integer.valueOf(0):Boolean.FALSE;
        return (List<ActivityManager.RunningTaskInfo>)m.invoke(manager(),args);
    }
    private int user(ActivityManager.RunningTaskInfo t)throws Exception{return t.getClass().getField("userId").getInt(t);}
    private int mode(ActivityManager.RunningTaskInfo t)throws Exception {
        Object cfg=t.getClass().getField("configuration").get(t);
        Object win=cfg.getClass().getField("windowConfiguration").get(cfg);
        return (Integer)win.getClass().getMethod("getWindowingMode").invoke(win);
    }
    private boolean visible(ActivityManager.RunningTaskInfo t)throws Exception{return t.getClass().getField("isVisible").getBoolean(t);}
    private String pkg(ActivityManager.RunningTaskInfo t){return t.baseActivity==null?null:t.baseActivity.getPackageName();}
    private ActivityManager.RunningTaskInfo find(String pkg,int uid)throws Exception {
        for(ActivityManager.RunningTaskInfo t:tasks())if(pkg.equals(pkg(t))&&user(t)==uid)return t;
        return null;
    }
    private void reorder(ActivityManager.RunningTaskInfo t,boolean top)throws Exception {
        Class<?> tx=Class.forName("android.window.WindowContainerTransaction");
        Class<?> token=Class.forName("android.window.WindowContainerToken");
        Object transaction=tx.getConstructor().newInstance();
        tx.getMethod("reorder",token,boolean.class).invoke(transaction,t.getClass().getField("token").get(t),top);
        Class<?> organizer=Class.forName("android.window.WindowOrganizer");
        organizer.getMethod("applyTransaction",tx).invoke(organizer.getConstructor().newInstance(),transaction);
    }
    synchronized boolean available() {
        if(Build.VERSION.SDK_INT<30)return false;
        try {
            getter(); tasks();
            Class<?> tx=Class.forName("android.window.WindowContainerTransaction"), token=Class.forName("android.window.WindowContainerToken");
            tx.getMethod("reorder",token,boolean.class);
            Class.forName("android.window.WindowOrganizer").getMethod("applyTransaction",tx);
            return true;
        }catch(Exception e){return false;}
    }
    private String key(String pkg,int user){return user+":"+pkg;}
    synchronized String open(String component,int[] bounds,int userId) {
        if(Build.VERSION.SDK_INT<30)return "DENIED";
        ComponentName c=ComponentName.unflattenFromString(component==null?"":component);
        if(c==null||!CompanionPolicy.packageName(c.getPackageName())||!c.getClassName().matches("[A-Za-z0-9_.$]+")||userId<0||userId>9999)return "INVALID";
        if(bounds==null||bounds.length!=4||bounds[0]<0||bounds[1]<0||bounds[2]-bounds[0]<96||bounds[3]-bounds[1]<96||bounds[2]>16384||bounds[3]>16384)return "INVALID";
        int previous=-1;
        try {
            ActivityManager.RunningTaskInfo existing=find(c.getPackageName(),userId);
            Integer ownedId=owned.get(key(c.getPackageName(),userId));
            if(existing!=null&&ownedId!=null&&existing.taskId==ownedId&&mode(existing)==5) {
                // Не отправляем launcher Intent повторно: сохраняем открытый чат/документ.
                reorder(existing,true);
                for(int n=0;n<5;n++){Thread.sleep(150);String status=state(c.getPackageName(),userId);if("OPEN".equals(status))return status;}
                return "DENIED";
            }
            for(ActivityManager.RunningTaskInfo t:tasks())if(user(t)==userId&&visible(t)){previous=t.taskId;break;}
            Process p=new ProcessBuilder("am","start","--user",String.valueOf(userId),"--windowingMode","5","-n",c.flattenToString())
                    .redirectErrorStream(true).redirectOutput(new File("/dev/null")).start();
            if(!p.waitFor(5,java.util.concurrent.TimeUnit.SECONDS)){p.destroyForcibly();return "DENIED";}
            if(p.exitValue()!=0)return "DENIED";
            ActivityManager.RunningTaskInfo task=null;
            for(int n=0;n<6;n++){task=find(c.getPackageName(),userId);if(task!=null&&mode(task)==5)break;Thread.sleep(200);}
            if(task==null||mode(task)!=5) {
                if(previous>=0)Class.forName("android.app.IActivityTaskManager").getMethod("setFocusedTask",int.class).invoke(manager(),previous);
                return "NOT_FREEFORM";
            }
            owned.put(key(c.getPackageName(),userId),task.taskId);
            try {Class.forName("android.app.IActivityTaskManager").getMethod("resizeTask",int.class,Rect.class,int.class)
                    .invoke(manager(),task.taskId,new Rect(bounds[0],bounds[1],bounds[2],bounds[3]),0);}catch(Exception ignored){}
            reorder(task,true);
            return "OPEN";
        }catch(Exception e){return "DENIED";}
    }
    synchronized String resumeGame(String component,int userId){
        ComponentName launch=ComponentName.unflattenFromString(component==null?"":component);
        if(Build.VERSION.SDK_INT<30||launch==null||!CompanionPolicy.packageName(launch.getPackageName())||userId<0||userId>9999)return "UNAVAILABLE";
        try{List<ActivityManager.RunningTaskInfo> running=tasks();List<GameTaskChoice.Task> candidates=new ArrayList<>();
            for(ActivityManager.RunningTaskInfo task:running)candidates.add(new GameTaskChoice.Task(task.taskId,user(task),task.baseActivity==null?null:task.baseActivity.getPackageName(),task.topActivity==null?null:task.topActivity.getPackageName(),task.topActivity==null?null:task.topActivity.getClassName()));
            int chosen=GameTaskChoice.choose(launch.getPackageName(),launch.getClassName(),userId,candidates);
            for(ActivityManager.RunningTaskInfo task:running)if(task.taskId==chosen){reorder(task,true);return "RESUMED";}
            return "NOT_RUNNING";
        }catch(Exception e){return "UNAVAILABLE";}
    }
    synchronized String state(String pkg,int userId) {
        if(Build.VERSION.SDK_INT<30)return "GONE";
        if(!CompanionPolicy.packageName(pkg))return "GONE";
        try {
            ActivityManager.RunningTaskInfo t=find(pkg,userId);
            Integer id=owned.get(key(pkg,userId));
            if(t==null||id==null||t.taskId!=id)return "GONE";
            if(mode(t)!=5)return "FULLSCREEN";
            return visible(t)?"OPEN":"MINIMIZED";
        }catch(Exception e){return "UNKNOWN";}
    }
    synchronized String minimize(String pkg,int userId) {
        if(Build.VERSION.SDK_INT<30)return "DENIED";
        try {
            ActivityManager.RunningTaskInfo t=find(pkg,userId);
            Integer id=owned.get(key(pkg,userId));
            if(t==null||id==null||t.taskId!=id||mode(t)!=5)return "GONE";
            reorder(t,false);
            for(int n=0;n<5;n++){Thread.sleep(150);String state=state(pkg,userId);if(!state.equals("OPEN"))return state;}
            return "CANNOT_MINIMIZE";
        }catch(Exception e){return "DENIED";}
    }
    synchronized void end() {
        if(Build.VERSION.SDK_INT<30){owned.clear();return;}
        try {for(ActivityManager.RunningTaskInfo t:tasks())if(Objects.equals(owned.get(key(pkg(t),user(t))),t.taskId)&&mode(t)==5)try{reorder(t,false);}catch(Exception ignored){}}
        catch(Exception ignored){}finally{owned.clear();}
    }
}
