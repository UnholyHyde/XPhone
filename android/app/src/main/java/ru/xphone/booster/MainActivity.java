package ru.xphone.booster;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.AppOpsManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Process;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import rikka.shizuku.Shizuku;

/** XPhone на телефоне: игровой хаб, панель и дополнительные возможности. */
public class MainActivity extends Activity {

    private static final int TAB_GAME = 0, TAB_STREAM = 1, TAB_PANEL = 2, TAB_SETTINGS = 3;

    private ScrollView scroll;
    private LinearLayout content, navigation;
    private ImageView editingAvatar;
    private static final int PICK_AVATAR=40;
    private String launchingGame;
    private int tab = TAB_GAME;
    private PcLink.State lastPc = PcLink.State.NO_PC;

    private final Shizuku.OnRequestPermissionResultListener permListener = (code, result) -> runOnUiThread(()->{render();probeCompanion();});
    private final Shizuku.OnBinderReceivedListener binderListener = () -> runOnUiThread(()->{render();probeCompanion();});
    private final Shizuku.OnBinderDeadListener deadListener = () -> runOnUiThread(this::render);

    /** Состояние ПК обновляется, не уводя пользователя из хаба. */
    private final PcLink.Listener pcListener = () -> {
        PcLink.State now = PcLink.state();
        // Connecting a PC must not replace the standalone hub screen.
        lastPc = now;
        render();
    };

    // ── Права ─────────────────────────────────────────────────────────

    public static boolean overlayOk(Context c) { return Settings.canDrawOverlays(c); }

    public static boolean usageOk(Context c) {
        AppOpsManager ops = c.getSystemService(AppOpsManager.class);
        int mode = Build.VERSION.SDK_INT >= 29
                ? ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), c.getPackageName())
                : ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), c.getPackageName());
        return mode == AppOpsManager.MODE_ALLOWED;
    }

    public static boolean notifOk(Context c) {
        return Build.VERSION.SDK_INT < 33 || c.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
    }

    public static boolean permissionsOk(Context c) { return overlayOk(c) && usageOk(c); }

    // ── Жизненный цикл ────────────────────────────────────────────────

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        int p = Ui.dp(this, 18);
        content.setPadding(p, Ui.dp(this, 22), p, Ui.dp(this, 28));
        scroll.addView(content);
        LinearLayout shell=new LinearLayout(this);shell.setOrientation(LinearLayout.VERTICAL);
        shell.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        navigation=new LinearLayout(this);navigation.setPadding(Ui.dp(this,12),Ui.dp(this,10),Ui.dp(this,12),Ui.dp(this,10));
        shell.addView(navigation,new LinearLayout.LayoutParams(-1,-2));
        setContentView(shell);
        // Android 15+ рисует приложение под строкой состояния — отодвигаем содержимое.
        final int padTop = content.getPaddingTop(), padBottom = content.getPaddingBottom();
        shell.setOnApplyWindowInsetsListener((v, insets) -> {
            int top, bottom;
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(android.view.WindowInsets.Type.systemBars());
                top = bars.top; bottom = bars.bottom;
            } else {
                top = insets.getSystemWindowInsetTop(); bottom = insets.getSystemWindowInsetBottom();
            }
            content.setPadding(content.getPaddingLeft(), padTop + top, content.getPaddingRight(), padBottom);
            navigation.setPadding(Ui.dp(this,12),Ui.dp(this,10),Ui.dp(this,12),Ui.dp(this,10)+bottom);
            return insets;
        });
        Shizuku.addRequestPermissionResultListener(permListener);
        Shizuku.addBinderReceivedListenerSticky(binderListener);
        Shizuku.addBinderDeadListener(deadListener);
        if (b != null) tab = Math.max(0, Math.min(TAB_SETTINGS, b.getInt("hub_tab", TAB_GAME)));
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putInt("hub_tab", tab);
    }

    @Override
    protected void onDestroy() {
        Shizuku.removeRequestPermissionResultListener(permListener);
        Shizuku.removeBinderReceivedListener(binderListener);
        Shizuku.removeBinderDeadListener(deadListener);
        super.onDestroy();
    }

    private final AppUpdates updates = new AppUpdates(this);
    private boolean resumed;

    boolean canShowUpdate() {
        return resumed && !isFinishing() && !isDestroyed();
    }

    @Override
    protected void onResume() {
        super.onResume();BoostService.hubVisible();
        resumed = true;
        PcLink.start(this);
        lastPc = PcLink.state();
        PcLink.addListener(pcListener);
        GameHistory.invalidate();render();
        probeCompanion();
        if (Prefs.enabled(this) && permissionsOk(this)) BoostService.start(this);
        updates.onResume();
    }

    private void probeCompanion() {
        Shell.background(()->{
            boolean before=CompanionApps.available(this);
            boolean oldFrames=ScreenFrames.available(this),oldSensitivity=TouchSensitivity.available(this),oldPrivacy=Prefs.get(this,"privacy_available",false);
            ScreenFrames.probe(this);TouchSensitivity.probe(this);Prefs.put(this,"privacy_available",Shell.privacyAvailable(this));
            CompanionApps.probe(this);
            if(before!=CompanionApps.available(this)||oldFrames!=ScreenFrames.available(this)||oldSensitivity!=TouchSensitivity.available(this)||oldPrivacy!=Prefs.get(this,"privacy_available",false))Shell.onMain(()->{if(!isFinishing()&&!isDestroyed())render();});
        });
    }

    @Override
    protected void onPause() {
        resumed = false;
        BoostService.hubHidden();
        PcLink.removeListener(pcListener);
        super.onPause();
    }

    // ── Экран ─────────────────────────────────────────────────────────

    private void render() {
        Ui.load(this);
        getWindow().getDecorView().setBackground(Ui.screenBackground(this));
        scroll.setBackgroundColor(0);
        content.removeAllViews();

        LinearLayout head=new LinearLayout(this);head.setOrientation(LinearLayout.VERTICAL);head.setGravity(Gravity.CENTER);
        ImageView logo=new ImageView(this);logo.setImageResource(R.mipmap.ic_launcher);head.addView(logo,new LinearLayout.LayoutParams(Ui.dp(this,58),Ui.dp(this,58)));
        head.addView(Ui.text(this,"XPHONE",22,Ui.ACCENT,800),Ui.lpMargins(this,-2,-2,0,6,0,0));
        TextView device=Ui.text(this,PlayerProfile.device(this),12,Ui.SILVER,600);device.setGravity(Gravity.CENTER);device.setMaxLines(2);
        head.addView(device,Ui.lpMargins(this,-1,-2,0,6,0,0));
        TextView model=Ui.text(this,Build.MODEL,10,Ui.MUTED,500);head.addView(model,Ui.lpMargins(this,-2,-2,0,3,0,0));
        content.addView(head,Ui.lpMargins(this,-1,-2,0,0,0,16));

        navigation.removeAllViews();
        navigation.setBackground(Ui.box(this,Ui.BG,Ui.LINE,0));
        int selected=tab==TAB_GAME?0:tab==TAB_PANEL?1:2;
        navigation.addView(Ui.segmented(this,new String[]{"Игры","Панель","Настройки"},selected,i->{
            tab=new int[]{TAB_GAME,TAB_PANEL,TAB_SETTINGS}[i];render();scroll.scrollTo(0,0);
        }),new LinearLayout.LayoutParams(-1,-2));

        if (tab == TAB_GAME) renderHub();
        else if (tab == TAB_STREAM) renderStream();
        else if (tab == TAB_PANEL) renderGame();
        else renderSettings();

        TextView foot = Ui.text(this, "XPhone " + BuildConfig.VERSION_NAME, 11, Ui.DIM, 500);
        foot.setGravity(Gravity.CENTER);
        content.addView(foot, Ui.lpMargins(this, -1, -2, 0, 24, 0, 0));
    }

    /** Главная библиотека: запуск игр, профили и состояние игровой панели. */
    private void renderHub() {
        LinearLayout hero=tacticalCard();hero.setOrientation(LinearLayout.HORIZONTAL);hero.setGravity(Gravity.CENTER_VERTICAL);
        ImageView avatar=new ImageView(this);PlayerProfile.avatar(this,avatar);hero.addView(avatar,new LinearLayout.LayoutParams(Ui.dp(this,54),Ui.dp(this,54)));
        LinearLayout player=textBlock(PlayerProfile.name(this),Prefs.enabled(this)?"Игровая панель включена":"Игровая панель выключена");player.setPadding(Ui.dp(this,12),0,Ui.dp(this,8),0);hero.addView(player,new LinearLayout.LayoutParams(0,-2,1));
        ImageView edit=Ui.icon(this,R.drawable.ic_user,20,Ui.ACCENT);edit.setContentDescription("Профиль игрока");edit.setOnClickListener(v->showPlayerProfile());hero.addView(edit,new LinearLayout.LayoutParams(Ui.dp(this,36),Ui.dp(this,44)));hero.setOnClickListener(v->showPlayerProfile());
        content.addView(hero,Ui.lpMargins(this,-1,-2,0,0,0,8));
        section("БИБЛИОТЕКА ИГР");
        List<App> apps=installedApps(), games=new ArrayList<>();
        for(App a:apps)if(Prefs.isGame(this,a.pkg))games.add(a);
        if(games.isEmpty()) {
            LinearLayout empty=tacticalCard();
            empty.addView(Ui.text(this,"Добавь первую игру",18,Ui.TEXT,700));
            empty.addView(Ui.hint(this,"Запускай её отсюда и настрой собственный профиль. Игры, распознанные Android, появляются автоматически.",12,Ui.MUTED,500),Ui.lpMargins(this,-1,-2,0,8,0,0));
            content.addView(empty);
        }
        List<String> historyPackages=new ArrayList<>();for(App game:games)historyPackages.add(game.pkg);
        GameHistory.request(this,historyPackages,null);
        int width=getResources().getConfiguration().screenWidthDp;
        int cellWidth=(width-46)/2;boolean[] wide=new boolean[games.size()+1];
        android.graphics.Paint measure=Ui.text(this,"",12,Ui.TEXT,600).getPaint();
        for(int i=0;i<games.size();i++)wide[i]=measure.measureText(games.get(i).label)>Ui.dp(this,Math.max(80,cellWidth-24))*1.8f;
        for(int[] packed:LibraryPacking.rows(wide)){
            LinearLayout row=new LinearLayout(this);row.setGravity(Gravity.TOP);
            boolean full=packed[0]>=0&&packed[0]==packed[1];
            for(int col=0;col<(full?1:2);col++){
                int index=packed[col];View item;
                if(index<0)item=new View(this);
                else if(index==games.size()){
                    LinearLayout add=tacticalCard();add.setGravity(Gravity.CENTER);add.addView(Ui.icon(this,R.drawable.ic_plus,28,Ui.SILVER));add.addView(Ui.text(this,"Добавить игру",12,Ui.TEXT,600),Ui.lpMargins(this,-2,-2,0,12,0,0));add.setOnClickListener(v->pickApps(apps));item=add;
                }else item=hubGameCard(games.get(index));
                LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,Ui.dp(this,Math.min(168,Math.max(142,cellWidth))),full?2:1);if(col>0)lp.leftMargin=Ui.dp(this,10);row.addView(item,lp);
            }content.addView(row,Ui.lpMargins(this,-1,-2,0,10,0,0));
        }
        section("БЫСТРЫЕ НАСТРОЙКИ");
        LinearLayout quick=tacticalCard();
        TextView mode=Ui.button(this,"Режим по умолчанию: "+GamePower.title(Prefs.getInt(this,"hub_power_mode",GamePower.PERFORMANCE)),false);
        if(GamePower.available(this)) {
            mode.setOnClickListener(v->{int next=(Prefs.getInt(this,"hub_power_mode",GamePower.PERFORMANCE)+1)%(FullFocus.available(this)?4:3);
                Prefs.putInt(this,"hub_power_mode",next);mode.setText("Режим по умолчанию: "+GamePower.title(next));});
            quick.addView(mode,Ui.lpMargins(this,-1,-2,0,0,0,8));
            quick.addView(Ui.hint(this,"Копируется в новые личные профили. Существующие профили сохраняют свой режим.",11,Ui.MUTED,500));
        }
        if(Booster.available(this,Booster.DND)) {
            TextView dnd=Ui.button(this,"Не беспокоить · таймер",false);dnd.setOnClickListener(v->showHubDnd());
            quick.addView(dnd,Ui.lpMargins(this,-1,-2,0,12,0,0));
        }
        quick.addView(plainSwitch("Мини-окно в игре","Показатели поверх игры",Prefs.get(this,MiniHud.ENABLED,false),on->{
            Prefs.put(this,MiniHud.ENABLED,on);BoostService.refreshHud();
        }),Ui.lpMargins(this,-1,-2,0,12,0,0));
        TextView customize=Ui.button(this,"Настроить панель",false);customize.setOnClickListener(v->{tab=TAB_PANEL;render();scroll.scrollTo(0,0);});
        quick.addView(customize,Ui.lpMargins(this,-1,-2,0,12,0,0));content.addView(quick);
    }

    private void showHubDnd() {
        LinearLayout body=card();body.addView(Ui.text(this,"На сколько включить «Не беспокоить»?",14,Ui.TEXT,600));
        int[] minutes={15,30,60,120,0};String[] labels={"15 минут","30 минут","1 час","2 часа","Выключить"};
        for(int i=0;i<minutes.length;i++){final int value=minutes[i];TextView button=Ui.button(this,labels[i],false);
            button.setOnClickListener(v->{button.setEnabled(false);Runnable done=()->{button.setEnabled(true);Toast.makeText(this,value==0?"Запрошено выключение режима":"Режим запрошен на "+value+" мин",Toast.LENGTH_SHORT).show();};
                if(value==0)Dnd.stop(this,done);else Dnd.start(this,value,done);
            });body.addView(button,Ui.lpMargins(this,-1,-2,0,10,0,0));}
        showThemedInfo("Не беспокоить",body,"Готово",this::render);
    }

    private LinearLayout appearanceControls() {
        LinearLayout colors=card();
        colors.addView(Ui.segmented(this,new String[]{"Графит","Стекло"},Ui.GLASS?1:0,i->{
            Ui.setTheme(this,i==1?Ui.THEME_GLASS:Ui.THEME_GRAPHITE);BoostService.refreshAppearance();render();
        }));
        TextView accent=Ui.button(this,"Цвет акцента · "+AccentColor.text(Ui.ACCENT),false);
        accent.setOnClickListener(v->AccentPicker.show(this,()->{BoostService.refreshAppearance();render();}));
        colors.addView(accent,Ui.lpMargins(this,-1,-2,0,8,0,0));
        TextView opacity=Ui.text(this,"Плотность панели: "+Prefs.getInt(this,"panel_opacity",35)+"%",12,Ui.SILVER,600);
        colors.addView(opacity,Ui.lpMargins(this,-1,-2,0,14,0,0));
        android.widget.SeekBar slider=new android.widget.SeekBar(this);slider.setMax(75);slider.setProgress(Prefs.getInt(this,"panel_opacity",35)-25);
        slider.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener(){
            public void onProgressChanged(android.widget.SeekBar b,int value,boolean user){if(user){Prefs.putInt(MainActivity.this,"panel_opacity",value+25);opacity.setText("Плотность панели: "+(value+25)+"%");}}
            public void onStartTrackingTouch(android.widget.SeekBar b){}public void onStopTrackingTouch(android.widget.SeekBar b){BoostService.refreshAppearance();}
        });colors.addView(slider,Ui.lpMargins(this,-1,-2,0,10,0,0));
        return colors;
    }

    private LinearLayout tacticalCard() {
        LinearLayout box=card();
        android.graphics.drawable.GradientDrawable bg=new android.graphics.drawable.GradientDrawable();
        bg.setColor(Ui.GLASS?0x20FFFFFF:Ui.CARD);bg.setStroke(Ui.dp(this,1),Ui.LINE2);
        float r=Ui.dp(this,16),small=Ui.dp(this,3);bg.setCornerRadii(new float[]{small,small,r,r,small,small,small,small});
        box.setBackground(bg);return box;
    }

    private View hubGameCard(App a) {
        android.widget.FrameLayout frame=new android.widget.FrameLayout(this);frame.setBackground(Ui.box(this,Ui.CARD,Ui.LINE2,12));frame.setPadding(Ui.dp(this,10),Ui.dp(this,8),Ui.dp(this,10),Ui.dp(this,8));
        LinearLayout body=new LinearLayout(this);body.setOrientation(LinearLayout.VERTICAL);body.setGravity(Gravity.CENTER);
        ImageView icon=new ImageView(this);try{icon.setImageDrawable(getPackageManager().getApplicationIcon(a.pkg));}catch(Exception ignored){}
        body.addView(icon,new LinearLayout.LayoutParams(Ui.dp(this,42),Ui.dp(this,42)));
        TextView name=Ui.text(this,a.label,12,Ui.TEXT,600);name.setGravity(Gravity.CENTER);name.setMaxLines(2);name.setEllipsize(android.text.TextUtils.TruncateAt.END);body.addView(name,Ui.lpMargins(this,-1,-2,0,8,0,0));
        android.widget.FrameLayout.LayoutParams bodyLp=new android.widget.FrameLayout.LayoutParams(-1,-1,Gravity.TOP);bodyLp.bottomMargin=Ui.dp(this,46);frame.addView(body,bodyLp);
        TextView play=Ui.button(this,"Играть",true);play.setGravity(Gravity.CENTER);play.setPadding(0,Ui.dp(this,6),0,Ui.dp(this,6));play.setOnClickListener(v->launchGame(a));frame.addView(play,new android.widget.FrameLayout.LayoutParams(-1,Ui.dp(this,40),Gravity.BOTTOM));
        frame.setContentDescription("Профиль и статистика игры "+a.label);frame.setOnClickListener(v->showGameProfile(a));return frame;
    }

    private void showPlayerProfile(){
        LinearLayout body=card();ImageView avatar=new ImageView(this);editingAvatar=avatar;PlayerProfile.avatar(this,avatar);body.addView(avatar,new LinearLayout.LayoutParams(Ui.dp(this,72),Ui.dp(this,72)));
        TextView photo=Ui.button(this,"Выбрать аватар",false);photo.setOnClickListener(v->{Intent picker=new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE);try{startActivityForResult(picker,PICK_AVATAR);}catch(Exception e){Toast.makeText(this,"Нет приложения для выбора фотографии",Toast.LENGTH_LONG).show();}});body.addView(photo,Ui.lpMargins(this,-1,-2,0,10,0,0));
        android.widget.EditText nickname=new android.widget.EditText(this);nickname.setText(PlayerProfile.name(this));nickname.setHint("Ник игрока");nickname.setTextColor(Ui.TEXT);nickname.setSingleLine(true);nickname.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(32)});body.addView(nickname,Ui.lpMargins(this,-1,-2,0,12,0,0));
        android.widget.EditText alias=new android.widget.EditText(this);alias.setText(PlayerProfile.device(this));alias.setHint("Название устройства");alias.setTextColor(Ui.TEXT);alias.setSingleLine(true);alias.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(80)});body.addView(alias,Ui.lpMargins(this,-1,-2,0,12,0,0));
        final android.app.Dialog[] dialog={null};TextView save=Ui.button(this,"Сохранить",true);save.setOnClickListener(v->{String name=nickname.getText().toString().trim();Prefs.sp(this).edit().putString("player_name",name.isEmpty()?"Игрок":name).putString("device_alias",alias.getText().toString().trim()).apply();if(dialog[0]!=null)dialog[0].dismiss();render();});body.addView(save,Ui.lpMargins(this,-1,-2,0,16,0,0));dialog[0]=showThemedInfo("Профиль игрока",body,"Отмена",this::render);
    }
    @Override protected void onActivityResult(int request,int result,Intent data){
        super.onActivityResult(request,result,data);
        if (request == AppUpdates.INSTALL_PERMISSION) {
            if (getPackageManager().canRequestPackageInstalls()) updates.installDownloaded();
            return;
        }
        if(request==PICK_AVATAR&&result==RESULT_OK&&data!=null&&data.getData()!=null){Uri uri=data.getData();Shell.background(()->{boolean ok=PlayerProfile.importAvatar(this,uri);Shell.onMain(()->{if(ok){if(editingAvatar!=null)PlayerProfile.avatar(this,editingAvatar);render();}else Toast.makeText(this,"Не удалось прочитать изображение",Toast.LENGTH_LONG).show();});});}
    }

    private void launchGame(App a) {
        if(launchingGame!=null)return;
        Intent launch=getPackageManager().getLaunchIntentForPackage(a.pkg);
        if(launch==null||launch.getComponent()==null){Toast.makeText(this,"Игра удалена или недоступна",Toast.LENGTH_LONG).show();render();return;}
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        if(Prefs.enabled(this)&&permissionsOk(this))BoostService.start(this);
        launchingGame=a.pkg;
        Runnable start=()->{
            launchingGame=null;if(isFinishing()||isDestroyed())return;
            try{startActivity(launch);}catch(Exception e){Toast.makeText(this,"Не удалось открыть игру. Проверьте, что она установлена и не отключена",Toast.LENGTH_LONG).show();}
        };
        if(!Shell.ready(this)){start.run();return;}
        String component=launch.getComponent().flattenToString();
        Shell.background(()->{String result=Shell.resumeGame(this,component);Shell.onMain(()->{if("RESUMED".equals(result))launchingGame=null;else start.run();});});
    }

    private void showGameProfile(App a) {
        LinearLayout body=card(),fields=new LinearLayout(this);fields.setOrientation(LinearLayout.VERTICAL);
        TextView total=Ui.text(this,"Загружаем игровое время…",17,Ui.TEXT,700);body.addView(total);
        TextView selected=Ui.text(this,"",12,Ui.SILVER,500);
        PlayTimeChart chart=new PlayTimeChart(this,day->selected.setText((day.date.equals(java.time.LocalDate.now())?"Сегодня":day.date.toString())+" · "+(day.knownTime?PlayTimeMath.precise(day.ms):"время: нет данных")+" · "+(day.sessions==null?"сессии: нет данных":(day.sessionsPartial?"сессий по доступным событиям: ":"начато сессий: ")+day.sessions)));
        body.addView(chart,new LinearLayout.LayoutParams(-1,Ui.dp(this,190)));body.addView(selected);
        body.addView(Ui.hint(this,"Дни — по времени телефона. Нажмите столбец для подробностей. Изменения профиля сохраняются сразу, даже при закрытии без кнопки «Готово».",11,Ui.MUTED,500),Ui.lpMargins(this,-1,-2,0,10,0,0));
        body.addView(plainSwitch("Свой профиль","При выключении автодействия отключены",GameProfiles.custom(this,a.pkg),on->{GameProfiles.setCustom(this,a.pkg,on);fillProfileFields(fields,a.pkg);}),Ui.lpMargins(this,-1,-2,0,14,0,0));
        fillProfileFields(fields,a.pkg);body.addView(fields,Ui.lpMargins(this,-1,-2,0,18,0,0));
        final android.app.Dialog[] dialog={null};TextView remove=Ui.button(this,"Убрать из библиотеки",false);
        remove.setOnClickListener(v->{Prefs.setGame(this,a.pkg,false);if(dialog[0]!=null)dialog[0].dismiss();});body.addView(remove,Ui.lpMargins(this,-1,-2,0,24,0,0));
        Runnable paint=()->{GameHistory.Summary stats=GameHistory.get(a.pkg);if(stats!=null){total.setText("Учтено: "+PlayTimeMath.precise(stats.total));chart.setData(stats);}else if(!usageOk(this))total.setText("Нужен доступ к истории использования");};paint.run();
        android.os.Handler refresh=new android.os.Handler(android.os.Looper.getMainLooper());
        Runnable update=new Runnable(){public void run(){if(dialog[0]==null||!dialog[0].isShowing()||isFinishing()||isDestroyed())return;GameHistory.invalidate();GameHistory.request(MainActivity.this,java.util.Collections.singletonList(a.pkg),()->{if(dialog[0]!=null&&dialog[0].isShowing())paint.run();});refresh.postDelayed(this,5000);}};
        dialog[0]=showThemedInfo(a.label,body,"Готово",()->{refresh.removeCallbacksAndMessages(null);render();});refresh.post(update);
    }

    private void fillProfileFields(LinearLayout fields,String pkg) {
        fields.removeAllViews();
        if(!GameProfiles.custom(this,pkg)){
            fields.addView(Ui.hint(this,"Автодействия этой игры выключены. Сохранённый профиль остаётся в памяти: при включении вернутся его настройки. Кнопки панели можно использовать вручную.",12,Ui.MUTED,500));return;
        }
        fields.addView(Ui.text(this,"ПРИ ЗАПУСКЕ",11,Ui.SILVER,700));
        for(String id:Booster.TOGGLES)if(Booster.available(this,id))
            fields.addView(plainSwitch(Booster.title(id),null,GameProfiles.auto(this,pkg,id),on->GameProfiles.setAuto(this,pkg,id,on)),Ui.lpMargins(this,-1,-2,0,12,0,0));
        if(GamePower.available(this)){
            fields.addView(Ui.text(this,"Режим питания",12,Ui.SILVER,600),Ui.lpMargins(this,-1,-2,0,14,0,8));
            fields.addView(Ui.segmented(this,(FullFocus.available(this)?new String[]{"Эконом","Баланс","Мощность","Full Focus"}:new String[]{"Эконом","Баланс","Мощность"}),Math.min(GameProfiles.mode(this,pkg),FullFocus.available(this)?3:2),i->GameProfiles.setMode(this,pkg,i)));
        }
        fields.addView(Ui.hint(this,"Включённый профиль запоминает изменения режимов, яркости и переключателей в игре.",12,Ui.MUTED,500),Ui.lpMargins(this,-1,-2,0,16,0,0));
        for(String id:new String[]{"hud","mic","touch"})if(PanelFeatures.available(this,id))fields.addView(plainSwitch("При запуске: "+PanelFeatures.title(id),null,GameProfiles.extra(this,pkg,id),on->GameProfiles.setExtra(this,pkg,id,on)),Ui.lpMargins(this,-1,-2,0,12,0,0));
        fields.addView(Ui.text(this,"КНОПКИ ПАНЕЛИ",11,Ui.SILVER,700),Ui.lpMargins(this,-1,-2,0,20,0,0));
        for(String id:PanelFeatures.IDS)if(PanelFeatures.available(this,id))
            fields.addView(plainSwitch(PanelFeatures.title(id),null,GameProfiles.tile(this,pkg,id),on->{GameProfiles.setTile(this,pkg,id,on);BoostService.refreshAppearance();}),Ui.lpMargins(this,-1,-2,0,12,0,0));
    }

    // ── Вкладка «Игра» ────────────────────────────────────────────────

    private void renderGame() {
        TextView buttons=Ui.button(this,"Кнопки оверлея",true);buttons.setOnClickListener(v->showPanelButtons());
        content.addView(buttons,Ui.lpMargins(this,-1,-2,0,0,0,12));
        LinearLayout master = card();
        master.addView(plainSwitch("Игровая панель включена", "В игре — двойное нажатие на язычок", Prefs.enabled(this), on -> {
            Prefs.put(this, Prefs.ENABLED, on);
            if (on && permissionsOk(this)) BoostService.start(this);
            if (!on) BoostService.stop(this);
        }));
        content.addView(master, Ui.lpMargins(this, -1, -2, 0, 12, 0, 0));
        if (!permissionsOk(this)) {
            TextView warn = Ui.text(this, "Нужны разрешения — откройте раздел «Настройки».", 12, Ui.SILVER, 600);
            warn.setOnClickListener(v -> {
                expandedMenus.add("Разрешения и Shizuku");
                tab = TAB_SETTINGS;
                render();
                scroll.scrollTo(0, 0);
            });
            content.addView(warn, Ui.lpMargins(this, -1, -2, 4, 10, 0, 0));
        }

        if (Prefs.get(this, "dnd_reverted", false)) {
            LinearLayout warn = card();
            warn.addView(Ui.text(this, "«Не беспокоить» сразу выключается", 14, Ui.TEXT, 700));
            warn.addView(Ui.text(this, "Его выключает служба Galaxy Watch: она синхронизирует режим со своим. "
                    + "Откройте Galaxy Wearable → настройки часов → Уведомления (или «Не беспокоить») и выключите синхронизацию с телефоном. "
                    + "Либо включайте режим на самих часах.", 12, Ui.MUTED, 500), Ui.lpMargins(this, -1, -2, 0, 6, 0, 0));
            TextView ok = Ui.button(this, "Понятно", false);
            ok.setOnClickListener(v -> { Prefs.put(this, "dnd_reverted", false); render(); });
            warn.addView(ok, Ui.lpMargins(this, -1, -2, 0, 12, 0, 0));
            content.addView(warn, Ui.lpMargins(this, -1, -2, 0, 12, 0, 0));
        }

        LinearLayout panel = card();
        panel.addView(plainSwitch("Приветствие при запуске игры", "Короткая анимация у края — вместо сразу открытой панели",
                Prefs.welcome(this), on -> Prefs.put(this, Prefs.WELCOME, on)));
        divider(panel);
        panel.addView(plainSwitch("Сразу открывать панель", null, Prefs.autoPanel(this),
                on -> Prefs.put(this, Prefs.AUTO_PANEL, on)));
        divider(panel);
        panel.addView(Ui.text(this, "Язычок панели", 14, Ui.TEXT, 600));
        panel.addView(Ui.segmented(this, new String[]{"Слева", "Справа", "Сверху", "Снизу"}, Prefs.handleEdge(this), i -> {
            Prefs.setHandleEdge(this,i);BoostService.refreshHandle();
            render();
        }), Ui.lpMargins(this, -1, -2, 0, 8, 0, 0));
        panel.addView(Ui.hint(this, "Двойное нажатие на язычок открывает панель. Удержание позволяет перетащить его к любой стороне. Одиночное нажатие панель не открывает.", 12, Ui.MUTED, 500),
                Ui.lpMargins(this, -1, -2, 0, 6, 0, 0));
        if (Mic.supported(this)) {
            divider(panel);
            panel.addView(plainSwitch("Кнопка микрофона", "Плавающая кнопка в игре: нажатие мгновенно выключает микрофон",
                    Prefs.get(this, Prefs.MIC_BUBBLE, false), on -> Prefs.put(this, Prefs.MIC_BUBBLE, on)));
        }
        TextView test = Ui.button(this, "Как открыть панель в игре", true);
        test.setOnClickListener(v -> Toast.makeText(this,"Откройте игру и дважды нажмите язычок XPhone. В хабе игровые окна скрыты.",Toast.LENGTH_LONG).show());
        panel.addView(test, Ui.lpMargins(this, -1, -2, 0, 14, 0, 0));
        menuGroup("Запуск панели и язычок", panel);
        menuGroup("Оформление панели", appearanceControls());
        LinearLayout hudSettings = card();
        hudSettings.addView(MiniHud.controls(this, BoostService::refreshHud));
        menuGroup("Мини-окно и показатели", hudSettings);
        if (ScreenFrames.available(this)) {
            LinearLayout fps = card();
            fps.addView(FpsHud.controls(this, BoostService::refreshHud));
            menuGroup("Окно FPS и герцовки", fps);
        }
        if (NativeRecorder.available(this)) {
            LinearLayout recording = card();
            recording.addView(Overlay.recordingControls(this));
            menuGroup("Запись экрана", recording);
        }
        if(Shell.state(this)!=Shell.State.READY) {
            LinearLayout help=card();
            help.addView(shizukuRow(Shell.state(this)));
            content.addView(help,Ui.lpMargins(this,-1,-2,0,12,0,0));
        }
        LinearLayout companions=card();
        String windowHelp=CompanionApps.available(this)
                ? "Выберите до шести приложений. В игре откройте «Окна». Значок приложения сворачивает и возвращает окно; его можно перетащить."
                : "Для окон нужен Shizuku и поддержка оконного режима телефоном. Выбранные приложения в любом случае исключаются из очистки XPhone.";
        companions.addView(Ui.hint(this,windowHelp,13,Ui.MUTED,500));
        java.util.List<String> chosen=CompanionApps.selected(this);
        if(!chosen.isEmpty()) {
            java.util.List<String> labels=new java.util.ArrayList<>();
            for(String pkg:chosen)labels.add(CompanionApps.label(this,pkg));
            companions.addView(Ui.text(this,String.join(" · ",labels),13,Ui.TEXT,600),Ui.lpMargins(this,-1,-2,0,10,0,0));
        }
        TextView choose=Ui.button(this,"Выбрать приложения",false);
        choose.setOnClickListener(v->showCompanionApps());
        companions.addView(choose,Ui.lpMargins(this,-1,-2,0,12,0,0));
        companions.addView(Ui.hint(this,"Окна экспериментальные: работа и сворачивание зависят от прошивки и приложения. XPhone не включает глобальные режимы и не обходит ограничения телефона.",12,Ui.MUTED,500),Ui.lpMargins(this,-1,-2,0,8,0,0));
        menuGroup("Приложения поверх игры", companions);

        LinearLayout autos = card();
        int locked = 0, shown = 0;
        for (String id : Booster.TOGGLES) {
            if (Booster.PERF.equals(id) && !GamePower.gameModesSupported(this)) continue;
            if (!Booster.supported(id)) continue;
            if (!Booster.available(this, id)) { locked++; continue; }
            if (shown++ > 0) divider(autos);
            autos.addView(plainSwitch("Включать «" + Booster.title(id) + "»", Booster.hint(id), Prefs.auto(this, id),
                    on -> Prefs.setAuto(this, id, on)));
        }
        if (Booster.available(this, Booster.PASS)) {
            divider(autos);
            autos.addView(Ui.text(this, "Питание USB от заряда", 14, Ui.TEXT, 600));
            autos.addView(Ui.hint(this, "Когда заряд дошёл до порога — телефон питается от зарядки, батарея не греется", 12, Ui.MUTED, 500),
                    Ui.lpMargins(this, -1, -2, 0, 4, 0, 0));
            int[] levels = {0, 50, 70, 80, 90};
            int cur = Prefs.getInt(this, Prefs.PASS_LEVEL, 0), sel = 0;
            for (int i = 0; i < levels.length; i++) if (levels[i] == cur) sel = i;
            autos.addView(Ui.segmented(this, new String[]{"Выкл", "50%", "70%", "80%", "90%"}, sel, i -> {
                Prefs.putInt(this, Prefs.PASS_LEVEL, levels[i]);
                render();
            }), Ui.lpMargins(this, -1, -2, 0, 8, 0, 0));
        }
        if (shown > 0 || Booster.available(this, Booster.PASS)) divider(autos);
        autos.addView(plainSwitch("После игры возвращать как было", "Всё, что включила панель, выключится само",
                Prefs.restore(this), on -> Prefs.put(this, Prefs.RESTORE, on)));
        if (locked > 0) {
            TextView more = Ui.text(this, "Ещё " + locked + " — с Shizuku (раздел «Настройки»).", 12, Ui.MUTED, 500);
            more.setOnClickListener(v -> { tab = TAB_SETTINGS; render(); });
            autos.addView(more, Ui.lpMargins(this, -1, -2, 0, 12, 0, 0));
        }
        menuGroup("Шаблон игровых профилей", autos);

    }

    // ── Вкладка «Стрим» ───────────────────────────────────────────────

    private void renderStream() {
        TextView back=Ui.button(this,"← Настройки хаба",false);back.setOnClickListener(v->{tab=TAB_SETTINGS;render();});
        content.addView(back,Ui.lpMargins(this,-1,-2,0,0,0,12));
        content.addView(pcCard(), Ui.lpMargins(this, -1, -2, 0, 12, 0, 0));
    }

    /** Связь с XPhone на компьютере: состояние, кнопки, трансляция вместе с игрой. */
    private LinearLayout pcCard() {
        LinearLayout card = card();
        PcLink.State ps = PcLink.state();
        String name = PcLink.pcName() != null ? PcLink.pcName() : "компьютеру";
        boolean ever = Prefs.get(this, "pc_ever_connected", false);
        String title, sub;
        switch (ps) {
            case STREAMING:
                title = "Идёт трансляция";
                sub = "Экран и звук телефона — на " + name;
                break;
            case CONNECTING:
                title = "Запускаем трансляцию…";
                sub = "Окно появится на компьютере через пару секунд";
                break;
            case IDLE:
                title = "Подключено к " + name;
                sub = "Можно начинать трансляцию";
                break;
            default:
                title = PcLink.waiting() ? "Ищем компьютер…" : "Компьютер не подключён";
                sub = ever
                        ? "Откройте XPhone на ПК. Телефон и компьютер — в одной локальной сети: Wi-Fi или LAN (или подключите USB-кабель)."
                        : "Первый раз: подключите телефон кабелем к XPhone на ПК (или «Сопряжение по Wi-Fi» → QR-код). Дальше всё само.";
        }
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        if (Ui.GLASS) {
            android.widget.FrameLayout circle = new android.widget.FrameLayout(this);
            boolean live = ps == PcLink.State.STREAMING;
            circle.setBackground(Ui.circle(live ? Ui.ACCENT : Ui.TILE_OFF));
            circle.addView(Ui.icon(this, R.drawable.ic_cast, 20, live ? Ui.ON_ACCENT : 0xFFFFFFFF),
                    new android.widget.FrameLayout.LayoutParams(Ui.dp(this, 20), Ui.dp(this, 20), Gravity.CENTER));
            row.addView(circle, new LinearLayout.LayoutParams(Ui.dp(this, 44), Ui.dp(this, 44)));
        } else {
            row.addView(Ui.icon(this, R.drawable.ic_cast, 22, ps == PcLink.State.STREAMING ? Ui.ACCENT : Ui.SILVER));
        }
        LinearLayout tb = textBlock(title, sub);
        tb.setPadding(Ui.dp(this, 12), 0, 0, 0);
        row.addView(tb, new LinearLayout.LayoutParams(0, -2, 1));
        card.addView(row);

        if (PcLink.message() != null) {
            TextView m = Ui.text(this, PcLink.message(), 12, Ui.SILVER, 600);
            card.addView(m, Ui.lpMargins(this, -1, -2, 0, 8, 0, 0));
        }

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.VERTICAL);
        if (ps == PcLink.State.STREAMING) {
            TextView stop = Ui.button(this, "Остановить трансляцию", false);
            stop.setOnClickListener(v -> PcLink.requestStop());
            buttons.addView(stop, new LinearLayout.LayoutParams(-1, -2));
        } else if (ps != PcLink.State.CONNECTING) {
            if (ps == PcLink.State.NO_PC) {
                TextView find = Ui.button(this, "Подключиться", false);
                find.setOnClickListener(v -> {
                    if (!PcLink.canEnableWifiDebugging(this))
                        Toast.makeText(this, "Включите «Беспроводную отладку» плиткой в шторке. Чтобы телефон делал это сам — один раз подключите его кабелем к XPhone на ПК.", Toast.LENGTH_LONG).show();
                    PcLink.findPc(this);
                });
                buttons.addView(find, new LinearLayout.LayoutParams(-1, -2));
            }
            TextView start = Ui.button(this, "Начать трансляцию", true);
            start.setOnClickListener(v -> PcLink.requestStart(this));
            LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(-1, -2);
            if (ps == PcLink.State.NO_PC) sl.topMargin = Ui.dp(this, 8);
            buttons.addView(start, sl);
        }
        if (buttons.getChildCount() > 0) card.addView(buttons, Ui.lpMargins(this, -1, -2, 0, 14, 0, 0));
        divider(card);
        card.addView(Ui.text(this,"USB · Wi-Fi · LAN",13,Ui.TEXT,700));
        List<String> lan=PhoneNetwork.lanAddresses();
        String lanHelp=lan.isEmpty()
                ? "Для проводной сети подключите Ethernet-переходник к тому же роутеру, что и ПК. Если отладка без Wi-Fi не включается — в XPhone на ПК откройте «Сеть / LAN» и подготовьте телефон через USB."
                : "LAN-адрес: "+String.join(", ",lan)+". На ПК откройте «Сеть / LAN» и укажите IP и порт. После подготовки по USB используется порт 5555; у защищённой отладки порт может быть другим.";
        card.addView(Ui.hint(this,lanHelp,12,Ui.MUTED,500),Ui.lpMargins(this,-1,-2,0,6,0,0));


        divider(card);
        card.addView(plainSwitch("Начинать трансляцию вместе с игрой", "Открыли игру — экран сразу на ПК",
                Prefs.get(this, Prefs.AUTO_STREAM, false), on -> Prefs.put(this, Prefs.AUTO_STREAM, on)));
        divider(card);
        card.addView(plainSwitch("Останавливать после игры", "Экономит заряд и трафик",
                Prefs.get(this, Prefs.AUTO_STREAM_STOP, true), on -> Prefs.put(this, Prefs.AUTO_STREAM_STOP, on)));
        return card;
    }

    // ── Вкладка «Настройки» ───────────────────────────────────────────

    private void renderSettings() {
        TextView update = Ui.button(this, updates.hasDownload() ? "Установить скачанное обновление" : "Проверить обновления", false);
        update.setOnClickListener(v -> {
            if (updates.hasDownload()) updates.installDownloaded();
            else updates.check(true);
        });
        content.addView(update,Ui.lpMargins(this,-1,-2,0,8,0,0));
        TextView connect=Ui.button(this,"Трансляция на компьютер",false);
        connect.setOnClickListener(v->{tab=TAB_STREAM;render();scroll.scrollTo(0,0);});
        content.addView(connect,Ui.lpMargins(this,-1,-2,0,8,0,0));
        LinearLayout appearance = appearanceControls();
        TextView overlayButtons=Ui.button(this,"Кнопки оверлея",false);
        overlayButtons.setOnClickListener(v->showPanelButtons());
        appearance.addView(overlayButtons,Ui.lpMargins(this,-1,-2,0,8,0,0));
        menuGroup("Оформление панели", appearance);
        LinearLayout preferences = card();
        preferences.addView(plainSwitch("Подсказки и пояснения","Показывать поясняющий текст в хабе и оверлее. Текущие данные показателей доступны всегда",Ui.hintsEnabled(this),enabled->{
            Prefs.put(this,"show_hints",enabled);BoostService.refreshAppearance();render();
        }));
        TextView language=Ui.button(this,"Язык: Русский",false);language.setOnClickListener(v->{LinearLayout info=card();info.addView(Ui.text(this,"Сейчас доступен русский язык. Другие языки появятся после подготовки переводов.",13,Ui.TEXT,500));showThemedInfo("Язык приложения",info,"Понятно",null);});
        preferences.addView(language,Ui.lpMargins(this,-1,-2,0,8,0,0));
        if(Prefs.get(this,"privacy_available",false)&&Shell.ready(this)) {
            divider(preferences);
            preferences.addView(plainSwitch("Скрывать уведомления при стриме","На время подключения и стрима скрывает системные баннеры и блокирует шторку. Уведомления сохраняются. Внутриигровые сообщения не скрываются.",Prefs.get(this,"stream_privacy",true),on->{Prefs.put(this,"stream_privacy",on);StreamPrivacy.tick(this);}));
        }
        if(Booster.isSamsung())preferences.addView(Ui.text(this,"Автоотключение штатного Game Booster недоступно: Samsung при этом завершает игры. XPhone больше не отключает Game Tools.",12,Ui.MUTED,500),Ui.lpMargins(this,-1,-2,0,8,0,0));
        menuGroup("Подсказки, язык и приватность", preferences);
        LinearLayout perms = card();
        Shell.State st = Shell.state(this);
        perms.addView(shizukuRow(st));
        divider(perms);
        perms.addView(statusRow("Поверх других приложений", "Чтобы показывать язычок и панель", overlayOk(this),
                () -> startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName())))));
        divider(perms);
        perms.addView(statusRow("Доступ к истории использования", "Чтобы понять, что открыта игра", usageOk(this),
                () -> startActivity(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))));
        if (Build.VERSION.SDK_INT >= 33) {
            divider(perms);
            perms.addView(statusRow("Уведомления", "Значок «XPhone следит за играми»", notifOk(this),
                    () -> requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 2)));
        }
        divider(perms);
        boolean wifiOk = Booster.canWriteSecure(this) || st == Shell.State.READY;
        perms.addView(statusRow("Сам включать «Отладку по Wi-Fi»",
                wifiOk ? "Телефон включит её сам, когда нужна связь с ПК"
                        : "Подключите телефон кабелем к XPhone на ПК один раз — он выдаст это разрешение",
                wifiOk, () -> {
                    if (st == Shell.State.READY) grantAllViaShizuku();
                    else Toast.makeText(this, "Подключите телефон кабелем к компьютеру с открытым XPhone — разрешение выдастся само.", Toast.LENGTH_LONG).show();
                }));
        if (st != Shell.State.READY) {
            divider(perms);
            perms.addView(statusRow("«Не беспокоить» без Shizuku", "Доступ к режиму «Не беспокоить»", Booster.dndAccess(this),
                    () -> startActivity(new Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))));
        }
        if (st == Shell.State.READY && !(overlayOk(this) && usageOk(this) && notifOk(this) && Booster.canWriteSecure(this))) {
            TextView all = Ui.button(this, "Выдать всё через Shizuku", true);
            all.setOnClickListener(v -> grantAllViaShizuku());
            perms.addView(all, Ui.lpMargins(this, -1, -2, 0, 14, 0, 0));
        }
        menuGroup("Разрешения и Shizuku", perms);

        LinearLayout support=card();
        support.addView(Ui.hint(this,"Если XPhone оказался полезен, можно угостить разработчика кофе. Поддержка добровольная и не влияет на доступность функций.",13,Ui.MUTED,500));
        String donatePayUrl="https://donatepay.ru/don/unholyhyde";
        TextView donate=Ui.button(this,"Поддержать через DonatePay",true);
        donate.setEnabled(!donatePayUrl.isEmpty());
        donate.setOnClickListener(v->openExternalLink(donatePayUrl));
        support.addView(donate,Ui.lpMargins(this,-1,-2,0,14,0,0));
        support.addView(Ui.hint(this,"Страница оплаты откроется в браузере.",12,Ui.MUTED,500),Ui.lpMargins(this,-1,-2,0,8,0,0));
        TextView telegram=Ui.button(this,"Telegram — новости и сообщество",false);
        telegram.setOnClickListener(v->openExternalLink("https://t.me/FCKNHYDE"));
        support.addView(telegram,Ui.lpMargins(this,-1,-2,0,14,0,0));
        TextView issues=Ui.button(this,"GitHub Issues — ошибки и предложения",false);
        issues.setOnClickListener(v->openExternalLink("https://github.com/UnholyHyde/XPhone/issues"));
        support.addView(issues,Ui.lpMargins(this,-1,-2,0,8,0,0));
        menuGroup("Поддержка и связь", support);

        TextView credits = Ui.text(this, "Иконки Lucide (ISC), шрифты Manrope и Unbounded (OFL), Shizuku API (Apache 2.0)", 11, Ui.DIM, 500);
        credits.setGravity(Gravity.CENTER);
        content.addView(credits, Ui.lpMargins(this, -1, -2, 0, 20, 0, 0));
    }

    // ── Общие элементы ────────────────────────────────────────────────

    private LinearLayout card() { return Ui.card(this); }

    private final java.util.Set<String> expandedMenus = new java.util.HashSet<>();

    private void menuGroup(String title, LinearLayout body) {
        TextView button = Ui.button(this, title, false);
        button.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        button.setMinHeight(Ui.dp(this, 48));
        Runnable refresh = () -> {
            boolean expanded = expandedMenus.contains(title);
            button.setText((expanded ? "−  " : "+  ") + title);
            button.setContentDescription(title + (expanded ? ", раскрыто" : ", свёрнуто"));
            body.setVisibility(expanded ? View.VISIBLE : View.GONE);
        };
        button.setOnClickListener(v -> {
            if (!expandedMenus.add(title)) expandedMenus.remove(title);
            refresh.run();
        });
        content.addView(button, Ui.lpMargins(this, -1, -2, 0, 8, 0, 0));
        content.addView(body, Ui.lpMargins(this, -1, -2, 0, 4, 0, 0));
        refresh.run();
    }

    private void section(String title) {
        content.addView(Ui.text(this, Ui.GLASS ? title.charAt(0) + title.substring(1).toLowerCase() : title,
                Ui.GLASS ? 13 : 11, Ui.MUTED, 700), Ui.lpMargins(this, -1, -2, 4, 22, 0, 10));
    }

    private void divider(LinearLayout l) {
        View d = new View(this);
        d.setBackgroundColor(Ui.LINE);
        l.addView(d, Ui.lpMargins(this, -1, 1, 0, 12, 0, 12));
    }

    private LinearLayout textBlock(String title, String sub) {
        LinearLayout t = new LinearLayout(this);
        t.setOrientation(LinearLayout.VERTICAL);
        t.addView(Ui.text(this, title, 14, Ui.TEXT, Ui.GLASS ? 700 : 600));
        if (sub != null) t.addView(Ui.hint(this, sub, 12, Ui.MUTED, 500), Ui.lpMargins(this, -2, -2, 0, 4, 0, 0));
        return t;
    }

    private LinearLayout plainSwitch(String title, String sub, boolean on, Ui.Toggle.Listener l) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(textBlock(title, sub), new LinearLayout.LayoutParams(0, -2, 1));
        TextView t = Ui.button(this, "", false);
        t.setMinHeight(Ui.dp(this, 48));
        t.setPadding(Ui.dp(this, 12), 0, Ui.dp(this, 12), 0);
        setOptionState(t, title, on);
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(-2, -2);
        tl.leftMargin = Ui.dp(this, 12);
        row.addView(t, tl);
        t.setOnClickListener(v -> {
            boolean next = !t.isSelected();
            setOptionState(t, title, next);
            l.changed(next);
        });
        row.setOnClickListener(v -> t.performClick());
        return row;
    }

    private void setOptionState(TextView button, String title, boolean on) {
        button.setSelected(on);
        button.setText(on ? "Вкл" : "Выкл");
        button.setContentDescription(title + (on ? ", включено. Выключить" : ", выключено. Включить"));
        button.setTextColor(on ? Ui.ON_ACCENT : Ui.TEXT);
        button.setBackground(Ui.box(this, on ? Ui.ACCENT : Ui.FIELD, on ? Ui.ACCENT : Ui.LINE2, Ui.R_BUTTON));
    }

    private LinearLayout statusRow(String title, String sub, boolean ok, Runnable fix) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(Ui.icon(this, ok ? R.drawable.ic_check : R.drawable.ic_alert, 18, ok ? (Ui.GLASS ? Ui.ACCENT : Ui.TEXT) : Ui.MUTED));
        LinearLayout tb = textBlock(title, sub);
        tb.setPadding(Ui.dp(this, 12), 0, Ui.dp(this, 8), 0);
        row.addView(tb, new LinearLayout.LayoutParams(0, -2, 1));
        if (!ok) {
            TextView b = Ui.button(this, "Выдать", false);
            b.setOnClickListener(v -> fix.run());
            row.addView(b);
        }
        return row;
    }

    private LinearLayout shizukuRow(Shell.State st) {
        String sub, btn = null;
        Runnable act = null;
        switch (st) {
            case READY:
                sub = "Работает — доступны расширенные функции, которые поддерживает телефон";
                btn = "О Shizuku";
                act = this::showShizukuHelp;
                break;
            case NO_PERMISSION:
                sub = "Shizuku запущен, но XPhone ещё не разрешён";
                btn = "Разрешить";
                act = Shell::requestPermission;
                break;
            case NOT_RUNNING:
                sub = "Не запущен. Откройте Shizuku → «Запустить» (через отладку по Wi-Fi). Или подключите телефон к XPhone на ПК — он запустит Shizuku сам.";
                btn = "Открыть";
                act = () -> {
                    Intent i = getPackageManager().getLaunchIntentForPackage(Shell.SHIZUKU_PKG);
                    if (i != null) startActivity(i);
                };
                break;
            default:
                sub = "Для расширенных функций установите Shizuku. Базовая панель работает и без него.";
                btn = "Установить";
                act = this::showShizukuHelp;
        }
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        boolean ok = st == Shell.State.READY;
        row.addView(Ui.icon(this, ok ? R.drawable.ic_check : R.drawable.ic_alert, 18, ok ? (Ui.GLASS ? Ui.ACCENT : Ui.TEXT) : Ui.MUTED));
        LinearLayout tb = textBlock("Shizuku", sub);
        tb.setPadding(Ui.dp(this, 12), 0, Ui.dp(this, 8), 0);
        row.addView(tb, new LinearLayout.LayoutParams(0, -2, 1));
        if (btn != null) {
            TextView b = Ui.button(this, btn, st == Shell.State.NO_PERMISSION);
            Runnable a = act;
            b.setOnClickListener(v -> a.run());
            row.addView(b);
        }
        return row;
    }

    private void showCompanionApps() {
        LinearLayout body=card();
        body.addView(Ui.hint(this,"До шести приложений. Они защищены от очистки XPhone, даже если сейчас закрыты. Игры в этот список не включаются.",12,Ui.MUTED,500));
        for(App app:installedApps()) {
            if(Prefs.isGame(this,app.pkg)||app.pkg.equals(Shell.SHIZUKU_PKG))continue;
            divider(body);
            LinearLayout row=new LinearLayout(this);row.setGravity(Gravity.CENTER_VERTICAL);
            ImageView icon=new ImageView(this);
            try{icon.setImageDrawable(getPackageManager().getApplicationIcon(app.pkg));}catch(Exception ignored){}
            row.addView(icon,new LinearLayout.LayoutParams(Ui.dp(this,32),Ui.dp(this,32)));
            TextView name=Ui.text(this,app.label,13,Ui.TEXT,600);name.setPadding(Ui.dp(this,10),0,Ui.dp(this,10),0);
            row.addView(name,new LinearLayout.LayoutParams(0,-2,1));
            Ui.Toggle toggle=new Ui.Toggle(this);toggle.setOn(CompanionApps.selected(this).contains(app.pkg));
            toggle.setListener(on->{
                if(!CompanionApps.select(this,app.pkg,on)) {
                    toggle.setOn(false);
                    Toast.makeText(this,"Можно выбрать не больше шести приложений",Toast.LENGTH_SHORT).show();
                }
            });
            row.addView(toggle,new LinearLayout.LayoutParams(Ui.dp(this,48),Ui.dp(this,28)));
            row.setMinimumHeight(Ui.dp(this,48));row.setOnClickListener(v->toggle.performClick());
            body.addView(row);
        }
        showThemedInfo("Приложения поверх игры",body,"Готово",this::render);
    }

    private void showPanelButtons() {
        LinearLayout body=card();
        body.addView(Ui.hint(this,"Общий набор кнопок оверлея. Для игры со своим профилем набор выбирается в её профиле. Скрытие кнопки не выключает функцию или её автозапуск.",12,Ui.MUTED,500));
        for(String id:PanelFeatures.IDS) {
            if(!PanelFeatures.available(this,id)) continue;
            divider(body);
            body.addView(plainSwitch(PanelFeatures.title(id),null,PanelFeatures.selected(this,id),
                    on -> {Prefs.put(this,"panel_tile_"+id,on);BoostService.refreshAppearance();}));
        }
        showThemedInfo("Кнопки оверлея",body,"Готово",this::render);
    }

    private void openExternalLink(String url) {
        try { startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse(url))); }
        catch(android.content.ActivityNotFoundException e) {
            Toast.makeText(this,"Нет приложения для открытия ссылки. Установите браузер или проверьте приложения по умолчанию.",Toast.LENGTH_LONG).show();
        }
    }

    /** Собственное оформление без системной шапки и серой рамки AlertDialog. */
    private android.app.Dialog showThemedInfo(String heading, LinearLayout body, String closeLabel, Runnable after) {
        android.app.Dialog dialog=new android.app.Dialog(this);
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        body.setBackground(Ui.box(this,0xFF17191F,Ui.LINE2,Ui.R_CARD));
        TextView title=Ui.text(this,heading,18,Ui.TEXT,800);
        body.addView(title,0,Ui.lpMargins(this,-1,-2,0,0,0,16));
        TextView close=Ui.button(this,closeLabel,false);
        close.setOnClickListener(v->dialog.dismiss());
        body.addView(close,Ui.lpMargins(this,-1,-2,0,14,0,0));
        ScrollView sc=new ScrollView(this);
        sc.setVerticalScrollBarEnabled(false);
        sc.addView(body);
        dialog.setContentView(sc);
        dialog.setOnDismissListener(d->{if(after!=null)after.run();});
        android.view.Window window=dialog.getWindow();
        if(window==null)return dialog;
        window.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT));
        dialog.show();
        android.graphics.Point size=new android.graphics.Point();
        getWindowManager().getDefaultDisplay().getSize(size);
        int width=Math.min(size.x-Ui.dp(this,32),Ui.dp(this,520));
        int maxHeight=Math.max(Ui.dp(this,120),size.y-Ui.dp(this,96));
        sc.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(maxHeight,View.MeasureSpec.AT_MOST));
        window.setLayout(width,Math.min(maxHeight,sc.getMeasuredHeight()));
        return dialog;
    }

    private void showShizukuHelp() {
        LinearLayout body=card();
        body.addView(Ui.text(this,"Shizuku — отдельное бесплатное приложение. Оно даёт XPhone доступ к системным командам через отладку Android, без root. Разрешение XPhone вы выдаёте сами.",14,Ui.TEXT,500));
        divider(body);
        body.addView(Ui.hint(this,"С ним доступны касания, микрофон, режимы питания и другие расширенные кнопки — если их поддерживает телефон. Shizuku не добавляет отсутствующие функции. Без него остаются базовая панель, показатели и подключение к ПК.",13,Ui.MUTED,500));
        divider(body);
        body.addView(Ui.text(this,"1. Установите Shizuku с официальной страницы: там есть Google Play и GitHub.\n\n2. Откройте Shizuku и запустите через беспроводную отладку по его инструкции (Android 11+). Либо подключите телефон к XPhone на ПК через USB и подтвердите отладку.\n\n3. Вернитесь в XPhone и нажмите «Разрешить». Подтвердите запрос Shizuku.\n\nПосле перезагрузки телефона Shizuku обычно нужно запускать снова.",13,Ui.TEXT,500));
        TextView download=Ui.button(this,"Официальная страница установки",true);
        download.setOnClickListener(v->openExternalLink("https://shizuku.rikka.app/download/"));
        body.addView(download,Ui.lpMargins(this,-1,-2,0,14,0,0));
        if(Shell.state(this)!=Shell.State.NOT_INSTALLED) {
            TextView open=Ui.button(this,"Открыть Shizuku",false);
            open.setOnClickListener(v->{
                Intent intent=getPackageManager().getLaunchIntentForPackage(Shell.SHIZUKU_PKG);
                if(intent!=null)startActivity(intent);
                else Toast.makeText(this,"Не удалось открыть Shizuku",Toast.LENGTH_SHORT).show();
            });
            body.addView(open,Ui.lpMargins(this,-1,-2,0,8,0,0));
        }
        showThemedInfo("Расширенные функции с Shizuku",body,"Понятно",null);
    }

    private void grantAllViaShizuku() {
        String p = getPackageName();
        String cmd = "appops set " + p + " SYSTEM_ALERT_WINDOW allow; appops set " + p + " GET_USAGE_STATS allow; "
                + "cmd notification allow_dnd " + p + "; pm grant " + p + " android.permission.WRITE_SECURE_SETTINGS; "
                + (Build.VERSION.SDK_INT >= 33 ? "pm grant " + p + " android.permission.POST_NOTIFICATIONS; " : "");
        Shell.runAsync(this, cmd, r -> {
            render();
            if (Prefs.enabled(this) && permissionsOk(this)) BoostService.start(this);
            Toast.makeText(this, permissionsOk(this) ? "Готово" : "Не всё получилось — выдайте вручную", Toast.LENGTH_SHORT).show();
        });
    }

    // ── Игры ──────────────────────────────────────────────────────────

    private static final class App {
        String pkg, label;
    }

    private List<App> installedApps() {
        PackageManager pm = getPackageManager();
        Intent main = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<App> list = new ArrayList<>();
        for (ResolveInfo ri : pm.queryIntentActivities(main, 0)) {
            String pkg = ri.activityInfo.packageName;
            if (pkg.equals(getPackageName()) || list.stream().anyMatch(a -> a.pkg.equals(pkg))) continue;
            App a = new App();
            a.pkg = pkg;
            a.label = ri.loadLabel(pm).toString();
            list.add(a);
        }
        Collections.sort(list, (x, y) -> x.label.compareToIgnoreCase(y.label));
        return list;
    }

    private LinearLayout gameRow(App a) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        ImageView ic = new ImageView(this);
        try { ic.setImageDrawable(getPackageManager().getApplicationIcon(a.pkg)); } catch (Exception ignored) { }
        row.addView(ic, new LinearLayout.LayoutParams(Ui.dp(this, 32), Ui.dp(this, 32)));
        TextView name = Ui.text(this, a.label, 14, Ui.TEXT, 600);
        name.setPadding(Ui.dp(this, 12), 0, Ui.dp(this, 8), 0);
        name.setSingleLine(true);
        row.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
        Ui.Toggle t = new Ui.Toggle(this);
        t.setOn(true);
        t.setListener(on -> Prefs.setGame(this, a.pkg, on));
        row.addView(t);
        return row;
    }

    private void pickApps(List<App> apps) {
        LinearLayout body=card();java.util.Set<String> chosen=new java.util.HashSet<>();
        android.widget.EditText search=new android.widget.EditText(this);search.setTextColor(Ui.TEXT);search.setHintTextColor(Ui.MUTED);search.setHint("Найти приложение");search.setSingleLine(true);body.addView(search);
        LinearLayout list=new LinearLayout(this);list.setOrientation(LinearLayout.VERTICAL);body.addView(list);
        Runnable fill=()->{list.removeAllViews();String query=search.getText().toString().toLowerCase(java.util.Locale.ROOT);
            for(App a:apps){if(Prefs.isGame(this,a.pkg)||!a.label.toLowerCase(java.util.Locale.ROOT).contains(query))continue;
                LinearLayout row=new LinearLayout(this);row.setGravity(Gravity.CENTER_VERTICAL);row.setPadding(0,Ui.dp(this,9),0,Ui.dp(this,9));
                ImageView icon=new ImageView(this);try{icon.setImageDrawable(getPackageManager().getApplicationIcon(a.pkg));}catch(Exception ignored){}row.addView(icon,new LinearLayout.LayoutParams(Ui.dp(this,32),Ui.dp(this,32)));
                TextView title=Ui.text(this,a.label,13,Ui.TEXT,500);title.setPadding(Ui.dp(this,10),0,Ui.dp(this,8),0);row.addView(title,new LinearLayout.LayoutParams(0,-2,1));
                Ui.Toggle check=new Ui.Toggle(this);check.setOn(chosen.contains(a.pkg));check.setListener(on->{if(on)chosen.add(a.pkg);else chosen.remove(a.pkg);});row.addView(check,new LinearLayout.LayoutParams(Ui.dp(this,44),Ui.dp(this,26)));row.setOnClickListener(v->check.performClick());list.addView(row);
            }
        };search.addTextChangedListener(new android.text.TextWatcher(){public void beforeTextChanged(CharSequence s,int st,int n,int a){}public void onTextChanged(CharSequence s,int st,int before,int count){fill.run();}public void afterTextChanged(android.text.Editable e){}});fill.run();
        final android.app.Dialog[] dialog={null};TextView add=Ui.button(this,"Добавить выбранные",true);add.setOnClickListener(v->{for(String pkg:chosen)Prefs.setGame(this,pkg,true);if(dialog[0]!=null)dialog[0].dismiss();});body.addView(add,Ui.lpMargins(this,-1,-2,0,14,0,0));dialog[0]=showThemedInfo("Добавить игры",body,"Отмена",this::render);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        render();
    }
}
