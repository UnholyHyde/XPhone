package ru.xphone.booster;
import android.content.Context;
import android.graphics.*;
import android.view.*;
import java.util.*;
/** Seven-day chart with a real time axis; selection opens precise day values. */
final class PlayTimeChart extends View {
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);private GameHistory.Summary data;private int selected=6;
    private final java.util.function.Consumer<GameHistory.Day> listener;
    PlayTimeChart(Context c,java.util.function.Consumer<GameHistory.Day> listener){super(c);this.listener=listener;paint.setTypeface(Ui.font(c,500));setContentDescription("График игрового времени за семь дней. Нажмите столбец, чтобы выбрать день.");}
    void setData(GameHistory.Summary value){data=value;invalidate();if(value!=null&&value.days.size()>selected)listener.accept(value.days.get(selected));}
    private float dp(float n){return Ui.dp(getContext(),n);}
    protected void onDraw(Canvas canvas){super.onDraw(canvas);if(data==null||data.days.isEmpty())return;
        float left=dp(43),right=getWidth()-dp(8),top=dp(20),bottom=getHeight()-dp(36),height=Math.max(1,bottom-top),step=(right-left)/7f;
        long peak=60000;for(GameHistory.Day d:data.days)peak=Math.max(peak,d.ms);long maxMinutes=(long)Math.ceil(peak/60000.0);long scale=maxMinutes*60000;
        paint.setTextSize(dp(9));paint.setStrokeWidth(dp(1));
        for(int i=0;i<=2;i++){float y=bottom-height*i/2f;paint.setColor(Ui.LINE2);canvas.drawLine(left,y,right,y,paint);paint.setColor(Ui.MUTED);String label=Math.round(maxMinutes*i/2f)+" м";canvas.drawText(label,left-dp(5)-paint.measureText(label),y+dp(3),paint);}
        for(int i=0;i<data.days.size();i++){GameHistory.Day d=data.days.get(i);float center=left+(i+.5f)*step,width=Math.min(dp(28),step*.58f),h=height*d.ms/Math.max(1,scale);
            paint.setColor(i==selected?Ui.ACCENT:Ui.SILVER);paint.setAlpha(i==selected?255:140);
            if(d.knownTime){if(d.ms>0)canvas.drawRoundRect(center-width/2,bottom-Math.max(dp(2),h),center+width/2,bottom,dp(4),dp(4),paint);}
            else{paint.setAlpha(255);String dash="—";canvas.drawText(dash,center-paint.measureText(dash)/2,bottom-dp(5),paint);}
            paint.setAlpha(255);paint.setColor(i==selected?Ui.ACCENT:Ui.MUTED);paint.setTextSize(dp(10));String day=String.format(java.util.Locale.ROOT,"%02d.%02d",d.date.getDayOfMonth(),d.date.getMonthValue());canvas.drawText(day,center-paint.measureText(day)/2,bottom+dp(20),paint);
        }
    }
    @android.annotation.SuppressLint("ClickableViewAccessibility") public boolean onTouchEvent(MotionEvent e){if(e.getActionMasked()==MotionEvent.ACTION_UP&&data!=null){float step=(getWidth()-dp(51))/7f;selected=Math.max(0,Math.min(6,(int)((e.getX()-dp(43))/Math.max(1,step))));invalidate();if(data.days.size()>selected)listener.accept(data.days.get(selected));performClick();}return true;}
    public boolean performClick(){super.performClick();return true;}
}
