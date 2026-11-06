package ru.xphone.booster;

import android.app.ActivityManager;
import android.content.Context;
import android.net.*;
import android.os.SystemClock;
import java.io.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.*;

/** Снимок показателей. Все блокирующие операции выполняются вне UI-потока. */
public final class Telemetry {
    public static final String[] KEYS = {"battery", "temperature", "ram", "network", "traffic", "ping", "cpu", "gpu"};
    public static final String[] TITLES = {"Заряд", "Температура батареи", "Заполнение оперативной памяти", "Тип подключения", "Текущий трафик", "Задержка до роутера", "Загрузка CPU", "Загрузка GPU"};
    private final ScreenFrames screenFrames=new ScreenFrames();
    private long lastTime, lastRx = -1, lastTx = -1, cpuTotal, cpuIdle;
    private String lastNetwork;
    private long pingTime;
    private String ping = "";
    public static boolean selected(Context c, String k) { return Prefs.get(c, "hud_" + k, !k.equals("cpu") && !k.equals("gpu")); }
    public static boolean supported(Context c, String k) {
        if (!k.equals("cpu") && !k.equals("gpu")) return true;
        return Shell.ready(c) && Prefs.get(c, "telemetry_" + k + "_available", false);
    }
    public static void probe(Context c) {
        if (!Shell.ready(c)) return;
        String cpu = Shell.run(c, "head -1 /proc/stat");
        Prefs.put(c, "telemetry_cpu_available", cpu != null && cpu.trim().startsWith("cpu "));
        String gpu = Shell.run(c, "cat /sys/class/kgsl/kgsl-3d0/gpubusy 2>/dev/null");
        Prefs.put(c, "telemetry_gpu_available", gpu != null && gpu.trim().matches("[0-9]+\\s+[0-9]+"));
    }
    public static final class Metric {
        public final String key,value,description;
        public final int color;
        Metric(String key,String value,String description){this(key,value,description,0);}
        Metric(String key,String value,String description,int color){this.key=key;this.value=value;this.description=description;this.color=color;}
    }
    public List<Metric> sample(Context c) {return sample(c,false);}
    public List<Metric> sampleAll(Context c) {return sample(c,true);}
    public static List<Metric> quick(Context c){
        List<Metric> values=new ArrayList<>();Booster.Info b=Booster.info(c);
        if(b.batteryPercent>=0)values.add(new Metric(b.charging?"charge":"battery",b.batteryPercent+"%","Заряд "+b.batteryPercent+"%"));
        if(!Float.isNaN(b.batteryTemp))values.add(temperatureMetric(b.batteryTemp));
        ActivityManager.MemoryInfo m=new ActivityManager.MemoryInfo();c.getSystemService(ActivityManager.class).getMemoryInfo(m);
        values.add(ramMetric(m));
        return values;
    }
    private static Metric temperatureMetric(float c){
        return new Metric("temperature",Math.round(c)+"°",String.format(Locale.ROOT,"Батарея %.1f °C\n%s",c,TemperatureStatus.label(c)),TemperatureStatus.color(c));
    }
    private static Metric ramMetric(ActivityManager.MemoryInfo m){
        int percent=MemoryUsage.percent(m.totalMem,m.availMem);
        return new Metric("ram",percent<0?"—":percent+"%",percent<0?"Ожидаем данные о памяти":String.format(Locale.ROOT,"Занято %d%% · %.1f из %.1f ГБ\nДоступно %.1f ГБ",percent,Math.max(0,m.totalMem-m.availMem)/1073741824.0,m.totalMem/1073741824.0,m.availMem/1073741824.0));
    }
    static List<Metric> selectedValues(Context c,List<Metric> values){
        List<Metric> out=new ArrayList<>();
        for(Metric m:values){String key=m.key;
            if(key.equals("charge"))key="battery";
            else if(key.equals("down")||key.equals("up"))key="traffic";
            else if(Arrays.asList("wifi","lan","vpn","cell","offline").contains(key))key="network";
            if(selected(c,key))out.add(m);
        }return out;
    }
    private List<Metric> sample(Context c,boolean allFields) {
        List<Metric> lines = new ArrayList<>();
        Booster.Info b = Booster.info(c);
        if ((allFields || selected(c,"battery")) && b.batteryPercent >= 0) lines.add(new Metric(b.charging?"charge":"battery",b.batteryPercent+"%","Заряд "+b.batteryPercent+"%"+(b.charging?", заряжается":"")));
        if ((allFields || selected(c,"temperature")) && !Float.isNaN(b.batteryTemp)) lines.add(temperatureMetric(b.batteryTemp));
        if ((allFields || selected(c,"ram"))) {
            ActivityManager.MemoryInfo m = new ActivityManager.MemoryInfo();
            c.getSystemService(ActivityManager.class).getMemoryInfo(m);
            lines.add(ramMetric(m));
        }
        ConnectivityManager cm = c.getSystemService(ConnectivityManager.class);
        Network n = cm.getActiveNetwork();
        NetworkCapabilities caps = n == null ? null : cm.getNetworkCapabilities(n);
        String type = "Нет сети", gateway = null;
        boolean activeVpn=caps!=null&&caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN);
        // A split-tunnel VPN need not be this application's default network.
        // Check all currently visible networks, not saved VPN profiles.
        boolean vpn=activeVpn;
        for(Network candidate:cm.getAllNetworks()){
            NetworkCapabilities nc=cm.getNetworkCapabilities(candidate);
            if(nc!=null&&nc.hasTransport(NetworkCapabilities.TRANSPORT_VPN)){
                vpn=true;break;
            }
        }
        Network transport=n;NetworkCapabilities underlying=caps;
        if(activeVpn){for(Network candidate:cm.getAllNetworks()){
            NetworkCapabilities nc=cm.getNetworkCapabilities(candidate);
            if(nc!=null&&!nc.hasTransport(NetworkCapabilities.TRANSPORT_VPN)&&nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)){
                transport=candidate;underlying=nc;
                if(nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)||nc.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))break;
            }
        }}
        if(underlying!=null){
            if(underlying.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))type="LAN";
            else if(underlying.hasTransport(NetworkCapabilities.TRANSPORT_WIFI))type="Wi-Fi";
            else if(underlying.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR))type="Мобильная сеть";
            else type=vpn?"VPN":"Другая сеть";
            if(type.equals("Wi-Fi")||type.equals("LAN")){
                LinkProperties lp=cm.getLinkProperties(transport);
                if(lp!=null)for(RouteInfo route:lp.getRoutes())if(route.isDefaultRoute()&&route.getGateway()!=null&&!route.getGateway().isAnyLocalAddress()){
                    gateway=route.getGateway().getHostAddress();if(route.getGateway() instanceof java.net.Inet4Address)break;
                }
            }
        }
        if(allFields||selected(c,"network")){
            String icon=type.equals("Wi-Fi")?"wifi":type.equals("LAN")?"lan":type.equals("Мобильная сеть")?"cell":"offline";
            lines.add(new Metric(icon,vpn?"VPN":"","Сеть: "+type));
        }
        long now = SystemClock.elapsedRealtime(), rx = TrafficStats.getTotalRxBytes(), tx = TrafficStats.getTotalTxBytes();
        String identity = n == null ? "none" : n.toString();
        if (!identity.equals(lastNetwork)) { lastTime = 0; pingTime = 0; ping = ""; lastNetwork = identity; }
        if ((allFields || selected(c,"traffic")) && rx >= 0 && tx >= 0 && lastTime > 0 && now > lastTime && rx >= lastRx && tx >= lastTx) {
            double down=(rx-lastRx)*8.0/(now-lastTime)/1000,up=(tx-lastTx)*8.0/(now-lastTime)/1000;
            lines.add(new Metric("down",HudFormat.rate(down),String.format(Locale.ROOT,"Сейчас: %.3f Мбит/с (%.1f КБ/с)",down,down*125)));
            lines.add(new Metric("up",HudFormat.rate(up),String.format(Locale.ROOT,"Сейчас: %.3f Мбит/с (%.1f КБ/с)",up,up*125)));
        }
        lastTime = now; lastRx = rx; lastTx = tx;
        if ((allFields || selected(c,"ping")) && gateway != null) {
            if (now - pingTime >= 2000 || pingTime == 0) { ping = ping(gateway); pingTime = now; }
            lines.add(new Metric("ping",HudFormat.ping(ping),"Задержка до роутера: "+ping));
        }
        if ((allFields || selected(c,"cpu")) && supported(c,"cpu")) {
            String s = Shell.run(c,"head -1 /proc/stat");
            if (s != null && s.trim().startsWith("cpu ")) try {
                String[] fields = s.trim().split("\\s+"); long total = 0;
                for (int i=1; i < Math.min(fields.length,9); i++) total += Long.parseLong(fields[i]);
                long idle = Long.parseLong(fields[4]) + (fields.length > 5 ? Long.parseLong(fields[5]) : 0);
                if (cpuTotal > 0 && total > cpuTotal && idle >= cpuIdle && idle-cpuIdle <= total-cpuTotal) {
                    String value=Math.round(100.0*(1-(idle-cpuIdle)/(double)(total-cpuTotal)))+"%";
                    lines.add(new Metric("cpu",value,"Текущая загрузка: "+value));
                }
                cpuTotal = total; cpuIdle = idle;
            } catch (RuntimeException ignored) { }
        }
        if ((allFields || selected(c,"gpu")) && supported(c,"gpu")) {
            String s = Shell.run(c,"cat /sys/class/kgsl/kgsl-3d0/gpubusy 2>/dev/null");
            if (s != null) try {
                String[] f = s.trim().split("\\s+"); long busy = Long.parseLong(f[0]), total = Long.parseLong(f[1]);
                if (total > 0 && busy >= 0 && busy <= total) {String value=Math.round(100.0*busy/total)+"%";lines.add(new Metric("gpu",value,"Текущая загрузка: "+value));}
            } catch (RuntimeException ignored) { }
        }
        screenFrames.sample(c,lines);
        return lines;
    }
    private String ping(String host) {
        Process p = null;
        try {
            p = new ProcessBuilder("/system/bin/ping","-n","-c","1","-W","1",host).redirectErrorStream(true).start();
            if (!p.waitFor(1500,TimeUnit.MILLISECONDS)) return "нет ответа";
            StringBuilder data = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) { String line; while ((line=r.readLine())!=null) data.append(line).append("\n"); }
            String out = data.toString();
            Matcher m = Pattern.compile("time[=<]([0-9.]+)\\s*ms").matcher(out);
            return m.find() ? m.group(1) + " мс" : "нет ответа";
        } catch (Exception e) { return "недоступно"; }
        finally { if (p != null) p.destroy(); }
    }
}
