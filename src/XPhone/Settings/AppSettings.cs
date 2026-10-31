using System.Text.Json;
using System.Text.Json.Serialization;

namespace XPhone.Settings;

public enum QualityMode { P720, P1080, Custom }
public enum AudioMode { PcOnly, Both, Off }

/// <summary>Настройки трансляции. У каждого телефона — свой профиль.</summary>
public sealed class PhoneProfile
{
    public QualityMode Quality { get; set; } = QualityMode.P1080;
    /// <summary>«Своё значение»: высота картинки (короткая сторона), например 900.</summary>
    public int CustomLines { get; set; } = 900;
    public int Fps { get; set; } = 60;
    /// <summary>0 — авто.</summary>
    public int BitrateMbps { get; set; }
    public bool LowLatency { get; set; }
    public AudioMode Audio { get; set; } = AudioMode.PcOnly;
    public bool TurnScreenOff { get; set; }
    public bool StayAwake { get; set; } = true;
    public bool Borderless { get; set; }
    public bool AlwaysOnTop { get; set; }
    public string WindowTitle { get; set; } = "XPhone — экран телефона";

    public int Lines => Quality switch
    {
        QualityMode.P720 => 720,
        QualityMode.P1080 => 1080,
        _ => Math.Clamp(CustomLines, 240, 2160),
    };

    public PhoneProfile Clone() => (PhoneProfile)MemberwiseClone();
}

public sealed class AppSettings
{
    public int Version { get; set; } = 1;
    public bool FirstRunDone { get; set; }
    public bool RememberPhone { get; set; } = true;
    public bool AutoConnect { get; set; }
    /// <summary>Серийный номер запомненного телефона (одинаков для USB и Wi-Fi).</summary>
    public string? RememberedPhone { get; set; }
    public string? RememberedPhoneName { get; set; }
    /// <summary>Профиль для новых телефонов.</summary>
    public PhoneProfile DefaultProfile { get; set; } = new();
    public Dictionary<string, PhoneProfile> Profiles { get; set; } = new();
    public double? WindowLeft { get; set; }
    public double? WindowTop { get; set; }

    public PhoneProfile ProfileFor(string? phoneKey)
    {
        if (phoneKey == null) return DefaultProfile;
        if (!Profiles.TryGetValue(phoneKey, out var p))
        {
            p = DefaultProfile.Clone();
            Profiles[phoneKey] = p;
        }
        return p;
    }
}

/// <summary>Хранение настроек: %APPDATA%\XPhone\settings.json.</summary>
public static class SettingsStore
{
    public static string Folder { get; } =
        Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "XPhone");
    public static string FilePath { get; } = Path.Combine(Folder, "settings.json");

    private static readonly JsonSerializerOptions Json = new()
    {
        WriteIndented = true,
        Converters = { new JsonStringEnumConverter() },
        Encoder = System.Text.Encodings.Web.JavaScriptEncoder.UnsafeRelaxedJsonEscaping,
    };

    public static AppSettings Load()
    {
        try
        {
            if (File.Exists(FilePath))
                return JsonSerializer.Deserialize<AppSettings>(File.ReadAllText(FilePath), Json) ?? new AppSettings();
        }
        catch
        {
            // Повреждённый файл не должен мешать запуску: сохраним копию и начнём с чистых настроек.
            try { File.Copy(FilePath, FilePath + ".broken", true); } catch { }
        }
        return new AppSettings();
    }

    public static void Save(AppSettings s)
    {
        try
        {
            Directory.CreateDirectory(Folder);
            var tmp = FilePath + ".tmp";
            File.WriteAllText(tmp, JsonSerializer.Serialize(s, Json));
            File.Move(tmp, FilePath, true);
        }
        catch { }
    }
}
