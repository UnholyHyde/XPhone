package ru.xphone.booster;
import android.content.Context;
/** Auto-disabling OEM tools is retired: Samsung GOS force-stops games on PACKAGE_CHANGED. */
final class OemBooster {
    private static final String SAMSUNG="com.samsung.android.game.gametools";
    static String component(Context c){if(!Booster.isSamsung())return null;try{c.getPackageManager().getApplicationInfo(SAMSUNG,0);return SAMSUNG;}catch(Exception e){return null;}}
    static boolean available(Context c){return false;}
    static void restore(Context c){
        // Retire saved opt-ins, so no future game launch can repeat this transition.
        if(Prefs.get(c,"disable_oem_booster",false))Prefs.sp(c).edit().putBoolean("disable_oem_booster",false).putBoolean("oem_disable_retired",true).commit();
        if(!Prefs.sp(c).contains("oem_booster_original")||!Shell.ready(c)||component(c)==null)return;
        int original=Prefs.getInt(c,"oem_booster_original",-1);
        if(original<0||original>3)return;
        try{
            int actual=c.getPackageManager().getApplicationEnabledSetting(SAMSUNG);
            if(actual==original){Prefs.sp(c).edit().remove("oem_booster_original").commit();return;}
            // One recovery of XPhone's recorded change, never an unconditional enable.
            String verb=original==0?"default-state":original==1?"enable":original==2?"disable":"disable-user";
            Shell.run(c,"pm "+verb+" --user "+(android.os.Process.myUid()/100000)+" "+SAMSUNG);
            if(c.getPackageManager().getApplicationEnabledSetting(SAMSUNG)==original)Prefs.sp(c).edit().remove("oem_booster_original").commit();
        }catch(Exception e){android.util.Log.w("XPhone","Game Tools recovery remains pending",e);}
    }
}
