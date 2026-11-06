using XPhone.Adb;
using XPhone.Audio;
using XPhone.Settings;
using XPhone.UI;

namespace XPhone.Diagnostics;

public enum CheckStatus { Ok, Warn, Info }

public sealed record CheckItem(CheckStatus Status, string Title, string Text)
{
    public string Icon => Status switch
    {
        CheckStatus.Ok => "circle-check",
        CheckStatus.Warn => "triangle-alert",
        _ => "info",
    };
    public bool IsWarn => Status == CheckStatus.Warn;
}

/// <summary>«Проверка телефона»: что включено, что мешает — простыми словами.</summary>
public static class PhoneCheck
{
    private const string DevMode =
        "Включите режим разработчика: Настройки → Сведения о телефоне → Сведения о ПО → 7 раз нажмите «Номер сборки».";

    public static async Task<List<CheckItem>> RunAsync(MainViewModel vm, Action<string>? progress = null)
    {
        var items = new List<CheckItem>();
        var adb = vm.Adb;

        if (!adb.ToolsPresent)
        {
            items.Add(new(CheckStatus.Warn, "Не хватает файлов XPhone",
                "Рядом с XPhone.exe должна быть папка tools (scrcpy и adb). Распакуйте программу заново целиком."));
            return items;
        }

        progress?.Invoke("Ищем телефоны…");
        if (!await adb.EnsureServerAsync())
        {
            items.Add(new(CheckStatus.Warn, "Не запускается связь с телефоном (adb)",
                "Закройте другие программы для Android (Android Studio, другие зеркала экрана) и перезапустите XPhone. Проверьте, что антивирус не блокирует папку tools."));
            return items;
        }

        List<PhoneDevice> raw;
        try { raw = await adb.ListDevicesAsync(); } catch { raw = new(); }
        foreach (var d in raw.Where(d => d.IsReady)) await adb.FillInfoAsync(d);

        progress?.Invoke("Ищем телефоны в локальной сети…");
        var mdns = await vm.Watcher.ScanWifiAsync();

        var usb = raw.Where(d => d.Transport == Transport.Usb).ToList();
        var wifi = raw.Where(d => d.IsNetwork).ToList();
        var usbReady = usb.FirstOrDefault(d => d.IsReady);
        var wifiReady = wifi.FirstOrDefault(d => d.IsReady);
        var anyReady = vm.Session?.Device ?? (vm.Selected is { IsReady: true } sel ? raw.FirstOrDefault(r => r.Serial == sel.Serial) : null) ?? usbReady ?? wifiReady;

        // ── USB ──
        if (usbReady != null)
            items.Add(new(CheckStatus.Ok, "Отладка по USB включена",
                $"{usbReady.DisplayName} подключён кабелем и разрешил отладку."));
        else if (usb.Any(d => d.State is "unauthorized" or "authorizing"))
            items.Add(new(CheckStatus.Warn, "Разрешите отладку на телефоне",
                "Разблокируйте телефон: там есть окно «Разрешить отладку по USB?». Поставьте галочку «Всегда разрешать с этого компьютера» и нажмите «Разрешить». " +
                "Если окна нет — выньте и вставьте кабель."));
        else if (usb.Any(d => d.State == "offline"))
            items.Add(new(CheckStatus.Warn, "Телефон не отвечает по кабелю",
                "Выньте и вставьте кабель. Если не помогает — в «Параметрах разработчика» нажмите «Отозвать авторизации отладки по USB» и подключите кабель снова."));
        else if (wifiReady != null)
            items.Add(new(CheckStatus.Info, "По кабелю телефон не подключён",
                "Это нормально, если вы работаете по Wi-Fi или LAN. Для кабеля: Настройки → Параметры разработчика → «Отладка по USB» → включить."));
        else
            items.Add(new(CheckStatus.Warn, "Телефон по кабелю не виден",
                "1. " + DevMode + "\n" +
                "2. Настройки → Параметры разработчика → «Отладка по USB» → включить.\n" +
                "3. Кабель должен передавать данные, а не только заряжать (лучше родной).\n" +
                "4. Если телефон спросит режим USB — выберите «Передача файлов»."));

        // ── Samsung Auto Blocker ──
        bool samsung = raw.Any(d => d.IsSamsung) || usb.Count == 0;
        if (usbReady is { IsSamsung: true })
            items.Add(new(CheckStatus.Ok, "Samsung Auto Blocker не мешает",
                "Телефон отвечает по кабелю — значит, «Автоблокировщик» выключен."));
        else if (usbReady == null && samsung)
            items.Add(new(usb.Count == 0 && wifiReady == null ? CheckStatus.Warn : CheckStatus.Info, "Проверьте Samsung Auto Blocker",
                "На Samsung «Автоблокировщик» запрещает отладку по кабелю. Выключите его: Настройки → Безопасность и конфиденциальность → Автоблокировщик → выключить."));

        // ── VPN мешает Wi-Fi ──
        var phoneIp = raw.FirstOrDefault(d => d.IpAddress != null)?.IpAddress;
        var vpnOnRoute = Core.NetCheck.VpnOnRouteTo(phoneIp);
        var vpnAny = vpnOnRoute ?? (wifiReady == null ? Core.NetCheck.ActiveVpnName() : null);
        if (vpnOnRoute != null)
            items.Add(new(CheckStatus.Warn, "Сетевому подключению мешает VPN", Core.NetCheck.VpnHint(vpnOnRoute)));
        else if (vpnAny != null && phoneIp == null)
            items.Add(new(CheckStatus.Info, $"Включён VPN «{vpnAny}»",
                "Если телефон по Wi-Fi не находится — скорее всего, мешает VPN. " + Core.NetCheck.VpnHint(vpnAny)));

        // ── Wi-Fi ──
        if (wifiReady != null)
            items.Add(new(CheckStatus.Ok, "Подключение по локальной сети работает",
                $"{wifiReady.DisplayName} подключён: {wifiReady.TransportText}" + (wifiReady.IpAddress != null ? $" ({wifiReady.IpAddress})." : ".")));
        else if (wifi.Any(d => !d.IsReady))
            items.Add(new(CheckStatus.Warn, "Телефон по Wi-Fi не отвечает",
                "Проверьте, что телефон разблокирован и в той же сети. Выключите и включите «Отладку по Wi-Fi» на телефоне."));
        else if (mdns.Any(s => s.IsConnect))
            items.Add(new(CheckStatus.Warn, "Телефон виден в сети, но не сопряжён",
                "Нажмите «Сопряжение по Wi-Fi» в главном окне и введите код с телефона. Это нужно один раз."));
        else if (mdns.Any(s => s.IsPairing))
            items.Add(new(CheckStatus.Info, "На телефоне открыто окно с кодом",
                "Нажмите «Сопряжение по Wi-Fi» в главном окне — адрес подставится сам, останется ввести код."));
        else
            items.Add(new(CheckStatus.Info, "Отладка по Wi-Fi выключена (или телефон в другой сети)",
                "Если хотите без кабеля: телефон и компьютер — в одной сети Wi-Fi, на телефоне Настройки → Параметры разработчика → «Отладка по Wi-Fi» → включить. " +
                "Затем один раз сделайте «Сопряжение по Wi-Fi»."));

        // ── Звук ──
        var profile = vm.Settings.ProfileFor(anyReady?.HardwareSerial);
        if (anyReady is { Sdk: > 0 and < 30 })
            items.Add(new(CheckStatus.Warn, "Звук не передаётся на этом Android",
                "Передача звука работает с Android 11. Картинка будет, звука — нет."));
        else if (profile.Audio == AudioMode.Both && anyReady is { Sdk: > 0 and < 33 })
            items.Add(new(CheckStatus.Warn, "Режим «И на ПК, и на телефоне» не поддерживается",
                "Он работает с Android 13. Выберите «Только на ПК»."));
        else if (profile.Audio == AudioMode.Off)
            items.Add(new(CheckStatus.Info, "Звук выключен в настройках", "Выберите «Только на ПК», чтобы звук телефона шёл на компьютер."));
        else if (vm.Session is { } s && vm.IsConnected)
        {
            progress?.Invoke("Слушаем звук с телефона…");
            bool flowing = false, hasSession = false;
            for (int i = 0; i < 12 && !flowing; i++)
            {
                var peak = AudioMeter.PeakForProcess(s.ProcessId);
                if (peak != null) hasSession = true;
                if (peak is > 0.002f) flowing = true;
                await Task.Delay(250);
            }
            if (flowing)
                items.Add(new(CheckStatus.Ok, "Звук идёт", "Звук телефона приходит на компьютер."));
            else
                items.Add(new(CheckStatus.Warn, "Звука сейчас нет",
                    "Включите на телефоне музыку или видео и нажмите «Проверить ещё раз». " +
                    "Если в одном приложении звука нет, а в других есть — это приложение запрещает захват звука. Это его защита, XPhone тут ничего не может сделать." +
                    (hasSession ? "" : " Также проверьте, что на компьютере выбраны колонки или наушники.")));
        }
        else
            items.Add(new(CheckStatus.Info, "Звук проверим во время трансляции",
                (anyReady is { Sdk: >= 30 } ? "Android подходит для передачи звука. " : "") +
                "Нажмите «Подключить», включите на телефоне музыку и откройте проверку ещё раз."));

        // ── Чёрный экран ──
        items.Add(new(CheckStatus.Info, "Чёрный экран в некоторых приложениях — это нормально",
            "Банки, менеджеры паролей и некоторые видеосервисы прячут свой экран от записи. Это защита самого приложения, а не ошибка XPhone: пока оно открыто, в трансляции будет чёрный экран."));

        return items;
    }
}
