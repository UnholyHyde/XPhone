package ru.xphone.booster;

import android.app.Activity;
import android.app.Dialog;
import android.text.Editable;
import android.text.TextWatcher;
import android.text.InputType;
import android.view.Gravity;
import android.view.Window;
import android.widget.*;

/** Edits a draft color. Preferences change only after Apply or Reset. */
final class AccentPicker {
    static void show(Activity c,Runnable changed) {
        Ui.load(c);
        Dialog dialog=new Dialog(c);dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout body=Ui.card(c);body.setBackground(Ui.box(c,0xFF17191F,Ui.LINE2,Ui.R_CARD));
        body.addView(Ui.text(c,"Цвет акцента",20,Ui.TEXT,800));
        body.addView(Ui.hint(c,"HEX — шесть символов, RGB — числа от 0 до 255. Цвет применяется к хабу и игровой панели.",12,Ui.MUTED,500),Ui.lpMargins(c,-1,-2,0,10,0,12));
        TextView preview=Ui.text(c,"XPHONE  ·  Предпросмотр",15,Ui.ON_ACCENT,700);
        preview.setGravity(Gravity.CENTER);preview.setMinHeight(Ui.dp(c,64));
        body.addView(preview,Ui.lpMargins(c,-1,-2,0,0,0,14));
        EditText hex=field(c,"HEX · #RRGGBB",false);
        body.addView(hex,Ui.lpMargins(c,-1,-2,0,0,0,8));
        EditText[] rgb=new EditText[3];LinearLayout row=new LinearLayout(c);
        for(int i=0;i<3;i++) {
            rgb[i]=field(c,new String[]{"R","G","B"}[i],true);
            LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,-2,1);if(i>0)lp.leftMargin=Ui.dp(c,8);
            row.addView(rgb[i],lp);
        }
        body.addView(row);
        TextView error=Ui.text(c,"",12,Ui.SILVER,500);body.addView(error,Ui.lpMargins(c,-1,-2,0,8,0,8));
        TextView apply=Ui.button(c,"Применить",true);
        int[] selected={Ui.ACCENT};boolean[] updating={false};
        java.util.function.BiConsumer<Integer,Boolean> update=(color,fromHex)->{
            updating[0]=true;
            selected[0]=color;
            if(!fromHex)hex.setText(AccentColor.text(color));
            if(fromHex)for(int i=0;i<3;i++)rgb[i].setText(Integer.toString((color>>(16-i*8))&255));
            preview.setBackground(Ui.box(c,color,0,8));preview.setTextColor(AccentColor.foreground(color));
            error.setText(AccentColor.text(color));apply.setEnabled(true);apply.setAlpha(1f);updating[0]=false;
        };
        hex.setText(AccentColor.text(selected[0]));update.accept(selected[0],true);
        hex.addTextChangedListener(watch(()->{
            if(updating[0])return;Integer value=AccentColor.hex(hex.getText().toString());
            if(value!=null)update.accept(value,true);else{error.setText("Введите HEX: например, #56D88B");apply.setEnabled(false);apply.setAlpha(.4f);}
        }));
        for(EditText input:rgb)input.addTextChangedListener(watch(()->{
            if(updating[0])return;
            Integer value=AccentColor.rgb(rgb[0].getText().toString(),rgb[1].getText().toString(),rgb[2].getText().toString());
            if(value!=null)update.accept(value,false);else{error.setText("R, G и B должны быть целыми числами от 0 до 255");apply.setEnabled(false);apply.setAlpha(.4f);}
        }));
        LinearLayout presets=new LinearLayout(c);
        int[] colors={0xFFE6E7EB,0xFF56D88B,0xFF68B5FF,0xFFB59AFF,0xFFFFAD66};
        for(int color:colors){TextView swatch=Ui.button(c,"●",false);swatch.setTextColor(color);swatch.setTextSize(24);swatch.setPadding(0,0,0,0);swatch.setContentDescription("Цвет "+AccentColor.text(color));swatch.setOnClickListener(v->{hex.setText(AccentColor.text(color));});presets.addView(swatch,new LinearLayout.LayoutParams(0,Ui.dp(c,44),1));}
        body.addView(presets,Ui.lpMargins(c,-1,-2,0,8,0,8));
        apply.setOnClickListener(v->{Ui.setAccent(c,selected[0]);dialog.dismiss();changed.run();});body.addView(apply);
        TextView reset=Ui.button(c,"Цвет по умолчанию",false);reset.setOnClickListener(v->{Ui.resetAccent(c);dialog.dismiss();changed.run();});body.addView(reset,Ui.lpMargins(c,-1,-2,0,8,0,0));
        TextView cancel=Ui.button(c,"Отмена",false);cancel.setOnClickListener(v->dialog.dismiss());body.addView(cancel,Ui.lpMargins(c,-1,-2,0,8,0,0));
        ScrollView scroll=new ScrollView(c);scroll.addView(body);dialog.setContentView(scroll);
        Window window=dialog.getWindow();if(window==null)return;
        window.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT));
        window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        dialog.show();int width=Math.min(c.getResources().getDisplayMetrics().widthPixels-Ui.dp(c,32),Ui.dp(c,480));
        window.setLayout(width,Math.min(Ui.dp(c,650),c.getResources().getDisplayMetrics().heightPixels-Ui.dp(c,80)));
    }
    private static EditText field(Activity c,String hint,boolean number){
        EditText e=new EditText(c);e.setSingleLine(true);e.setTextSize(15);e.setTextColor(Ui.TEXT);e.setHintTextColor(Ui.MUTED);e.setHint(hint);
        e.setInputType(number?InputType.TYPE_CLASS_NUMBER:InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS|InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        e.setMinHeight(Ui.dp(c,48));e.setPadding(Ui.dp(c,10),Ui.dp(c,8),Ui.dp(c,10),Ui.dp(c,8));e.setBackground(Ui.box(c,Ui.FIELD,Ui.LINE2,8));return e;
    }
    private static TextWatcher watch(Runnable changed){return new TextWatcher(){public void beforeTextChanged(CharSequence s,int start,int count,int after){}public void onTextChanged(CharSequence s,int start,int before,int count){}public void afterTextChanged(Editable e){changed.run();}};}
}
