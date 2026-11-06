package ru.xphone.booster;

import android.content.*;
import android.hardware.display.DisplayManager;
import android.view.Display;
import java.util.*;

/** Changes logical rendering size only. Native display timing/refresh rate is untouched. */
final class DisplayResolution {
    static volatile boolean changing;
    static List<int[]> modes(Context c){
        Map<Integer,int[]> sizes=new TreeMap<>();
        Display display=c.getSystemService(DisplayManager.class).getDisplay(Display.DEFAULT_DISPLAY);
        if(display!=null)for(Display.Mode mode:display.getSupportedModes()){
            int w=Math.min(mode.getPhysicalWidth(),mode.getPhysicalHeight()),h=Math.max(mode.getPhysicalWidth(),mode.getPhysicalHeight());
            if(w>=240&&h<=16384)sizes.put(w,new int[]{w,h});
        }
        return new ArrayList<>(sizes.values());
    }
    private static SharedPreferences originals(Context c){return c.getSharedPreferences("display_originals",Context.MODE_PRIVATE);}
    static boolean hasChanges(Context c){return originals(c).contains("size");}
    static String select(Context c,int w,int h){
        if(!Shell.ready(c))return "Нужен запущенный Shizuku";
        if(modes(c).stream().noneMatch(m->m[0]==w&&m[1]==h))return "Телефон не сообщил такой размер экрана";
        String oldSize=Shell.run(c,"wm size"),oldDensity=Shell.run(c,"wm density");
        int[] physical=DisplayGeometry.size(oldSize,false),current=DisplayGeometry.size(oldSize,true);
        if(current==null)current=physical;
        int dpi=DisplayGeometry.density(oldDensity,true);if(dpi==0)dpi=DisplayGeometry.density(oldDensity,false);
        if(physical==null||current==null||dpi==0)return "Не удалось прочитать исходный масштаб. Настройки не изменены";
        int targetDpi=DisplayGeometry.scaledDensity(Math.min(current[0],current[1]),w,dpi);
        int width=physical[0]<=physical[1]?w:h,height=physical[0]<=physical[1]?h:w;
        String beforeSize=sizeCommand(oldSize),beforeDensity=densityCommand(oldDensity);
        SharedPreferences saved=originals(c);
        if(!hasChanges(c)&&!saved.edit().putString("size",beforeSize).putString("density",beforeDensity).commit())return "Не удалось сохранить исходные настройки";
        changing=true;try{Shell.run(c,"wm size "+width+"x"+height+" && wm density "+targetDpi);}finally{changing=false;}
        int[] actual=DisplayGeometry.size(Shell.run(c,"wm size"),true);
        if(actual==null)actual=DisplayGeometry.size(Shell.run(c,"wm size"),false);
        int actualDpi=effectiveDensity(Shell.run(c,"wm density"));
        if(actual!=null&&actual[0]==width&&actual[1]==height&&actualDpi==targetDpi)return "Отрисовка: "+w+" × "+h+". Физический режим экрана не менялся";
        restoreCommands(c,beforeSize,beforeDensity);
        return "Телефон не подтвердил новый размер. Запрошен возврат предыдущих настроек";
    }
    private static String sizeCommand(String output){int[] o=DisplayGeometry.size(output,true);return o==null?"reset":o[0]+"x"+o[1];}
    private static String densityCommand(String output){int o=DisplayGeometry.density(output,true);return o==0?"reset":Integer.toString(o);}
    private static int effectiveDensity(String output){int n=DisplayGeometry.density(output,true);return n>0?n:DisplayGeometry.density(output,false);}
    private static boolean restoreCommands(Context c,String size,String density){
        // Only validated numeric commands from wm output or our stored originals are accepted.
        if(size==null||density==null||!size.matches("reset|[0-9]{3,5}x[0-9]{3,5}")||!density.matches("reset|[0-9]{2,4}"))return false;
        changing=true;try{Shell.run(c,"wm size "+size+" && wm density "+density);}finally{changing=false;}
        return size.equals(sizeCommand(Shell.run(c,"wm size")))&&density.equals(densityCommand(Shell.run(c,"wm density")));
    }
    static boolean restore(Context c){
        if(!hasChanges(c))return true;if(!Shell.ready(c))return false;
        SharedPreferences saved=originals(c);
        boolean ok=restoreCommands(c,saved.getString("size",null),saved.getString("density",null));
        if(ok)saved.edit().clear().commit();return ok;
    }
}
