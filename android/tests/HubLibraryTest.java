package ru.xphone.booster;
import java.util.*;

public final class HubLibraryTest {
    private static int checks;
    private static void check(boolean ok){checks++;if(!ok)throw new AssertionError("check "+checks);}
    public static void main(String[] args){
        for(int length=0;length<=9;length++)for(int mask=0;mask<(1<<length);mask++){
            boolean[] wide=new boolean[length];for(int i=0;i<length;i++)wide[i]=(mask&(1<<i))!=0;
            List<int[]> rows=LibraryPacking.rows(wide);int[] counts=new int[length];
            for(int[] row:rows){check(row.length==2);for(int id:row)if(id>=0){check(id<length);counts[id]++;}}
            for(int i=0;i<length;i++)check(counts[i]==(wide[i]?2:1));
        }
        List<int[]> fill=LibraryPacking.rows(new boolean[]{false,true,false});
        check(fill.size()==2);check(Arrays.equals(fill.get(0),new int[]{0,2}));check(Arrays.equals(fill.get(1),new int[]{1,1}));
        check(RecordingPolicy.valid("XPhone-20261106-123456.mp4",12));
        for(String value:new String[]{null,"../video.mp4","XPhone-20261106-123456.mp4;id","/sdcard/test.mp4","x.mp4","XPhone-20261106-123456.mp4\n"})check(!RecordingPolicy.valid(value,12));
        check(!RecordingPolicy.valid("XPhone-20261106-123456.mp4",3));check(!RecordingPolicy.valid("XPhone-20261106-123456.mp4",31));
        System.out.println("HubLibrary: "+checks+" checks passed");
    }
}
