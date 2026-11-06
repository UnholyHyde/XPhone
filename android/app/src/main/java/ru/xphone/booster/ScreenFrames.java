package ru.xphone.booster;
import android.content.Context;
import android.hardware.display.DisplayManager;
import android.view.Display;
import android.os.SystemClock;
import java.util.*;
final class ScreenFrames {
    static final String COMMAND="dumpsys SurfaceFlinger --timestats -dump | head -14";
    private final FrameCounter counter=new FrameCounter();
    static boolean available(Context c){return Shell.ready(c)&&Prefs.get(c,"screen_frames_available",false);}
    static void probe(Context c){if(Shell.ready(c))Prefs.put(c,"screen_frames_available",FrameCounter.number(Shell.run(c,COMMAND),"totalFrames")>=0);}
    void sample(Context c,List<Telemetry.Metric> out){
        Display d=c.getSystemService(DisplayManager.class).getDisplay(Display.DEFAULT_DISPLAY);
        if(d!=null){String hz=Math.round(d.getRefreshRate())+"";out.add(new Telemetry.Metric("hz",hz,"Текущая частота дисплея: "+hz+" Гц"));}
        if(!available(c))return;
        double fps=counter.sample(Shell.run(c,COMMAND),SystemClock.elapsedRealtime());
        String value=Double.isNaN(fps)?"—":String.format(Locale.ROOT,"%.0f",fps);
        out.add(new Telemetry.Metric("fps",value,Double.isNaN(fps)?"Ожидаем два замера счётчика кадров":"Кадров композиции экрана в секунду: "+value));
    }
}
