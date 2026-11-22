package ru.xphone.booster;

/** Parser tests: multiple fingers, releases, frame boundaries, rotation. */
public final class TouchStateTest {
    private static int checks;
    public static void main(String[] args) {
        TouchState s=new TouchState(0,4095,0,4095);
        event(s,"ABS_MT_TRACKING_ID","00000001");
        event(s,"ABS_MT_POSITION_X","00000fff");
        event(s,"ABS_MT_POSITION_Y","00000000");
        check(s.accept("[1.0] EV_SYN SYN_REPORT 00000000"),"frame boundary");
        check(s.points().length==2,"one finger");
        near(s.points()[0],1); near(s.points()[1],0);
        event(s,"ABS_MT_SLOT","00000001");event(s,"ABS_MT_TRACKING_ID","00000002");
        event(s,"ABS_MT_POSITION_X","00000000");event(s,"ABS_MT_POSITION_Y","00000fff");
        check(s.points().length==4,"two fingers");
        event(s,"ABS_MT_TRACKING_ID","ffffffff");check(s.points().length==2,"release one");
        event(s,"ABS_MT_SLOT","00000000");event(s,"ABS_MT_TRACKING_ID","ffffffff");
        check(s.points().length==0,"no points after release");
        event(s,"ABS_MT_TRACKING_ID","00000003");event(s,"ABS_MT_POSITION_X","00000100");
        check(s.points().length==0,"incomplete new contact hidden");
        event(s,"ABS_MT_POSITION_Y","00000100");
        check(s.points().length==2,"new contact complete");
        check(!s.accept("[1.0] EV_KEY KEY_POWER DOWN"),"ignore keyboard");
        s.accept("[1.0] EV_KEY BTN_TOUCH UP");check(s.points().length==0,"global release clears");
        event(s,"ABS_MT_SLOT","ffffffff");event(s,"ABS_MT_POSITION_X","00000200");
        check(s.points().length==0,"bad slot ignored");
        s.accept("[1.0] EV_SYN SYN_DROPPED 00000000");check(s.points().length==0,"drop clears stale points");
        near(TouchState.rotate(.2f,.7f,0)[0],.2f); near(TouchState.rotate(.2f,.7f,0)[1],.7f);
        near(TouchState.rotate(.2f,.7f,1)[0],.7f); near(TouchState.rotate(.2f,.7f,1)[1],.8f);
        near(TouchState.rotate(.2f,.7f,2)[0],.8f); near(TouchState.rotate(.2f,.7f,2)[1],.3f);
        near(TouchState.rotate(.2f,.7f,3)[0],.3f); near(TouchState.rotate(.2f,.7f,3)[1],.2f);
        System.out.println("TouchState: "+checks+" checks passed");
    }
    static void event(TouchState s,String code,String v){s.accept("[12.3456] EV_ABS "+code+" "+v);}
    static void near(float a,float b){check(Math.abs(a-b)<.0001,"coordinate "+a+" expected "+b);}
    static void check(boolean ok,String what){checks++;if(!ok)throw new AssertionError(what);}
}
