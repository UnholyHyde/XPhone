package ru.xphone.booster;

/** Pure RGB validation and readable foreground selection. Alpha is never user supplied. */
final class AccentColor {
    static Integer hex(String value) {
        if(value==null)return null;
        String s=value.trim();if(s.startsWith("#"))s=s.substring(1);
        if(!s.matches("[0-9a-fA-F]{6}"))return null;
        return (int)(0xFF000000L|Long.parseLong(s,16));
    }
    static Integer rgb(String r,String g,String b) {
        try {
            String[] values={r,g,b};int color=0xFF000000;
            for(int i=0;i<3;i++) {
                if(values[i]==null||!values[i].trim().matches("[0-9]{1,3}"))return null;
                int n=Integer.parseInt(values[i].trim());if(n>255)return null;
                color|=n<<(16-i*8);
            }
            return color;
        }catch(NumberFormatException e){return null;}
    }
    static String text(int color){return String.format(java.util.Locale.ROOT,"#%06X",color&0xFFFFFF);}
    private static double linear(int n){double v=n/255.0;return v<=0.04045?v/12.92:Math.pow((v+0.055)/1.055,2.4);}
    static int foreground(int color){
        double l=0.2126*linear((color>>16)&255)+0.7152*linear((color>>8)&255)+0.0722*linear(color&255);
        return (l+0.05)/0.05>=1.05/(l+0.05)?0xFF000000:0xFFFFFFFF;
    }
}
