package ru.xphone.booster;

/** Old usage events must not cancel a newly requested hide. */
final class ReentryGate {
    private long hiddenAt;
    private boolean hidden,left;
    void hide(long now){hidden=true;left=false;hiddenAt=now;}
    void visitHubOrHome(long eventTime){if(hidden&&eventTime>=hiddenAt)left=true;}
    boolean shouldRestore(){return hidden&&left;}
    void clear(){hidden=false;left=false;}
}
