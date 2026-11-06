package ru.xphone.booster;
public class WelcomePlacementTest {
    static int n;static void check(boolean b){n++;if(!b)throw new AssertionError("check "+n);}
    public static void main(String[] args){
        for(int[] size:new int[][]{{1080,2340},{2340,1080},{1440,3120},{3120,1440},{800,1280}})for(int edge=0;edge<4;edge++)for(float ratio:new float[]{0,.25f,.5f,.75f,1}){
            int[] a=HandleGeometry.place(size[0],size[1],0,0,0,4,6,24,56,edge,ratio);
            int[] p=WelcomePlacement.place(size[0],size[1],a[0],a[1],a[2],a[3],edge,230,56,8,8);
            check(p[0]>=8&&p[1]>=8);check(p[0]+p[2]<=size[0]-8);check(p[1]+p[3]<=size[1]-8);
            if(edge==0)check(p[0]==a[0]+a[2]+8);if(edge==1)check(p[0]+p[2]+8==a[0]);
            if(edge==2)check(p[1]==a[1]+a[3]+8);if(edge==3)check(p[1]+p[3]+8==a[1]);
            if(ratio==.5f)check(edge<2?Math.abs((p[1]*2+p[3])-(a[1]*2+a[3]))<=1:Math.abs((p[0]*2+p[2])-(a[0]*2+a[2]))<=1);
        }
        System.out.println("WelcomePlacement: "+n+" checks passed");
    }
}
