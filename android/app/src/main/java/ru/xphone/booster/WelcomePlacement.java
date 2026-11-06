package ru.xphone.booster;
/** Center the greeting along the handle, placing it toward the inside of the display. */
final class WelcomePlacement {
    static int[] place(int sw,int sh,int ax,int ay,int aw,int ah,int edge,int width,int height,int margin,int gap){
        int w=Math.max(1,Math.min(width,Math.max(1,sw-2*margin))),h=Math.max(1,Math.min(height,Math.max(1,sh-2*margin)));
        int x=ax+(aw-w)/2,y=ay+(ah-h)/2;
        if(edge==HandleGeometry.LEFT)x=ax+aw+gap;
        else if(edge==HandleGeometry.RIGHT)x=ax-gap-w;
        else if(edge==HandleGeometry.TOP)y=ay+ah+gap;
        else y=ay-gap-h;
        x=HandleGeometry.clamp(x,margin,sw-margin-w);y=HandleGeometry.clamp(y,margin,sh-margin-h);
        return new int[]{x,y,w,h};
    }
}
