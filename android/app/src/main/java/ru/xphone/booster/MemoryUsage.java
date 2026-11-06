package ru.xphone.booster;
final class MemoryUsage {
    static int percent(long total,long available){
        if(total<=0||available<0)return -1;
        return (int)Math.round(100.0*(1-Math.min(total,available)/(double)total));
    }
}
