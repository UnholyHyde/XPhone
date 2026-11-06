package ru.xphone.booster;
import java.util.Arrays;

public final class PanelSymmetryTest {
    private static int checks;
    private static void check(boolean value){checks++;if(!value)throw new AssertionError("check "+checks);}
    public static void main(String[] args){
        check(HeaderSlots.canonical("charge").equals("battery"));
        for(String key:new String[]{"wifi","lan","cell","offline","vpn"}){
            check(HeaderSlots.canonical(key).equals("network"));check(HeaderSlots.weight(key)==HeaderSlots.weight("network"));
        }
        check(Arrays.equals(HeaderSlots.KEYS,new String[]{"battery","temperature","ram","network","down","up","ping","cpu","gpu","fps","hz"}));
        check(HeaderSlots.canonical("fps").equals("fps"));
        check(HeaderSlots.canonical("hz").equals("hz"));
        TacticalLayout l=new TacticalLayout(766,328,13,1);
        check(Arrays.equals(l.rowCounts(7),new int[]{2,2,2,1}));
        check(Arrays.equals(l.rowCounts(6),new int[]{2,2,1,1}));
        for(int count=0;count<=13;count++){
            TacticalLayout layout=new TacticalLayout(766,328,count,1);
            int[] left=layout.rowCounts((count+1)/2),right=layout.rowCounts(count/2);
            if(count>1)check(left.length==right.length);
            for(int[] bank:new int[][]{left,right})for(int cells:bank)check(cells>=1&&cells<=layout.columns);
            check(Arrays.stream(left).sum()+Arrays.stream(right).sum()==count);
            check(layout.fits(328));
        }
        System.out.println("PanelSymmetry: "+checks+" checks passed");
    }
}
