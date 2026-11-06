package ru.xphone.booster;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import java.util.*;

final class CompanionApps {
    private static final String KEY="companion_apps";
    static List<String> selected(Context c) {
        List<String> list=CompanionPolicy.normalize(Prefs.sp(c).getStringSet(KEY,Collections.emptySet()));
        list.removeIf(pkg->pkg.equals(c.getPackageName())||pkg.equals(Shell.SHIZUKU_PKG)||Prefs.isGame(c,pkg)||c.getPackageManager().getLaunchIntentForPackage(pkg)==null);return list;
    }
    static boolean select(Context c,String pkg,boolean on) {
        Set<String> values=new HashSet<>(selected(c));
        if(!CompanionPolicy.packageName(pkg))return false;
        if(on&&values.size()>=CompanionPolicy.LIMIT&&!values.contains(pkg))return false;
        if(on)values.add(pkg);else values.remove(pkg);
        Prefs.sp(c).edit().putStringSet(KEY,values).apply();return true;
    }
    static boolean platform(Context c) {
        if(Build.VERSION.SDK_INT<30)return false;
        PackageManager pm=c.getPackageManager();
        return pm.hasSystemFeature(PackageManager.FEATURE_FREEFORM_WINDOW_MANAGEMENT)
                || pm.hasSystemFeature("com.sec.feature.multiwindow")
                || pm.hasSystemFeature("com.samsung.feature.multiwindow") || Booster.isSamsung();
    }
    static void probe(Context c) {
        Prefs.put(c,"companion_capable",platform(c)&&Shell.ready(c)&&Shell.windowAvailable(c));
    }
    static boolean available(Context c) {
        return platform(c)&&Shell.ready(c)&&Prefs.get(c,"companion_capable",false);
    }
    static String label(Context c,String pkg) {
        try{return c.getPackageManager().getApplicationLabel(c.getPackageManager().getApplicationInfo(pkg,0)).toString();}
        catch(Exception e){return pkg;}
    }
}
