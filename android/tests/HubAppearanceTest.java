package ru.xphone.booster;

public final class HubAppearanceTest {
    private static int checks;
    private static void check(boolean ok){checks++;if(!ok)throw new AssertionError("check "+checks);}
    public static void main(String[] args){
        check(AccentColor.hex("#56D88B")==0xFF56D88B);
        check(AccentColor.hex(" 56d88b ")==0xFF56D88B);
        check(AccentColor.hex("#000000")==0xFF000000);
        for(String s:new String[]{null,"","#FFF","#12345678","#GG0000","#12 345","red","0xFFFFFF"})check(AccentColor.hex(s)==null);
        check(AccentColor.rgb("86","216","139")==0xFF56D88B);
        check(AccentColor.rgb("0","0","0")==0xFF000000);
        check(AccentColor.rgb("255","255","255")==0xFFFFFFFF);
        for(String s:new String[]{null,"","-1","256","999","1.5","+1","x"})check(AccentColor.rgb(s,"0","0")==null);
        check(AccentColor.foreground(0xFF000000)==0xFFFFFFFF);
        check(AccentColor.foreground(0xFFFFFFFF)==0xFF000000);
        check(AccentColor.foreground(0xFF0000FF)==0xFFFFFFFF);
        check(AccentColor.foreground(0xFF00FF00)==0xFF000000);
        check(AccentColor.text(0xFF00AA09).equals("#00AA09"));
        for(int value=0;value<=255;value++)check(AccentColor.hex(AccentColor.text(0xFF000000|value*0x010101))==(0xFF000000|value*0x010101));
        int[][] screens={{320,720},{411,891},{600,960},{891,411},{960,600},{1280,800}};
        for(int[] screen:screens)for(int count=0;count<=13;count++){
            TacticalLayout l=new TacticalLayout(screen[0],screen[1],count,1f);
            check(l.fits(screen[1]));check(l.bankWidth*2<screen[0]);check(l.tileHeight>=44);
            int needed=((count+1)/2+l.columns-1)/l.columns;check(l.rows>=needed);
        }
        TacticalLayout safe=new TacticalLayout(766,328,13,1f);
        check(safe.fits(328));check(safe.tileHeight>=44);check(safe.rows*safe.tileHeight+(safe.rows-1)*6+84<=328);
        check(!new TacticalLayout(640,240,13,1.5f).fits(240));
        System.out.println("HubAppearance: "+checks+" checks passed");
    }
}
