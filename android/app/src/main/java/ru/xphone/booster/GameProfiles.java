package ru.xphone.booster;

import android.content.Context;

/** Per-game choices are isolated from the global defaults and reversible. */
final class GameProfiles {
    private static String key(String pkg,String field){return "profile_"+pkg+"_"+field;}
    static boolean custom(Context c,String pkg){return pkg!=null&&Prefs.get(c,key(pkg,"enabled"),false);}
    static void setCustom(Context c,String pkg,boolean value){
        android.content.SharedPreferences.Editor edit=Prefs.sp(c).edit();
        if(value&&!Prefs.get(c,key(pkg,"initialized"),false)){
            for(String id:Booster.TOGGLES)edit.putBoolean(key(pkg,"auto_"+id),Prefs.auto(c,id));
            for(String id:PanelFeatures.IDS)edit.putBoolean(key(pkg,"tile_"+id),PanelFeatures.selected(c,id));
            edit.putBoolean(key(pkg,"extra_hud"),Prefs.get(c,MiniHud.ENABLED,false));
            edit.putBoolean(key(pkg,"extra_mic"),Prefs.get(c,Prefs.MIC_BUBBLE,false));
            edit.putBoolean(key(pkg,"extra_touch"),TouchTrace.enabled(c));
            edit.putInt(key(pkg,"usb_level"),Prefs.getInt(c,Prefs.PASS_LEVEL,0));
            edit.putInt(key(pkg,"mode"),Prefs.getInt(c,"hub_power_mode",GamePower.PERFORMANCE));
            edit.putBoolean(key(pkg,"initialized"),true);
        }
        edit.putBoolean(key(pkg,"enabled"),value).apply();
    }
    static boolean auto(Context c,String pkg,String id){return pkg==null?Prefs.auto(c,id):custom(c,pkg)&&Prefs.get(c,key(pkg,"auto_"+id),false);}
    static boolean tile(Context c,String pkg,String id){return custom(c,pkg)?Prefs.get(c,key(pkg,"tile_"+id),PanelFeatures.selected(c,id)):PanelFeatures.selected(c,id);}
    static boolean extra(Context c,String pkg,String id){
        boolean global=id.equals("hud")?Prefs.get(c,MiniHud.ENABLED,false):id.equals("mic")&&Prefs.get(c,Prefs.MIC_BUBBLE,false);
        return pkg==null?global:custom(c,pkg)&&Prefs.get(c,key(pkg,"extra_"+id),false);
    }
    static void setExtra(Context c,String pkg,String id,boolean on){if(custom(c,pkg))Prefs.put(c,key(pkg,"extra_"+id),on);}
    static void record(Context c,String pkg,String id,boolean on){if(custom(c,pkg)&&java.util.Arrays.asList(Booster.TOGGLES).contains(id))setAuto(c,pkg,id,on);}
    static int usbLevel(Context c,String pkg){return custom(c,pkg)?Prefs.getInt(c,key(pkg,"usb_level"),0):0;}
    static void setUsbLevel(Context c,String pkg,int value){if(custom(c,pkg))Prefs.putInt(c,key(pkg,"usb_level"),value);}
    static int brightness(Context c,String pkg){return custom(c,pkg)?Prefs.getInt(c,key(pkg,"brightness"),-1):-1;}
    static void recordBrightness(Context c,String pkg,int value){if(custom(c,pkg)){Prefs.putInt(c,key(pkg,"brightness"),value);setAuto(c,pkg,Booster.BRIGHT,true);}}
    static int mode(Context c,String pkg){return custom(c,pkg)?Prefs.getInt(c,key(pkg,"mode"),GamePower.PERFORMANCE):Prefs.getInt(c,"hub_power_mode",GamePower.PERFORMANCE);}
    static void setMode(Context c,String pkg,int mode){if(mode>=0&&mode<=3)Prefs.putInt(c,key(pkg,"mode"),mode);}
    static void setAuto(Context c,String pkg,String id,boolean value){Prefs.put(c,key(pkg,"auto_"+id),value);}
    static void setTile(Context c,String pkg,String id,boolean value){Prefs.put(c,key(pkg,"tile_"+id),value);}
}
