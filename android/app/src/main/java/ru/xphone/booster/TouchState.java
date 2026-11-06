package ru.xphone.booster;

import java.util.Arrays;
import java.util.regex.*;

/** Linux multitouch type B: keep only current contacts, never their history. */
final class TouchState {
    private static final Pattern EVENT=Pattern.compile("\\b(EV_ABS|EV_SYN|EV_KEY)\\s+(\\w+)\\s+(\\w+)");
    private final boolean[] active=new boolean[32], hasX=new boolean[32], hasY=new boolean[32];
    private final int[] x=new int[32],y=new int[32];
    private final int minX,maxX,minY,maxY;
    private int slot;
    TouchState(int minX,int maxX,int minY,int maxY) { this.minX=minX;this.maxX=maxX;this.minY=minY;this.maxY=maxY; }
    /** true on a complete frame. */
    boolean accept(String line) {
        Matcher m=EVENT.matcher(line);
        if(!m.find())return false;
        String code=m.group(2),value=m.group(3);
        int v;
        try { v=(int)Long.parseLong(value,16); } catch(NumberFormatException e) { v=0; }
        switch(code) {
            case "ABS_MT_SLOT": slot=v; break;
            case "ABS_MT_TRACKING_ID":
                if(valid()) { active[slot]=v!=-1; hasX[slot]=false;hasY[slot]=false; } break;
            case "ABS_MT_POSITION_X": if(valid()){x[slot]=v;hasX[slot]=true;} break;
            case "ABS_MT_POSITION_Y": if(valid()){y[slot]=v;hasY[slot]=true;} break;
            case "BTN_TOUCH": if(value.equals("UP") || value.equals("00000000")) clear(); break;
            case "SYN_DROPPED": clear(); return true;
            case "SYN_REPORT": return true;
        }
        return false;
    }
    private boolean valid(){return slot>=0 && slot<active.length;}
    private void clear(){Arrays.fill(active,false);Arrays.fill(hasX,false);Arrays.fill(hasY,false);}
    float[] points(){
        float[] result=new float[64];int n=0;
        for(int i=0;i<32;i++)if(active[i]&&hasX[i]&&hasY[i]){
            result[n++]=Math.max(0,Math.min(1,(x[i]-minX)/(float)(maxX-minX)));
            result[n++]=Math.max(0,Math.min(1,(y[i]-minY)/(float)(maxY-minY)));
        }
        return Arrays.copyOf(result,n);
    }
    /** Transform natural touchscreen coordinates to current display rotation. */
    static float[] rotate(float u,float v,int rotation){
        switch(rotation){case 1:return new float[]{v,1-u};case 2:return new float[]{1-u,1-v};case 3:return new float[]{1-v,u};default:return new float[]{u,v};}
    }
}
