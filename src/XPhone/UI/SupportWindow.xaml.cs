using System.Diagnostics;
using System.Windows;
using System.Windows.Interop;
using XPhone.Core;

namespace XPhone.UI;

public partial class SupportWindow : Window
{
    // Публичная страница добровольной поддержки автора.
    private const string DonatePayUrl = "https://donatepay.ru/don/unholyhyde";

    public SupportWindow()
    {
        InitializeComponent();
        DonateButton.IsEnabled = DonatePayUrl.Length > 0;
        SourceInitialized += (_, _) => Win32.DarkTitleBar(new WindowInteropHelper(this).Handle);
    }

    private void OpenLink(string url)
    {
        try { Process.Start(new ProcessStartInfo(url) { UseShellExecute = true }); }
        catch { StatusText.Text = "Не удалось открыть браузер. Проверьте приложение для открытия ссылок по умолчанию."; }
    }
    private void Donate_Click(object sender, RoutedEventArgs e) => OpenLink(DonatePayUrl);
    private void Telegram_Click(object sender, RoutedEventArgs e) => OpenLink("https://t.me/FCKNHYDE");
    private void Issues_Click(object sender, RoutedEventArgs e) => OpenLink("https://github.com/UnholyHyde/XPhone/issues");
    private void Close_Click(object sender, RoutedEventArgs e) => Close();
}
