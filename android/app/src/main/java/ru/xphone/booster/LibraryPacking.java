package ru.xphone.booster;
import java.util.*;

/** First-fit, two-column packing: later small cards fill earlier half-empty rows. */
final class LibraryPacking {
    static List<int[]> rows(boolean[] wide){
        List<int[]> rows=new ArrayList<>();
        for(int i=0;i<wide.length;i++){
            int[] target=null;
            for(int[] row:rows)if(wide[i]?row[0]<0&&row[1]<0:row[0]<0||row[1]<0){target=row;break;}
            if(target==null){target=new int[]{-1,-1};rows.add(target);}
            if(wide[i]){target[0]=i;target[1]=i;}
            else target[target[0]<0?0:1]=i;
        }return rows;
    }
}
