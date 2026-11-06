package ru.xphone.booster;
public class HandleDoubleTapTest {
    static int n;static void check(boolean b){n++;if(!b)throw new AssertionError("check "+n);}
    public static void main(String[] args){HandleDoubleTap taps=new HandleDoubleTap();
        check(!taps.tap(0,10,10,300,30));check(taps.tap(200,12,12,300,30));
        check(!taps.tap(400,12,12,300,30));check(taps.tap(700,12,12,300,30));
        check(!taps.tap(1000,12,12,300,30));check(!taps.tap(1301,12,12,300,30));
        taps.clear();check(!taps.tap(1400,12,12,300,30));
        check(!taps.tap(1500,100,100,300,30));check(taps.tap(1600,100,100,300,30));
        check(!taps.tap(2000,0,0,300,30));check(!taps.tap(1990,0,0,300,30));
        taps.clear();check(!taps.tap(2100,0,0,300,30));taps.clear();check(!taps.tap(2200,0,0,300,30));
        System.out.println("HandleDoubleTap: "+n+" checks passed");
    }
}
