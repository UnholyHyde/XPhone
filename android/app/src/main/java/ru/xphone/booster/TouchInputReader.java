package ru.xphone.booster;

import java.io.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.*;

/** Reads only the direct multitouch screen. No keyboard, no grabbing/injection, no disk log. */
final class TouchInputReader {
    private Process process;
    private volatile boolean stopped;
    private Thread reader;
    private ITouchListener listener;
    private android.os.IBinder.DeathRecipient death;
    static final class Device { String path; int minX,maxX,minY,maxY; }
    static Device findDevice() {
        Process p=null;
        try {
            p=new ProcessBuilder("/system/bin/getevent","-lp").redirectErrorStream(true).start();
            StringBuilder text=new StringBuilder();
            try(BufferedReader r=new BufferedReader(new InputStreamReader(p.getInputStream()))){String line;while((line=r.readLine())!=null)text.append(line).append('\n');}
            p.waitFor(2,TimeUnit.SECONDS);
            return parseDevice(text.toString());
        }catch(Exception e){return null;}finally{if(p!=null)p.destroy();}
    }
    static Device parseDevice(String listing){
        for(String section:listing.split("add device ")){
            if(!section.contains("INPUT_PROP_DIRECT")||!section.contains("ABS_MT_SLOT")||!section.contains("ABS_MT_TRACKING_ID"))continue;
            Matcher path=Pattern.compile("/dev/input/event[0-9]+").matcher(section);
            Matcher x=Pattern.compile("ABS_MT_POSITION_X[^\\n]*min (-?[0-9]+), max ([0-9]+)").matcher(section);
            Matcher y=Pattern.compile("ABS_MT_POSITION_Y[^\\n]*min (-?[0-9]+), max ([0-9]+)").matcher(section);
            if(!path.find()||!x.find()||!y.find())continue;
            Device d=new Device();d.path=path.group();d.minX=Integer.parseInt(x.group(1));d.maxX=Integer.parseInt(x.group(2));d.minY=Integer.parseInt(y.group(1));d.maxY=Integer.parseInt(y.group(2));
            if(d.maxX>d.minX&&d.maxY>d.minY)return d;
        }
        return null;
    }
    synchronized boolean start(ITouchListener callback){
        stop();Device d=findDevice();if(d==null||callback==null)return false;
        try{
            stopped=false;listener=callback;
            process=new ProcessBuilder("/system/bin/getevent","-lt",d.path).redirectErrorStream(true).start();
            if(process.waitFor(100,TimeUnit.MILLISECONDS)){stop();return false;}
            death=this::stop;callback.asBinder().linkToDeath(death,0);
            final Process capture=process;
            reader=new Thread(() -> {
                TouchState state=new TouchState(d.minX,d.maxX,d.minY,d.maxY);
                try(BufferedReader in=new BufferedReader(new InputStreamReader(capture.getInputStream()))){
                    String line;
                    while(!stopped&&(line=in.readLine())!=null)if(state.accept(line))callback.onPoints(state.points());
                }catch(Exception ignored){}
                finally{try{callback.onPoints(new float[0]);callback.onStopped();}catch(Exception ignored){} capture.destroy();}
            },"xphone-touchscreen");reader.setDaemon(true);reader.start();return true;
        }catch(Exception e){stop();return false;}
    }
    synchronized void stop(){
        stopped=true;
        if(listener!=null&&death!=null)try{listener.asBinder().unlinkToDeath(death,0);}catch(Exception ignored){}
        listener=null;death=null;
        if(process!=null){process.destroy();process=null;}
        if(reader!=null){reader.interrupt();reader=null;}
    }
}
