package ru.xphone.booster;
/** Screen-pixel geometry for a floating handle, independent of Android views. */
final class HandleGeometry {
    static final int LEFT=0,RIGHT=1,TOP=2,BOTTOM=3;
    static int clamp(int n,int min,int max){return Math.max(min,Math.min(Math.max(min,max),n));}
    static int[] place(int width,int height,int l,int t,int r,int b,int gap,int thin,int length,int edge,float ratio){
        int x0=clamp(l+gap,0,Math.max(0,width-1)),y0=clamp(t+gap,0,Math.max(0,height-1));
        int x1=Math.max(x0+1,width-r-gap),y1=Math.max(y0+1,height-b-gap);
        boolean vertical=edge<2;int w=Math.min(vertical?thin:length,x1-x0),h=Math.min(vertical?length:thin,y1-y0);
        float position=Float.isNaN(ratio)?.38f:Math.max(0,Math.min(1,ratio));
        int x=vertical?(edge==LEFT?x0:x1-w):x0+Math.round((x1-x0-w)*position);
        int y=vertical?y0+Math.round((y1-y0-h)*position):(edge==TOP?y0:y1-h);
        return new int[]{x,y,w,h};
    }
    static int nearest(float x,float y,int width,int height,int l,int t,int r,int b){float[] distance={Math.abs(x-l),Math.abs(width-r-x),Math.abs(y-t),Math.abs(height-b-y)};int edge=0;for(int i=1;i<4;i++)if(distance[i]<distance[edge])edge=i;return edge;}
    static boolean inward(int edge,float dx,float dy,float threshold){float forward=edge==LEFT?dx:edge==RIGHT?-dx:edge==TOP?dy:-dy;float across=edge<2?Math.abs(dy):Math.abs(dx);return forward>=threshold&&forward>across*1.25f;}
    static float ratio(float center,int size,int before,int after,int gap,int handle){int start=before+gap,room=size-before-after-gap*2-handle;return room<=0?.5f:Math.max(0,Math.min(1,(center-handle/2f-start)/room));}
}
