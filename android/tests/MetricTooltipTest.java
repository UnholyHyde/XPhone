package ru.xphone.booster;
public final class MetricTooltipTest {
    private static int checks;
    private static void check(boolean value){checks++;if(!value)throw new AssertionError("check "+checks);}
    public static void main(String[] args){
        check(MemoryUsage.percent(100,0)==100);check(MemoryUsage.percent(100,100)==0);
        check(MemoryUsage.percent(100,33)==67);check(MemoryUsage.percent(100,200)==0);
        check(MemoryUsage.percent(0,0)==-1);check(MemoryUsage.percent(100,-1)==-1);
        check(MemoryUsage.percent(Long.MAX_VALUE,0)==100);
        check(MemoryUsage.percent(12L*1073741824,3L*1073741824)==75);
        int[] middle=TooltipPlacement.place(10,20,710,400,330,30,390,70,260,120,6);
        check(middle[0]==230);check(middle[1]==76);
        for(int x:new int[]{-20,10,30,350,690,720})for(int y:new int[]{30,250,390}){
            int[] p=TooltipPlacement.place(10,20,710,400,x,y,x+50,y+30,260,120,6);
            check(p[0]>=10&&p[0]+p[2]<=710);check(p[1]>=20&&p[1]+p[3]<=400);
        }
        int[] small=TooltipPlacement.place(5,5,105,85,50,30,70,50,260,120,6);
        check(small[2]==100&&small[3]==80);
        System.out.println("MetricTooltip: "+checks+" checks passed");
    }
}
