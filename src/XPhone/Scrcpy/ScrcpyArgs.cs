using System.Globalization;
using XPhone.Adb;
using XPhone.Features;
using XPhone.Settings;

namespace XPhone.Scrcpy;

/// <summary>Превращает настройки в параметры командной строки scrcpy.</summary>
public static class ScrcpyArgs
{
    /// <summary>Соотношение сторон по умолчанию (Galaxy S25 Ultra: 1440×3120).</summary>
    private const double DefaultAspect = 3120.0 / 1440.0;

    /// <summary>Длинная сторона картинки для выбранного качества (scrcpy ограничивает именно её).</summary>
    public static int MaxSize(PhoneDevice d, PhoneProfile p)
    {
        int lines = p.Lines;
        double aspect = d.ScreenShort > 0 ? (double)d.ScreenLong / d.ScreenShort : DefaultAspect;
        if (d.ScreenShort > 0 && lines >= d.ScreenShort) return 0; // не больше, чем у телефона
        int max = (int)Math.Round(lines * aspect / 8.0) * 8;
        return Math.Max(max, 320);
    }

    /// <summary>Битрейт «авто»: по размеру картинки и FPS (≈0,08 бита на точку), 4…40 Мбит/с.</summary>
    public static int AutoBitrateMbps(PhoneDevice? d, PhoneProfile p)
    {
        double aspect = d is { ScreenShort: > 0 } ? (double)d.ScreenLong / d.ScreenShort : DefaultAspect;
        int lines = d is { ScreenShort: > 0 } ? Math.Min(p.Lines, d.ScreenShort) : p.Lines;
        double px = lines * lines * aspect;
        double mbps = px * p.Fps * 0.08 / 1_000_000.0;
        return (int)Math.Clamp(Math.Round(mbps), 4, 40);
    }

    public static int BitrateMbps(PhoneDevice? d, PhoneProfile p) =>
        p.BitrateMbps > 0 ? p.BitrateMbps : AutoBitrateMbps(d, p);

    public static List<string> Build(PhoneDevice d, PhoneProfile p)
    {
        var a = new List<string> { "--serial=" + d.Serial };

        int maxSize = MaxSize(d, p);
        if (maxSize > 0) a.Add("--max-size=" + maxSize);
        a.Add("--max-fps=" + p.Fps);
        a.Add("--video-bit-rate=" + BitrateMbps(d, p) + "M");

        // Буферы: обычный режим чуть сглаживает рывки Wi-Fi, «минимальная задержка» — без запаса.
        if (p.LowLatency)
        {
            a.Add("--video-buffer=0");
            a.Add("--audio-buffer=30");
        }
        else
        {
            a.Add("--video-buffer=" + (d.Transport is Transport.Wifi or Transport.Network ? 60 : 30));
            a.Add("--audio-buffer=" + (d.Transport is Transport.Wifi or Transport.Network ? 100 : 70));
        }

        switch (p.Audio)
        {
            case AudioMode.PcOnly:
                // Android 13+: «playback» без дублирования — звук уходит на ПК, телефон молчит.
                // Он же ловит игры с голосовым чатом (режим звонка), где «output» даёт тишину
                // (проверено на Galaxy S25 Ultra + Mobile Legends).
                // Старые телефоны — «output» (весь звук, телефон молчит).
                a.Add(d.Sdk >= 33 ? "--audio-source=playback" : "--audio-source=output");
                break;
            case AudioMode.Both:
                a.Add("--audio-source=playback"); // Android 13+
                a.Add("--audio-dup");
                break;
            case AudioMode.Off:
                a.Add("--no-audio");
                break;
        }

        if (p.TurnScreenOff) a.Add("--turn-screen-off");
        if (p.StayAwake)
        {
            a.Add("--stay-awake"); // работает при подключении по кабелю
            if (d.IsNetwork)
                a.Add("--screen-off-timeout=" + (24 * 3600).ToString(CultureInfo.InvariantCulture));
        }
        if (p.Borderless) a.Add("--window-borderless");
        if (p.AlwaysOnTop) a.Add("--always-on-top");
        var title = string.IsNullOrWhiteSpace(p.WindowTitle) ? "XPhone" : p.WindowTitle.Trim();
        a.Add("--window-title=" + title);

        foreach (var c in ExtensionPoints.ArgsContributors)
            a.AddRange(c.Contribute(d, p));
        return a;
    }

    /// <summary>Реальная высота картинки: не больше, чем отдаёт сам телефон.</summary>
    public static int EffectiveLines(PhoneDevice? d, PhoneProfile p) =>
        d is { ScreenShort: > 0 } ? Math.Min(p.Lines, d.ScreenShort) : p.Lines;

    /// <summary>Телефон отдаёт меньше, чем выбрано (например, на Samsung выбрано разрешение экрана HD+).</summary>
    public static bool PhoneLimitsQuality(PhoneDevice? d, PhoneProfile p) =>
        d is { ScreenShort: > 0 } && d.ScreenShort < p.Lines;

    /// <summary>Короткое описание для строки состояния: «1080p · 60 FPS · 12 Мбит/с».</summary>
    public static string Describe(PhoneDevice? d, PhoneProfile p) =>
        $"{EffectiveLines(d, p)}p · {p.Fps} FPS · {BitrateMbps(d, p)} Мбит/с";
}
