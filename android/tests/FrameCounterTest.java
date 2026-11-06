package ru.xphone.booster;
public final class FrameCounterTest {
    private static int checks;
    static void check(boolean b){checks++;if(!b)throw new AssertionError("Проверка "+checks);}
    static String dump(long epoch,long frames){return "statsStart = "+epoch+"\ntotalFrames = "+frames+"\n";}
    public static void main(String[] args){
        FrameCounter c=new FrameCounter();
        check(Double.isNaN(c.sample(dump(1,100),1000)));
        check(Math.abs(c.sample(dump(1,194),3000)-47)<.001);
        check(c.sample(dump(1,194),5000)==0);
        check(Double.isNaN(c.sample(dump(2,4),7000)));
        check(c.sample(dump(2,124),9000)==60);
        check(Double.isNaN(c.sample(dump(2,1),11000)));
        check(Double.isNaN(c.sample(dump(2,101),19000)));
        check(Double.isNaN(c.sample("Permission denied",21000)));
        check(Double.isNaN(c.sample(dump(2,200),23000)));
        check(c.sample(dump(2,440),25000)==120);
        check(FrameCounter.number("totalFrames = -1","totalFrames")==-1);
        check(FrameCounter.number("totalFrames = 999999999999999999999","totalFrames")==-1);
        check(FrameCounter.number("totalFrames = 123\nother = 99","totalFrames")==123);
        System.out.println("FrameCounter: "+checks+" checks passed");
    }
}
