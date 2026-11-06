package ru.xphone.booster;

final class CompanionGeometry {
    static int[] window(int w,int h) {
        int margin=Math.max(12,Math.min(w,h)/24);
        int width=Math.max(96,Math.min(w-2*margin,w>h?w/3:w*3/4));
        int height=Math.max(96,Math.min(h-2*margin,h*3/4));
        int x=Math.max(0,w-margin-width), y=Math.max(0,(h-height)/2);
        return new int[]{x,y,Math.min(w,x+width),Math.min(h,y+height)};
    }
    static int clamp(int pos,int screen,int size){return Math.max(0,Math.min(Math.max(0,screen-size),pos));}
}
