package ru.xphone.booster;
import android.content.Context;
final class StreamPrivacy {
    private static boolean active,pending,warned;private static long at=-10000;
    private static final android.os.Handler MAIN = new android.os.Handler(android.os.Looper.getMainLooper());
    private static Context app;
    private static final Runnable renew = () -> tick(app);
    static void tick(Context c){
        app = c.getApplicationContext();
        boolean wanted=Shell.ready(c)&&Prefs.get(c,"privacy_available",false)&&(FullFocus.active()||
                (Prefs.usageMode(c).hasStream() && Prefs.get(c,"stream_privacy",true)&&(PcLink.state()==PcLink.State.STREAMING||PcLink.state()==PcLink.State.CONNECTING)));
        MAIN.removeCallbacks(renew);
        if (wanted) MAIN.postDelayed(renew, 3500);
        if(!wanted)warned=false;
        if(pending||(wanted&&!active&&android.os.SystemClock.elapsedRealtime()-at<8000)||(!wanted&&!active)||(android.os.SystemClock.elapsedRealtime()-at<3000&&wanted==active))return;
        pending=true;Shell.background(()->{String result=Shell.streamPrivacy(c,wanted);Shell.onMain(()->{pending=false;at=android.os.SystemClock.elapsedRealtime();active=wanted&&"PROTECTED".equals(result);if(wanted&&!active&&!warned){warned=true;BoostService.toast("Защита уведомлений не включилась. Используйте «Не беспокоить»",R.drawable.ic_alert);} });});
    }
    static void stop(Context c){MAIN.removeCallbacks(renew);Shell.background(()->Shell.streamPrivacy(c,false));active=false;at=0;}
}
