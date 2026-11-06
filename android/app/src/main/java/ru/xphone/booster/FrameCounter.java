package ru.xphone.booster;
import java.util.regex.*;
/** Actual SurfaceFlinger composition deltas; never substitutes display Hz for FPS. */
final class FrameCounter {
    private long previous=-1,at,epoch=-1;
    static long number(String text,String key){if(text==null)return -1;Matcher m=Pattern.compile("(?m)^"+key+" = ([0-9]+)\\s*$").matcher(text);try{return m.find()?Long.parseLong(m.group(1)):-1;}catch(NumberFormatException e){return -1;}}
    double sample(String dump,long now){
        long count=number(dump,"totalFrames"),start=number(dump,"statsStart");
        double result=Double.NaN;
        if(count>=0&&start>=0&&start==epoch&&previous>=0&&count>=previous&&now>at&&now-at<=6000)result=(count-previous)*1000.0/(now-at);
        previous=count;epoch=start;at=now;return result;
    }
}
