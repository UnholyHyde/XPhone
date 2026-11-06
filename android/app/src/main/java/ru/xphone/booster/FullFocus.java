package ru.xphone.booster;
import android.app.*;
import android.content.*;
import android.net.Uri;
import android.os.Build;
import android.service.notification.*;
import java.util.*;
/** Own DND rule; does not replace the user's global policy or suspend system packages. */
final class FullFocus {
    private static final Uri CONDITION=Uri.parse("condition://ru.xphone.booster/full_focus");
    private static volatile String activeGame;
    static boolean available(Context c){return Build.VERSION.SDK_INT>=35&&Shell.ready(c);}
    static boolean active(){return activeGame!=null;}
    static boolean active(String pkg){return pkg!=null&&pkg.equals(activeGame);}
    private static android.content.SharedPreferences saved(Context c){return c.getSharedPreferences("full_focus_originals",Context.MODE_PRIVATE);}
    static String start(Context c,String pkg){
        if(Build.VERSION.SDK_INT<35||!available(c)||pkg==null||!Prefs.isGame(c,pkg))return "Full Focus доступен в игре с Shizuku на Android 15+";
        if(active(pkg))return null;
        if(active()&&!stop(c))return "Не удалось завершить предыдущий Full Focus";
        NotificationManager nm=c.getSystemService(NotificationManager.class);
        boolean access=nm.isNotificationPolicyAccessGranted();
        if(!access){saved(c).edit().putBoolean("revoke_access",true).commit();Shell.run(c,"cmd notification allow_dnd "+c.getPackageName()+" "+(android.os.Process.myUid()/100000));}
        if(!nm.isNotificationPolicyAccessGranted()){stop(c);return "Телефон не разрешил управление уведомлениями";}
        GamePower.State before=GamePower.read(c,pkg);
        int target=before.choices.contains(GamePower.PERFORMANCE)?GamePower.PERFORMANCE:GamePower.BALANCE;
        if(!saved(c).edit().putString("game",pkg).putString("mode",before.game==null?"":before.game).putBoolean("saver",before.economy).commit()){stop(c);return "Не удалось сохранить исходный режим";}
        try{
            ZenPolicy policy=new ZenPolicy.Builder().disallowAllSounds().allowMedia(true).hideAllVisualEffects().allowPriorityChannels(false).build();
            AutomaticZenRule rule=new AutomaticZenRule("XPhone Full Focus",null,new ComponentName(c,MainActivity.class),CONDITION,policy,NotificationManager.INTERRUPTION_FILTER_PRIORITY,true);
            String id=nm.addAutomaticZenRule(rule);
            if(id==null||!saved(c).edit().putString("rule",id).commit())throw new IllegalStateException("rule");
            nm.setAutomaticZenRuleState(id,new Condition(CONDITION,"Full Focus",Condition.STATE_TRUE));
            boolean accepted=false;
            for(int i=0;i<6;i++){if(nm.getCurrentInterruptionFilter()==NotificationManager.INTERRUPTION_FILTER_PRIORITY&&nm.getAutomaticZenRuleState(id)==Condition.STATE_TRUE){accepted=true;break;}Thread.sleep(150);}
            if(!accepted)throw new IllegalStateException("not active");
            String error=GamePower.select(c,pkg,target);
            if(error!=null){stop(c);return error;}
            activeGame=pkg;
            MemoryCleaner.clean(c,pkg);
            Shell.onMain(()->{StreamPrivacy.tick(c);BoostService.refreshNotice();});
            return null;
        }catch(Exception e){stop(c);return "Full Focus не включён: телефон не подтвердил строгий режим уведомлений";}
    }
    static boolean stop(Context c){
        activeGame=null;boolean ok=true;
        NotificationManager nm=c.getSystemService(NotificationManager.class);
        if(Build.VERSION.SDK_INT>=29&&nm.isNotificationPolicyAccessGranted())try{
            for(Map.Entry<String,AutomaticZenRule> entry:nm.getAutomaticZenRules().entrySet()){
                AutomaticZenRule r=entry.getValue();
                if(CONDITION.equals(r.getConditionId())&&r.getConfigurationActivity()!=null&&c.getPackageName().equals(r.getConfigurationActivity().getPackageName())){
                    nm.setAutomaticZenRuleState(entry.getKey(),new Condition(CONDITION,"Full Focus выключен",Condition.STATE_FALSE));
                    if(!nm.removeAutomaticZenRule(entry.getKey()))ok=false;
                }
            }
        }catch(Exception e){ok=false;}
        android.content.SharedPreferences prefs=saved(c);
        String pkg=prefs.getString("game",null);
        if(pkg!=null){if(GamePower.restoreImmediate(c,pkg,prefs.getString("mode",""),prefs.getBoolean("saver",false)))prefs.edit().remove("game").remove("mode").remove("saver").commit();else ok=false;}
        if(ok){prefs.edit().remove("rule").commit();
            if(prefs.getBoolean("revoke_access",false)&&Shell.ready(c)){Shell.run(c,"cmd notification disallow_dnd "+c.getPackageName()+" "+(android.os.Process.myUid()/100000));if(!nm.isNotificationPolicyAccessGranted())prefs.edit().remove("revoke_access").commit();}
        }
        Shell.onMain(()->{StreamPrivacy.tick(c);BoostService.refreshNotice();});return ok;
    }
    static boolean stillActive(Context c){if(Build.VERSION.SDK_INT<35||!active())return false;try{NotificationManager nm=c.getSystemService(NotificationManager.class);String id=saved(c).getString("rule","");return nm.getCurrentInterruptionFilter()==NotificationManager.INTERRUPTION_FILTER_PRIORITY&&nm.getAutomaticZenRuleState(id)==Condition.STATE_TRUE;}catch(Exception e){return false;}}
}
