package ru.xphone.booster;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.os.Build;
import android.provider.Settings;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Всё, что поверх игры: язычок у края, панель, приветствие, кнопка микрофона, короткие сообщения.
 * Показывает только то, что есть на этом телефоне.
 */
public final class Overlay {
    private final Context ctx;
    private final WindowManager wm;
    private final MiniHud hud;
    private final CompanionWindows companions;
    private static final String HUD = "hud", TOUCH = "touch";
    private View handle, micBubble, toastView, welcomeView;
    private WindowManager.LayoutParams handleLp, micLp;
    private boolean hubSuspended,resumeTouches;
    private boolean handleDragging,recoveringHandle;
    private Runnable handleHold;
    private int handleScreenW, handleScreenH;
    private float handleDensity;
    private Runnable displayReflow;
    private boolean displayReflowPending;
    private View retiredPanel;
    private final FpsHud fpsHud;
    private FrameLayout panelRoot;
    private View slideHeader,slideLeft,slideRight;
    private int slideToken;
    private boolean slidingClosed;
    private float slideVisible=1f;
    private View popupView;
    private String popupTitle;
    private View metricTooltip;
    private TextView metricTooltipValue;
    private String metricTooltipKey;
    private final Map<String,Telemetry.Metric> latestHeaderMetrics=new HashMap<>();
    private View panelView;
    private boolean tacticalBuilding;
    private boolean powerBusy;
    private Integer powerCursor;
    private GamePower.State powerState;
    private static final String RECORD="record";
    private String gamePkg;
    private final Map<String, Tile> tiles = new HashMap<>();
    private LinearLayout headerMetrics;
    private final Map<String,TextView> headerCells=new java.util.LinkedHashMap<>();
    private java.util.function.Consumer<List<Telemetry.Metric>> warmMetrics;
    private Runnable micLongPress;
    private final Map<View,Integer> pulses=new java.util.WeakHashMap<>();
    private java.util.function.Consumer<List<Telemetry.Metric>> metricsSubscription;
    private final Map<String,Boolean> sessionExtras=new HashMap<>();
    private final Map<String,Boolean> cachedStates=new HashMap<>();
    private final java.util.Set<String> pendingToggles=new java.util.HashSet<>();
    private long statesAt;
    private int panelRequest;
    private boolean opening;


    /** Что панель просит у службы. */
    public interface Listener {
        /** Панель открыла другое окно (настройки) — не считать это выходом из игры. */
        void openedExternal();
        /** «Скрыть до перезахода». */
        void hideUntilExit();
        void restartGame();
    }
    private final Listener listener;

    private static final String STREAM = Booster.STREAM, MIC = "mic";
    private static final android.os.Handler MAIN = new android.os.Handler(android.os.Looper.getMainLooper());

    public Overlay(Context ctx, Listener listener) {
        this.ctx = ctx;
        this.listener = listener;
        this.wm = ctx.getSystemService(WindowManager.class);
        hud = new MiniHud(ctx);fpsHud=new FpsHud(ctx);
        companions = new CompanionWindows(ctx,this::toast,this::closePanel);
    }

    public boolean canShow() { return !hubSuspended&&Settings.canDrawOverlays(ctx); }

    public void suspendForHub(boolean suspended){
        if(hubSuspended==suspended)return;hubSuspended=suspended;
        if(suspended){
            cancelHandleHold();handleDragging=false;closePanel();closePopup();closeMetricTooltip();
            if(handle!=null)handle.setVisibility(View.INVISIBLE);
            if(micBubble!=null)micBubble.setVisibility(View.INVISIBLE);
            hud.hide();fpsHud.hide();remove(welcomeView);welcomeView=null;remove(toastView);toastView=null;
            resumeTouches=TouchTrace.enabled(ctx);TouchTrace.stop();companions.suspend(true);
        }else{
            if(handle!=null)handle.setVisibility(View.VISIBLE);syncMicPanelVisibility();companions.suspend(false);
            boolean restore=resumeTouches;resumeTouches=false;
            if(restore&&handle!=null&&gamePkg!=null)Shell.background(()->{if(!hubSuspended&&!BoostService.hubOnScreen())TouchTrace.set(ctx,true);});
            syncHud();
        }
    }
    private int dp(float v) { return Ui.dp(ctx, v); }

    private Point screen() {
        Point p = new Point();
        // Service WindowMetrics can lag behind the game's rotation.
        android.view.Display display = ctx.getSystemService(android.hardware.display.DisplayManager.class)
                .getDisplay(android.view.Display.DEFAULT_DISPLAY);
        if (display != null) display.getRealSize(p);
        else wm.getDefaultDisplay().getRealSize(p);
        return p;
    }

    private static WindowManager.LayoutParams baseLp(int w, int h, int flags) {
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(w, h,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, flags, PixelFormat.TRANSLUCENT);
        if (Build.VERSION.SDK_INT >= 30) lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        else if (Build.VERSION.SDK_INT >= 28) lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        return lp;
    }

    private boolean left() { return Prefs.handleLeft(ctx); }

    private void remove(View v) {
        if (v == null) return;
        try { wm.removeView(v); } catch (Exception ignored) { }
    }

    // ── Язычок ────────────────────────────────────────────────────────

    private android.graphics.Rect handleInsets(){
        android.graphics.Rect result=new android.graphics.Rect();
        if(Build.VERSION.SDK_INT>=30)try{
            android.graphics.Insets gestures=wm.getCurrentWindowMetrics().getWindowInsets().getInsets(android.view.WindowInsets.Type.mandatorySystemGestures());
            int cap=dp(4);result.set(Math.min(cap,gestures.left),Math.min(cap,gestures.top),Math.min(cap,gestures.right),Math.min(cap,gestures.bottom));
        }catch(Exception ignored){}return result;
    }
    private int[] handlePlacement(int edge){Point size=screen();android.graphics.Rect insets=handleInsets();float ratio=edge<2?Prefs.handleY(ctx):Prefs.getFloat(ctx,"handle_x",.5f);
        int[] p=HandleGeometry.place(size.x,size.y,insets.left,insets.top,insets.right,insets.bottom,dp(6),dp(24),dp(56),edge,ratio);
        // A camera cutout only displaces a handle that actually intersects it, along the edge.
        if(Build.VERSION.SDK_INT>=30)try{
            android.view.DisplayCutout cutout=wm.getCurrentWindowMetrics().getWindowInsets().getDisplayCutout();
            if(cutout!=null)for(android.graphics.Rect rect:cutout.getBoundingRects()){
                android.graphics.Rect box=new android.graphics.Rect(p[0],p[1],p[0]+p[2],p[1]+p[3]);
                if(!android.graphics.Rect.intersects(box,rect))continue;
                if(edge<2){int before=rect.top-dp(6)-p[3],after=rect.bottom+dp(6);boolean beforeFits=before>=insets.top+dp(6),afterFits=after+p[3]<=size.y-insets.bottom-dp(6);
                    if(beforeFits&&(!afterFits||Math.abs(p[1]-before)<Math.abs(p[1]-after)))p[1]=before;else if(afterFits)p[1]=after;
                }else{int before=rect.left-dp(6)-p[2],after=rect.right+dp(6);boolean beforeFits=before>=insets.left+dp(6),afterFits=after+p[2]<=size.x-insets.right-dp(6);
                    if(beforeFits&&(!afterFits||Math.abs(p[0]-before)<Math.abs(p[0]-after)))p[0]=before;else if(afterFits)p[0]=after;
                }
            }
        }catch(Exception ignored){}return p;
    }
    private void cancelHandleHold(){if(handleHold!=null){MAIN.removeCallbacks(handleHold);handleHold=null;}}
    @SuppressLint("ClickableViewAccessibility")
    public void showHandle(String pkg) {
        companions.begin(pkg);gamePkg=pkg;
        if(!canShow()||handle!=null)return;
        if(warmMetrics==null){warmMetrics=values->{};TelemetryFeed.listen(ctx,warmMetrics);}
        Ui.load(ctx);final int edge=Prefs.handleEdge(ctx);boolean vertical=edge<2;
        FrameLayout h=new FrameLayout(ctx);View pill=new View(ctx);
        pill.setBackground(Ui.box(ctx,0xFFFFFFFF,0x66000000,4));
        h.addView(pill,new FrameLayout.LayoutParams(dp(vertical?8:32),dp(vertical?32:8),Gravity.CENTER));
        int[] position=handlePlacement(edge);handleLp=baseLp(position[2],position[3],WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN);
        handleLp.setTitle("XPhone:handle");handleLp.alpha=.5f;handleLp.gravity=Gravity.TOP|Gravity.START;handleLp.x=position[0];handleLp.y=position[1];if(Build.VERSION.SDK_INT>=30)handleLp.setFitInsetsTypes(0);
        Point size=screen();handleScreenW=size.x;handleScreenH=size.y;handleDensity=ctx.getResources().getDisplayMetrics().density;
        float[] down=new float[4];boolean[] moved={false};long[] downAt={0};HandleDoubleTap taps=new HandleDoubleTap();
        h.setContentDescription("Язычок XPhone. Двойное нажатие открывает панель; удержание позволяет переместить.");
        h.setOnTouchListener((v,e)->{
            switch(e.getActionMasked()){
                case MotionEvent.ACTION_DOWN:
                    cancelHandleHold();handleDragging=false;moved[0]=false;downAt[0]=e.getEventTime();down[0]=e.getRawX();down[1]=e.getRawY();down[2]=handleLp.x;down[3]=handleLp.y;
                    handleHold=()->{if(handle!=h||moved[0])return;taps.clear();handleDragging=true;h.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);};
                    MAIN.postDelayed(handleHold,android.view.ViewConfiguration.getLongPressTimeout());return true;
                case MotionEvent.ACTION_MOVE:
                    if(handle!=h)return true;
                    float dx=e.getRawX()-down[0],dy=e.getRawY()-down[1];
                    if(handleDragging){Point bounds=screen();handleLp.x=HandleGeometry.clamp(Math.round(down[2]+dx),0,bounds.x-handleLp.width);handleLp.y=HandleGeometry.clamp(Math.round(down[3]+dy),0,bounds.y-handleLp.height);try{wm.updateViewLayout(h,handleLp);}catch(Exception ignored){}return true;}
                    if(Math.hypot(dx,dy)>android.view.ViewConfiguration.get(ctx).getScaledTouchSlop()){moved[0]=true;taps.clear();cancelHandleHold();}return true;
                case MotionEvent.ACTION_UP:
                    cancelHandleHold();
                    if(handleDragging&&handle==h){handleDragging=false;Point bounds=screen();android.graphics.Rect insets=handleInsets();float centerX=handleLp.x+handleLp.width/2f,centerY=handleLp.y+handleLp.height/2f;
                        int target=HandleGeometry.nearest(centerX,centerY,bounds.x,bounds.y,insets.left,insets.top,insets.right,insets.bottom);
                        float ratio=target<2?HandleGeometry.ratio(centerY,bounds.y,insets.top,insets.bottom,dp(6),dp(56)):HandleGeometry.ratio(centerX,bounds.x,insets.left,insets.right,dp(6),dp(56));
                        if(target<2)Prefs.setHandleY(ctx,ratio);else Prefs.putFloat(ctx,"handle_x",ratio);
                        taps.clear();setHandleEdge(target);
                    }else if(handle==h&&!moved[0]&&e.getEventTime()-downAt[0]<android.view.ViewConfiguration.getLongPressTimeout()){
                        if(taps.tap(e.getEventTime(),e.getRawX(),e.getRawY(),android.view.ViewConfiguration.getDoubleTapTimeout(),android.view.ViewConfiguration.get(ctx).getScaledDoubleTapSlop()))openPanel();
                    }else taps.clear();return true;
                case MotionEvent.ACTION_POINTER_DOWN:
                    moved[0]=true;taps.clear();cancelHandleHold();return true;
                case MotionEvent.ACTION_CANCEL:
                    taps.clear();cancelHandleHold();handleDragging=false;
                    if(handle==h){int[] p=handlePlacement(edge);handleLp.x=p[0];handleLp.y=p[1];try{wm.updateViewLayout(h,handleLp);}catch(Exception ignored){}}return true;
            }return true;
        });
        handle=h;try{wm.addView(h,handleLp);}catch(Exception e){handle=null;android.util.Log.w("XPhone","Handle window could not be attached",e);Diagnostics.failure(ctx,"overlay.handle.failed",e);return;}
        Diagnostics.event(ctx, "overlay.handle.shown edge=" + edge);
        if(extraEnabled("mic"))showMicBubble();syncHud();
    }
    public void setSide(boolean leftSide){setHandleEdge(leftSide?HandleGeometry.LEFT:HandleGeometry.RIGHT);}
    private void setHandleEdge(int edge){
        remove(welcomeView);welcomeView=null;
        cancelHandleHold();handleDragging=false;Prefs.setHandleEdge(ctx,edge);String pkg=gamePkg;boolean wasOpen=panelRoot!=null;
        closePanel();remove(handle);handle=null;showHandle(pkg);if(wasOpen)openPanel();
    }

    public void hideAll() {
        resumeTouches=false;
        cancelHandleHold();handleDragging=false;
        if(displayReflow!=null){MAIN.removeCallbacks(displayReflow);displayReflow=null;}
        displayReflowPending=false;
        if(warmMetrics!=null){TelemetryFeed.stop(warmMetrics);warmMetrics=null;}
        sessionExtras.clear();cachedStates.clear();statesAt=0;powerState=null;
        companions.end();
        TouchTrace.stop();
        hud.hide();fpsHud.hide();
        closePanel();
        remove(handle);
        handle = null;
        hideMicBubble();
        remove(welcomeView);
        welcomeView = null;
        gamePkg = null;
    }

    /** Reflow after rotation; keep the handle within the visible screen. */
    public void ensureHandleVisible() {
        if(!canShow()||gamePkg==null)return;
        // A reflow or WindowManager failure may have detached the view while the
        // game itself did not change. Restore it without reapplying game actions.
        if(handle==null||!handle.isAttachedToWindow()){
            if(recoveringHandle)return;
            recoveringHandle=true;
            try{View old=handle;handle=null;remove(old);showHandle(gamePkg);}
            finally{recoveringHandle=false;}
            return;
        }
        if(handle.getVisibility()!=View.VISIBLE)handle.setVisibility(View.VISIBLE);
        Point size=screen();
        if(displayReflowPending||size.x!=handleScreenW||size.y!=handleScreenH||Math.abs(handleDensity-ctx.getResources().getDisplayMetrics().density)>.01f){requestDisplayReflow();return;}
        if(handleDragging)return;int[] position=handlePlacement(Prefs.handleEdge(ctx));
        if(handleLp.x!=position[0]||handleLp.y!=position[1]){handleLp.x=position[0];handleLp.y=position[1];try{wm.updateViewLayout(handle,handleLp);}catch(Exception ignored){}}
    }
    public void requestDisplayReflow(){
        displayReflowPending=true;
        if(displayReflow!=null)MAIN.removeCallbacks(displayReflow);
        displayReflow=()->{
            if(DisplayResolution.changing){requestDisplayReflow();return;}
            displayReflow=null;
            // showHandle() deliberately refuses windows while the hub is on
            // screen. Do not delete the suspended handle and lose its only
            // reference; resume will reflow it against the new display bounds.
            if(hubSuspended||!canShow())return;
            displayReflowPending=false;
            closeMetricTooltip();Ui.load(ctx);
            if(handle!=null){View old=handle;handle=null;showHandle(gamePkg);remove(old);}
            if(micBubble!=null){View old=micBubble;micBubble=null;showMicBubble();remove(old);}
            hud.hide();fpsHud.hide();companions.reflow();
            if(panelRoot!=null){
                if(retiredPanel!=null)remove(retiredPanel);retiredPanel=panelRoot;
                if(metricsSubscription!=null){TelemetryFeed.stop(metricsSubscription);metricsSubscription=null;}
                PcLink.removeListener(pcListener);panelRoot=null;panelView=null;headerMetrics=null;headerCells.clear();opening=false;panelRequest++;
                statesAt=android.os.SystemClock.elapsedRealtime();openPanel();
            }
            syncHud();
        };MAIN.postDelayed(displayReflow,220);
    }
    private void finishDisplaySwap(View replacement){
        if(retiredPanel==null)return;final View old=retiredPanel;
        replacement.getViewTreeObserver().addOnPreDrawListener(new android.view.ViewTreeObserver.OnPreDrawListener(){public boolean onPreDraw(){replacement.getViewTreeObserver().removeOnPreDrawListener(this);remove(old);if(retiredPanel==old)retiredPanel=null;return true;}});
    }

    private boolean extraEnabled(String id){return sessionExtras.containsKey(id)?sessionExtras.get(id):GameProfiles.extra(ctx,gamePkg,id);}
    public boolean companionForeground(String pkg) { return companions.ownsForeground(pkg); }

    public void syncHud() {
        if(hubSuspended){hud.hide();fpsHud.hide();return;}
        companions.tick(ctx.getSystemService(android.os.PowerManager.class).isInteractive());
        ensureHandleVisible();
        if ((panelRoot!=null||handle!=null) && extraEnabled("hud")) hud.show(); else hud.hide();
        hud.refreshAppearance();
        if((panelRoot!=null||handle!=null)&&Prefs.get(ctx,"fps_hud_enabled",false)&&ScreenFrames.available(ctx))fpsHud.show();else fpsHud.hide();fpsHud.refreshAppearance();
        Tile tile = tiles.get(HUD);
        if (tile != null) tile.paint();
    }

    public void applyHudPreference(){boolean enabled=Prefs.get(ctx,MiniHud.ENABLED,false);sessionExtras.put("hud",enabled);GameProfiles.setExtra(ctx,gamePkg,"hud",enabled);syncHud();}
    public boolean isPanelOpen() { return panelRoot != null; }

    // ── Приветствие ───────────────────────────────────────────────────

    /** Короткое приветствие у язычка вместо сразу открытой панели. */
    public void welcome() {
        android.util.Log.i("XPhone", "welcome canShow=" + canShow());
        if (!canShow()) return;
        Ui.load(ctx);
        remove(welcomeView);
        if(handle==null||handleLp==null)return;
        int edge=Prefs.handleEdge(ctx);
        LinearLayout pill = new LinearLayout(ctx);
        pill.setGravity(Gravity.CENTER_VERTICAL);
        pill.setPadding(dp(14), dp(10), dp(16), dp(10));
        pill.setBackground(Ui.box(ctx, Ui.GLASS ? 0xE6141820 : Ui.PANEL, Ui.LINE2, 999));
        ImageView logo = Ui.icon(ctx, R.drawable.ic_gamepad, 20, Ui.ACCENT);
        pill.addView(logo);
        LinearLayout texts = new LinearLayout(ctx);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setPadding(dp(10), 0, 0, 0);
        texts.addView(Ui.text(ctx, "XPHONE", 13, Ui.TEXT, 800));
        texts.addView(Ui.hint(ctx, "Двойное нажатие — панель", 11, Ui.MUTED, 500), Ui.lpMargins(ctx, -2, -2, 0, 3, 0, 0));
        pill.addView(texts);

        Point size=screen();int margin=dp(8);
        pill.measure(View.MeasureSpec.makeMeasureSpec(Math.max(1,size.x-2*margin),View.MeasureSpec.AT_MOST),View.MeasureSpec.makeMeasureSpec(Math.max(1,size.y-2*margin),View.MeasureSpec.AT_MOST));
        int[] anchor=new int[2];handle.getLocationOnScreen(anchor);
        int width=handle.getWidth()>0?handle.getWidth():handleLp.width,height=handle.getHeight()>0?handle.getHeight():handleLp.height;
        int[] placed=WelcomePlacement.place(size.x,size.y,anchor[0],anchor[1],width,height,edge,pill.getMeasuredWidth(),pill.getMeasuredHeight(),margin,dp(8));
        WindowManager.LayoutParams lp=baseLp(placed[2],placed[3],WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE|WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN);
        lp.gravity=Gravity.TOP|Gravity.START;lp.x=placed[0];lp.y=placed[1];if(Build.VERSION.SDK_INT>=30)lp.setFitInsetsTypes(0);
        welcomeView = pill;
        try { wm.addView(pill, lp); } catch (Exception e) { android.util.Log.w("XPhone", "welcome failed", e); welcomeView = null; return; }
        float from=(edge==HandleGeometry.LEFT||edge==HandleGeometry.TOP?-1:1)*dp(60);
        boolean vertical=edge==HandleGeometry.TOP||edge==HandleGeometry.BOTTOM;
        pill.setAlpha(0f);if(vertical)pill.setTranslationY(from);else pill.setTranslationX(from);
        android.view.ViewPropertyAnimator enter=pill.animate().alpha(1f).setDuration(320).setInterpolator(new DecelerateInterpolator());
        if(vertical)enter.translationY(0);else enter.translationX(0);enter.start();
        MAIN.postDelayed(()->{android.view.ViewPropertyAnimator exit=pill.animate().alpha(0f).setDuration(260);if(vertical)exit.translationY(from);else exit.translationX(from);exit.start();},2200);
        MAIN.postDelayed(()->{remove(pill);if(welcomeView==pill)welcomeView=null;},2500);
        // Never resize the anchor: it must keep the same center and touch target.
        handle.animate().cancel();handle.setScaleX(1f);handle.setScaleY(1f);
    }

    // ── Короткие сообщения ────────────────────────────────────────────

    /** Сообщение сверху по центру на пару секунд («Не беспокоить включено» и т. п.). */
    public void toast(String text, int icon) {
        if (!canShow()) return;
        Ui.load(ctx);
        remove(toastView);
        LinearLayout pill = new LinearLayout(ctx);
        pill.setGravity(Gravity.CENTER_VERTICAL);
        pill.setPadding(dp(14), dp(9), dp(16), dp(9));
        pill.setBackground(Ui.box(ctx, Ui.GLASS ? 0xE6141820 : 0xFFFFFFFF, Ui.GLASS ? Ui.LINE2 : 0, 999));
        int fg = Ui.GLASS ? Ui.TEXT : 0xFF0B0B0E;
        pill.addView(Ui.icon(ctx, icon, 16, Ui.GLASS ? Ui.ACCENT : fg));
        TextView t = Ui.text(ctx, text, 13, fg, 700);
        t.setPadding(dp(8), 0, 0, 0);
        pill.addView(t);
        WindowManager.LayoutParams lp = baseLp(-2, -2,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE);
        lp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        lp.y = dp(18);
        toastView = pill;
        try { wm.addView(pill, lp); } catch (Exception e) { toastView = null; return; }
        pill.setAlpha(0f);
        pill.setTranslationY(-dp(16));
        pill.animate().alpha(1f).translationY(0).setDuration(220).start();
        MAIN.postDelayed(() -> pill.animate().alpha(0f).setDuration(250).start(), 2500);
        MAIN.postDelayed(() -> { remove(pill); if (toastView == pill) toastView = null; }, 2800);
    }

    // ── Кнопка микрофона ──────────────────────────────────────────────

    @SuppressLint("ClickableViewAccessibility")
    public void showMicBubble() {
        if (!canShow() || micBubble != null || !Mic.supported(ctx)) return;
        Ui.load(ctx);
        FrameLayout b = new FrameLayout(ctx);
        FrameLayout face=new FrameLayout(ctx);face.setTag("mic_face");b.addView(face,new FrameLayout.LayoutParams(dp(38),dp(40),Gravity.CENTER));
        ImageView ic = Ui.icon(ctx, Mic.muted() ? R.drawable.ic_mic_off : R.drawable.ic_mic, 22, Ui.TEXT);
        FrameLayout.LayoutParams iconPosition=new FrameLayout.LayoutParams(dp(18),dp(18),Gravity.TOP|Gravity.CENTER_HORIZONTAL);iconPosition.topMargin=dp(4);face.addView(ic,iconPosition);
        TextView status=Ui.text(ctx,"",8,Ui.TEXT,700);status.setTag("mic_status");status.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams statusPosition=new FrameLayout.LayoutParams(-1,dp(17),Gravity.BOTTOM);statusPosition.bottomMargin=dp(1);face.addView(status,statusPosition);
        paintMic(b, ic);

        micLp = baseLp(dp(48), dp(48), WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS);
        micLp.gravity = Gravity.TOP | Gravity.START;
        micLp.alpha=1f;face.setAlpha(Prefs.getInt(ctx,"mic_opacity",95)/100f);
        Point s = screen();
        micLp.x = Math.round(Prefs.getFloat(ctx, "mic_x", left() ? 0.9f : 0.03f) * s.x);
        micLp.y = Math.round(Prefs.getFloat(ctx, "mic_y", 0.45f) * s.y);

        final float[] down = new float[4];final boolean[] held={false},dragged={false};
        b.setOnTouchListener((v, e) -> {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    down[0] = e.getRawX(); down[1] = e.getRawY(); down[2] = micLp.x; down[3] = micLp.y;
                    b.setScaleX(.92f);b.setScaleY(.92f);held[0]=false;dragged[0]=false;
                    micLongPress=()->{if(micBubble!=b||dragged[0])return;held[0]=true;b.setScaleX(1f);b.setScaleY(1f);showPopup("Прозрачность кнопки",buildMicOpacity(),b);};
                    MAIN.postDelayed(micLongPress,android.view.ViewConfiguration.getLongPressTimeout());
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (Math.abs(e.getRawX() - down[0]) > dp(6) || Math.abs(e.getRawY() - down[1]) > dp(6)) {
                        dragged[0]=true;if(micLongPress!=null)MAIN.removeCallbacks(micLongPress);
                        b.setScaleX(1f);b.setScaleY(1f);
                        if(held[0])return true;
                        micLp.x = Math.round(down[2] + e.getRawX() - down[0]);
                        micLp.y = Math.round(down[3] + e.getRawY() - down[1]);
                        wm.updateViewLayout(b, micLp);
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                    if(micLongPress!=null)MAIN.removeCallbacks(micLongPress);
                    b.setScaleX(1f);b.setScaleY(1f);
                    if(held[0])return true;
                    if (!dragged[0] && Math.abs(e.getRawX() - down[0]) < dp(6) && Math.abs(e.getRawY() - down[1]) < dp(6)) {
                        b.setScaleX(1f);b.setScaleY(1f);toggleMic(b, ic);
                    } else {
                        Point p = screen();
                        Prefs.putFloat(ctx, "mic_x", micLp.x / (float) p.x);
                        Prefs.putFloat(ctx, "mic_y", micLp.y / (float) p.y);
                    }
                    return true;
                case MotionEvent.ACTION_CANCEL:if(micLongPress!=null)MAIN.removeCallbacks(micLongPress);b.setScaleX(1f);b.setScaleY(1f);return true;
            }
            return false;
        });
        b.setVisibility(panelRoot==null?View.VISIBLE:View.INVISIBLE);
        micBubble = b;
        try { wm.addView(b, micLp); } catch (Exception e) { micBubble = null; }
    }

    private void syncMicPanelVisibility(){
        // Keep the floating control out of the panel's visual and touch layer.
        if(micBubble!=null)micBubble.setVisibility(panelRoot==null&&!hubSuspended?View.VISIBLE:View.INVISIBLE);
    }

    public void hideMicBubble() {
        if(micLongPress!=null)MAIN.removeCallbacks(micLongPress);
        remove(micBubble);
        micBubble = null;
    }

    private void paintMic(View b, ImageView ic) {
        boolean m = Mic.muted();
        android.graphics.drawable.GradientDrawable background=Ui.box(ctx,0xF01A1B21,0,16);
        background.setStroke(dp(m?2:1),m?(AccentColor.foreground(Ui.ACCENT)==0xFFFFFFFF?Ui.SILVER:Ui.ACCENT):Ui.LINE3);
        View face=b.findViewWithTag("mic_face");if(face!=null)face.setBackground(background);
        TextView status=b.findViewWithTag("mic_status");if(status!=null){status.setText(m?"ВЫКЛ":"ВКЛ");status.setTextColor(Ui.TEXT);}
        ic.setImageDrawable(ctx.getDrawable(m ? R.drawable.ic_mic_off : R.drawable.ic_mic).mutate());
        Ui.tint(ic, Ui.TEXT);
        b.setContentDescription(m ? "Микрофон выключен" : "Микрофон включён");
    }

    private void toggleMic(View b, ImageView ic) {
        final boolean target = !Mic.muted();b.setEnabled(false);
        Shell.background(() -> {
            boolean ok = Mic.set(ctx, target);
            Shell.onMain(() -> {
                b.setEnabled(true);paintMic(b, ic);pulse(b);
                if (ok) toast(target ? "Микрофон выключен" : "Микрофон включён", target ? R.drawable.ic_mic_off : R.drawable.ic_mic);
                else toast("Не получилось переключить микрофон", R.drawable.ic_alert);
                Tile t = tiles.get(MIC);
                if (t != null) t.paint();
            });
        });
    }

    // ── Панель ────────────────────────────────────────────────────────

    /** Какие плитки показывать: только то, что есть и работает на этом телефоне. */
    private List<String> visibleTiles(int[] lockedOut) {
        lockedOut[0] = PanelFeatures.locked(ctx);
        return PanelFeatures.visible(ctx);
    }

    public void reloadAppearance() {
        Ui.load(ctx);
        boolean open=panelRoot!=null;if(open){closePanel();openPanel();}
        if(micBubble!=null){hideMicBubble();showMicBubble();}
        syncHud();
    }

    public void openPanel() {
        if(!canShow()||panelRoot!=null||opening)return;
        // Paint cached states immediately; refreshTiles reads system state in the background.
        Point available=screen();android.graphics.Rect insets=safeInsets();available.x-=2*Math.max(insets.left,insets.right);available.y-=insets.top+insets.bottom;float density=ctx.getResources().getDisplayMetrics().density;
        TacticalLayout candidate=new TacticalLayout((int)(available.x/density),(int)(available.y/density),PanelFeatures.visible(ctx).size(),ctx.getResources().getConfiguration().fontScale);
        if(candidate.fits((int)(available.y/density))){openTacticalPanel(candidate);return;}
        // Accessibility fallback for a very short display or unusually large system text.
        openCompactPanel();
    }

    private void openCompactPanel() {
        if (!canShow() || panelRoot != null) return;
        Ui.load(ctx);
        boolean landscape = ctx.getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
        boolean left = left();
        Point s = screen();

        FrameLayout root = new FrameLayout(ctx);
        root.setBackgroundColor(Ui.SCRIM);
        root.setOnClickListener(v -> closePanel());

        LinearLayout panel = new LinearLayout(ctx);
        panel.setOrientation(LinearLayout.VERTICAL);
        boolean blur = Ui.GLASS && Build.VERSION.SDK_INT >= 31 && wm.isCrossWindowBlurEnabled();
        panel.setBackground(Ui.box(ctx, Ui.GLASS ? (blur ? Ui.PANEL : 0xEB141820) : Ui.PANEL, Ui.GLASS ? Ui.LINE2 : Ui.LINE2, Ui.R_PANEL));
        panel.setPadding(dp(16), dp(14), dp(16), dp(14));
        panel.setClickable(true);
        if (!Ui.GLASS) panel.setElevation(dp(12));

        // Шапка
        LinearLayout head = new LinearLayout(ctx);
        head.setGravity(Gravity.CENTER_VERTICAL);
        FrameLayout mark = new FrameLayout(ctx);
        mark.addView(Ui.icon(ctx, R.drawable.ic_gamepad, 20, Ui.GLASS ? Ui.ACCENT : Ui.TEXT),
                new FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER));
        head.addView(mark, new LinearLayout.LayoutParams(dp(32), dp(32)));
        String brand = Ui.GLASS ? "XPhone" : "XPHONE";
        String heading = brand + " · " + appName(gamePkg);
        android.text.SpannableString headingText = new android.text.SpannableString(heading);
        headingText.setSpan(new android.text.style.ForegroundColorSpan(Ui.MUTED), brand.length(),
                heading.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        TextView title = Ui.text(ctx, heading, 13, Ui.TEXT, 700);
        title.setText(headingText);
        title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        title.setGravity(Gravity.CENTER);
        title.setPadding(dp(6), 0, dp(6), 0);
        title.setContentDescription(heading);
        head.addView(title, new LinearLayout.LayoutParams(0, dp(32), 1));
        FrameLayout close = new FrameLayout(ctx);
        close.setBackground(Ui.GLASS ? Ui.circle(0x1AFFFFFF) : Ui.box(ctx, 0, Ui.LINE2, 9));
        close.addView(Ui.icon(ctx, R.drawable.ic_x, 16, Ui.SILVER), new FrameLayout.LayoutParams(dp(16), dp(16), Gravity.CENTER));
        close.setContentDescription("Закрыть");
        close.setOnClickListener(v -> closePanel());
        head.addView(close, new LinearLayout.LayoutParams(dp(32), dp(32)));
        panel.addView(head);

        /* Header uses the same selected metrics as the mini-window. */
        addHeaderMetrics(head);

        // Плитки
        int[] locked = new int[1];
        List<String> ids = visibleTiles(locked);
        PanelLayout layout = new PanelLayout((int)(s.x/ctx.getResources().getDisplayMetrics().density),
                (int)(s.y/ctx.getResources().getDisplayMetrics().density),ids.size(),Ui.GLASS,
                ctx.getResources().getConfiguration().fontScale,locked[0]>0);
        int cols = layout.columns;
        tiles.clear();
        LinearLayout grid = new LinearLayout(ctx);
        grid.setOrientation(LinearLayout.VERTICAL);
        LinearLayout row = null;
        int tileH = layout.tileHeight;
        for (int i = 0; i < ids.size(); i++) {
            if (i % cols == 0) {
                row = new LinearLayout(ctx);
                grid.addView(row, Ui.lpMargins(ctx, -1, -2, 0, i == 0 ? 0 : 6, 0, 0));
            }
            Tile t = new Tile(ids.get(i), tileH < 90);
            tiles.put(t.id, t);
            LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(0, dp(tileH), 1);
            if (i % cols != 0) tl.leftMargin = dp(8);
            row.addView(t.view, tl);
        }
        int rest = ids.size() % cols;
        if (rest != 0 && row != null) for (int i = rest; i < cols; i++) {
            LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(0, dp(tileH), 1);
            tl.leftMargin = dp(8);
            row.addView(new View(ctx), tl);
        }
        panel.addView(grid);
        TextView hudSettings = Ui.button(ctx, "Настроить мини-окно", false);
        hudSettings.setOnClickListener(v -> showPopup("Мини-окно", MiniHud.controls(ctx,this::applyHudPreference),hudSettings));
        if (ids.contains(HUD)) panel.addView(hudSettings, Ui.lpMargins(ctx,-1,-2,0,8,0,0));
        if(ids.isEmpty()) panel.addView(Ui.hint(ctx,"Кнопки скрыты. Выберите их в XPhone → Панель → Кнопки панели.",12,Ui.MUTED,500));

        // Ещё функции — с Shizuku
        if (locked[0] > 0) {
            TextView hint = Ui.text(ctx, "Ещё " + locked[0] + " " + plural(locked[0]) + " — с Shizuku. Нажмите, чтобы узнать как.", 12, Ui.MUTED, 500);
            hint.setPadding(0, dp(12), 0, 0);
            hint.setOnClickListener(v -> openApp());
            panel.addView(hint);
        }

        // Низ: сторона язычка и «Скрыть до перезахода»
        LinearLayout foot = new LinearLayout(ctx);
        TextView side = footButton(left ? "Вправо" : "Влево", R.drawable.ic_side);
        side.setContentDescription(left ? "Перенести язычок вправо" : "Перенести язычок влево");
        side.setOnClickListener(v -> setSide(!left));
        TextView hide = footButton("Скрыть до перезахода", R.drawable.ic_eye_off);
        hide.setOnClickListener(v -> { if (listener != null) listener.hideUntilExit(); });
        foot.addView(side, new LinearLayout.LayoutParams(0, dp(36), 1));
        LinearLayout.LayoutParams hl = new LinearLayout.LayoutParams(0, dp(36), 2f);
        hl.leftMargin = dp(8);
        foot.addView(hide, hl);
        panel.addView(foot, Ui.lpMargins(ctx, -1, -2, 0, 12, 0, 0));

        ScrollView scroll = new ScrollView(ctx);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.addView(panel);
        panelView=scroll;
        int width = dp(layout.width);
        FrameLayout.LayoutParams sl = new FrameLayout.LayoutParams(width, -2,
                (left ? Gravity.START : Gravity.END) | Gravity.CENTER_VERTICAL);
        sl.leftMargin = sl.rightMargin = dp(12);
        sl.topMargin = sl.bottomMargin = dp(12);
        root.addView(scroll, sl);

        WindowManager.LayoutParams lp = baseLp(-1, -1,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN);
        lp.gravity = Gravity.TOP | Gravity.START;
        if (blur) {
            // «Стекло»: игра за панелью размывается, пока панель открыта.
            lp.flags |= WindowManager.LayoutParams.FLAG_BLUR_BEHIND;
            lp.setBlurBehindRadius(dp(18));
        }
        panelRoot = root;
        try { wm.addView(root, lp); } catch (Exception e) { panelRoot = null; Diagnostics.failure(ctx,"overlay.panel.failed",e); return; }
        Diagnostics.event(ctx, "overlay.panel.open compact");

        finishDisplaySwap(root);startHeaderMetrics();syncHud();syncMicPanelVisibility();
        PcLink.addListener(pcListener);
        float from = (left ? -1 : 1) * (width + dp(24));
        scroll.setTranslationX(from);
        scroll.animate().translationX(0).setDuration(220).setInterpolator(new DecelerateInterpolator()).start();
        refreshTiles();
    }

    private void openTacticalPanel(TacticalLayout layout) {
        if(!canShow()||panelRoot!=null)return;
        Ui.load(ctx);Point size=screen();boolean left=left();
        FrameLayout root=new FrameLayout(ctx);root.setBackgroundColor(0x12000000);root.setOnClickListener(v->closePanelAnimated());
        LinearLayout shell=new LinearLayout(ctx);shell.setOrientation(LinearLayout.VERTICAL);shell.setClickable(false);
        LinearLayout header=new LinearLayout(ctx);header.setGravity(Gravity.CENTER_VERTICAL);
        TextView brand=Ui.text(ctx,"XPHONE",12,Ui.TEXT,800);brand.setGravity(Gravity.CENTER);brand.setBackground(Ui.box(ctx,Ui.overlayFill(ctx),Ui.LINE2,10));
        LinearLayout.LayoutParams brandLp=new LinearLayout.LayoutParams(dp(88),dp(44));brandLp.rightMargin=dp(6);header.addView(brand,brandLp);
        View menu=headerAction(R.drawable.ic_settings,"Действия панели",()->showPopup("Действия панели",buildPanelActions(),header));
        View close=headerAction(R.drawable.ic_x,"Закрыть панель",this::closePanelAnimated);
        header.addView(menu,new LinearLayout.LayoutParams(dp(44),dp(44)));
        LinearLayout.LayoutParams closeLp=new LinearLayout.LayoutParams(dp(44),dp(44));closeLp.leftMargin=dp(6);header.addView(close,closeLp);
        addHeaderMetrics(header);
        shell.addView(header,new LinearLayout.LayoutParams(-1,dp(48)));
        int[] locked=new int[1];List<String> ids=visibleTiles(locked);
        if(ids.remove("sensitivity"))ids.add(0,"sensitivity");tiles.clear();int split=(ids.size()+1)/2;
        LinearLayout banks=new LinearLayout(ctx);banks.setGravity(Gravity.TOP);
        tacticalBuilding=true;
        try {
            View first=tacticalBank(ids.subList(0,split),layout),second=tacticalBank(ids.subList(split,ids.size()),layout);
            banks.addView(first,new LinearLayout.LayoutParams(dp(layout.bankWidth),-2));
            banks.addView(new View(ctx),new LinearLayout.LayoutParams(0,1,1));
            banks.addView(second,new LinearLayout.LayoutParams(dp(layout.bankWidth),-2));
        }finally{tacticalBuilding=false;}
        LinearLayout.LayoutParams bankLp=new LinearLayout.LayoutParams(-1,0,1);bankLp.topMargin=dp(8);shell.addView(banks,bankLp);
        if(ids.isEmpty()){TextView empty=Ui.text(ctx,"Кнопки скрыты — выберите их в разделе «Панель»",12,Ui.TEXT,600);empty.setOnClickListener(v->openApp());shell.addView(empty);}
        android.graphics.Rect safe=safeInsets();int edge=Math.max(safe.left,safe.right)+dp(8);
        FrameLayout.LayoutParams position=new FrameLayout.LayoutParams(-1,-1);
        position.setMargins(edge,safe.top+dp(8),edge,safe.bottom+dp(8));root.addView(shell,position);
        panelView=root;
        WindowManager.LayoutParams lp=baseLp(-1,-1,WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN);
        lp.gravity=Gravity.TOP|Gravity.START;
        if(Build.VERSION.SDK_INT>=30){lp.setFitInsetsTypes(0);lp.layoutInDisplayCutoutMode=WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;}
        slideHeader=header;slideLeft=banks.getChildAt(0);slideRight=banks.getChildAt(2);
        boolean displaySwap=retiredPanel!=null;
        panelRoot=root;
        try{wm.addView(root,lp);}catch(Exception e){panelRoot=null;panelView=null;tiles.clear();Diagnostics.failure(ctx,"overlay.panel.failed",e);return;}
        Diagnostics.event(ctx, "overlay.panel.open tactical");
        finishDisplaySwap(root);startHeaderMetrics();syncHud();syncMicPanelVisibility();
        PcLink.addListener(pcListener);refreshTiles();
        if(!displaySwap){
            setPanelSlide(0f);
            root.getViewTreeObserver().addOnPreDrawListener(new android.view.ViewTreeObserver.OnPreDrawListener(){public boolean onPreDraw(){
                root.getViewTreeObserver().removeOnPreDrawListener(this);
                if(panelRoot==root&&!slidingClosed)animatePanelSlide(false);
                return true;
            }});
        }
    }

    private View tacticalBank(List<String> ids,TacticalLayout layout) {
        LinearLayout bank=new LinearLayout(ctx);bank.setOrientation(LinearLayout.VERTICAL);
        if(ids.isEmpty())return bank;
        bank.setBackground(Ui.box(ctx,Ui.overlayFill(ctx),Ui.LINE2,4));bank.setPadding(dp(4),dp(4),dp(4),dp(4));bank.setClickable(true);
        int index=0,rowNumber=0;
        for(int count:layout.rowCounts(ids.size())){LinearLayout row=new LinearLayout(ctx);
            for(int c=0;c<count;c++){
                Tile tile=new Tile(ids.get(index++),true);tiles.put(tile.id,tile);
                LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,dp(layout.tileHeight),1);if(c>0)lp.leftMargin=dp(4);row.addView(tile.view,lp);
            }
            bank.addView(row,Ui.lpMargins(ctx,-1,-2,0,rowNumber++==0?0:4,0,0));
        }
        return bank;
    }

    private android.graphics.Rect safeInsets() {
        android.graphics.Rect result=new android.graphics.Rect();
        if(Build.VERSION.SDK_INT>=30)try {
            android.graphics.Insets bars=wm.getCurrentWindowMetrics().getWindowInsets().getInsets(
                android.view.WindowInsets.Type.systemBars()|android.view.WindowInsets.Type.displayCutout());
            result.set(bars.left,bars.top,bars.right,bars.bottom);
        }catch(Exception ignored){}
        return result;
    }

    private int pulseSequence;
    /** Explicit frames also work when the game has disabled Android animator scale. */
    private void pulse(View view){
        final int token=++pulseSequence;pulses.put(view,token);
        final long start=android.os.SystemClock.uptimeMillis();
        Runnable frames=new Runnable(){public void run(){
            if(!java.util.Objects.equals(pulses.get(view),token))return;
            if(!view.isAttachedToWindow()){pulses.remove(view);return;}
            float t=Math.min(1f,(android.os.SystemClock.uptimeMillis()-start)/180f);
            float scale=t<.4f?.94f+.08f*(t/.4f):1.02f-.02f*((t-.4f)/.6f);
            view.setScaleX(scale);view.setScaleY(scale);
            if(t<1)MAIN.postDelayed(this,16);else{view.setScaleX(1f);view.setScaleY(1f);pulses.remove(view);}
        }};frames.run();
    }

    private android.graphics.Rect headerInsets(){
        android.graphics.Rect result=safeInsets();
        if(Build.VERSION.SDK_INT>=30)try{
            android.view.WindowInsets insets=wm.getCurrentWindowMetrics().getWindowInsets();
            android.graphics.Insets bars=insets.getInsets(android.view.WindowInsets.Type.systemBars());
            android.view.DisplayCutout cutout=insets.getDisplayCutout();
            boolean intersects=false;
            if(cutout!=null)for(android.graphics.Rect rect:cutout.getBoundingRects())if(rect.top<result.top+dp(44)&&rect.bottom>result.top)intersects=true;
            if(!intersects){result.left=bars.left;result.right=bars.right;}
        }catch(Exception ignored){}return result;
    }
    private View buildPanelActions(){
        LinearLayout body=new LinearLayout(ctx);body.setOrientation(LinearLayout.VERTICAL);
        TextView side=Ui.button(ctx,"Расположение язычка",false);
        side.setOnClickListener(v->{LinearLayout positions=new LinearLayout(ctx);positions.setOrientation(LinearLayout.VERTICAL);String[] names={"Слева","Справа","Сверху","Снизу"};for(int i=0;i<4;i++){final int edge=i;TextView button=Ui.button(ctx,names[i],Prefs.handleEdge(ctx)==i);button.setOnClickListener(w->{closePopup();setHandleEdge(edge);});positions.addView(button,Ui.lpMargins(ctx,-1,-2,0,6,0,0));}showPopup("Расположение язычка",positions,side);});body.addView(side);
        TextView hide=Ui.button(ctx,"Скрыть до перезахода в игру",false);
        hide.setOnClickListener(v->{closePopup();if(listener!=null)listener.hideUntilExit();});body.addView(hide,Ui.lpMargins(ctx,-1,-2,0,10,0,0));
        if(gamePkg!=null&&Shell.ready(ctx)){
            TextView restart=Ui.button(ctx,"Перезапустить игру",false);
            restart.setOnClickListener(v->{closePopup();if(listener!=null)listener.restartGame();});body.addView(restart,Ui.lpMargins(ctx,-1,-2,0,10,0,0));
            body.addView(Ui.hint(ctx,"Закрывает и заново запускает текущую игру.",11,Ui.MUTED,500),Ui.lpMargins(ctx,-1,-2,0,5,0,0));
        }
        TextView app=Ui.button(ctx,"Открыть настройки хаба",false);app.setOnClickListener(v->openApp());body.addView(app,Ui.lpMargins(ctx,-1,-2,0,10,0,0));return body;
    }
    private View headerAction(int icon,String label,Runnable action){
        FrameLayout button=new FrameLayout(ctx);button.setBackground(Ui.box(ctx,Ui.overlayFill(ctx),Ui.LINE2,10));
        button.addView(Ui.icon(ctx,icon,18,Ui.TEXT),new FrameLayout.LayoutParams(dp(18),dp(18),Gravity.CENTER));
        button.setContentDescription(label);button.setOnClickListener(v->{pulse(button);action.run();});return button;
    }
    private void addHeaderMetrics(LinearLayout header) {
        LinearLayout strip=new LinearLayout(ctx);strip.setGravity(Gravity.CENTER_VERTICAL);strip.setBackground(Ui.box(ctx,Ui.overlayFill(ctx),Ui.LINE2,10));strip.setPadding(dp(3),0,dp(3),0);headerMetrics=strip;headerCells.clear();latestHeaderMetrics.clear();
        for(String key:HeaderSlots.KEYS){
            if(key.equals("fps")&&!ScreenFrames.available(ctx))continue;
            if((key.equals("cpu")||key.equals("gpu"))&&!Telemetry.supported(ctx,key))continue;
            TextView cell=Ui.text(ctx,"—",11,Ui.TEXT,600);cell.setSingleLine(true);cell.setFontFeatureSettings("tnum");cell.setEllipsize(android.text.TextUtils.TruncateAt.END);
            cell.setCompoundDrawablePadding(dp(3));cell.setPadding(dp(4),0,dp(4),0);cell.setGravity(Gravity.CENTER_VERTICAL);
            setMetricIcon(cell,key);headerCells.put(key,cell);
            cell.setOnClickListener(v->showMetricTooltip(key,cell));
            strip.addView(cell,key.equals("network")?new LinearLayout.LayoutParams(dp(28),-1):new LinearLayout.LayoutParams(0,-1,HeaderSlots.weight(key)));
            if(key.equals("network")){
                TextView vpnCell=Ui.text(ctx,"",11,Ui.TEXT,600);vpnCell.setGravity(Gravity.CENTER);
                vpnCell.setContentDescription("VPN: ожидаем данные");headerCells.put("vpn_status",vpnCell);
                vpnCell.setOnClickListener(v->showMetricTooltip("vpn_status",vpnCell));
                strip.addView(vpnCell,new LinearLayout.LayoutParams(dp(28),-1));
            }
        }
        LinearLayout.LayoutParams slot=new LinearLayout.LayoutParams(0,dp(44),1);slot.leftMargin=dp(6);slot.rightMargin=dp(6);
        header.addView(strip,Math.max(0,header.getChildCount()-2),slot);
    }
    private void setMetricIcon(TextView cell,String key){
        android.graphics.drawable.Drawable icon=ctx.getDrawable(HudStrip.icon(key)).mutate();icon.setTint(Ui.SILVER);icon.setBounds(0,0,dp(13),dp(13));cell.setCompoundDrawables(icon,null,null,null);
    }
    private void startHeaderMetrics() {
        final LinearLayout strip=headerMetrics;if(strip==null)return;
        metricsSubscription=values->{
            if(headerMetrics!=strip)return;
            for(Telemetry.Metric metric:values){
                TextView cell=headerCells.get(HeaderSlots.canonical(metric.key));if(cell==null)continue;
                String canonical=HeaderSlots.canonical(metric.key);latestHeaderMetrics.put(canonical,metric);
                cell.setText(canonical.equals("network")?"":metric.value);cell.setContentDescription(MetricHints.title(canonical)+". "+metric.description);setMetricIcon(cell,metric.key);
                cell.setTextColor(metric.color==0?Ui.TEXT:metric.color);
                if(metric.color!=0){android.graphics.drawable.Drawable icon=cell.getCompoundDrawables()[0];if(icon!=null)icon.setTint(metric.color);}
                if(canonical.equals(metricTooltipKey)&&metricTooltipValue!=null){metricTooltipValue.setText(metric.description);metricTooltipValue.setTextColor(metric.color==0?Ui.TEXT:metric.color);}
                if(canonical.equals("network")){
                    boolean enabled=metric.value.equals("VPN");
                    Telemetry.Metric status=new Telemetry.Metric("vpn_status","",enabled?"VPN включён":"VPN выключен");
                    latestHeaderMetrics.put("vpn_status",status);
                    TextView vpnCell=headerCells.get("vpn_status");
                    if(vpnCell!=null){
                        android.graphics.drawable.Drawable vpn=ctx.getDrawable(enabled?R.drawable.ic_shield:R.drawable.ic_shield_x).mutate();
                        vpn.setTint(Ui.TEXT);vpn.setBounds(0,0,dp(14),dp(14));
                        vpnCell.setCompoundDrawables(vpn,null,null,null);vpnCell.setContentDescription(status.description);
                    }
                    if("vpn_status".equals(metricTooltipKey)&&metricTooltipValue!=null)metricTooltipValue.setText(status.description);
                }
            }
        };
        TelemetryFeed.listen(ctx,metricsSubscription);
    }

    private View buildMicOpacity(){
        LinearLayout body=new LinearLayout(ctx);body.setOrientation(LinearLayout.VERTICAL);
        TextView value=Ui.text(ctx,"Прозрачность: "+(100-Prefs.getInt(ctx,"mic_opacity",95))+"%",13,Ui.TEXT,600);body.addView(value);
        android.widget.SeekBar slider=new android.widget.SeekBar(ctx);slider.setMax(80);slider.setProgress(100-Prefs.getInt(ctx,"mic_opacity",95));
        slider.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener(){
            public void onProgressChanged(android.widget.SeekBar bar,int progress,boolean user){if(!user)return;
                int opacity=100-progress;Prefs.putInt(ctx,"mic_opacity",opacity);value.setText("Прозрачность: "+progress+"%");
                if(micBubble!=null&&micLp!=null){View face=micBubble.findViewWithTag("mic_face");if(face!=null)face.setAlpha(opacity/100f);}
            }
            public void onStartTrackingTouch(android.widget.SeekBar bar){}public void onStopTrackingTouch(android.widget.SeekBar bar){}
        });body.addView(slider);body.addView(Ui.hint(ctx,"Нажатие плитки показывает кнопку. Удержание плитки или самой кнопки открывает этот ползунок. Прозрачность сохраняется.",12,Ui.MUTED,500));return body;
    }

    private View buildPowerModes() {
        LinearLayout body=new LinearLayout(ctx);body.setOrientation(LinearLayout.VERTICAL);
        if(powerState==null||powerState.choices.isEmpty()){body.addView(Ui.text(ctx,"Нет доступных режимов для этой игры",13,Ui.MUTED,500));return body;}
        body.addView(Ui.hint(ctx,"Full Focus: строгие уведомления без обычных звонков и вибрации, доступная производительность и очистка обычного фона. Звук игры сохраняется. Интернет других приложений не блокируется, системные службы и защита нагрева работают.",12,Ui.MUTED,500));
        for(int mode:powerState.choices){TextView choice=Ui.button(ctx,GamePower.title(mode),mode==powerState.choice());
            choice.setOnClickListener(v->{closePopup();selectPowerMode(mode);});body.addView(choice,Ui.lpMargins(ctx,-1,-2,0,8,0,0));}
        return body;
    }
    private void selectPowerMode(int mode) {
        if(powerBusy)return;powerBusy=true;
        Tile tile=tiles.get(Booster.PERF);if(tile!=null)tile.paint();final String pkg=gamePkg;
        Shell.background(()->{String error=GamePower.select(ctx,pkg,mode);GamePower.State after=GamePower.read(ctx,pkg);
            Shell.onMain(()->{powerBusy=false;powerState=after;powerCursor=mode;if(error==null&&GameProfiles.custom(ctx,pkg))GameProfiles.setMode(ctx,pkg,mode);statesAt=android.os.SystemClock.elapsedRealtime();
                Tile current=tiles.get(Booster.PERF);if(current!=null)current.paint();toast(error==null?"Режим: "+GamePower.title(mode):error,error==null?R.drawable.ic_zap:R.drawable.ic_alert);
            });});
    }
    private View buildBrightness() {
        LinearLayout body=new LinearLayout(ctx);body.setOrientation(LinearLayout.VERTICAL);
        TextView value=Ui.text(ctx,"Текущая яркость",13,Ui.TEXT,600);body.addView(value);
        android.widget.SeekBar slider=new android.widget.SeekBar(ctx);slider.setMax(100);
        int current=Settings.System.getInt(ctx.getContentResolver(),Settings.System.SCREEN_BRIGHTNESS,128);
        slider.setProgress(Math.max(1,Math.min(100,Math.round(current*100f/255))));
        int[] target={slider.getProgress()};boolean[] busy={false};final String pkg=gamePkg;
        Runnable pump=new Runnable(){public void run(){
            if(busy[0]||!java.util.Objects.equals(pkg,gamePkg))return;busy[0]=true;int requested=target[0];
            Shell.background(()->{boolean ok=Booster.setBrightness(ctx,requested);if(ok)GameProfiles.recordBrightness(ctx,pkg,requested);Shell.onMain(()->{
                busy[0]=false;if(target[0]!=requested)run();else refreshTiles();
                if(!ok)value.setText("Телефон не подтвердил яркость");
            });});
        }};
        slider.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener(){
            public void onProgressChanged(android.widget.SeekBar bar,int progress,boolean user){value.setText("Яркость: "+progress+"%");if(user){target[0]=progress;pump.run();}}
            public void onStartTrackingTouch(android.widget.SeekBar bar){}
            public void onStopTrackingTouch(android.widget.SeekBar bar){target[0]=bar.getProgress();pump.run();}
        });body.addView(slider);body.addView(Ui.hint(ctx,"Яркость меняется при движении ползунка. Короткое нажатие плитки включает или выключает фиксацию.",12,Ui.MUTED,500));return body;
    }
    static View recordingControls(Context c){
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        int selected = Math.max(0, Math.min(2, Prefs.getInt(c, "record_quality", 1)));
        TextView pixels = Ui.text(c, recordingPixels(c, selected), 13, Ui.TEXT, 600);
        box.addView(pixels);
        box.addView(Ui.segmented(c, new String[]{"720p", "1080p", "1440p"}, selected, i -> {
            Prefs.putInt(c, "record_quality", i);
            pixels.setText(recordingPixels(c, i));
        }), Ui.lpMargins(c, -1, -2, 0, 6, 0, 4));
        recordOption(c, box, "Микрофон", "record_mic");
        recordOption(c, box, "Звук игры и приложений", "record_playback");
        if (TouchTrace.available(c)) recordOption(c, box, "Светящиеся касания", "record_touches");
        if (Build.VERSION.SDK_INT >= 34) {
            android.widget.CheckBox chooseApp = new android.widget.CheckBox(c);
            chooseApp.setText("Выбирать приложение для записи");
            chooseApp.setTextColor(Ui.TEXT);
            chooseApp.setButtonTintList(android.content.res.ColorStateList.valueOf(Ui.ACCENT));
            chooseApp.setChecked(Prefs.get(c, "record_choose_app", true));
            chooseApp.setOnCheckedChangeListener((button, checked) -> Prefs.put(c, "record_choose_app", checked));
            box.addView(chooseApp);
        }
        box.addView(OverlayRecording.opacityControls(c));
        String hint = Build.VERSION.SDK_INT >= 34
                ? "Без выбора приложения записывается весь экран. Подтверждение записи запрашивает Android. При захвате всего экрана кнопки могут закрываться чёрной областью."
                : "При захвате всего экрана Android может закрывать кнопки записи чёрной областью.";
        box.addView(Ui.hint(c, hint + " Звук игры записывается, если игра разрешает захват.", 11, Ui.MUTED, 500),
                Ui.lpMargins(c, -1, -2, 0, 6, 0, 0));
        return box;
    }

    private static String recordingPixels(Context c, int quality) {
        Point size = OverlayRecording.outputSize(c, quality);
        return String.format(java.util.Locale.forLanguageTag("ru"), "%d × %d · %.1f Мп", size.x, size.y,
                size.x * (double) size.y / 1000000);
    }

    private static void recordOption(Context c, LinearLayout box, String title, String key) {
        android.widget.CheckBox option = new android.widget.CheckBox(c);
        option.setText(title);
        option.setTextColor(Ui.TEXT);
        option.setButtonTintList(android.content.res.ColorStateList.valueOf(Ui.ACCENT));
        option.setChecked(Prefs.get(c, key, false));
        option.setOnCheckedChangeListener((button, checked) -> Prefs.put(c, key, checked));
        box.addView(option);
    }

    private void showRecording(View anchor) {
        LinearLayout body = (LinearLayout) recordingControls(ctx);
        TextView start = Ui.button(ctx, "Начать запись", true);
        start.setOnClickListener(v -> {
            closePanel();
            if (listener != null) listener.openedExternal();
            try { OverlayRecording.request(ctx); }
            catch (RuntimeException error) {
                Diagnostics.failure(ctx, "record.launch.failed", error);
                toast("Не удалось открыть запись экрана", R.drawable.ic_alert);
            }
        });
        body.addView(start, Ui.lpMargins(ctx, -1, -2, 0, 8, 0, 0));
        showPopup("Запись экрана", body, anchor);
    }

    private View buildUsbRule(){
        LinearLayout body=new LinearLayout(ctx);body.setOrientation(LinearLayout.VERTICAL);
        int level=GameProfiles.custom(ctx,gamePkg)?GameProfiles.usbLevel(ctx,gamePkg):Prefs.getInt(ctx,Prefs.PASS_LEVEL,0);boolean[] enabled={level>0};
        Ui.Toggle toggle=new Ui.Toggle(ctx);toggle.setOn(enabled[0]);
        LinearLayout line=new LinearLayout(ctx);line.setGravity(Gravity.CENTER_VERTICAL);
        line.addView(Ui.text(ctx,"Включать автоматически",13,Ui.TEXT,600),new LinearLayout.LayoutParams(0,-2,1));line.addView(toggle,new LinearLayout.LayoutParams(dp(44),dp(26)));body.addView(line);
        TextView label=Ui.text(ctx,"Заряд не ниже "+(level>0?level:80)+"%",13,Ui.TEXT,500);body.addView(label,Ui.lpMargins(ctx,-1,-2,0,14,0,0));
        android.widget.SeekBar slider=new android.widget.SeekBar(ctx);slider.setMax(99);slider.setProgress((level>0?level:80)-1);body.addView(slider);
        Runnable save=()->{int threshold=enabled[0]?slider.getProgress()+1:0;Prefs.putInt(ctx,Prefs.PASS_LEVEL,threshold);GameProfiles.setUsbLevel(ctx,gamePkg,threshold);BoostService.refreshUsbRule();};
        toggle.setListener(on->{enabled[0]=on;save.run();});
        slider.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener(){
            public void onProgressChanged(android.widget.SeekBar b,int value,boolean user){label.setText("Заряд не ниже "+(value+1)+"%");}
            public void onStartTrackingTouch(android.widget.SeekBar b){}public void onStopTrackingTouch(android.widget.SeekBar b){save.run();}
        });body.addView(Ui.hint(ctx,"Работает во время игры, если функция поддерживается зарядкой и телефоном. Повторное включение зарядки — на 5% ниже порога.",12,Ui.MUTED,500));return body;
    }
    private View buildResolutions() {
        LinearLayout body=new LinearLayout(ctx);body.setOrientation(LinearLayout.VERTICAL);
        body.addView(Ui.hint(ctx,"Размеры, которые сообщает дисплей телефона. Через Shizuku меняется разрешение отрисовки и масштаб интерфейса; физический режим матрицы не переключается.",12,Ui.MUTED,500));
        if(!Shell.ready(ctx)){body.addView(Ui.text(ctx,"Для выбора нужен Shizuku",13,Ui.TEXT,600));return body;}
        List<int[]> modes=DisplayResolution.modes(ctx);
        if(modes.isEmpty())body.addView(Ui.text(ctx,"Телефон не сообщил доступные размеры",13,Ui.MUTED,500));
        for(int[] mode:modes){TextView button=Ui.button(ctx,mode[0]+" × "+mode[1],false);
            button.setOnClickListener(v->{closePopup();Shell.background(()->{String message=DisplayResolution.select(ctx,mode[0],mode[1]);Shell.onMain(()->{requestDisplayReflow();toast(message,R.drawable.ic_monitor);});});});
            body.addView(button,Ui.lpMargins(ctx,-1,-2,0,8,0,0));}
        TextView reset=Ui.button(ctx,"Вернуть исходное",false);reset.setOnClickListener(v->{closePopup();Shell.background(()->{boolean ok=DisplayResolution.restore(ctx);Shell.onMain(()->{requestDisplayReflow();toast(ok?"Исходное разрешение возвращено":"Не удалось вернуть исходное разрешение",R.drawable.ic_monitor);});});});body.addView(reset,Ui.lpMargins(ctx,-1,-2,0,8,0,0));return body;
    }

    private static String plural(int n) {
        int m10 = n % 10, m100 = n % 100;
        if (m10 == 1 && m100 != 11) return "функция";
        if (m10 >= 2 && m10 <= 4 && (m100 < 12 || m100 > 14)) return "функции";
        return "функций";
    }

    private TextView footButton(String label, int icon) {
        TextView b = Ui.text(ctx, label, 12, Ui.MUTED, 600);
        b.setGravity(Gravity.CENTER);
        b.setSingleLine(true);
        b.setBackground(Ui.box(ctx, 0, Ui.LINE3, Ui.GLASS ? 999 : 10));
        android.graphics.drawable.Drawable d = ctx.getDrawable(icon).mutate();
        d.setTint(Ui.MUTED);
        d.setBounds(0, 0, dp(14), dp(14));
        b.setCompoundDrawables(d, null, null, null);
        b.setCompoundDrawablePadding(dp(6));
        b.setPadding(dp(10), 0, dp(10), 0);
        return b;
    }

    /** Ряд выбора времени для «Не беспокоить». */
    private LinearLayout buildDndRow() {
        LinearLayout box=new LinearLayout(ctx);box.setOrientation(LinearLayout.VERTICAL);
        box.addView(Ui.text(ctx,"Включить на…",12,Ui.MUTED,600));
        String[] labels={"15 мин","30 мин","1 час","2 часа","До конца игры","Выключить"};
        int[] minutes={15,30,60,120,Dnd.UNTIL_GAME_END,0};
        for(int row=0;row<2;row++){
            LinearLayout line=new LinearLayout(ctx);
            for(int col=0;col<2;col++){
                int i=row*2+col;TextView chip=dndChoice(labels[i],minutes[i]);
                LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,-2,1);if(col>0)lp.leftMargin=dp(8);
                line.addView(chip,lp);
            }
            box.addView(line,Ui.lpMargins(ctx,-1,-2,0,8,0,0));
        }
        android.widget.NumberPicker custom=new android.widget.NumberPicker(ctx);custom.setMinValue(1);custom.setMaxValue(1440);custom.setValue(12);custom.setWrapSelectorWheel(false);
        custom.setDescendantFocusability(android.view.ViewGroup.FOCUS_BLOCK_DESCENDANTS);custom.setContentDescription("Свой таймер в минутах");
        box.addView(Ui.text(ctx,"Свой таймер · минуты",12,Ui.TEXT,600),Ui.lpMargins(ctx,-1,-2,0,12,0,0));
        box.addView(custom,new LinearLayout.LayoutParams(-1,dp(100)));
        TextView apply=Ui.button(ctx,"Включить свой таймер",false);apply.setOnClickListener(v->{closePopup();Dnd.start(ctx,custom.getValue(),this::refreshTiles);});box.addView(apply,Ui.lpMargins(ctx,-1,-2,0,8,0,0));
        // Long labels get a full-width button, not one third of the popup.
        box.addView(dndChoice(labels[4],minutes[4]),Ui.lpMargins(ctx,-1,-2,0,8,0,0));
        box.addView(dndChoice(labels[5],minutes[5]),Ui.lpMargins(ctx,-1,-2,0,8,0,0));
        return box;
    }

    private TextView dndChoice(String label,int minutes){
        TextView button=Ui.button(ctx,label,false);button.setMinHeight(dp(40));
        button.setMaxLines(2);button.setPadding(dp(8),dp(6),dp(8),dp(6));
        button.setOnClickListener(v -> {
            closePopup();Runnable done=this::refreshTiles;
            if(minutes==0)Dnd.stop(ctx,done);else Dnd.start(ctx,minutes,done);
        });return button;
    }

    private void setPanelSlide(float visible){
        slideVisible=visible;
        if(slideHeader!=null)slideHeader.setTranslationY(-(1f-visible)*(slideHeader.getHeight()+safeInsets().top+dp(24)));
        if(slideLeft!=null)slideLeft.setTranslationX(-(1f-visible)*(slideLeft.getWidth()+safeInsets().left+dp(24)));
        if(slideRight!=null)slideRight.setTranslationX((1f-visible)*(slideRight.getWidth()+safeInsets().right+dp(24)));
    }
    private void closePanelAnimated(){
        if(panelRoot==null||slidingClosed)return;
        if(slideHeader==null){closePanel();return;}
        closeMetricTooltip();closePopup();slidingClosed=true;animatePanelSlide(true);
    }
    /** Handler frames keep these short transitions working with system animations disabled. */
    private void animatePanelSlide(boolean closing){
        final FrameLayout root=panelRoot;final int token=++slideToken;
        final float from=closing?slideVisible:0f;
        final long start=android.os.SystemClock.uptimeMillis();
        Runnable frames=new Runnable(){public void run(){
            if(token!=slideToken||panelRoot!=root)return;
            float t=Math.min(1f,(android.os.SystemClock.uptimeMillis()-start)/170f);
            float eased=1f-(1f-t)*(1f-t)*(1f-t);
            setPanelSlide(closing?from*(1f-eased):eased);
            if(t<1f)MAIN.postDelayed(this,16);else if(closing)closePanel();
        }};frames.run();
    }

    public void closePanel() {
        slideToken++;slidingClosed=false;slideHeader=null;slideLeft=null;slideRight=null;
        opening=false;panelRequest++;statesAt=0;
        closeMetricTooltip();closePopup();if(retiredPanel!=null){remove(retiredPanel);retiredPanel=null;}
        if(metricsSubscription!=null){TelemetryFeed.stop(metricsSubscription);metricsSubscription=null;}
        headerMetrics=null;headerCells.clear();
        if (panelRoot == null) return;
        Diagnostics.event(ctx, "overlay.panel.close");
        PcLink.removeListener(pcListener);
        remove(panelRoot);
        panelRoot = null;
        syncMicPanelVisibility();
        if (gamePkg == null) hud.hide();
        panelView=null;
        powerCursor=null;
        tiles.clear();
        MAIN.post(this::syncHud);
    }

    /** Обновить плитки, если панель открыта. */
    public void refreshIfOpen() {
        if (panelRoot != null) refreshTiles();
    }

    private void refreshTiles() {
        if(panelRoot==null){statesAt=0;return;}
        final String pkg = gamePkg;
        final FrameLayout activePanel=panelRoot;
        Shell.background(() -> {
            Map<String, Boolean> states = new HashMap<>();
            for (String id : Booster.TOGGLES)
                if (Booster.available(ctx, id)) states.put(id, Booster.read(ctx, id, pkg));
            if(TouchSensitivity.available(ctx))states.put("sensitivity",TouchSensitivity.enabled(ctx));
            GamePower.State modes=GamePower.available(ctx)?GamePower.read(ctx,pkg):null;
            Shell.onMain(() -> {
                if(panelRoot!=activePanel)return;
                if(!powerBusy)powerState=modes;
                for(Map.Entry<String,Boolean> entry:states.entrySet())if(!pendingToggles.contains(entry.getKey()))cachedStates.put(entry.getKey(),entry.getValue());statesAt=android.os.SystemClock.elapsedRealtime();
                for (Map.Entry<String, Boolean> e : states.entrySet()) {
                    Tile t = tiles.get(e.getKey());
                    if (t != null && !pendingToggles.contains(e.getKey())) t.setOn(e.getValue());
                }
            });
        });
    }

    private void cyclePowerMode(Tile tile) {
        if(powerBusy)return;
        powerBusy=true;tile.paint();
        final String pkg=gamePkg;
        final Integer cursor=powerCursor;
        Shell.background(() -> {
            GamePower.State before=GamePower.read(ctx,pkg);
            int next=GameModeInfo.nextChoice(before.choices,cursor==null?before.choice():cursor);
            String error=next<0?"Для этой игры нет доступных режимов":GamePower.select(ctx,pkg,next);
            GamePower.State after=GamePower.read(ctx,pkg);
            Shell.onMain(() -> {
                powerBusy=false;
                // Advance past a rejected mode, so a charging restriction cannot trap the cycle.
                if(next>=0)powerCursor=next;
                powerState=after;if(error==null&&GameProfiles.custom(ctx,pkg))GameProfiles.setMode(ctx,pkg,next);
                Tile current=tiles.get(Booster.PERF);if(current!=null)current.paint();
                toast(error==null?"Режим: "+GamePower.title(next):error,error==null?R.drawable.ic_zap:R.drawable.ic_alert);
            });
        });
    }

    private void closeMetricTooltip(){remove(metricTooltip);metricTooltip=null;metricTooltipValue=null;metricTooltipKey=null;}
    private void showMetricTooltip(String key,View anchor){
        if(panelRoot==null)return;
        key=HeaderSlots.canonical(key);
        if(key.equals(metricTooltipKey)){closeMetricTooltip();return;}
        closePopup();closeMetricTooltip();
        final String selected=key;Point screen=screen();android.graphics.Rect safe=safeInsets();
        int width=Math.min(dp(260),screen.x-safe.left-safe.right-dp(16));
        LinearLayout box=new LinearLayout(ctx);box.setOrientation(LinearLayout.VERTICAL);box.setPadding(dp(10),dp(8),dp(10),dp(10));
        box.setBackground(Ui.box(ctx,0xFF17191F,Ui.LINE2,10));box.setElevation(dp(8));
        LinearLayout head=new LinearLayout(ctx);head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(Ui.text(ctx,MetricHints.title(key),12,Ui.TEXT,700),new LinearLayout.LayoutParams(0,-2,1));
        FrameLayout close=new FrameLayout(ctx);close.addView(Ui.icon(ctx,R.drawable.ic_x,13,Ui.SILVER),new FrameLayout.LayoutParams(dp(13),dp(13),Gravity.CENTER));close.setContentDescription("Закрыть подсказку");close.setOnClickListener(v->closeMetricTooltip());head.addView(close,new LinearLayout.LayoutParams(dp(28),dp(28)));box.addView(head);
        Telemetry.Metric metric=latestHeaderMetrics.get(key);
        TextView value=Ui.text(ctx,metric==null?"Ожидаем первое измерение…":metric.description,12,Ui.TEXT,600);
        if(metric!=null&&metric.color!=0)value.setTextColor(metric.color);
        value.setMinLines(2);box.addView(value,Ui.lpMargins(ctx,-1,-2,0,5,0,5));
        box.addView(Ui.hint(ctx,MetricHints.explanation(key),11,Ui.MUTED,500));
        box.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED));
        int[] at=new int[2];anchor.getLocationOnScreen(at);
        int[] placed=TooltipPlacement.place(safe.left+dp(8),safe.top+dp(8),screen.x-safe.right-dp(8),screen.y-safe.bottom-dp(8),at[0],at[1],at[0]+anchor.getWidth(),at[1]+anchor.getHeight(),width,box.getMeasuredHeight(),dp(6));
        WindowManager.LayoutParams lp=baseLp(placed[2],-2,WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL|WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH|WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN);
        lp.gravity=Gravity.TOP|Gravity.START;lp.x=placed[0];lp.y=placed[1];if(Build.VERSION.SDK_INT>=30)lp.setFitInsetsTypes(0);
        box.setOnTouchListener((v,event)->{if(event.getActionMasked()==MotionEvent.ACTION_OUTSIDE){if(metricTooltip==box)closeMetricTooltip();return false;}return false;});
        try{wm.addView(box,lp);metricTooltip=box;metricTooltipValue=value;metricTooltipKey=selected;}catch(Exception e){android.util.Log.w("XPhone","Metric hint unavailable",e);}
    }

    private void closePopup(){
        remove(popupView);popupView=null;popupTitle=null;
    }

    /** Independent, bounded sidecar window; never expands the large game panel. */
    @SuppressLint("ClickableViewAccessibility")
    private void showPopup(String title,View body,View anchor){
        if(!canShow())return;
        closeMetricTooltip();
        if(title.equals(popupTitle)){closePopup();return;}
        closePopup();
        Point screen=screen();
        android.graphics.Rect popupSafe=safeInsets();
        int width=Math.min(dp(320),screen.x-popupSafe.left-popupSafe.right-dp(24));
        body.measure(View.MeasureSpec.makeMeasureSpec(Math.max(1,width-dp(28)),View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED));
        int height=Math.min(screen.y-popupSafe.top-popupSafe.bottom-dp(24),body.getMeasuredHeight()+dp(72));
        int[] panelPosition=new int[2],anchorPosition=new int[2];
        if(panelView!=null)panelView.getLocationOnScreen(panelPosition);anchor.getLocationOnScreen(anchorPosition);
        int[] placement=PopupPlacement.place(screen.x,screen.y,panelPosition[0],panelPosition[0]+(panelView==null?screen.x:panelView.getWidth()),anchorPosition[1],width,height,dp(12));
        android.graphics.Rect safe=safeInsets();
        placement[0]=safe.left+Math.max(0,(screen.x-safe.left-safe.right-placement[2])/2);
        placement[1]=safe.top+Math.max(0,(screen.y-safe.top-safe.bottom-placement[3])/2);
        LinearLayout window=new LinearLayout(ctx);window.setOrientation(LinearLayout.VERTICAL);
        window.setPadding(dp(14),dp(10),dp(14),dp(12));
        window.setBackground(Ui.box(ctx,0xFF17191F,Ui.LINE2,Ui.R_PANEL));
        window.setElevation(dp(16));
        LinearLayout header=new LinearLayout(ctx);header.setGravity(Gravity.CENTER_VERTICAL);
        header.addView(Ui.text(ctx,title,14,Ui.TEXT,700),new LinearLayout.LayoutParams(0,-2,1));
        FrameLayout close=new FrameLayout(ctx);close.setContentDescription("Закрыть окно «"+title+"»");
        close.addView(Ui.icon(ctx,R.drawable.ic_x,16,Ui.SILVER),new FrameLayout.LayoutParams(dp(16),dp(16),Gravity.CENTER));
        close.setOnClickListener(v -> closePopup());header.addView(close,new LinearLayout.LayoutParams(dp(36),dp(36)));
        window.addView(header);
        ScrollView scroll=new ScrollView(ctx);scroll.addView(body);window.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        WindowManager.LayoutParams lp=baseLp(placement[2],placement[3],WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL|WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH|WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN);
        lp.gravity=Gravity.TOP|Gravity.START;lp.x=placement[0];lp.y=placement[1];
        if(Build.VERSION.SDK_INT>=30)lp.setFitInsetsTypes(0);
        window.setOnTouchListener((v,event) -> {if(event.getActionMasked()==MotionEvent.ACTION_OUTSIDE){closePopup();return false;}return false;});
        try{wm.addView(window,lp);popupView=window;popupTitle=title;}
        catch(Exception e){android.util.Log.w("XPhone","Cannot open settings window",e);}
    }

    private String appName(String pkg) {
        if (pkg == null) return "Игровая панель";
        try {
            PackageManager pm = ctx.getPackageManager();
            ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
            return pm.getApplicationLabel(ai).toString();
        } catch (Exception e) {
            return pkg;
        }
    }

    private void openApp() {
        closePanel();
        if (listener != null) listener.openedExternal();
        ctx.startActivity(new Intent(ctx, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    private final PcLink.Listener pcListener = () -> {
        Tile t = tiles.get(STREAM);
        if (t != null) t.paint();
    };

    // ── Плитка ────────────────────────────────────────────────────────

    private final class Tile {
        final String id;
        final LinearLayout view;
        final boolean tacticalTile;
        final FrameLayout iconBox;   // «Стекло»: круг с иконкой
        final ImageView icon;
        final TextView label, state;
        boolean on;
        final boolean action;

        Tile(String id, boolean compact) {
            this.id = id;
            this.tacticalTile=tacticalBuilding;
            on=Boolean.TRUE.equals(cachedStates.get(id));
            this.action = "apps".equals(id) || RECORD.equals(id) || Booster.CLEAN.equals(id) || Booster.RES.equals(id);
            view = new LinearLayout(ctx);
            view.setOrientation(LinearLayout.VERTICAL);
            icon = Ui.icon(ctx, iconRes(), Ui.GLASS ? 21 : 20, Ui.SILVER);
            label = Ui.text(ctx, title(), Ui.GLASS ? 11 : 12, Ui.TEXT, 700);
            state = Ui.text(ctx, action ? "Нажмите" : "Выкл", 10, Ui.MUTED, 500);
            state.setSingleLine(true);
            if(tacticalTile) {
                iconBox=null;view.setOrientation(LinearLayout.HORIZONTAL);view.setGravity(Gravity.CENTER_VERTICAL);
                view.setPadding(dp(7),dp(5),dp(7),dp(5));view.addView(icon,new LinearLayout.LayoutParams(dp(18),dp(18)));
                LinearLayout text=new LinearLayout(ctx);text.setOrientation(LinearLayout.VERTICAL);
                label.setTextSize(11);label.setMaxLines(2);label.setEllipsize(android.text.TextUtils.TruncateAt.END);
                state.setTextSize(10);text.addView(label);text.addView(state,Ui.lpMargins(ctx,-1,-2,0,3,0,0));
                LinearLayout.LayoutParams textLp=new LinearLayout.LayoutParams(0,-2,1);textLp.leftMargin=dp(6);view.addView(text,textLp);
            } else if (Ui.GLASS) {
                view.setGravity(Gravity.CENTER_HORIZONTAL);
                iconBox = new FrameLayout(ctx);
                iconBox.addView(icon, new FrameLayout.LayoutParams(dp(21), dp(21), Gravity.CENTER));
                view.addView(iconBox, new LinearLayout.LayoutParams(dp(compact ? 36 : 52), dp(compact ? 36 : 52)));
                label.setGravity(Gravity.CENTER);
                label.setSingleLine(true);
                view.addView(label, Ui.lpMargins(ctx, -2, -2, 0, compact ? 3 : 7, 0, 0));
                state.setGravity(Gravity.CENTER);
                view.addView(state, Ui.lpMargins(ctx, -2, -2, 0, 2, 0, 0));
            } else {
                iconBox = null;
                view.setPadding(dp(8), dp(compact ? 5 : 10), dp(8), dp(compact ? 5 : 9));
                view.addView(icon);
                view.addView(new View(ctx), new LinearLayout.LayoutParams(1, 0, 1));
                label.setMaxLines(compact ? 1 : 2);
                label.setEllipsize(android.text.TextUtils.TruncateAt.END);
                view.addView(label);
                view.addView(state, Ui.lpMargins(ctx, -2, -2, 0, 3, 0, 0));
            }
            view.setContentDescription(title());
            view.setOnClickListener(v -> {Diagnostics.event(ctx, "overlay.action " + id);pulse(view);click();});
            view.setOnLongClickListener(v -> {
                if("fps".equals(id))showPopup("FPS / Гц",FpsHud.controls(ctx,()->{syncHud();paint();}),view);
                else if(RECORD.equals(id) && !OverlayRecording.active())showRecording(view);
                else if(MIC.equals(id))showPopup("Прозрачность кнопки",buildMicOpacity(),view);
                else if(HUD.equals(id))showPopup("Мини-окно",MiniHud.controls(ctx,Overlay.this::applyHudPreference),view);
                else if("apps".equals(id))showPopup("Приложения",companions.controls(),view);
                else if(Booster.DND.equals(id))showPopup("Не беспокоить",buildDndRow(),view);
                else if(Booster.PERF.equals(id))showPopup("Режим питания",buildPowerModes(),view);
                else if(Booster.PASS.equals(id))showPopup("Питание USB",buildUsbRule(),view);
                else if(Booster.BRIGHT.equals(id))showPopup("Яркость",buildBrightness(),view);
                else if(Booster.RES.equals(id))showPopup("Разрешение",buildResolutions(),view);
                else Toast.makeText(ctx,hint(),Toast.LENGTH_SHORT).show();
                return true;
            });
            paint();
        }

        int iconRes() { if("fps".equals(id))return R.drawable.ic_monitor;if("sensitivity".equals(id))return R.drawable.ic_frame_trace;if("apps".equals(id))return R.drawable.ic_monitor; if(RECORD.equals(id)) return R.drawable.ic_video; if(HUD.equals(id)) return R.drawable.ic_info; if(TOUCH.equals(id)) return R.drawable.ic_frame_trace; return MIC.equals(id) ? (Mic.muted()?R.drawable.ic_mic_off:R.drawable.ic_mic) : Booster.icon(id); }
        String title() { if("fps".equals(id))return "FPS / Гц";if("sensitivity".equals(id))return "Чувствительность";if("apps".equals(id))return "Окна"; if(RECORD.equals(id)) return "Запись"; if(HUD.equals(id)) return "Мини-окно"; if(TOUCH.equals(id)) return "Касания"; return MIC.equals(id) ? "Микрофон" : Ui.GLASS ? Booster.tinyTitle(id) : Booster.shortTitle(id); }
        String hint() { if("fps".equals(id))return "Отдельное окно фактического FPS экрана и частоты дисплея";if("sensitivity".equals(id))return "Системная чувствительность Samsung для защитного стекла. Не разгон частоты опроса сенсора.";if("apps".equals(id))return "Выбранные приложения в системных окнах поверх игры; значок сворачивает и возвращает окно"; if(RECORD.equals(id)) return "Запись XPhone: качество, звук и касания"; if(HUD.equals(id)) return "Показатели поверх игры; состав и прозрачность — в отдельном окне настроек"; if(TOUCH.equals(id)) return "Мягкие светящиеся точки под пальцами: без линий и координат. Исчезают сразу после отпускания."; return MIC.equals(id) ? "Плавающая кнопка, которая мгновенно выключает микрофон телефона" : Booster.hint(id); }

        void setOn(boolean v) { on = v; paint(); }

        void paint() {
            String st;
            if("fps".equals(id)){on=Prefs.get(ctx,"fps_hud_enabled",false);st=on?"На экране":"Скрыто";}
            else if("sensitivity".equals(id)){st=on?"Повышена":"Обычная";}
            else if (Booster.PERF.equals(id)) {
                st=powerBusy?"Меняем…":powerState==null?"По кругу":powerState.label();
                on=powerState!=null&&(powerState.focus||powerState.economy||"performance".equals(powerState.game)||"battery".equals(powerState.game));
            } else if (RECORD.equals(id)) {
                on=OverlayRecording.active();st=on?"Идёт":"Настроить";
            } else if (HUD.equals(id)) {
                on = extraEnabled("hud"); st = on ? "На экране" : "Скрыто";
            } else if (TOUCH.equals(id)) {
                on = TouchTrace.enabled(ctx); st = on ? "Вкл" : "Выкл";
            } else if (STREAM.equals(id)) {
                PcLink.State s = PcLink.state();
                on = s == PcLink.State.STREAMING;
                st = s == PcLink.State.STREAMING ? "Идёт" : s == PcLink.State.CONNECTING ? "Запуск…"
                        : s == PcLink.State.IDLE ? "Выкл" : PcLink.waiting() ? "Ищем ПК…" : "Нет ПК";
            } else if (MIC.equals(id)) {
                on = micBubble != null;
                st = !on ? "Кнопка скрыта" : Mic.muted() ? "Звук выключен" : "Микрофон вкл";
                icon.setImageDrawable(ctx.getDrawable(Mic.muted()?R.drawable.ic_mic_off:R.drawable.ic_mic).mutate());
            } else if (Booster.DND.equals(id)) {
                st = on ? (Dnd.timed() ? Dnd.untilText() : "Вкл") : "Выкл";
            } else if (action) {
                st = state.getText().toString().isEmpty() ? "Нажмите" : state.getText().toString();
            } else {
                st = Booster.stateText(id, on);
            }
            state.setText(st);
            boolean quiet=Booster.DND.equals(id);
            boolean lit = ((on && !action && !quiet) || (RECORD.equals(id)&&OverlayRecording.active())) && !(MIC.equals(id)&&Mic.muted());
            if (Ui.GLASS && !tacticalTile) {
                iconBox.setBackground(Ui.circle(lit ? Ui.ACCENT : Ui.TILE_OFF));
                Ui.tint(icon, lit ? Ui.ON_ACCENT : 0xFFFFFFFF);
                label.setTextColor(Ui.TEXT);
                state.setTextColor(lit || (quiet && on) ? (AccentColor.foreground(Ui.ACCENT)==0xFFFFFFFF?Ui.TEXT:Ui.ACCENT) : Ui.MUTED);
            } else {
                view.setBackground(Ui.box(ctx, lit ? Ui.ACCENT : tacticalTile?0x6617191E:Ui.TILE_OFF, lit ? Ui.ACCENT : Ui.LINE2, tacticalTile?4:Ui.R_TILE));
                Ui.tint(icon, lit ? Ui.ON_ACCENT : Ui.SILVER);
                label.setTextColor(lit ? Ui.ON_ACCENT : Ui.TEXT);
                state.setTextColor(lit ? Ui.ON_ACCENT : quiet && on ? Ui.TEXT : tacticalTile?Ui.SILVER:Ui.MUTED);
            }
        }

        void click() {
            if(Booster.DND.equals(id)&&FullFocus.active()){toast("Сначала выйдите из Full Focus через выбор режима",R.drawable.ic_info);return;}
            if("fps".equals(id)){Prefs.put(ctx,"fps_hud_enabled",!Prefs.get(ctx,"fps_hud_enabled",false));syncHud();paint();return;}
            if("sensitivity".equals(id)){view.setEnabled(false);boolean target=!on;Shell.background(()->{String error=TouchSensitivity.set(ctx,target);boolean actual=TouchSensitivity.enabled(ctx);Shell.onMain(()->{on=actual;cachedStates.put(id,actual);view.setEnabled(true);paint();if(error!=null)toast(error,R.drawable.ic_alert);});});return;}

            if("apps".equals(id)){if(!companions.openLast())showPopup("Приложения",companions.controls(),view);return;}
            if(Booster.PERF.equals(id)){cyclePowerMode(this);return;}
            if(RECORD.equals(id)){
                if (OverlayRecording.active()) {
                    closePanel();
                    OverlayRecording.request(ctx);
                } else showRecording(view);
                return;
            }
            if (HUD.equals(id)) {
                boolean enabled=!extraEnabled("hud");sessionExtras.put("hud",enabled);GameProfiles.setExtra(ctx,gamePkg,"hud",enabled);Prefs.put(ctx,MiniHud.ENABLED,enabled);syncHud();paint();return;
            }
            if (TOUCH.equals(id)) {
                boolean target = !TouchTrace.enabled(ctx);
                Shell.background(() -> { boolean ok = TouchTrace.set(ctx,target);if(ok)GameProfiles.setExtra(ctx,gamePkg,"touch",target); Shell.onMain(() -> { paint(); if(!ok) toast("Не удалось включить светящиеся касания — проверьте Shizuku",R.drawable.ic_alert); }); }); return;
            }
            if (STREAM.equals(id)) {
                if (PcLink.state() == PcLink.State.STREAMING) PcLink.requestStop();
                else {
                    PcLink.requestStart(ctx);
                    if (PcLink.state() == PcLink.State.NO_PC)
                        toast(PcLink.canEnableWifiDebugging(ctx) ? "Ищем компьютер…" : "Включите «Беспроводную отладку» в шторке", R.drawable.ic_cast);
                }
                paint();
                return;
            }
            if (MIC.equals(id)) {
                boolean show = micBubble == null;
                sessionExtras.put("mic",show);GameProfiles.setExtra(ctx,gamePkg,"mic",show);Prefs.put(ctx, Prefs.MIC_BUBBLE, show);
                if (show) showMicBubble(); else hideMicBubble();
                paint();
                return;
            }
            if (Booster.DND.equals(id)) {
                view.setEnabled(false);Runnable done=()->{view.setEnabled(true);Shell.background(()->{boolean actual=Booster.read(ctx,Booster.DND,gamePkg);GameProfiles.record(ctx,gamePkg,Booster.DND,actual);Shell.onMain(Overlay.this::refreshTiles);});};
                if(on)Dnd.stop(ctx,done);else Dnd.start(ctx,Dnd.INDEFINITE,done);
                return;
            }
            if (Booster.RES.equals(id)) {
                showPopup("Разрешение",buildResolutions(),view);
                return;
            }

            if (Booster.CLEAN.equals(id)) {
                state.setText("Закрываем…");
                final String cleaningGame=gamePkg;
                Shell.background(() -> {
                    String result=Booster.clean(ctx,cleaningGame);
                    Shell.onMain(() -> { state.setText("Нажмите"); if(panelRoot!=null)showPopup("Очистка памяти",Ui.text(ctx,result,13,Ui.TEXT,500),view);else toast(result,R.drawable.ic_trash); });
                });
                return;
            }
            final boolean target = !on;
            setOn(target);view.setEnabled(false);pendingToggles.add(id);
            final String pkg = gamePkg;
            Shell.background(() -> {
                Booster.set(ctx, id, target, pkg);
                boolean real = Booster.read(ctx, id, pkg);
                Shell.onMain(() -> {
                    view.setEnabled(true);pendingToggles.remove(id);if(!java.util.Objects.equals(pkg,gamePkg))return;GameProfiles.record(ctx,pkg,id,real);cachedStates.put(id,real);statesAt=android.os.SystemClock.elapsedRealtime();setOn(real);
                    if (real != target) toast("Телефон не дал переключить «" + Booster.title(id) + "»", R.drawable.ic_alert);
                });
            });
        }
    }
}
