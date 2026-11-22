package ru.xphone.booster;
public final class PopupPlacementTest {
    public static void main(String[] args){
        int[] landscape=PopupPlacement.place(3120,1440,1450,3070,1000,1088,1300,45);
        check(landscape[0]+landscape[2]<1450,"beside right panel");
        bounded(landscape,3120,1440);
        int[] left=PopupPlacement.place(3120,1440,45,1685,200,1088,900,45);
        check(left[0]>1685,"beside left panel"); bounded(left,3120,1440);
        bounded(PopupPlacement.place(1440,3120,45,1395,3000,1088,2000,45),1440,3120);
        bounded(PopupPlacement.place(320,240,10,310,-100,1088,2000,12),320,240);
        System.out.println("PopupPlacement: side positioning and 4 boundary cases passed");
    }
    static void bounded(int[] p,int w,int h){check(p[0]>=0&&p[1]>=0&&p[0]+p[2]<=w&&p[1]+p[3]<=h,"inside screen");}
    static void check(boolean ok,String what){if(!ok)throw new AssertionError(what);}
}
