package ru.xphone.booster;
import java.util.*;
public class GameTaskChoiceTest {
    static int n;static void check(boolean value){n++;if(!value)throw new AssertionError("check "+n);}
    static GameTaskChoice.Task t(int id,int user,String base,String top,String cls){return new GameTaskChoice.Task(id,user,base,top,cls);}
    static int choose(GameTaskChoice.Task... tasks){return GameTaskChoice.choose("game.pkg","Launcher",0,Arrays.asList(tasks));}
    public static void main(String[] args){
        check(choose()==-1);
        check(choose(t(1,0,"game.pkg","game.pkg","Launcher"))==1);
        check(choose(t(1,0,"game.pkg","game.pkg","Launcher"),t(2,0,"game.pkg","game.pkg","Unity"))==2);
        check(choose(t(3,0,"game.pkg","game.pkg","Unity"),t(2,0,"game.pkg","game.pkg","Game"))==3);
        check(choose(t(1,10,"game.pkg","game.pkg","Unity"))==-1);
        check(choose(t(1,0,"other","game.pkg","Unity"))==-1);
        check(choose(t(1,0,"game.pkg","other","Unity"))==-1);
        check(choose(t(1,0,null,"game.pkg","Unity"))==-1);
        check(choose(t(1,0,"game.pkg",null,"Unity"))==-1);
        check(choose(t(1,0,"game.pkg","game.pkg",null))==-1);
        check(choose(t(-1,0,"game.pkg","game.pkg","Unity"))==-1);
        check(choose(t(1,0,"game.pkg","game.pkg","Launcher"),t(2,10,"game.pkg","game.pkg","Unity"))==1);
        System.out.println("GameTaskChoice: "+n+" checks passed");
    }
}
