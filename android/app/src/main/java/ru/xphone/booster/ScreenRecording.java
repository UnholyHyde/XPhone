package ru.xphone.booster;

import java.io.*;
import java.util.concurrent.TimeUnit;

/** Shell-service owned, user-started video-only recording. Never kills unrelated processes. */
final class ScreenRecording {
    private Process process;
    private File output;
    private int ownedPid;
    private String last="IDLE";
    synchronized String state(){return process!=null&&process.isAlive()?"RUNNING":last;}
    synchronized String start(String filename,int mbps){
        if(android.os.Build.VERSION.SDK_INT<33)return "ERROR";
        if(process!=null&&process.isAlive())return "RUNNING";
        if(!RecordingPolicy.valid(filename,mbps))return "ERROR";
        try{
            File folder=new File("/sdcard/Movies/XPhone");if(!folder.isDirectory()&&!folder.mkdirs())return "ERROR";
            if(new android.os.StatFs(folder.getPath()).getAvailableBytes()<64L*1024*1024)return "NO_SPACE";
            output=new File(folder,filename);if(output.exists())return "ERROR";
            // All interpolated arguments are validated above; exec retains this shell's PID.
            Process running=new ProcessBuilder("sh","-c","echo $$; exec /system/bin/screenrecord --bit-rate "+(mbps*1000000)+" --time-limit 0 "+output.getAbsolutePath()+" > /data/local/tmp/xphone-record.log 2>&1").redirectErrorStream(true).start();
            process=running;
            try(java.io.BufferedReader reader=new java.io.BufferedReader(new java.io.InputStreamReader(running.getInputStream()))){ownedPid=Integer.parseInt(reader.readLine().trim());}
            if(ownedPid<=0){running.destroy();return "ERROR";}
            process=running;
            if(running.waitFor(350,TimeUnit.MILLISECONDS)){process=null;last="ERROR";return last;}
            last="RUNNING";File file=output;
            new Thread(()->{try{int result=running.waitFor();synchronized(this){if(process==running){process=null;last=(result==0&&file.length()>0)?"SAVED:"+file.getAbsolutePath():"ERROR";}}scan(file);}catch(InterruptedException e){Thread.currentThread().interrupt();}},"xphone-record").start();
            return "RUNNING";
        }catch(Exception e){if(process!=null&&process.isAlive())process.destroy();process=null;last="ERROR";return last;}
    }
    String stop(){
        Process running;File file;int pid;synchronized(this){running=process;file=output;pid=ownedPid;if(running==null)return last;}
        try{
            if(running.isAlive()&&pid>0)android.system.Os.kill(pid,android.system.OsConstants.SIGINT);
            if(!running.waitFor(10,TimeUnit.SECONDS))return "STOPPING";
            String result=running.exitValue()==0&&file!=null&&file.length()>0?"SAVED:"+file.getAbsolutePath():"ERROR";
            synchronized(this){if(process==running)process=null;last=result;}
            if(file!=null)scan(file);return result;
        }catch(Exception e){return "ERROR";}
    }
    private static void scan(File file){
        if(file.length()==0)return;
        try{new ProcessBuilder("am","broadcast","-a","android.intent.action.MEDIA_SCANNER_SCAN_FILE","-d","file://"+file.getAbsolutePath()).redirectErrorStream(true).redirectOutput(new File("/dev/null")).start().waitFor(3,TimeUnit.SECONDS);}catch(Exception ignored){}
    }
}
