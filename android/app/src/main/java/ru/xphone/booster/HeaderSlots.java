package ru.xphone.booster;

/** Slot identity and width do not depend on the latest sample or its text length. */
final class HeaderSlots {
    static final String[] KEYS={"battery","temperature","ram","network","down","up","ping","cpu","gpu","fps","hz"};
    static String canonical(String key){
        switch(key){case "charge":return "battery";case "wifi":case "lan":case "vpn":case "cell":case "offline":return "network";default:return key;}
    }
    static int weight(String key){switch(canonical(key)){case "network":return 6;case "ram":case "down":case "up":case "ping":return 6;default:return 5;}}
}
