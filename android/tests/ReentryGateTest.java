package ru.xphone.booster;
public final class ReentryGateTest {
    private static int checks;
    private static void check(boolean value){checks++;if(!value)throw new AssertionError("check "+checks);}
    public static void main(String[] args){
        ReentryGate gate=new ReentryGate();check(!gate.shouldRestore());
        gate.hide(1000);check(!gate.shouldRestore());
        gate.visitHubOrHome(999);check(!gate.shouldRestore());
        gate.visitHubOrHome(1001);check(gate.shouldRestore());
        gate.clear();check(!gate.shouldRestore());
        gate.hide(2000);gate.visitHubOrHome(1001);check(!gate.shouldRestore());
        gate.visitHubOrHome(2000);check(gate.shouldRestore());
        gate.hide(3000);check(!gate.shouldRestore());
        gate.clear();gate.visitHubOrHome(4000);check(!gate.shouldRestore());
        System.out.println("ReentryGate: "+checks+" checks passed");
    }
}
