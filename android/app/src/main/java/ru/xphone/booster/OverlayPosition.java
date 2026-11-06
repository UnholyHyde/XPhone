package ru.xphone.booster;

/** Pure geometry shared by initial placement and rotation recovery. */
final class OverlayPosition {
    private OverlayPosition() {}
    static int y(float fraction, int screenHeight, int handleHeight) {
        if (Float.isNaN(fraction) || Float.isInfinite(fraction)) fraction=0.38f;
        return Math.max(0, Math.min(Math.max(0,screenHeight-handleHeight),Math.round(screenHeight*fraction)));
    }
}
