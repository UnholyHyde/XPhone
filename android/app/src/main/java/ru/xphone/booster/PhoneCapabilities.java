package ru.xphone.booster;

import android.app.ActivityManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.hardware.display.DisplayManager;
import android.hardware.Sensor;
import android.hardware.SensorManager;
import android.os.Build;
import android.util.AtomicFile;
import android.view.Display;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

final class PhoneCapabilities {
    static final String SHARE_PATH = "/phone-capabilities.txt";
    static final String TITLE = "Технические характеристики телефона";
    private static final AtomicBoolean SCANNING = new AtomicBoolean();
    private static final Pattern SYSTEM_NAME = Pattern.compile("[A-Za-z][A-Za-z0-9_.:-]{0,127}");

    private PhoneCapabilities() {}

    static File file(Context context) {
        return new File(context.getFilesDir(), TITLE + ".txt");
    }

    static String read(Context context) throws IOException {
        return new String(new AtomicFile(file(context)).readFully(), StandardCharsets.UTF_8);
    }

    static boolean scan(MainActivity activity) {
        if (!SCANNING.compareAndSet(false, true)) return false;
        Context context = activity.getApplicationContext();
        Shell.background(() -> {
            String report = null;
            try {
                if (!Shell.ready(context)) throw new IOException("Shizuku unavailable");
                String data = collect(context);
                if (!Shell.ready(context)) throw new IOException("Shizuku disconnected");
                save(context, data);
                report = data;
            } catch (Exception error) {
                Diagnostics.failure(context, "capabilities.scan.failed", error);
            } finally {
                SCANNING.set(false);
            }
            String result = report;
            Shell.onMain(() -> {
                if (!activity.canShowUpdate()) return;
                if (result == null) activity.showThemedMessage(TITLE,
                        "Не удалось создать отчёт. Проверьте, запущен ли Shizuku и есть ли свободное место.");
                else activity.showCapabilitiesReport(result);
            });
        });
        return true;
    }

    private static String collect(Context context) {
        StringBuilder report = new StringBuilder(TITLE).append("\nФормат отчёта 2\n\n");
        entry(report, "Бренд", Build.BRAND);
        entry(report, "Производитель", Build.MANUFACTURER);
        entry(report, "Модель", Build.MODEL);
        entry(report, "Код модели", Build.DEVICE);
        entry(report, "Android", Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")");
        entry(report, "Сборка прошивки", Build.ID);
        entry(report, "Обновление безопасности Android", Build.VERSION.SECURITY_PATCH);
        entry(report, "Версия XPhone", BuildConfig.VERSION_NAME);
        if (Build.VERSION.SDK_INT >= 31) {
            entry(report, "Процессор", Build.SOC_MANUFACTURER + " " + Build.SOC_MODEL);
        }
        entry(report, "Архитектуры", String.join(", ", Build.SUPPORTED_ABIS));
        ActivityManager.MemoryInfo memory = new ActivityManager.MemoryInfo();
        context.getSystemService(ActivityManager.class).getMemoryInfo(memory);
        entry(report, "Доступная системе оперативная память",
                String.format(Locale.forLanguageTag("ru"), "%.1f ГиБ", memory.totalMem / (double) (1L << 30)));

        report.append("\nДисплей\n");
        String wmSize = Shell.run(context, "wm size");
        int[] physical = DisplayGeometry.size(wmSize, false);
        int[] override = DisplayGeometry.size(wmSize, true);
        dimensions(report, "Физическое разрешение", physical);
        dimensions(report, "Текущее разрешение", override != null ? override : physical);
        Display display = context.getSystemService(DisplayManager.class).getDisplay(Display.DEFAULT_DISPLAY);
        if (display != null) {
            LinkedHashSet<String> modes = new LinkedHashSet<>();
            for (Display.Mode mode : display.getSupportedModes()) {
                modes.add(mode.getPhysicalWidth() + " × " + mode.getPhysicalHeight() + " · "
                        + Math.round(mode.getRefreshRate()) + " Гц");
            }
            entry(report, "Режимы, объявленные Android", modes.isEmpty() ? "не сообщены" : String.join(", ", modes));
        }

        report.append("\nВозможности Android\n");
        PackageManager packages = context.getPackageManager();
        flag(report, "Оконный режим (freeform)", packages.hasSystemFeature("android.software.freeform_window_management"));
        flag(report, "Режим «Картинка в картинке»", packages.hasSystemFeature("android.software.picture_in_picture"));
        flag(report, "Проводная сеть", packages.hasSystemFeature(PackageManager.FEATURE_ETHERNET));
        flag(report, "Микрофон", packages.hasSystemFeature(PackageManager.FEATURE_MICROPHONE));

        report.append("\nПроверка через Shizuku\n");
        commandFlag(report, "Системная команда игровых режимов", Shell.run(context, "cmd game help"), "list-modes");
        commandFlag(report, "Системная команда энергосбережения", Shell.run(context, "cmd power help"), "set-mode");
        String brightness = Shell.run(context, "cmd display get-brightness");
        entry(report, "Чтение яркости через системную команду",
                brightness != null && brightness.matches("[0-9]+(?:\\.[0-9]+)?") ? "доступно" : "не подтверждено");
        flag(report, "Доступ к счётчику кадров экрана",
                FrameCounter.number(Shell.run(context, ScreenFrames.COMMAND), "totalFrames") >= 0);
        flag(report, "Доступ к подсветке касаний", Shell.touchAvailable(context));
        flag(report, "Доступ к защите уведомлений при стриме", Shell.privacyAvailable(context));

        appendFirmwareCatalog(report, context, packages);

        report.append("\nКнопки, доступные в текущей версии XPhone\n");
        for (String id : PanelFeatures.IDS) {
            if ("stream".equals(id)) continue;
            entry(report, PanelFeatures.title(id) + " [" + id + "]",
                    PanelFeatures.available(context, id) ? "доступна" : "не предлагается");
        }
        report.append("\nКаталог показывает, что прошивка сообщает о себе. Наличие записи помогает найти "
                + "возможную функцию. Доступ к ней и её работу проверяют отдельно на этой модели.\n");
        return report.toString();
    }

    private static void appendFirmwareCatalog(StringBuilder report, Context context, PackageManager packages) {
        Set<String> features = new TreeSet<>();
        var declared = packages.getSystemAvailableFeatures();
        if (declared != null) {
            for (var feature : declared) {
                if (feature.name == null && feature.reqGlEsVersion != 0) {
                    features.add("OpenGL ES " + feature.getGlEsVersion());
                } else if (isSystemName(feature.name)) {
                    features.add(feature.name + (feature.version > 0 ? " · версия " + feature.version : ""));
                }
            }
        }
        list(report, "Все возможности, объявленные прошивкой", features);

        Set<String> sensors = new TreeSet<>();
        SensorManager manager = context.getSystemService(SensorManager.class);
        if (manager != null) {
            for (Sensor sensor : manager.getSensorList(Sensor.TYPE_ALL)) {
                String type = sensor.getStringType();
                sensors.add(isSystemName(type) ? type + " · тип " + sensor.getType() : "Тип " + sensor.getType());
            }
        }
        list(report, "Типы датчиков, видимые Android", sensors);

        Set<String> services = new TreeSet<>();
        String output = Shell.run(context, "cmd -l");
        if (output != null) {
            for (String line : output.split("\\R")) {
                if (!line.startsWith(" ") && !line.startsWith("\t")) continue;
                String name = line.trim();
                if (isSystemName(name)) services.add(name);
            }
        }
        list(report, "Системные службы с командным интерфейсом, видимые Shizuku", services);
    }

    private static boolean isSystemName(String name) {
        return name != null && SYSTEM_NAME.matcher(name).matches();
    }

    private static void list(StringBuilder report, String heading, Set<String> names) {
        report.append('\n').append(heading).append('\n');
        if (names.isEmpty()) report.append("Список недоступен\n");
        for (String name : names) report.append("• ").append(name).append('\n');
    }

    private static void dimensions(StringBuilder report, String label, int[] size) {
        entry(report, label, size == null ? "не прочитано" : size[0] + " × " + size[1]);
    }

    private static void flag(StringBuilder report, String label, boolean available) {
        entry(report, label, available ? "обнаружено" : "не подтверждено");
    }

    private static void commandFlag(StringBuilder report, String label, String help, String option) {
        flag(report, label, help != null && help.contains(option));
    }

    private static void entry(StringBuilder report, String label, String value) {
        String text = value == null || value.trim().isEmpty() ? "не сообщено" : value.replace('\r', ' ').replace('\n', ' ');
        report.append(label).append(": ").append(text).append('\n');
    }

    private static void save(Context context, String report) throws IOException {
        AtomicFile target = new AtomicFile(file(context));
        FileOutputStream output = target.startWrite();
        try {
            output.write(report.getBytes(StandardCharsets.UTF_8));
            target.finishWrite(output);
        } catch (IOException error) {
            target.failWrite(output);
            throw error;
        }
    }
}
