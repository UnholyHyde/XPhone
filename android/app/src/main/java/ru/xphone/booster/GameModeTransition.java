package ru.xphone.booster;
/** Samsung GOS can force-stop a game when the mode is applied, even repeatedly. */
final class GameModeTransition {
    static boolean unchanged(String current,String target){return target!=null&&target.equals(current);}
    static boolean blocked(String current,String target,boolean samsung,boolean running){return samsung&&running&&!unchanged(current,target);}
}
