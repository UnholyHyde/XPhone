package ru.xphone.booster;
/** A second completed tap opens; dragging/holding cancels the pending first tap. */
final class HandleDoubleTap {
    private long last=-1;private float x,y;
    void clear(){last=-1;}
    boolean tap(long now,float px,float py,long timeout,float slop){
        boolean doubleTap=last>=0&&now>=last&&now-last<=timeout&&Math.hypot(px-x,py-y)<=slop;
        if(doubleTap){clear();return true;}
        last=now;x=px;y=py;return false;
    }
}
