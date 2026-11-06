package ru.xphone.booster;

import java.util.regex.*;

final class DisplayGeometry {
    static int[] size(String output,boolean override){
        if(output==null)return null;
        Matcher m=Pattern.compile((override?"Override":"Physical")+" size:\\s*(\\d+)x(\\d+)").matcher(output);
        if(!m.find())return null;
        try{int w=Integer.parseInt(m.group(1)),h=Integer.parseInt(m.group(2));return w>=240&&h>=240&&w<=16384&&h<=16384?new int[]{w,h}:null;}catch(Exception e){return null;}
    }
    static int density(String output,boolean override){
        if(output==null)return 0;
        Matcher m=Pattern.compile((override?"Override":"Physical")+" density:\\s*(\\d+)").matcher(output);
        if(!m.find())return 0;
        try{int v=Integer.parseInt(m.group(1));return v>=72&&v<=2000?v:0;}catch(Exception e){return 0;}
    }
    static int scaledDensity(int oldWidth,int newWidth,int oldDensity){
        if(oldWidth<=0||newWidth<=0||oldDensity<=0)return 0;
        return Math.max(72,Math.min(2000,Math.round(oldDensity*(newWidth/(float)oldWidth))));
    }
}
