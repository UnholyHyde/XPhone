package ru.xphone.booster;
import java.util.*;
/** Tasks arrive most-recent first. Prefer a working activity over a launcher trampoline. */
final class GameTaskChoice {
    static final class Task{final int id,user;final String base,top,cls;Task(int id,int user,String base,String top,String cls){this.id=id;this.user=user;this.base=base;this.top=top;this.cls=cls;}}
    static int choose(String pkg,String launcher,int user,List<Task> tasks){int fallback=-1;for(Task t:tasks){if(t.id<0||t.user!=user||!pkg.equals(t.base)||!pkg.equals(t.top)||t.cls==null)continue;if(!launcher.equals(t.cls))return t.id;if(fallback<0)fallback=t.id;}return fallback;}
}
