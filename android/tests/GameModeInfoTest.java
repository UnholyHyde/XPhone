package ru.xphone.booster;

public final class GameModeInfoTest {
    private static int checks;
    public static void main(String[] args){
        GameModeInfo samsung=GameModeInfo.parse("com.mobile.legends current mode: performance, available game modes: [standard,performance,custom]");
        check("performance".equals(samsung.current),"current mode");
        check(samsung.available.contains("standard"),"standard available");
        check(samsung.available.contains("performance"),"performance available");
        check(!samsung.available.contains("battery"),"no invented game economy");
        GameModeInfo battery=GameModeInfo.parse("example.game current mode: battery, available game modes: [standard, battery]");
        check(battery.available.contains("battery"),"declared economy");
        check(!battery.available.contains("performance"),"no invented performance");
        check("custom".equals(GameModeInfo.parse("current mode: custom, available game modes: [custom]").current),"custom preserved");
        check(GameModeInfo.parse("Permission denied").available.isEmpty(),"failure has no choices");
        check(GameModeInfo.parse(null).current==null,"null current");
        check(GameModeInfo.parse("current mode: unknown, available game modes: [unknown]").available.isEmpty(),"unknown ignored");
        check(GameModeInfo.validPackage("com.mobile.legends"),"real package");
        check(!GameModeInfo.validPackage("com.game; reboot"),"reject shell input");
        check(!GameModeInfo.validPackage(null),"reject null package");
        java.util.List<Integer> all=java.util.Arrays.asList(0,1,2);
        check(GameModeInfo.nextChoice(all,0)==1,"economy to balance");
        check(GameModeInfo.nextChoice(all,1)==2,"balance to performance");
        check(GameModeInfo.nextChoice(all,2)==0,"performance to economy");
        check(GameModeInfo.nextChoice(java.util.Arrays.asList(1,2),2)==1,"skip unavailable economy");
        check(GameModeInfo.nextChoice(all,0)==1,"next press after rejected economy advances");
        check(GameModeInfo.nextChoice(java.util.Collections.emptyList(),0)==-1,"empty modes");
        check(GameModeInfo.nextChoice(all,-1)==0,"custom begins first supported mode");
        System.out.println("GameModeInfo: "+checks+" checks passed");
    }
    private static void check(boolean ok,String message){checks++;if(!ok)throw new AssertionError(message);}
}
