package ru.xphone.booster;
public class HandleGeometryTest {
    static int checks;static void check(boolean value){checks++;if(!value)throw new AssertionError("check "+checks);}
    public static void main(String[] args){
        int[][] sizes={{1080,2340},{2340,1080},{1440,3120},{3120,1440},{800,1280},{2560,1600},{320,480}};
        for(int[] size:sizes)for(int edge=0;edge<4;edge++)for(float ratio:new float[]{0,.25f,.5f,.9f,1,Float.NaN}){
            int[] p=HandleGeometry.place(size[0],size[1],24,30,24,40,12,24,76,edge,ratio);
            check(p[0]>=36&&p[1]>=42);check(p[0]+p[2]<=size[0]-36);check(p[1]+p[3]<=size[1]-52);
            check(p[2]==(edge<2?24:76));check(p[3]==(edge<2?76:24));
            if(!Float.isNaN(ratio)){
                float actual=edge<2?HandleGeometry.ratio(p[1]+p[3]/2f,size[1],30,40,12,76):HandleGeometry.ratio(p[0]+p[2]/2f,size[0],24,24,12,76);
                check(Math.abs(actual-ratio)<.003);
            }
        }
        for(int edge=0;edge<4;edge++){
            float dx=edge==0?30:edge==1?-30:0,dy=edge==2?30:edge==3?-30:0;
            check(HandleGeometry.inward(edge,dx,dy,26));check(!HandleGeometry.inward(edge,-dx,-dy,26));check(!HandleGeometry.inward(edge,0,0,26));check(!HandleGeometry.inward(edge,dx/2,dy/2,26));
        }
        check(!HandleGeometry.inward(0,30,30,26));check(!HandleGeometry.inward(2,30,30,26));
        check(HandleGeometry.nearest(30,400,1080,2340,0,0,0,0)==0);
        check(HandleGeometry.nearest(1040,400,1080,2340,0,0,0,0)==1);
        check(HandleGeometry.nearest(500,30,1080,2340,0,0,0,0)==2);
        check(HandleGeometry.nearest(500,2300,1080,2340,0,0,0,0)==3);
        System.out.println("HandleGeometry: "+checks+" checks passed");
    }
}
