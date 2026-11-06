package ru.xphone.booster;

import android.content.*;
import android.content.pm.*;
import android.provider.Settings;
import java.util.*;

/** Только обычный am kill по отдельным пакетам, без force-stop и без kill-all. */
final class MemoryCleaner {
    private static Set<String> processes(String output){Set<String> values=new HashSet<>();if(output!=null)for(String line:output.split("\\r?\\n")){String name=line.trim().split(":",2)[0];if(CompanionPolicy.packageName(name))values.add(name);}return values;}
    static String clean(Context c,String game) {
        if(!Shell.ready(c))return "Нужен запущенный Shizuku с разрешением XPhone";
        PackageManager pm=c.getPackageManager();
        Set<String> protectedPkgs=new HashSet<>(CompanionApps.selected(c));
        protectedPkgs.add(c.getPackageName());protectedPkgs.add(Shell.SHIZUKU_PKG);
        if(game!=null)protectedPkgs.add(game);
        // Keep VPN connections and active foreground services (voice/streaming) intact.
        for(ResolveInfo vpn:pm.queryIntentServices(new Intent(android.net.VpnService.SERVICE_INTERFACE),0))if(vpn.serviceInfo!=null)protectedPkgs.add(vpn.serviceInfo.packageName);
        List<android.app.ActivityManager.RunningAppProcessInfo> processes=c.getSystemService(android.app.ActivityManager.class).getRunningAppProcesses();
        if(processes!=null)for(android.app.ActivityManager.RunningAppProcessInfo process:processes)if(process.importance<=android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE&&process.pkgList!=null)Collections.addAll(protectedPkgs,process.pkgList);
        try {
            ResolveInfo home=pm.resolveActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),PackageManager.MATCH_DEFAULT_ONLY);
            if(home!=null&&home.activityInfo!=null)protectedPkgs.add(home.activityInfo.packageName);
            String ime=Settings.Secure.getString(c.getContentResolver(),Settings.Secure.DEFAULT_INPUT_METHOD);
            ComponentName keyboard=ComponentName.unflattenFromString(ime==null?"":ime);
            if(keyboard!=null)protectedPkgs.add(keyboard.getPackageName());
            List<CompanionPolicy.App> apps=new ArrayList<>();
            Set<String> seen=new HashSet<>();
            // Сначала учитываем все видимые установленные пакеты: защита распространяется на общий UID.
            for(ApplicationInfo ai:pm.getInstalledApplications(0)) {
                seen.add(ai.packageName);
                boolean system=(ai.flags&(ApplicationInfo.FLAG_SYSTEM|ApplicationInfo.FLAG_UPDATED_SYSTEM_APP))!=0;
                apps.add(new CompanionPolicy.App(ai.packageName,ai.uid,system));
            }
            // На ограниченной видимости включаем UID каждого явно защищённого приложения отдельно.
            for(String pkg:protectedPkgs)if(!seen.contains(pkg))try {
                ApplicationInfo ai=pm.getApplicationInfo(pkg,0);
                apps.add(new CompanionPolicy.App(pkg,ai.uid,true));
            }catch(PackageManager.NameNotFoundException ignored){}
            Set<String> launchable=new HashSet<>();
            for(ResolveInfo r:pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),0))
                if(r.activityInfo!=null)launchable.add(r.activityInfo.packageName);
            List<String> targets=CompanionPolicy.cleanTargets(apps,protectedPkgs);
            targets.removeIf(pkg->!launchable.contains(pkg));
            if(targets.isEmpty())return "Нет фоновых приложений для очистки. Исключения сохранены";
            StringBuilder command=new StringBuilder();
            for(String pkg:targets)command.append("am kill --user current ").append(pkg).append("; ");
            android.app.ActivityManager manager=c.getSystemService(android.app.ActivityManager.class);
            android.app.ActivityManager.MemoryInfo before=new android.app.ActivityManager.MemoryInfo();manager.getMemoryInfo(before);
            Set<String> runningBefore=processes(Shell.run(c,"ps -A -o NAME"));
            String result=Shell.run(c,command.toString());
            if(result==null||!result.trim().isEmpty())return "Не удалось подтвердить очистку. Выбранные приложения не затрагивались";
            try{Thread.sleep(400);}catch(InterruptedException e){Thread.currentThread().interrupt();}
            Set<String> runningAfter=processes(Shell.run(c,"ps -A -o NAME"));
            List<String> closed=new ArrayList<>();for(String pkg:targets)if(runningBefore.contains(pkg)&&!runningAfter.contains(pkg))try{closed.add(pm.getApplicationLabel(pm.getApplicationInfo(pkg,0)).toString());}catch(Exception ignored){}
            android.app.ActivityManager.MemoryInfo after=new android.app.ActivityManager.MemoryInfo();manager.getMemoryInfo(after);
            long change=(after.availMem-before.availMem)/1048576;
            String names=closed.isEmpty()?"Завершение процессов не подтверждено":("Завершились: "+String.join(", ",closed.subList(0,Math.min(3,closed.size())))+(closed.size()>3?" и ещё "+(closed.size()-3):""));
            return names+"\nСвободная RAM: "+Math.round(before.availMem/1048576.0)+" → "+Math.round(after.availMem/1048576.0)+" МБ\nИзменение: "+(change>=0?"+":"")+change+" МБ. Игра и исключения сохранены";
        }catch(Exception e){return "Не удалось получить список приложений для очистки";}
    }
}
