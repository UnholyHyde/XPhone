package ru.xphone.booster;
public final class HudFormatTest {
    private static int checks;
    public static void main(String[] args){
        check(HudFormat.opacity(90,false)==90,"normal opacity preserved");
        check(HudFormat.opacity(1,false)==5,"very low visible limit");
        check(HudFormat.opacity(120,false)==100,"normal max");
        check(HudFormat.opacity(90,true)==40,"pinned pass-through max");
        check(HudFormat.opacity(5,true)==5,"pinned low opacity");
        check(1-(1-.65)*(1-HudFormat.opacity(90,true)/100.0)<.8,"combined touch glow opacity safe");
        check("0К".equals(HudFormat.rate(0)),"idle traffic");
        check("250К".equals(HudFormat.rate(.25)),"kilobits");
        check("12.5М".equals(HudFormat.rate(12.5)),"megabits");
        check("1.5Г".equals(HudFormat.rate(1500)),"gigabits");
        check("—".equals(HudFormat.rate(Double.NaN)),"invalid rate");
        check("—".equals(HudFormat.rate(-1)),"unsupported counter");
        check("4мс".equals(HudFormat.ping("3.7 мс")),"numeric ping");
        check("—".equals(HudFormat.ping("нет ответа")),"missing reply");
        check("—".equals(HudFormat.ping(null)),"no route");
        System.out.println("HudFormat: "+checks+" checks passed");
    }
    static void check(boolean ok,String why){checks++;if(!ok)throw new AssertionError(why);}
}
