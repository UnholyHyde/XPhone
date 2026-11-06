package ru.xphone.booster;

public final class DisplayGeometryTest {
    private static int checks;
    private static void check(boolean ok){checks++;if(!ok)throw new AssertionError("check "+checks);}
    public static void main(String[] args){
        String sizes="Physical size: 1440x3120\nOverride size: 1080x2340\n";
        check(DisplayGeometry.size(sizes,false)[0]==1440);
        check(DisplayGeometry.size(sizes,true)[1]==2340);
        check(DisplayGeometry.size("Physical size: 1440x3120",true)==null);
        for(String s:new String[]{null,"Error: Permission denied","Physical size: 0x0","Physical size: 999999999999x1000","Physical size: 100000x100000"})check(DisplayGeometry.size(s,false)==null);
        String dpi="Physical density: 600\nOverride density: 548\n";
        check(DisplayGeometry.density(dpi,false)==600);check(DisplayGeometry.density(dpi,true)==548);
        check(DisplayGeometry.density("Physical density: 600",true)==0);
        check(DisplayGeometry.density("Physical density: 999999999999",false)==0);
        check(DisplayGeometry.scaledDensity(1440,1080,600)==450);
        check(DisplayGeometry.scaledDensity(1440,720,600)==300);
        check(DisplayGeometry.scaledDensity(1080,1440,450)==600);
        check(DisplayGeometry.scaledDensity(1440,1080,548)==411);
        check(DisplayGeometry.scaledDensity(0,1080,600)==0);
        check(DisplayGeometry.scaledDensity(1440,1,600)==72);
        check(DisplayGeometry.scaledDensity(1,16384,600)==2000);
        System.out.println("DisplayGeometry: "+checks+" checks passed");
    }
}
