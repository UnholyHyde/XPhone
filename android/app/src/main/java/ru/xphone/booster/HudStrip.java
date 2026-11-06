package ru.xphone.booster;
import android.content.Context;
import android.graphics.*;
import android.graphics.drawable.Drawable;
import android.view.View;
import java.util.*;
/** One line of Lucide icons and numbers, scaled to fit the screen. */
final class HudStrip extends View {
    private final Paint text=new Paint(Paint.ANTI_ALIAS_FLAG),separator=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Map<String,Drawable> icons=new HashMap<>();
    private List<Telemetry.Metric> metrics=Collections.emptyList();
    private float scale=1;
    private int maxWidth=10000;
    private final int padding,iconSize,gap,itemGap,baseHeight;
    HudStrip(Context c){
        super(c);
        android.widget.TextView model=Ui.text(c,"",10.5f,Ui.TEXT,600);
        text.setTypeface(model.getTypeface());text.setTextSize(model.getTextSize());
        padding=Ui.dp(c,5);iconSize=Ui.dp(c,11);gap=Ui.dp(c,2);itemGap=Ui.dp(c,3);baseHeight=Ui.dp(c,24);
        setContentDescription("Строка показателей");
    }
    static int icon(String key){
        switch(key){
            case "charge":return R.drawable.ic_hud_charge;
            case "battery":return R.drawable.ic_battery;
            case "temperature":return R.drawable.ic_thermo;
            case "ram":return R.drawable.ic_hud_ram;
            case "fps":return R.drawable.ic_frame_trace;
            case "hz":return R.drawable.ic_monitor;
            case "cpu":return R.drawable.ic_hud_cpu;
            case "gpu":return R.drawable.ic_hud_gpu;
            case "wifi":case "network":return R.drawable.ic_hud_wifi;
            case "lan":return R.drawable.ic_hud_lan;
            case "cell":return R.drawable.ic_hud_cell;
            case "vpn":return R.drawable.ic_shield;
            case "vpn_off":return R.drawable.ic_shield_x;
            case "offline":return R.drawable.ic_hud_offline;
            case "traffic":case "down":return R.drawable.ic_hud_down;
            case "up":return R.drawable.ic_hud_up;
            case "ping":return R.drawable.ic_timer;
            default:return R.drawable.ic_info;
        }
    }
    void update(List<Telemetry.Metric> values){
        Map<String,Telemetry.Metric> available=new HashMap<>();for(Telemetry.Metric m:values)available.put(HeaderSlots.canonical(m.key),m);
        metrics=new ArrayList<>();
        for(String key:HeaderSlots.KEYS){if(key.equals("fps")||key.equals("hz"))continue;String preference=key.equals("down")||key.equals("up")?"traffic":key;
            if(!Telemetry.selected(getContext(),preference)||!Telemetry.supported(getContext(),preference))continue;
            metrics.add(available.containsKey(key)?available.get(key):new Telemetry.Metric(key,"—",preference+": замер ещё не получен"));
        }
        StringBuilder description=new StringBuilder();
        for(Telemetry.Metric m:metrics){if(description.length()>0)description.append("; ");description.append(m.description);}
        setContentDescription(description.length()==0?"Выберите показатели в настройках":description.toString());
        requestLayout();invalidate();
    }
    void maxWidth(int pixels){if(maxWidth!=pixels){maxWidth=pixels;requestLayout();}}
    @Override protected void onMeasure(int ws,int hs){
        float width=padding*2;
        if(metrics.isEmpty())width+=text.measureText("Выберите показатели");
        for(int i=0;i<metrics.size();i++){
            Telemetry.Metric m=metrics.get(i);width+=slotWidth(m.key);
            if(i>0)width+=itemGap;
        }
        scale=Math.min(1f,Math.max(1,maxWidth)/width);
        setMeasuredDimension(Math.max(1,(int)Math.ceil(width*scale)),Math.max(1,(int)Math.ceil(baseHeight*scale)));
    }
    @Override protected void onDraw(Canvas canvas){
        super.onDraw(canvas);canvas.save();canvas.scale(scale,scale);
        text.setColor(Ui.TEXT);separator.setColor(Ui.LINE2);
        float x=padding,cy=baseHeight/2f;
        Paint.FontMetrics fm=text.getFontMetrics();float baseline=cy-(fm.ascent+fm.descent)/2;
        if(metrics.isEmpty())canvas.drawText("Выберите показатели",x,baseline,text);
        for(int i=0;i<metrics.size();i++){
            Telemetry.Metric m=metrics.get(i);
            if(i>0)canvas.drawLine(x-itemGap/2f,cy-Ui.dp(getContext(),4),x-itemGap/2f,cy+Ui.dp(getContext(),4),separator);
            float slot=slotWidth(m.key);canvas.save();canvas.clipRect(x,0,x+slot,baseHeight);
            drawIcon(canvas,m.key,x,cy);
            text.setColor(m.color==0?Ui.TEXT:m.color);
            if(m.color!=0){Drawable colored=icons.get(m.key);if(colored!=null){colored.setTint(m.color);colored.draw(canvas);}}
            if(HeaderSlots.canonical(m.key).equals("network")){
                drawIcon(canvas,m.value.equals("VPN")?"vpn":"vpn_off",x+iconSize+gap,cy);
            }else if(!m.value.isEmpty()){
                float normal=text.getTextSize(),available=Math.max(1,slot-iconSize-gap);
                float measured=text.measureText(m.value);if(measured>available)text.setTextSize(normal*available/measured);
                Paint.FontMetrics cell=text.getFontMetrics();
                canvas.drawText(m.value,x+iconSize+gap,cy-(cell.ascent+cell.descent)/2,text);text.setTextSize(normal);
            }
            canvas.restore();x+=slot+itemGap;
        }
        canvas.restore();
    }
    private int slotWidth(String key){
        int width;
        switch(HeaderSlots.canonical(key)){
            case "temperature":case "network":width=32;break;
            case "ram":case "down":case "up":case "ping":width=44;break;
            case "cpu":case "gpu":width=36;break;
            default:width=38;
        }
        return Ui.dp(getContext(),width);
    }
    private void drawIcon(Canvas canvas,String key,float x,float cy){
        Drawable d=icons.get(key);if(d==null){d=getContext().getDrawable(icon(key)).mutate();icons.put(key,d);}
        d.setTint(Ui.SILVER);d.setBounds((int)x,(int)(cy-iconSize/2f),(int)x+iconSize,(int)(cy-iconSize/2f)+iconSize);d.draw(canvas);
    }
}
