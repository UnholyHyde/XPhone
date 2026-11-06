package ru.xphone.booster;

import android.content.*;
import android.graphics.*;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.widget.ImageView;
import java.io.*;

final class PlayerProfile {
    static String name(Context c){return Prefs.sp(c).getString("player_name","Игрок");}
    static String device(Context c){
        String local=Prefs.sp(c).getString("device_alias","");if(!local.isEmpty())return local;
        try{String n=Settings.Global.getString(c.getContentResolver(),"device_name");if(n!=null&&!n.trim().isEmpty()&&!n.trim().equals(Build.MODEL))return n;}catch(Exception ignored){}
        if(Build.MODEL.startsWith("SM-S938"))return "Samsung Galaxy S25 Ultra";
        return Build.MANUFACTURER+" "+Build.MODEL;
    }
    static void avatar(Context c,ImageView view){
        File file=new File(c.getFilesDir(),"player-avatar.jpg");
        Bitmap bitmap=file.exists()?BitmapFactory.decodeFile(file.getPath()):null;
        if(bitmap!=null)view.setImageBitmap(bitmap);else view.setImageResource(R.drawable.ic_user);
        view.setScaleType(ImageView.ScaleType.CENTER_CROP);view.setClipToOutline(true);view.setBackground(Ui.box(c,Ui.FIELD,Ui.LINE2,14));
    }
    static boolean importAvatar(Context c,Uri uri){
        try{
            BitmapFactory.Options options=new BitmapFactory.Options();options.inJustDecodeBounds=true;
            try(InputStream in=c.getContentResolver().openInputStream(uri)){BitmapFactory.decodeStream(in,null,options);}
            if(options.outWidth<=0||options.outHeight<=0)return false;
            options.inSampleSize=1;while(Math.max(options.outWidth,options.outHeight)/options.inSampleSize>1024)options.inSampleSize*=2;
            options.inJustDecodeBounds=false;Bitmap decoded;
            try(InputStream in=c.getContentResolver().openInputStream(uri)){decoded=BitmapFactory.decodeStream(in,null,options);}
            if(decoded==null)return false;
            int edge=Math.min(decoded.getWidth(),decoded.getHeight());Bitmap square=Bitmap.createBitmap(decoded,(decoded.getWidth()-edge)/2,(decoded.getHeight()-edge)/2,edge,edge);
            Bitmap small=Bitmap.createScaledBitmap(square,384,384,true);File temp=new File(c.getFilesDir(),"player-avatar.tmp");
            try(FileOutputStream out=new FileOutputStream(temp)){if(!small.compress(Bitmap.CompressFormat.JPEG,90,out))return false;}
            File target=new File(c.getFilesDir(),"player-avatar.jpg");return temp.renameTo(target);
        }catch(Exception|OutOfMemoryError e){return false;}
    }
}
