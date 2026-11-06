using XPhone.Adb;

namespace XPhone.Scrcpy;

/// <summary>Сообщение для человека: что случилось и что сделать.</summary>
public sealed record HumanMessage(string Title, string Hint, bool IsError = true);

/// <summary>Переводит журнал scrcpy/adb в понятные слова.</summary>
public static class HumanErrors
{
    private static bool Has(string log, params string[] parts) =>
        parts.Any(p => log.Contains(p, StringComparison.OrdinalIgnoreCase));

    /// <summary>scrcpy закрылся сам. Почему?</summary>
    public static HumanMessage ForExit(string log, PhoneDevice device, bool windowWasShown)
    {
        bool wifi = device.IsNetwork;

        if (Has(log, "unauthorized"))
            return new("Телефон не разрешил подключение",
                "Разблокируйте телефон: там должно появиться окно «Разрешить отладку по USB?». " +
                "Поставьте галочку «Всегда разрешать с этого компьютера» и нажмите «Разрешить».");

        if (Has(log, "Device disconnected", "Closing after device disconnection", "device offline",
                "stream disabled due to connection error", "Connection reset", "Broken pipe") || (windowWasShown && Has(log, "Demuxer error", "end of frames")))
            return wifi && Core.NetCheck.VpnOnRouteTo(device.IpAddress) is { } vpn
                ? new("Связь по сети потерялась: мешает VPN", Core.NetCheck.VpnHint(vpn))
                : wifi
                ? new("Связь с телефоном по сети потерялась",
                    "Проверьте, что телефон и компьютер в одной локальной сети и на телефоне включена «Отладка по Wi-Fi». " +
                    "Телефон переподключится сам, когда снова появится в сети (если включено «Подключаться автоматически»).")
                : new("Кабель отключён или телефон перестал отвечать",
                    "Проверьте кабель и разъём. Если кабель на месте — выньте и вставьте его снова. " +
                    "Иногда помогает другой порт USB на компьютере.");

        if (Has(log, "Could not find ADB device", "Could not find any ADB device", "device not found", "device '"))
            return wifi
                ? new("Телефон пропал из сети",
                    "Для Wi-Fi включите отладку; для LAN проверьте TCP/IP и сетевой адрес. Проверьте, что он в той же сети, что и компьютер. Затем нажмите «Обновить».")
                : new("Телефон не найден",
                    "Подключите телефон кабелем и нажмите «Обновить». Если не помогает — откройте «Проверку телефона».");

        if (Has(log, "Device could not be connected", "authorizing"))
            return new("Телефон ещё не готов",
                "Разблокируйте телефон и подтвердите отладку, затем нажмите «Подключить» ещё раз.");

        if (Has(log, "Demuxer 'video': stream configuration error", "Could not open video encoder", "MediaCodec", "encoder", "IllegalStateException"))
            return new("Телефон не смог выдать такое качество",
                "Попробуйте качество пониже (1080p или 720p) или меньше FPS, затем нажмите «Подключить».");

        if (Has(log, "Could not create window", "Could not create renderer", "Could not initialize SDL video"))
            return new("Не получилось открыть окно с экраном телефона",
                "Обновите драйвер видеокарты и попробуйте ещё раз.");

        if (Has(log, "Could not forward", "Could not listen on", "adb reverse", "Could not start adb server", "Could not execute"))
            return new("Не удалось наладить связь с телефоном",
                "Закройте другие программы для Android (Android Studio, другие зеркала экрана), отключите и подключите телефон, затем нажмите «Подключить».");

        if (Has(log, "Permission denied", "SecurityException", "INJECT_EVENTS"))
            return new("Телефон запретил управление",
                device.IsSamsung
                    ? "Выключите «Автоблокировщик» (Auto Blocker): Настройки → Безопасность и конфиденциальность → Автоблокировщик."
                    : "Проверьте в «Параметрах разработчика», что отладка по USB разрешена (и, если есть, «Отладка по USB (настройки безопасности)»).");

        if (windowWasShown)
            return new("Трансляция остановилась",
                "Нажмите «Подключить» ещё раз. Если повторяется — откройте «Проверку телефона».");

        return new("Не получилось подключиться к телефону",
            "Нажмите «Подключить» ещё раз. Если не помогает — откройте «Проверку телефона», она подскажет, что включить.");
    }

    /// <summary>Предупреждения во время работы (не останавливают трансляцию).</summary>
    public static HumanMessage? ForWarning(string line, PhoneDevice device)
    {
        if (Has(line, "Demuxer 'audio': stream explicitly disabled by the device", "Audio disabled", "audio capture", "Could not capture audio"))
        {
            if (device.Sdk is > 0 and < 30)
                return new("Звук не передаётся", "На этом телефоне Android старше 11-го: передача звука невозможна. Картинка работает.", false);
            return new("Звук не передаётся",
                "Телефон не дал захватить звук. Попробуйте режим звука «Только на ПК» или переподключитесь. Картинка работает.", false);
        }
        if (Has(line, "Could not open audio device", "Could not initialize SDL audio"))
            return new("Нет звука на компьютере",
                "Не удалось открыть звуковое устройство Windows. Проверьте, что колонки или наушники подключены и выбраны по умолчанию.", false);
        return null;
    }
}
