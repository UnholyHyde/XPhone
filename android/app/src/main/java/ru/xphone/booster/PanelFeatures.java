package ru.xphone.booster;

import android.content.Context;
import android.os.Build;
import java.util.ArrayList;
import java.util.List;

/** Единый список доступных кнопок для настроек и игровой панели. */
final class PanelFeatures {
    static final String[] IDS = {"stream", "hud", "fps", "record", "touch", "sensitivity", "mic", "apps",
            Booster.DND, Booster.PERF, Booster.PASS, Booster.ANIM, Booster.BRIGHT, Booster.CLEAN, Booster.RES};
    static boolean selected(Context c, String id) { return Prefs.get(c, "panel_tile_"+id, true); }
    static boolean available(Context c, String id) {
        switch(id) {
            case "stream": case "hud": return true;
            case "fps":return ScreenFrames.available(c);
            case "sensitivity":return TouchSensitivity.available(c);
            case "apps": return CompanionApps.available(c);
            case "res": return Shell.ready(c)&&!DisplayResolution.modes(c).isEmpty();
            case "record": return OverlayRecording.available();
            case "touch": return TouchTrace.available(c);
            case "mic": return Mic.supported(c);
            default: return Booster.supported(id) && Booster.available(c,id);
        }
    }
    static String title(String id) {
        switch(id) {
            case "hud": return "Мини-окно";
            case "fps":return "FPS / Гц";
            case "sensitivity":return "Чувствительность";
            case "apps": return "Приложения в окнах";
            case "record": return "Запись экрана";
            case "touch": return "Касания";
            case "mic": return "Микрофон";
            default: return Booster.title(id);
        }
    }
    static List<String> visible(Context c) {
        List<String> ids=new ArrayList<>();
        for(String id:IDS) if(GameProfiles.tile(c,BoostService.activeGame(),id)&&available(c,id)) ids.add(id);
        return ids;
    }
    static int locked(Context c) {
        int n=0;
        for(String id:IDS) {
            if(!GameProfiles.tile(c,BoostService.activeGame(),id)||available(c,id)) continue;
            if("apps".equals(id)) {if(CompanionApps.platform(c)&&!Shell.ready(c))n++;}
            else if("mic".equals(id)) { if(Build.VERSION.SDK_INT>=31)n++; }
            else if(!"record".equals(id)&&!"touch".equals(id)&&Booster.supported(id)) n++;
        }
        return n;
    }
}
