package ru.xphone.booster;

import java.util.LinkedHashMap;

/** Tracks resumed activities, including multiple simultaneous freeform windows. */
final class ForegroundActivities {
    private final LinkedHashMap<String,String> resumed = new LinkedHashMap<>();
    private String key(String pkg,String cls) { return pkg + "/" + (cls == null ? "" : cls); }
    void resume(String pkg,String cls) {
        if(pkg==null)return;
        String key=key(pkg,cls);resumed.remove(key);resumed.put(key,pkg);
    }
    void pause(String pkg,String cls) { resumed.remove(key(pkg,cls)); }
    boolean contains(String pkg) { return pkg!=null && resumed.containsValue(pkg); }
    String top() { String top=null;for(String pkg:resumed.values())top=pkg;return top; }
}
