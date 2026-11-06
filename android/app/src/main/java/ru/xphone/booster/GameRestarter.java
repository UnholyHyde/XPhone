package ru.xphone.booster;

import android.content.ComponentName;
import java.util.concurrent.TimeUnit;

/** Runs only in the Shizuku service; argv is never interpreted as shell code. */
final class GameRestarter {
    static String restart(String component,int user){
        ComponentName target=component==null?null:ComponentName.unflattenFromString(component);
        if(target==null||!CompanionPolicy.packageName(target.getPackageName())||user<0||user>9999)return "DENIED";
        try{
            if(!run("am","force-stop","--user",Integer.toString(user),target.getPackageName()))return "ERROR";
            return run("am","start","--user",Integer.toString(user),"-n",target.flattenToString())?"STARTED":"ERROR";
        }catch(Exception e){return "ERROR";}
    }
    private static boolean run(String...args)throws Exception{
        Process p=new ProcessBuilder(args).redirectErrorStream(true).start();
        if(!p.waitFor(8,TimeUnit.SECONDS)){p.destroy();return false;}
        java.io.ByteArrayOutputStream bytes=new java.io.ByteArrayOutputStream();
        try(java.io.InputStream in=p.getInputStream()){byte[] buffer=new byte[2048];int count;while((count=in.read(buffer))>0)bytes.write(buffer,0,count);}
        String output=bytes.toString("UTF-8");
        return p.exitValue()==0&&!output.contains("Error:")&&!output.contains("Exception");
    }
}
