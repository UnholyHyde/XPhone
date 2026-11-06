package ru.xphone.booster;

import java.net.*;
import java.util.*;

/** Local adapter addresses only; never guesses LAN from a VPN or mobile address. */
public final class PhoneNetwork {
    private PhoneNetwork(){}
    static boolean wiredName(String name){
        String n=name==null?"":name.toLowerCase(Locale.ROOT);
        return n.startsWith("eth")||n.startsWith("en")||n.startsWith("usb")||n.startsWith("rndis");
    }
    public static List<String> lanAddresses(){
        List<String> result=new ArrayList<>();
        try{
            Enumeration<NetworkInterface> all=NetworkInterface.getNetworkInterfaces();
            if(all==null)return result;
            while(all.hasMoreElements()){
                NetworkInterface nic=all.nextElement();
                if(!wiredName(nic.getName())||!nic.isUp()||nic.isLoopback())continue;
                Enumeration<InetAddress> addresses=nic.getInetAddresses();
                while(addresses.hasMoreElements()){
                    InetAddress ip=addresses.nextElement();
                    if(ip instanceof Inet4Address&&!ip.isLoopbackAddress()&&!ip.isAnyLocalAddress())result.add(ip.getHostAddress());
                }
            }
        }catch(Exception ignored){}
        Collections.sort(result);return result;
    }
}
