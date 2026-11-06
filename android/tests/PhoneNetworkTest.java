package ru.xphone.booster;
public final class PhoneNetworkTest {
    public static void main(String[] args){
        for(String s:new String[]{"eth0","enp0s1","usb0","rndis0","ETH1"})if(!PhoneNetwork.wiredName(s))throw new AssertionError(s);
        for(String s:new String[]{"wlan0","tun0","rmnet_data0","lo",null})if(PhoneNetwork.wiredName(s))throw new AssertionError(s);
        System.out.println("PhoneNetwork: 10 interface checks passed");
    }
}
