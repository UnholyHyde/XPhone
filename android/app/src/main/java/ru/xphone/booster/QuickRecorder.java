package ru.xphone.booster;

import android.content.Context;
import java.util.*;

final class QuickRecorder {
    private static volatile boolean active;
    private static long polledAt;
    static boolean active(){return active;}
    static boolean available(Context c){return android.os.Build.VERSION.SDK_INT>=33&&Shell.ready(c)&&Prefs.get(c,"quick_record_supported",false);}
    static boolean enabled(Context c){return available(c)&&(Prefs.get(c,"quick_record",false)||!NativeRecorder.nativeAvailable(c));}
    static void poll(Context c){if(!active||android.os.SystemClock.elapsedRealtime()-polledAt<2000)return;polledAt=android.os.SystemClock.elapsedRealtime();Shell.background(()->{String state=Shell.recordState(c);if("ERROR".equals(state))return;if(!"RUNNING".equals(state)){active=false;Shell.onMain(()->{BoostService.refreshNotice();BoostService.toast(message(state),R.drawable.ic_video);});}});}
    static String start(Context c){
        String name="XPhone-"+new java.text.SimpleDateFormat("yyyyMMdd-HHmmss",Locale.ROOT).format(new Date())+".mp4";
        String state=Shell.startRecord(c,name,Prefs.getInt(c,"quick_record_mbps",12));active="RUNNING".equals(state);Shell.onMain(BoostService::refreshNotice);return message(state);
    }
    static String stop(Context c){String state=Shell.stopRecord(c);String actual=Shell.recordState(c);active="RUNNING".equals(actual)||"STOPPING".equals(state)||(active&&"ERROR".equals(actual));Shell.onMain(BoostService::refreshNotice);return active&&!"STOPPING".equals(state)?"Остановка записи не подтверждена. Проверьте Shizuku и повторите через уведомление":message(state);}
    private static String message(String state){
        if("RUNNING".equals(state))return "Запись экрана началась · без звука";
        if("STOPPING".equals(state))return "Завершаем запись…";
        if(state!=null&&state.startsWith("SAVED:"))return "Запись сохранена: "+state.substring(6).replace("/sdcard/","");
        if("NO_SPACE".equals(state))return "Недостаточно свободного места для записи";
        return "Не удалось начать или завершить запись. Проверьте Shizuku и свободное место";
    }
}
