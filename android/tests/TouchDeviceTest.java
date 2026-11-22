package ru.xphone.booster;

public final class TouchDeviceTest {
    public static void main(String[] args) {
        String touchpad="add device 1: /dev/input/event9\nABS_MT_SLOT : min 0, max 9\nABS_MT_TRACKING_ID\nABS_MT_POSITION_X : min 0, max 4095\nABS_MT_POSITION_Y : min 0, max 4095\nINPUT_PROP_POINTER\n";
        String screen="add device 2: /dev/input/event8\nABS_MT_SLOT : min 0, max 9\nABS_MT_TRACKING_ID\nABS_MT_POSITION_X : value 0, min 0, max 4095, fuzz 0\nABS_MT_POSITION_Y : value 0, min 0, max 4095, fuzz 0\nINPUT_PROP_DIRECT\n";
        TouchInputReader.Device d=TouchInputReader.parseDevice(touchpad+screen);
        if(d==null||!d.path.equals("/dev/input/event8")||d.maxX!=4095||d.maxY!=4095)throw new AssertionError("screen detection");
        if(TouchInputReader.parseDevice(touchpad)!=null)throw new AssertionError("touchpad must not be captured");
        if(TouchInputReader.parseDevice(screen.replace("max 4095","max 0"))!=null)throw new AssertionError("invalid range");
        System.out.println("TouchDevice: 3 checks passed");
    }
}
