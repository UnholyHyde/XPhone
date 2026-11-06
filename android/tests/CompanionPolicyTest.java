package ru.xphone.booster;
import java.util.*;
public final class CompanionPolicyTest {
    private static int checks;
    private static void check(boolean ok,String reason){checks++;if(!ok)throw new AssertionError(reason);}
    public static void main(String[] args) {
        for(String pkg:new String[]{"org.telegram.messenger","com.example_app.editor2","ru.xphone.booster"})check(CompanionPolicy.packageName(pkg),"Valid package");
        for(String pkg:new String[]{"com.chat;reboot","com.chat $(id)","com..chat","com/chat","com.chat\n","",null})check(!CompanionPolicy.packageName(pkg),"Invalid package: "+pkg);
        check(CompanionPolicy.normalize(Arrays.asList("com.a","com.a","com.b","bad")).equals(Arrays.asList("com.a","com.b")),"Unique valid selection");
        check(CompanionPolicy.normalize(Arrays.asList("com.g","com.f","com.e","com.d","com.c","com.b","com.a")).size()==6,"Limit");
        List<CompanionPolicy.App> apps=Arrays.asList(
            new CompanionPolicy.App("com.game",10001,false),
            new CompanionPolicy.App("com.chat",10002,false),
            new CompanionPolicy.App("com.chat.worker",10002,false),
            new CompanionPolicy.App("com.sys",10003,true),
            new CompanionPolicy.App("com.sys.shared",10003,false),
            new CompanionPolicy.App("com.ordinary",10004,false),
            new CompanionPolicy.App("com.ordinary",10004,false),
            new CompanionPolicy.App("com.root",1000,false),
            new CompanionPolicy.App("com.inject;reboot",10005,false));
        check(CompanionPolicy.cleanTargets(apps,new HashSet<>(Arrays.asList("com.game","com.chat"))).equals(Arrays.asList("com.ordinary")),"Protect selection, game and shared UIDs; reject injection");
        check(CompanionPolicy.cleanTargets(apps,new HashSet<>(Arrays.asList("com.game","com.chat","com.ordinary"))).isEmpty(),"All protected");
        check(CompanionPolicy.cleanTargets(Collections.emptyList(),Collections.emptySet()).isEmpty(),"Empty list");
        for(int[] size:new int[][]{{1440,3120},{3120,1440},{720,1280},{1280,720},{240,320}}){
            int[] r=CompanionGeometry.window(size[0],size[1]);
            check(r[0]>=0&&r[1]>=0&&r[2]<=size[0]&&r[3]<=size[1]&&r[2]-r[0]>=96&&r[3]-r[1]>=96,"Window fits both orientations");
        }
        check(CompanionGeometry.clamp(-20,411,48)==0,"Drag top bound");
        check(CompanionGeometry.clamp(999,411,48)==363,"Drag bottom bound");
        check(CompanionGeometry.clamp(90,20,48)==0,"Screen smaller than icon");
        for(boolean glass:new boolean[]{true,false}) {
            PanelLayout layout=new PanelLayout(891,411,13,glass,1,false);
            int rows=(13+layout.columns-1)/layout.columns;
            check(218+rows*layout.tileHeight+(rows-1)*6<=411-24,"13 buttons fit landscape");
        }
        System.out.println("CompanionPolicy: "+checks+" checks passed");
    }
}
