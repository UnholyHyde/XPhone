namespace XPhone.Adb;

public enum Transport { Usb, Wifi }

/// <summary>Одно подключение к телефону (по USB или по Wi-Fi). Один телефон может быть виден дважды.</summary>
public sealed class PhoneDevice
{
    /// <summary>Адрес для adb: серийник (USB), ip:порт или имя mDNS (Wi-Fi).</summary>
    public required string Serial { get; init; }
    /// <summary>device, unauthorized, offline, authorizing, connecting…</summary>
    public required string State { get; init; }
    public required Transport Transport { get; init; }
    public string Model { get; set; } = "";

    // Заполняется после опроса телефона (только в состоянии device).
    public string? HardwareSerial { get; set; }
    public string? Name { get; set; }
    public string? Manufacturer { get; set; }
    public int Sdk { get; set; }
    public int ScreenShort { get; set; }
    public int ScreenLong { get; set; }
    public string? IpAddress { get; set; }

    public bool IsReady => State == "device";
    public bool IsSamsung => (Manufacturer ?? Model).Contains("samsung", StringComparison.OrdinalIgnoreCase)
                             || Model.StartsWith("SM", StringComparison.OrdinalIgnoreCase);

    /// <summary>Ключ телефона для настроек: один и тот же для USB и Wi-Fi.</summary>
    public string Key => HardwareSerial ?? Serial;

    public string DisplayName =>
        !string.IsNullOrWhiteSpace(Name) ? Name! :
        !string.IsNullOrWhiteSpace(Model) ? Model : "Android-телефон";

    public string TransportText => Transport == Transport.Usb ? "USB" : "Wi-Fi";

    public string StateText => State switch
    {
        "device" => "Готов",
        "unauthorized" => "Ждёт разрешения на телефоне",
        "authorizing" => "Ждёт разрешения на телефоне",
        "offline" => "Не отвечает",
        "connecting" => "Подключается…",
        _ => "Недоступен",
    };

    public static bool LooksLikeWifi(string serial) =>
        serial.Contains(':') || serial.Contains("._adb-tls-connect", StringComparison.OrdinalIgnoreCase)
                             || serial.Contains("._tcp", StringComparison.OrdinalIgnoreCase);
}
