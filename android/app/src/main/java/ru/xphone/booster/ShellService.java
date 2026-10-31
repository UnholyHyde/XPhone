package ru.xphone.booster;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.concurrent.TimeUnit;

/**
 * Работает в отдельном процессе, который запускает Shizuku с правами adb (shell).
 * Выполняет команды вроде «settings put …», «cmd game mode …».
 */
public class ShellService extends IShellService.Stub {

    public ShellService() {}

    @Override
    public void destroy() {
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
