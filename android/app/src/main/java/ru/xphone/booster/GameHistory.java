package ru.xphone.booster;
import android.app.usage.*;
import android.content.*;
import android.database.Cursor;
import android.database.sqlite.*;
import java.time.*;
import java.util.*;
/** Durable Android history baseline plus cached future daily usage. No Shizuku needed. */
final class GameHistory {
    static final class Day{final LocalDate date;long ms;boolean knownTime,sessionsPartial;Integer sessions;Day(LocalDate d){date=d;}}
    static final class Summary{long total,baseline,at;boolean imported;final List<Day> days=new ArrayList<>();}
    private static final Map<String,Summary> cache=new HashMap<>();
    private static final Set<String> known=new HashSet<>();
    private static boolean busy;private static long last;
    private static class Db extends SQLiteOpenHelper{
        Db(Context c){super(c,"game_history.db",null,2);}
        public void onCreate(SQLiteDatabase db){db.execSQL("CREATE TABLE baseline(pkg TEXT PRIMARY KEY, imported_at INTEGER, amount INTEGER, bucket INTEGER, initial INTEGER, known INTEGER)");db.execSQL("CREATE TABLE days(pkg TEXT, start INTEGER, amount INTEGER, PRIMARY KEY(pkg,start))");db.execSQL("CREATE TABLE sessions(pkg TEXT, day TEXT, zone TEXT, count INTEGER, partial INTEGER DEFAULT 0, PRIMARY KEY(pkg,day,zone))");db.execSQL("CREATE TABLE coverage(start INTEGER PRIMARY KEY)");db.execSQL("CREATE TABLE years(pkg TEXT, start INTEGER, amount INTEGER, initial INTEGER, PRIMARY KEY(pkg,start))");db.execSQL("CREATE TABLE observed(pkg TEXT, day TEXT, zone TEXT, amount INTEGER, PRIMARY KEY(pkg,day,zone))");}
        public void onUpgrade(SQLiteDatabase db,int old,int next){if(old<2){db.execSQL("CREATE TABLE IF NOT EXISTS observed(pkg TEXT, day TEXT, zone TEXT, amount INTEGER, PRIMARY KEY(pkg,day,zone))");db.execSQL("ALTER TABLE sessions ADD COLUMN partial INTEGER DEFAULT 0");}}
    }
    static void invalidate(){last=0;}
    static Summary get(String pkg){return cache.get(pkg);}
    static String compact(Context c,String pkg){if(!MainActivity.usageOk(c))return "Нужен доступ к истории";Summary s=get(pkg);if(s==null)return "Загружаем статистику…";Day today=s.days.isEmpty()?null:s.days.get(6);return "Учтено: "+PlayTimeMath.duration(s.total)+"\nСегодня: "+(today!=null&&today.knownTime?PlayTimeMath.duration(today.ms):"нет данных");}
    static void request(Context c,Collection<String> packages,Runnable changed){
        known.addAll(packages);if(busy||packages.isEmpty()||!MainActivity.usageOk(c))return;
        long now=android.os.SystemClock.elapsedRealtime();if(now-last<45000&&cache.keySet().containsAll(packages))return;
        busy=true;List<String> selected=new ArrayList<>(known);Context app=c.getApplicationContext();
        Shell.background(()->{Map<String,Summary> loaded=null;try{loaded=load(app,selected);}catch(Exception e){android.util.Log.w("XPhone","Game history unavailable",e);}final Map<String,Summary> values=loaded;
            Shell.onMain(()->{busy=false;last=android.os.SystemClock.elapsedRealtime();if(values!=null){cache.putAll(values);if(changed!=null)changed.run();}});
        });
    }
    static void tick(Context c){if(!busy&&!known.isEmpty()&&android.os.SystemClock.elapsedRealtime()-last>=60000)request(c,new ArrayList<>(known),null);}
    private static Map<String,Summary> load(Context c,List<String> packages){
        long now=System.currentTimeMillis();ZoneId zone=ZoneId.systemDefault();List<LocalDate> week=PlayTimeMath.week(now,zone);long begin=week.get(0).atStartOfDay(zone).toInstant().toEpochMilli();long today=week.get(6).atStartOfDay(zone).toInstant().toEpochMilli();
        UsageStatsManager manager=c.getSystemService(UsageStatsManager.class);
        List<UsageStats> daily=manager.queryUsageStats(UsageStatsManager.INTERVAL_DAILY,begin,now);if(daily==null)throw new IllegalStateException("Usage access/unlock required");
        Set<String> target=new HashSet<>(packages);Map<String,Map<Long,Long>> fresh=new HashMap<>();
        for(UsageStats usage:daily)if(target.contains(usage.getPackageName()))fresh.computeIfAbsent(usage.getPackageName(),k->new TreeMap<>()).merge(usage.getFirstTimeStamp(),Math.max(0,usage.getTotalTimeInForeground()),Math::max);
        Map<String,Long> historical=new HashMap<>();
        try(Db helper=new Db(c)){SQLiteDatabase db=helper.getWritableDatabase();Set<String> existing=new HashSet<>();
            for(String pkg:packages)try(Cursor q=db.rawQuery("SELECT 1 FROM baseline WHERE pkg=?",new String[]{pkg})){if(q.moveToFirst())existing.add(pkg);}
            Map<String,Map<Long,Long>> unique=new HashMap<>();
            {List<UsageStats> yearly=manager.queryUsageStats(UsageStatsManager.INTERVAL_YEARLY,0,now);if(yearly==null)throw new IllegalStateException("Yearly history unavailable");if(yearly!=null)for(UsageStats usage:yearly)if(target.contains(usage.getPackageName()))unique.computeIfAbsent(usage.getPackageName(),k->new TreeMap<>()).merge(usage.getFirstTimeStamp(),Math.max(0,usage.getTotalTimeInForeground()),Math::max);
                for(Map.Entry<String,Map<Long,Long>> e:unique.entrySet()){long total=0;for(long value:e.getValue().values())total+=value;historical.put(e.getKey(),total);}}
            UsageEvents logs=manager.queryEvents(begin,now);List<PlayTimeMath.Event> events=new ArrayList<>();long earliest=Long.MAX_VALUE;
            if(logs!=null){UsageEvents.Event event=new UsageEvents.Event();while(logs.hasNextEvent()){logs.getNextEvent(event);earliest=Math.min(earliest,event.getTimeStamp());int type=event.getEventType();if(target.contains(event.getPackageName())||type==16||type==26)events.add(new PlayTimeMath.Event(event.getPackageName(),event.getClassName(),event.getTimeStamp(),type));}}
            Map<String,Map<LocalDate,Long>> observed=PlayTimeMath.foreground(events,now,zone);
            Map<String,Map<LocalDate,Integer>> counts=PlayTimeMath.sessions(events,zone);
            LocalDate completeFrom=earliest==Long.MAX_VALUE?null:Instant.ofEpochMilli(earliest).atZone(zone).toLocalDate().plusDays(1);
            db.beginTransaction();try{
                for(UsageStats usage:daily)db.execSQL("INSERT OR IGNORE INTO coverage VALUES(?)",new Object[]{usage.getFirstTimeStamp()});
                for(String pkg:packages){
                    for(Map.Entry<LocalDate,Long> e:observed.getOrDefault(pkg,Collections.emptyMap()).entrySet()){
                        db.execSQL("INSERT OR IGNORE INTO observed VALUES(?,?,?,?)",new Object[]{pkg,e.getKey().toString(),zone.getId(),e.getValue()});
                        db.execSQL("UPDATE observed SET amount=MAX(amount,?) WHERE pkg=? AND day=? AND zone=?",new Object[]{e.getValue(),pkg,e.getKey().toString(),zone.getId()});
                    }
                    Map<Long,Long> data=fresh.getOrDefault(pkg,Collections.emptyMap());
                    for(Map.Entry<Long,Long> year:unique.getOrDefault(pkg,Collections.emptyMap()).entrySet()){
                        db.execSQL("INSERT OR IGNORE INTO years VALUES(?,?,?,?)",new Object[]{pkg,year.getKey(),year.getValue(),existing.contains(pkg)?0:year.getValue()});
                        db.execSQL("UPDATE years SET amount=MAX(amount,?) WHERE pkg=? AND start=?",new Object[]{year.getValue(),pkg,year.getKey()});
                    }
                    for(Map.Entry<Long,Long> e:data.entrySet()){
                        db.execSQL("INSERT OR IGNORE INTO days VALUES(?,?,?)",new Object[]{pkg,e.getKey(),e.getValue()});
                        db.execSQL("UPDATE days SET amount=MAX(amount,?) WHERE pkg=? AND start=?",new Object[]{e.getValue(),pkg,e.getKey()});
                    }
                    long bucket=today,initial=0;for(Map.Entry<Long,Long> e:data.entrySet())if(e.getKey()>=today){bucket=e.getKey();initial=e.getValue();}
                    ContentValues base=new ContentValues();base.put("pkg",pkg);base.put("imported_at",now);long recent=0;for(long value:data.values())recent+=value;base.put("amount",Math.max(recent,historical.getOrDefault(pkg,0L)));base.put("bucket",bucket);base.put("initial",initial);base.put("known",historical.containsKey(pkg)||!data.isEmpty()?1:0);db.insertWithOnConflict("baseline",null,base,SQLiteDatabase.CONFLICT_IGNORE);
                    if(completeFrom!=null)for(LocalDate date:week)if(!date.isBefore(completeFrom.minusDays(1))){
                        int count=counts.getOrDefault(pkg,Collections.emptyMap()).getOrDefault(date,0);
                        int partial=date.isBefore(completeFrom)?1:0;
                        db.execSQL("INSERT OR IGNORE INTO sessions VALUES(?,?,?,?,?)",new Object[]{pkg,date.toString(),zone.getId(),count,partial});
                        db.execSQL("UPDATE sessions SET count=MAX(count,?), partial=MIN(partial,?) WHERE pkg=? AND day=? AND zone=?",new Object[]{count,partial,pkg,date.toString(),zone.getId()});
                    }
                }db.setTransactionSuccessful();
            }finally{db.endTransaction();}
            Map<String,Summary> result=new HashMap<>();
            for(String pkg:packages){Summary s=new Summary();long baseBucket=0,initial=0;
                try(Cursor q=db.rawQuery("SELECT imported_at,amount,bucket,initial,known FROM baseline WHERE pkg=?",new String[]{pkg})){if(q.moveToFirst()){s.at=q.getLong(0);s.baseline=q.getLong(1);baseBucket=q.getLong(2);initial=q.getLong(3);s.imported=q.getInt(4)!=0;}}
                Map<Long,Long> all=new TreeMap<>();try(Cursor q=db.rawQuery("SELECT start,amount FROM days WHERE pkg=?",new String[]{pkg})){while(q.moveToNext())all.put(q.getLong(0),q.getLong(1));}
                long dailyTotal=PlayTimeMath.total(s.baseline,initial,baseBucket,all),yearDelta=0;
                try(Cursor q=db.rawQuery("SELECT COALESCE(SUM(MAX(0,amount-initial)),0) FROM years WHERE pkg=?",new String[]{pkg})){if(q.moveToFirst())yearDelta=q.getLong(0);}
                Map<LocalDate,Long> effective=new TreeMap<>();
                for(Map.Entry<Long,Long> e:all.entrySet())effective.merge(Instant.ofEpochMilli(e.getKey()).atZone(zone).toLocalDate(),e.getValue(),Long::sum);
                try(Cursor q=db.rawQuery("SELECT day,amount FROM observed WHERE pkg=? AND zone=?",new String[]{pkg,zone.getId()})){while(q.moveToNext())effective.merge(LocalDate.parse(q.getString(0)),q.getLong(1),Math::max);}
                LocalDate baselineDate=Instant.ofEpochMilli(baseBucket).atZone(zone).toLocalDate();long effectiveTotal=s.baseline;
                for(Map.Entry<LocalDate,Long> e:effective.entrySet())if(e.getKey().isAfter(baselineDate))effectiveTotal+=e.getValue();else if(e.getKey().equals(baselineDate))effectiveTotal+=Math.max(0,e.getValue()-initial);
                s.total=PlayTimeMath.combineTotals(s.baseline,Math.max(dailyTotal,effectiveTotal),yearDelta);
                Set<LocalDate> available=new HashSet<>();try(Cursor q=db.rawQuery("SELECT start FROM coverage WHERE start>=?",new String[]{Long.toString(begin-86400000L)})){while(q.moveToNext())available.add(Instant.ofEpochMilli(q.getLong(0)).atZone(zone).toLocalDate());}
                for(LocalDate date:week){Day d=new Day(date);d.knownTime=available.contains(date);d.ms=effective.getOrDefault(date,0L);d.knownTime=d.knownTime||effective.containsKey(date);try(Cursor q=db.rawQuery("SELECT count,partial FROM sessions WHERE pkg=? AND day=? AND zone=?",new String[]{pkg,date.toString(),zone.getId()})){if(q.moveToFirst()){d.sessions=q.getInt(0);d.sessionsPartial=q.getInt(1)!=0;}}s.days.add(d);}result.put(pkg,s);
            }return result;
        }
    }
}
