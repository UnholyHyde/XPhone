package ru.xphone.booster;
import java.time.*;
import java.util.*;
public class PlayTimeTest {
    static int checks;static void check(boolean value){checks++;if(!value)throw new AssertionError("check "+checks);}
    static PlayTimeMath.Event e(String cls,long at,int type){return new PlayTimeMath.Event("game",cls,at,type);}
    static int count(List<PlayTimeMath.Event> events,ZoneId zone){int total=0;for(Map<LocalDate,Integer> days:PlayTimeMath.sessions(events,zone).values())for(int n:days.values())total+=n;return total;}
    public static void main(String[] args){
        long now=Instant.parse("2026-11-06T00:30:00Z").toEpochMilli();
        for(String id:Arrays.asList("Europe/Moscow","America/Los_Angeles","Pacific/Kiritimati","Europe/Berlin")){
            ZoneId z=ZoneId.of(id);List<LocalDate> week=PlayTimeMath.week(now,z);check(week.size()==7);check(week.get(6).equals(Instant.ofEpochMilli(now).atZone(z).toLocalDate()));check(week.get(0).plusDays(6).equals(week.get(6)));
        }
        ZoneId berlin=ZoneId.of("Europe/Berlin");check(Duration.between(LocalDate.of(2026,3,29).atStartOfDay(berlin),LocalDate.of(2026,3,30).atStartOfDay(berlin)).toHours()==23);
        check(Duration.between(LocalDate.of(2026,10,25).atStartOfDay(berlin),LocalDate.of(2026,10,26).atStartOfDay(berlin)).toHours()==25);
        check(count(new ArrayList<>(Arrays.asList(e("A",1000,1),e("B",2000,1),e("A",3000,2),e("B",4000,2))),berlin)==1);
        check(count(new ArrayList<>(Arrays.asList(e("A",1000,1),e("A",2000,1),e("A",3000,2),e("A",4000,23))),berlin)==1);
        check(count(new ArrayList<>(Arrays.asList(e("A",1000,1),e("A",2000,2),e("B",31999,1))),berlin)==1);
        check(count(new ArrayList<>(Arrays.asList(e("A",1000,1),e("A",2000,2),e("B",32000,1))),berlin)==2);
        check(count(new ArrayList<>(Arrays.asList(e("A",1000,1),e("",2000,16),e("A",40000,1))),berlin)==2);
        check(count(new ArrayList<>(Arrays.asList(e("A",1000,1),e("",2000,15),e("A",40000,1))),berlin)==1);
        long before=Instant.parse("2026-11-05T23:59:00Z").toEpochMilli();check(count(new ArrayList<>(Arrays.asList(e("A",before,1),e("A",before+180000,2))),ZoneId.of("UTC"))==1);
        Map<Long,Long> buckets=new TreeMap<>();buckets.put(1L,9000000L);buckets.put(2L,5400000L);buckets.put(3L,7200000L);
        long total=PlayTimeMath.total(500*3600000L,3600000,2,buckets);check(total==500*3600000L+1800000+7200000);check(total==PlayTimeMath.total(500*3600000L,3600000,2,buckets));
        buckets.put(2L,0L);check(PlayTimeMath.total(500*3600000L,3600000,2,buckets)==502*3600000L);
        check(PlayTimeMath.duration(500*3600000L).equals("500 ч 0 мин"));check(PlayTimeMath.duration(-1).equals("0 мин"));
        check(PlayTimeMath.combineTotals(500,501,2)==502);
        check(PlayTimeMath.combineTotals(500,503,1)==503);
        check(PlayTimeMath.combineTotals(500,500,0)==500);
        check(PlayTimeMath.combineTotals(500,500,-10)==500);
        List<PlayTimeMath.Event> live=new ArrayList<>(Arrays.asList(e("A",1000,1),e("B",2000,1),e("A",3000,2),e("B",5000,2)));
        Map<String,Map<LocalDate,Long>> foreground=PlayTimeMath.foreground(live,6000,ZoneId.of("UTC"));
        check(foreground.get("game").values().iterator().next()==4000);
        check(PlayTimeMath.foreground(new ArrayList<>(Arrays.asList(e("A",1000,1))),6000,ZoneId.of("UTC")).get("game").values().iterator().next()==5000);
        check(PlayTimeMath.foreground(new ArrayList<>(Arrays.asList(e("A",1000,1),e("",3000,16))),6000,ZoneId.of("UTC")).get("game").values().iterator().next()==2000);
        check(PlayTimeMath.foreground(new ArrayList<>(Arrays.asList(e("A",1000,2))),6000,ZoneId.of("UTC")).isEmpty());
        check(PlayTimeMath.foreground(new ArrayList<>(Arrays.asList(e("A",9000,1))),6000,ZoneId.of("UTC")).isEmpty());
        Map<LocalDate,Long> split=PlayTimeMath.foreground(new ArrayList<>(Arrays.asList(e("A",before,1),e("A",before+180000,2))),before+200000,ZoneId.of("UTC")).get("game");
        check(split.get(LocalDate.of(2026,11,5))==60000);check(split.get(LocalDate.of(2026,11,6))==120000);
        check(PlayTimeMath.precise(19000).equals("19 с"));check(PlayTimeMath.precise(79000).equals("1 мин 19 с"));
        System.out.println("PlayTime: "+checks+" checks passed");
    }
}
