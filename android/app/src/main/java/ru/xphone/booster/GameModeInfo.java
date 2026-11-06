package ru.xphone.booster;

import java.util.*;
import java.util.regex.*;

/** Parse Android's declared capabilities; never infer modes from manufacturer. */
final class GameModeInfo {
    final String current;
    final Set<String> available;
    GameModeInfo(String current,Set<String> available){this.current=current;this.available=Collections.unmodifiableSet(available);}
    static GameModeInfo parse(String text){
        if(text==null)text="";
        Matcher current=Pattern.compile("current mode: *([a-z]+)").matcher(text);
        Matcher modes=Pattern.compile("available game modes: *\\[([^\\]]*)\\]").matcher(text);
        Set<String> result=new LinkedHashSet<>();
        if(modes.find())for(String mode:modes.group(1).split(","))if(validMode(mode.trim()))result.add(mode.trim());
        String selected=current.find()?current.group(1):null;
        return new GameModeInfo(validMode(selected)?selected:null,result);
    }
    static int nextChoice(List<Integer> choices,int current){
        if(choices.isEmpty())return -1;
        int at=choices.indexOf(current);
        return choices.get(at<0?0:(at+1)%choices.size());
    }
    static boolean validMode(String mode){return "standard".equals(mode)||"performance".equals(mode)||"battery".equals(mode)||"custom".equals(mode);}
    static boolean validPackage(String name){return name!=null && name.matches("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+");}
}
