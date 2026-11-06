package ru.xphone.booster;

/** Bounded sidecar placement in physical screen coordinates. */
final class PopupPlacement {
    private PopupPlacement(){}
    static int[] place(int sw,int sh,int panelLeft,int panelRight,int anchorTop,int width,int height,int gap){
        int w=Math.max(1,Math.min(width,Math.max(1,sw-2*gap)));
        int h=Math.max(1,Math.min(height,Math.max(1,sh-2*gap)));
        int right=panelRight+gap,left=panelLeft-gap-w,x;
        if(right+w<=sw-gap)x=right;
        else if(left>=gap)x=left;
        else x=panelLeft>sw/2?gap:sw-gap-w;
        x=Math.max(0,Math.min(Math.max(0,sw-w),x));
        int y=Math.max(gap,Math.min(Math.max(gap,sh-gap-h),anchorTop));
        return new int[]{x,y,w,h};
    }
}
