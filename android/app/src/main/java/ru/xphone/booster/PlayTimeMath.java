package ru.xphone.booster;
import java.time.*;
import java.util.*;
/** Local calendar math and package-level sessions. No device or fixed-zone assumptions. */
final class PlayTimeMath {
    static final long SESSION_GAP=30000;
    static List<LocalDate> week(long now,ZoneId zone){LocalDate today=Instant.ofEpochMilli(now).atZone(zone).toLocalDate();List<LocalDate> out=new ArrayList<>();for(int i=6;i>=0;i--)out.add(today.minusDays(i));return out;}
    static String duration(long ms){long minutes=Math.max(0,ms)/60000;return minutes<60?minutes+" мин":minutes/60+" ч "+minutes%60+" мин";}
    static long total(long baseline,long initialBucket,long baselineBucket,Map<Long,Long> buckets){long result=baseline;for(Map.Entry<Long,Long> e:buckets.entrySet())if(e.getKey()>baselineBucket)result+=e.getValue();else if(e.getKey()==baselineBucket)result+=Math.max(0,e.getValue()-initialBucket);return result;}
    static long combineTotals(long baseline,long dailyTotal,long yearDelta){return Math.max(dailyTotal,baseline+Math.max(0,yearDelta));}
    static final class Event{final String pkg,cls;final long at;final int type;Event(String p,String c,long t,int kind){pkg=p;cls=c==null?"":c;at=t;type=kind;}}
    static String precise(long ms){long seconds=Math.max(0,ms)/1000;return seconds<60?seconds+" с":seconds<3600?seconds/60+" мин "+seconds%60+" с":seconds/3600+" ч "+seconds/60%60+" мин "+seconds%60+" с";}
    static Map<String,Map<LocalDate,Long>> foreground(List<Event> events,long now,ZoneId zone){
        Map<String,Set<String>> active=new HashMap<>();Map<String,Long> starts=new HashMap<>();Map<String,Map<LocalDate,Long>> result=new HashMap<>();
        List<Event> ordered=new ArrayList<>(events);ordered.sort(Comparator.comparingLong(e->e.at));
        for(Event e:ordered){if(e.at>now)continue;
            if(e.type==16||e.type==26){for(String pkg:new ArrayList<>(starts.keySet())){split(result,pkg,starts.remove(pkg),e.at,zone);active.get(pkg).clear();}continue;}
            if(e.pkg==null)continue;Set<String> set=active.computeIfAbsent(e.pkg,k->new HashSet<>());
            if(e.type==1){if(set.isEmpty())starts.put(e.pkg,e.at);set.add(e.cls);}
            else if(e.type==2||e.type==23){set.remove(e.cls);if(set.isEmpty()&&starts.containsKey(e.pkg))split(result,e.pkg,starts.remove(e.pkg),e.at,zone);}
        }
        for(Map.Entry<String,Long> open:starts.entrySet())split(result,open.getKey(),open.getValue(),now,zone);
        return result;
    }
    private static void split(Map<String,Map<LocalDate,Long>> out,String pkg,long from,long to,ZoneId zone){
        while(from<to){LocalDate date=Instant.ofEpochMilli(from).atZone(zone).toLocalDate();long end=Math.min(to,date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli());if(end<=from)break;
            Map<LocalDate,Long> days=out.computeIfAbsent(pkg,k->new HashMap<>());days.put(date,days.getOrDefault(date,0L)+end-from);from=end;}
    }
    static Map<String,Map<LocalDate,Integer>> sessions(List<Event> events,ZoneId zone){
        Map<String,Set<String>> active=new HashMap<>();Map<String,Long> ended=new HashMap<>();Map<String,Map<LocalDate,Integer>> counts=new HashMap<>();
        events.sort(Comparator.comparingLong(e->e.at));
        for(Event e:events){
            if(e.type==16||e.type==26){for(String p:active.keySet())if(!active.get(p).isEmpty()){active.get(p).clear();ended.put(p,e.at);}continue;}
            if(e.pkg==null)continue;
            Set<String> classes=active.computeIfAbsent(e.pkg,k->new HashSet<>());
            if(e.type==1){
                if(classes.isEmpty()&&(!ended.containsKey(e.pkg)||e.at-ended.get(e.pkg)>=SESSION_GAP)){
                    LocalDate day=Instant.ofEpochMilli(e.at).atZone(zone).toLocalDate();Map<LocalDate,Integer> dates=counts.computeIfAbsent(e.pkg,k->new HashMap<>());dates.put(day,dates.getOrDefault(day,0)+1);
                }classes.add(e.cls);
            }else if(e.type==2||e.type==23){boolean was=!classes.isEmpty();classes.remove(e.cls);if(was&&classes.isEmpty())ended.put(e.pkg,e.at);}
        }return counts;
    }
}
