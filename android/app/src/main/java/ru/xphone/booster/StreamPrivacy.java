package ru.xphone.booster;
import android.content.Context;
final class StreamPrivacy {
    private static boolean active,pending,warned;private static long at=-10000;
    static void tick(Context c){boolean wanted=Shell.ready(c)&&Prefs.get(c,"privacy_available",false)&&(FullFocus.active()||(Prefs.get(c,"stream_privacy",true)&&(PcLink.state()==PcLink.State.STREAMING||PcLink.state()==PcLink.State.CONNECTING)));
        if(!wanted)warned=false;
        if(pending||(wanted&&!active&&android.os.SystemClock.elapsedRealtime()-at<8000)||(!wanted&&!active)||(android.os.SystemClock.elapsedRealtime()-at<3000&&wanted==active))return;
        pending=true;Shell.background(()->{String result=Shell.streamPrivacy(c,wanted);Shell.onMain(()->{pending=false;at=android.os.SystemClock.elapsedRealtime();active=wanted&&"PROTECTED".equals(result);if(wanted&&!active&&!warned){warned=true;BoostService.toast("Защита уведомлений не включилась. Используйте «Не беспокоить»",R.drawable.ic_alert);} });});
    }
    static void stop(Context c){Shell.background(()->Shell.streamPrivacy(c,false));active=false;at=0;}
}
