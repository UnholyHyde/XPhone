package ru.xphone.booster;

import java.util.*;

/** Правила списка и очистки без зависимостей от Android. */
final class CompanionPolicy {
    static final int LIMIT=6;
    static boolean packageName(String s) {
        return s!=null && s.length()<=255 && s.matches("[A-Za-z][A-Za-z0-9_]*([.][A-Za-z0-9_]+)+");
    }
    static List<String> normalize(Collection<String> values) {
        TreeSet<String> out=new TreeSet<>();
        if(values!=null)for(String s:values)if(packageName(s))out.add(s);
        List<String> list=new ArrayList<>(out);
        return new ArrayList<>(list.subList(0,Math.min(LIMIT,list.size())));
    }
    static final class App {
        final String pkg; final int uid; final boolean system;
        App(String p,int u,boolean s){pkg=p;uid=u;system=s;}
    }
    static List<String> cleanTargets(List<App> apps,Set<String> protectedPackages) {
        Set<Integer> protectedUids=new HashSet<>();
        for(App a:apps)if(a.system||protectedPackages.contains(a.pkg))protectedUids.add(a.uid);
        TreeSet<String> out=new TreeSet<>();
        for(App a:apps)if(packageName(a.pkg)&&a.uid>=10000&&!a.system&&!protectedPackages.contains(a.pkg)&&!protectedUids.contains(a.uid))out.add(a.pkg);
        return new ArrayList<>(out);
    }
}
