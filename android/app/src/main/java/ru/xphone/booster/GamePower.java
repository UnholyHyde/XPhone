package ru.xphone.booster;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.PowerManager;
import java.util.*;

/** Real game modes and OS power saver. All mutations use Shell.background(). */
public final class GamePower {
    private GamePower(){}
    public static final int ECONOMY=0,BALANCE=1,PERFORMANCE=2,FULL_FOCUS=3;
    public static String title(int mode){return mode==FULL_FOCUS?"Full Focus":mode==ECONOMY?"Эконом":mode==PERFORMANCE?"Производительность":"Баланс";}
    private static SharedPreferences originals(Context c){return c.getSharedPreferences("power_originals",Context.MODE_PRIVATE);}
    public static void probe(Context c){
        if(!Shell.ready(c))return;
        String power=Shell.run(c,"cmd power help"),game=Shell.run(c,"cmd game help");
        Prefs.put(c,"power_os_supported",power!=null&&power.contains("set-mode"));
        Prefs.put(c,"power_game_supported",game!=null&&game.contains("list-modes"));
    }
    public static boolean gameModesSupported(Context c){return Prefs.get(c,"power_game_supported",false);}
    public static boolean available(Context c){return Shell.ready(c)&&(Prefs.get(c,"power_os_supported",false)||Prefs.get(c,"power_game_supported",false));}
    public static boolean hasChanges(Context c){return !originals(c).getAll().isEmpty();}
    public static final class State {
        public boolean economy,focus;
        public String game;
        public final List<Integer> choices=new ArrayList<>();
        public int choice(){return focus?FULL_FOCUS:economy||"battery".equals(game)?ECONOMY:"performance".equals(game)?PERFORMANCE:"standard".equals(game)?BALANCE:-1;}
        public String label(){return focus?"Full Focus":economy||"battery".equals(game)?"Эконом":"performance".equals(game)?"Мощность":"custom".equals(game)?"Свой":"Баланс";}
    }
    private static boolean saver(Context c){return c.getSystemService(PowerManager.class).isPowerSaveMode();}
    private static GameModeInfo info(Context c,String pkg){
        return GameModeInfo.parse(GameModeInfo.validPackage(pkg)&&Shell.ready(c)&&gameModesSupported(c)?Shell.run(c,"cmd game list-modes "+pkg):null);
    }
    public static State read(Context c,String pkg){
        GameModeInfo i=info(c,pkg);State state=new State();state.game=i.current;state.economy=saver(c);state.focus=FullFocus.active(pkg);
        boolean system=Prefs.get(c,"power_os_supported",false);
        if(system||i.available.contains("battery"))state.choices.add(ECONOMY);
        if(system||i.available.contains("standard"))state.choices.add(BALANCE);
        if(i.available.contains("performance"))state.choices.add(PERFORMANCE);
        if(FullFocus.available(c)&&pkg!=null&&Prefs.isGame(c,pkg))state.choices.add(FULL_FOCUS);
        return state;
    }
    private static boolean remember(Context c,String pkg,GameModeInfo i){
        SharedPreferences o=originals(c);SharedPreferences.Editor e=o.edit();
        if(!o.contains("system"))e.putBoolean("system",saver(c));
        if(GameModeInfo.validPackage(pkg)&&i.current!=null&&!o.contains("game:"+pkg))e.putString("game:"+pkg,i.current);
        return e.commit();
    }
    private static boolean setSaver(Context c,boolean value){
        if(saver(c)==value)return true;
        if(!Prefs.get(c,"power_os_supported",false))return false;
        Shell.run(c,"cmd power set-mode "+(value?1:0));
        // PowerManager broadcasts the state asynchronously.
        for(int n=0;n<6;n++){
            if(saver(c)==value)return true;
            try{Thread.sleep(150);}catch(InterruptedException e){Thread.currentThread().interrupt();return false;}
        }
        return saver(c)==value;
    }
    private static boolean running(Context c,String pkg){
        String pids=Shell.run(c,"pidof "+pkg);return pids==null||!pids.trim().isEmpty();
    }
    private static boolean setGame(Context c,String pkg,String mode){
        if(!GameModeInfo.validPackage(pkg)||!GameModeInfo.validMode(mode))return false;
        String current=info(c,pkg).current;
        if(GameModeTransition.unchanged(current,mode))return true;
        if(Booster.isSamsung()&&running(c,pkg))return false;
        Shell.run(c,"cmd game mode "+mode+" "+pkg);
        return mode.equals(info(c,pkg).current);
    }
    /** Returns null on success or a user-facing explanation. */
    public static String select(Context c,String pkg,int desired){
        if(desired==FULL_FOCUS)return FullFocus.start(c,pkg);
        if(FullFocus.active()&&!FullFocus.stop(c))return "Не удалось полностью восстановить настройки Full Focus";
        if(!available(c))return "Для переключения режимов нужен Shizuku";
        State state=read(c,pkg);
        if(!state.choices.contains(desired))return "Этот режим недоступен для игры";
        GameModeInfo before=info(c,pkg);boolean powerBefore=saver(c);
        String requested=desired==ECONOMY?(before.available.contains("battery")?"battery":"standard"):desired==PERFORMANCE?"performance":"standard";
        if(before.available.contains(requested)&&!GameModeTransition.unchanged(before.current,requested)&&Booster.isSamsung()&&running(c,pkg))
            return "Samsung закрывает игру при смене этого режима. Переключение во время игры отменено.";
        if(!remember(c,pkg,before))return "Не удалось сохранить исходные настройки";
        boolean ok;
        if(desired==ECONOMY){
            if(before.available.contains("battery"))ok=setGame(c,pkg,"battery");
            else{
                ok=!before.available.contains("standard")||setGame(c,pkg,"standard");
                if(ok)ok=setSaver(c,true);
            }
        }else{
            ok=setSaver(c,false);
            String target=desired==PERFORMANCE?"performance":"standard";
            if(ok&&before.available.contains(target))ok=setGame(c,pkg,target);
            else if(desired==PERFORMANCE)ok=false;
        }
        if(ok)return null;
        // Failed transitions must not leave half of the new mode applied.
        if(before.current!=null)setGame(c,pkg,before.current);
        setSaver(c,powerBefore);
        return desired==ECONOMY?"Android не включил экономию. На зарядке она может быть недоступна.":"Телефон не разрешил переключить режим";
    }
    static boolean restoreGameMode(Context c,String pkg,String mode){return setGame(c,pkg,mode);}
    static boolean restoreImmediate(Context c,String pkg,String mode,boolean economy){
        if(!Shell.ready(c))return false;
        boolean game=mode.isEmpty()||setGame(c,pkg,mode);return setSaver(c,economy)&&game;
    }
    public static void restoreAll(Context c){
        if(!hasChanges(c)||!Shell.ready(c))return;
        SharedPreferences o=originals(c);
        for(Map.Entry<String,?> entry:o.getAll().entrySet()){
            String key=entry.getKey();boolean ok=false;
            if(key.equals("system")&&entry.getValue() instanceof Boolean)ok=setSaver(c,(Boolean)entry.getValue());
            else if(key.startsWith("game:")&&entry.getValue() instanceof String)ok=setGame(c,key.substring(5),(String)entry.getValue());
            if(ok)o.edit().remove(key).commit();
        }
    }
}
