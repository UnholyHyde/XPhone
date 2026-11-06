package ru.xphone.booster;

// Служба, которая работает через Shizuku с правами adb (shell) и выполняет команды.
import ru.xphone.booster.ITouchListener;

interface IShellService {
    // Код 16777114 требует Shizuku: так он закрывает службу.
    void destroy() = 16777114;

    String exec(String command) = 1;
    boolean touchAvailable() = 2;
    boolean startTouch(ITouchListener listener) = 3;
    void stopTouch() = 4;
    boolean windowAvailable() = 5;
    String openWindow(String component, in int[] bounds, int userId) = 6;
    String minimizeWindow(String packageName, int userId) = 7;
    String windowState(String packageName, int userId) = 8;
    void endWindows() = 9;
    String startRecord(String filename,int mbps) = 10;
    String stopRecord() = 11;
    String recordState() = 12;
    String restartApp(String component,int userId) = 13;
    boolean privacyAvailable() = 14;
    String streamPrivacy(boolean active,int userId) = 15;
    String resumeGame(String component,int userId) = 16;
}
