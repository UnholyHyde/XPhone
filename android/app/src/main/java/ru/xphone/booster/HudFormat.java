package ru.xphone.booster;
import java.util.Locale;
import java.util.regex.*;
/** Compact values and safe combined opacity with the touch glow. */
final class HudFormat {
    private HudFormat(){}
    static int opacity(int requested,boolean pinned){return Math.max(5,Math.min(pinned?40:100,requested));}
    static int opacity(int requested,boolean pinned,boolean otherPinned){return Math.min(opacity(requested,pinned),pinned&&otherPinned?20:100);}
    static String rate(double mbps){
        if(Double.isNaN(mbps)||Double.isInfinite(mbps)||mbps<0)return "—";
        if(mbps<1)return Math.round(mbps*1000)+"К";
        if(mbps>=1000)return String.format(Locale.ROOT,"%.1fГ",mbps/1000);
        return String.format(Locale.ROOT,"%.1fМ",mbps);
    }
    static String ping(String raw){
        if(raw==null)return "—";
        Matcher m=Pattern.compile("([0-9]+(?:\\.[0-9]+)?)").matcher(raw);
        if(!m.find())return "—";
        try{return Math.round(Double.parseDouble(m.group(1)))+"мс";}catch(NumberFormatException e){return "—";}
    }
}
