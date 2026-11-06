package ru.xphone.booster;
import android.content.Context;
/** Samsung's actual sensitivity setting, verified against the input device service. */
final class TouchSensitivity {
    static boolean available(Context c){return Shell.ready(c)&&Booster.isSamsung()&&Prefs.get(c,"sensitivity_available",false);}
    static void probe(Context c){
        if(!Shell.ready(c)||!Booster.isSamsung())return;
        String value=Shell.run(c,"settings get system auto_adjust_touch"),status=Shell.run(c,"dumpsys SemInputDeviceManagerService");
        Prefs.put(c,"sensitivity_available",("0".equals(value)||"1".equals(value))&&status!=null&&status.contains("GLOVE:"));
    }
    static boolean enabled(Context c){return "1".equals(Shell.run(c,"settings get system auto_adjust_touch"));}
    static String set(Context c,boolean target){
        if(!available(c))return "Управление чувствительностью недоступно";
        String before=Shell.run(c,"settings get system auto_adjust_touch");if(!"0".equals(before)&&!"1".equals(before))return "Не удалось прочитать исходное состояние";
        if(!Prefs.sp(c).contains("sensitivity_original"))Prefs.sp(c).edit().putString("sensitivity_original",before).commit();
        Shell.run(c,"settings put system auto_adjust_touch "+(target?1:0));
        for(int i=0;i<4;i++){
            String state=Shell.run(c,"dumpsys SemInputDeviceManagerService");
            if(state!=null&&java.util.regex.Pattern.compile("(?m)^\\s*GLOVE: "+(target?1:0)+"\\s*$").matcher(state).find())return null;
            try{Thread.sleep(150);}catch(InterruptedException e){Thread.currentThread().interrupt();break;}
        }
        Shell.run(c,"settings put system auto_adjust_touch "+before);
        return "Сенсор не подтвердил изменение чувствительности";
    }
    static void restore(Context c){String old=Prefs.sp(c).getString("sensitivity_original",null);if(old==null||!Shell.ready(c))return;
        if("0".equals(old)||"1".equals(old)){Shell.run(c,"settings put system auto_adjust_touch "+old);if(old.equals(Shell.run(c,"settings get system auto_adjust_touch")))Prefs.sp(c).edit().remove("sensitivity_original").commit();}}
}
