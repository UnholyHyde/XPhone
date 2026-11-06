package ru.xphone.booster;
public class GameModeTransitionTest {
    static int count;static void check(boolean value){count++;if(!value)throw new AssertionError("check "+count);}
    public static void main(String[] args){
        for(String mode:new String[]{"standard","performance","battery","custom"}){
            check(GameModeTransition.unchanged(mode,mode));
            check(!GameModeTransition.blocked(mode,mode,true,true));
        }
        check(GameModeTransition.blocked("standard","performance",true,true));
        check(GameModeTransition.blocked("performance","standard",true,true));
        check(GameModeTransition.blocked(null,"performance",true,true));
        check(!GameModeTransition.blocked("standard","performance",true,false));
        check(!GameModeTransition.blocked("standard","performance",false,true));
        check(!GameModeTransition.unchanged("performance",null));
        check(!GameModeTransition.unchanged(null,"performance"));
        System.out.println("GameModeTransition: "+count+" checks passed");
    }
}
