package ru.xphone.booster;

/** XPhone battery-temperature display bands, not device shutdown limits. */
final class TemperatureStatus {
    static int color(float c){
        if(Float.isNaN(c))return 0;
        if(c>=45)return 0xFFFF6464;
        if(c>=40)return 0xFFFFA04D;
        if(c>=35)return 0xFFFFD85A;
        return 0xFF72D99B;
    }
    static String label(float c){
        if(Float.isNaN(c))return "Нет данных";
        if(c>=45)return "Сильный нагрев. Приостановите игру и дайте телефону остыть.";
        if(c>=40)return "Горячий. Лучше снизить нагрузку или сделать перерыв.";
        if(c>=35)return "Тёплый";
        return "Нормальная температура";
    }
}
