package ru.xphone.booster;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.concurrent.TimeUnit;

/**
 * Работает в отдельном процессе, который запускает Shizuku с правами adb (shell).
 * Выполняет команды вроде «settings put …», «cmd game mode …».
 */
public class ShellService extends IShellService.Stub {

    @Override public String restartApp(String component,int user){return GameRestarter.restart(component,user);}
    private final StreamPrivacyBridge privacy=new StreamPrivacyBridge();
    private int privacyUser;
    @Override public boolean privacyAvailable(){return privacy.available();}
    @Override public String streamPrivacy(boolean active,int user){privacyUser=user;return privacy.set(active,user);}
    private final ScreenRecording recording=new ScreenRecording();
    @Override public String startRecord(String name,int mbps){return recording.start(name,mbps);}
    @Override public String stopRecord(){return recording.stop();}
    @Override public String recordState(){return recording.state();}

    private final WindowTaskBridge windows = new WindowTaskBridge();
    @Override public String resumeGame(String component,int user){return windows.resumeGame(component,user);}
    @Override public boolean windowAvailable(){return windows.available();}
    @Override public String openWindow(String c,int[] b,int u){return windows.open(c,b,u);}
    @Override public String minimizeWindow(String p,int u){return windows.minimize(p,u);}
    @Override public String windowState(String p,int u){return windows.state(p,u);}
    @Override public void endWindows(){windows.end();}

    private final TouchInputReader touches = new TouchInputReader();
    public ShellService() {}
    @Override public boolean touchAvailable() { return TouchInputReader.findDevice() != null; }
    @Override public boolean startTouch(ITouchListener listener) { return touches.start(listener); }
    @Override public void stopTouch() { touches.stop(); }

    @Override
    public void destroy() {
        privacy.set(false,privacyUser);touches.stop();recording.stop();
        windows.end();
        System.exit(0);
    }

    @Override
    public String exec(String command) {
        try {
            Process p = new ProcessBuilder("sh", "-c", command).redirectErrorStream(true).start();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (InputStream in = p.getInputStream()) {
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            }
            p.waitFor(10, TimeUnit.SECONDS);
            return out.toString("UTF-8").trim();
        } catch (Exception e) {
            return "ERROR: " + e;
        }
    }
}
