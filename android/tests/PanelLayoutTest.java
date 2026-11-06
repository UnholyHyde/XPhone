package ru.xphone.booster;

public final class PanelLayoutTest {
    public static void main(String[] args) {
        int checks=0;
        for(boolean glass:new boolean[]{false,true}) {
            for(int count:new int[]{0,1,4,8,12}) {
                for(int[] size:new int[][]{{411,891},{891,411},{360,800},{800,360}}) {
                    PanelLayout p=new PanelLayout(size[0],size[1],count,glass,1,false);
                    if(p.width>size[0]-24 || p.tileHeight<76 || p.columns<1)
                        throw new AssertionError("Invalid bounds");
                    int rows=(count+p.columns-1)/p.columns;
                    int height=218+rows*p.tileHeight+Math.max(0,rows-1)*6;
                    if(size[1]>=400 && height>size[1]-24) throw new AssertionError("Does not fit: "+size[0]+"x"+size[1]+" count="+count);
                    checks++;
                }
            }
        }
        PanelLayout large=new PanelLayout(640,320,12,true,1.5f,true);
        if(large.tileHeight<96)throw new AssertionError("Large font shrunk");
        System.out.println("PanelLayout: "+(checks+1)+" checks passed");
    }
}
