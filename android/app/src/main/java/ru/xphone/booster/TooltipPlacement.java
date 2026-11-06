package ru.xphone.booster;
final class TooltipPlacement {
    static int[] place(int left,int top,int right,int bottom,int anchorLeft,int anchorTop,int anchorRight,int anchorBottom,int width,int height,int gap){
        int w=Math.max(1,Math.min(width,right-left)),h=Math.max(1,Math.min(height,bottom-top));
        int x=Math.max(left,Math.min(right-w,anchorLeft+(anchorRight-anchorLeft-w)/2));
        int y=anchorBottom+gap;
        if(y+h>bottom)y=anchorTop-gap-h;
        y=Math.max(top,Math.min(bottom-h,y));return new int[]{x,y,w,h};
    }
}
