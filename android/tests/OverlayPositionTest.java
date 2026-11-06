package ru.xphone.booster;

/** Run with javac/java; no phone or test dependencies required. */
public final class OverlayPositionTest {
    public static void main(String[] args) {
        check(3120,360,0.565f,1763);
        check(1440,360,0.565f,814);
        check(1440,360,1.5f,1080);
        check(1440,360,-1f,0);
        check(200,360,0.5f,0);
        check(1440,360,Float.NaN,547);
        check(1440,360,Float.POSITIVE_INFINITY,547);
        System.out.println("OverlayPosition: 7 checks passed");
    }
    private static void check(int screen,int handle,float fraction,int expected) {
        int actual=OverlayPosition.y(fraction,screen,handle);
        if(actual!=expected) throw new AssertionError("screen="+screen+" fraction="+fraction+" actual="+actual+" expected="+expected);
    }
}
