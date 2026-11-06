package ru.xphone.booster;

/** Bounded edge banks: no scroll and no placeholder cells. Sizes in dp. */
final class TacticalLayout {
    final int bankWidth, columns, tileHeight, rows;
    final boolean landscape;
    TacticalLayout(int width,int height,int count,float fontScale) {
        landscape=width>height;
        bankWidth=landscape?Math.max(140,Math.min(290,(int)(width*.30))):Math.max(110,(width-36)/2);
        columns=bankWidth>=145?2:1;
        int perBank=(Math.max(0,count)+1)/2;
        rows=Math.max(1,(perBank+columns-1)/columns);
        int space=Math.max(0,height-84);
        tileHeight=Math.max((int)Math.ceil(44*Math.max(1,fontScale)),Math.min(76,(space-Math.max(0,rows-1)*6)/rows));
    }
    int[] rowCounts(int count){
        int n=Math.min(rows,Math.max(0,count));int[] result=new int[n];int left=count;
        for(int i=0;i<n;i++){result[i]=Math.min(columns,left-(n-i-1));left-=result[i];}
        return result;
    }
    boolean fits(int height){return rows*tileHeight+Math.max(0,rows-1)*6<=height-84;}
}
