using XPhone.Adb;
using XPhone.Settings;

namespace XPhone.Features;

// Места под будущие функции. Сейчас ничего не делают, но код уже умеет их вызывать.

/// <summary>
/// Модуль добавляет свои параметры scrcpy. Например, будущее «размытие части экрана»
/// (scrcpy --crop, своё окно-перекрытие и т.п.).
/// </summary>
public interface IScrcpyArgsContributor
{
    IEnumerable<string> Contribute(PhoneDevice device, PhoneProfile profile);
}

/// <summary>Будущие «быстрые кнопки»: нажать что-то на телефоне одной кнопкой (adb shell input …).</summary>
public interface IQuickAction
{
    string Title { get; }
    /// <summary>Имя иконки Lucide.</summary>
    string Icon { get; }
    Task RunAsync(AdbClient adb, PhoneDevice device);
}

/// <summary>Будущий лицензионный ключ (для передачи программы другим людям).</summary>
public interface ILicenseService
{
    bool IsAllowed(out string? reason);
}

public sealed class FreeLicense : ILicenseService
{
    public bool IsAllowed(out string? reason) { reason = null; return true; }
}

/// <summary>
/// Будущий APK-помощник на телефоне (например, для быстрых кнопок или проверки звука).
/// </summary>
public interface IPhoneHelper
{
    Task<bool> IsInstalledAsync(AdbClient adb, PhoneDevice device);
}

public static class ExtensionPoints
{
    public static List<IScrcpyArgsContributor> ArgsContributors { get; } = new();
    public static List<IQuickAction> QuickActions { get; } = new();
    public static ILicenseService License { get; set; } = new FreeLicense();
    public static IPhoneHelper? PhoneHelper { get; set; }
}
