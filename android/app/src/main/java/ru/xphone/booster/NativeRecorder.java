package ru.xphone.booster;

import android.content.ComponentName;
import android.content.Context;

/** Opens Samsung's real recording control, preserving its consent and audio dialog. */
public final class NativeRecorder {
    private NativeRecorder(){}
    private static final String PKG="com.samsung.android.app.smartcapture";
    private static final String CLASS="com.samsung.android.app.screenrecorder.view.RecordScreenTile";
    private static final ComponentName TILE=new ComponentName(PKG,CLASS);
    public static void probe(Context c){
        if(!Shell.ready(c))return;
        if(!Prefs.sp(c).contains("quick_record_supported")){
            String help=Shell.run(c,"screenrecord --help");Prefs.put(c,"quick_record_supported",help!=null&&help.contains("--bit-rate")&&help.contains("Set to 0"));
        }
        // Regular apps cannot reliably inspect Samsung's secure tile list/services.
        String services=Shell.run(c,"cmd package query-services --brief --components -a android.service.quicksettings.action.QS_TILE -p "+PKG);
        String tiles=Shell.run(c,"settings get secure sysui_qs_tiles");
        boolean found=services!=null&&java.util.Arrays.stream(services.split("\\r?\\n")).anyMatch(line -> TILE.flattenToString().equals(line.trim()));
        boolean added=false;
        if(tiles!=null)for(String tile:tiles.split(",")){
            String value=tile.trim();
            if(!value.startsWith("custom(")||!value.endsWith(")"))continue;
            ComponentName component=ComponentName.unflattenFromString(value.substring(7,value.length()-1));
            if(TILE.equals(component)){added=true;break;}
        }
        Prefs.put(c,"native_recorder_available",found&&added);
        android.util.Log.i("XPhone","Recorder capability: service="+found+" tile="+added);
    }
    public static boolean nativeAvailable(Context c){return Shell.ready(c)&&Prefs.get(c,"native_recorder_available",false);}
    public static boolean available(Context c){return nativeAvailable(c)||QuickRecorder.available(c);}
    public static boolean open(Context c){
        probe(c);
        if(!nativeAvailable(c))return false;
        String result=Shell.run(c,"cmd statusbar click-tile "+TILE.flattenToString());
        return result!=null&&result.trim().isEmpty();
    }
}
