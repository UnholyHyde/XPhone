package ru.xphone.booster;
public final class ForegroundActivitiesTest {
    private static int checks;
    private static void check(boolean ok){checks++;if(!ok)throw new AssertionError("check "+checks);}
    public static void main(String[] args){
        ForegroundActivities f=new ForegroundActivities();
        check(f.top()==null);
        f.resume("game","Game");f.resume("telegram","Chat");
        check("telegram".equals(f.top()));check(f.contains("game"));
        f.pause("telegram","Chat");check("game".equals(f.top()));
        f.resume("system","Dialog");f.pause("system","Dialog");check("game".equals(f.top()));
        f.resume("telegram","Chat");f.resume("telegram","Settings");
        f.pause("telegram","Chat");check("telegram".equals(f.top()));
        f.pause("telegram","Settings");check("game".equals(f.top()));
        f.pause("game","Game");f.resume("home","Home");check(!f.contains("game"));check("home".equals(f.top()));
        f.resume("game","Game");f.resume("game","Game");f.pause("game","Game");check(!f.contains("game"));
        f.pause("missing","None");check("home".equals(f.top()));
        System.out.println("ForegroundActivities: "+checks+" checks passed");
    }
}
