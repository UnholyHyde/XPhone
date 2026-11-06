package ru.xphone.booster;
import java.util.*;
public final class FocusHudTest {
    static int checks;
    static void check(boolean pass){checks++;if(!pass)throw new AssertionError("check "+checks);}
    public static void main(String[] args){
        List<Integer> modes=Arrays.asList(0,1,2,3);
        for(int i=0;i<4;i++)check(GameModeInfo.nextChoice(modes,i)==(i+1)%4);
        check(GameModeInfo.nextChoice(Arrays.asList(0,1,3),1)==3);
        check(GameModeInfo.nextChoice(Arrays.asList(0,1,3),3)==0);
        check(GameModeInfo.nextChoice(Collections.emptyList(),3)==-1);
        for(int requested=-10;requested<=110;requested++){
            int a=HudFormat.opacity(requested,true,true),b=HudFormat.opacity(90,true,true);
            check(a>=5&&a<=20);
            check(1-(1-.65)*(1-a/100.0)*(1-b/100.0)<.8);
            check(HudFormat.opacity(requested,true,false)<=40);
        }
        check(HudFormat.opacity(90,false,true)==90);
        System.out.println("FocusHud: "+checks+" checks passed");
    }
}
