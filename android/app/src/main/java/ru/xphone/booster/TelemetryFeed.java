package ru.xphone.booster;

import android.content.Context;
import android.os.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** One sampler shared by the panel header and the independent mini-window. UI-thread subscriptions. */
final class TelemetryFeed {
    private static final Handler MAIN=new Handler(Looper.getMainLooper());
    private static final Set<Consumer<List<Telemetry.Metric>>> listeners=new LinkedHashSet<>();
    private static ScheduledExecutorService worker;
    private static List<Telemetry.Metric> latest=Collections.emptyList();
    private static int generation;
    private static long sampledAt;
    static void listen(Context c,Consumer<List<Telemetry.Metric>> listener){
        listeners.add(listener);listener.accept(SystemClock.elapsedRealtime()-sampledAt<3000?latest:Telemetry.quick(c));
        if(worker!=null)return;
        Context app=c.getApplicationContext();Telemetry sampler=new Telemetry();int token=++generation;
        worker=Executors.newSingleThreadScheduledExecutor();
        worker.scheduleAtFixedRate(()->{
            if(!app.getSystemService(PowerManager.class).isInteractive())return;
            try{List<Telemetry.Metric> values=sampler.sampleAll(app);MAIN.post(()->{
                if(token!=generation)return;sampledAt=SystemClock.elapsedRealtime();latest=Collections.unmodifiableList(new ArrayList<>(values));
                for(Consumer<List<Telemetry.Metric>> subscriber:new ArrayList<>(listeners))subscriber.accept(latest);
            });}catch(Exception e){android.util.Log.w("XPhone","Telemetry unavailable",e);}
        },0,2,TimeUnit.SECONDS);
    }
    static void stop(Consumer<List<Telemetry.Metric>> listener){
        listeners.remove(listener);if(!listeners.isEmpty())return;
        generation++;if(worker!=null){worker.shutdownNow();worker=null;}
    }
}
