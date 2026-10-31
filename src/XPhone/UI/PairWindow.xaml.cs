using System.Security.Cryptography;
using System.Text.RegularExpressions;
using System.Windows;
using System.Windows.Input;
using System.Windows.Interop;
using System.Windows.Media;
using System.Windows.Media.Animation;
using System.Windows.Threading;
using XPhone.Adb;
using XPhone.Core;

namespace XPhone.UI;

/// <summary>
/// Сопряжение по Wi-Fi. Два способа:
/// QR-код — телефон сканирует код и объявляет себя в сети под нашим именем, мы находим его и сопрягаемся;
/// код из 6 цифр — человек вводит адрес и код с телефона.
/// </summary>
public partial class PairWindow : Window
{
    private readonly MainViewModel _vm;
    private readonly DispatcherTimer _scan;
    private readonly string _qrName;      // имя, под которым телефон объявит себя после сканирования
    private readonly string _qrPassword;  // пароль сопряжения из QR-кода
    private DateTime _qrShownAt;
    private bool _addressTyped;
    private bool _filling;
    private bool _busy;
    private bool _done;

    public PairWindow(MainViewModel vm)
    {
        InitializeComponent();
        _vm = vm;
        _qrName = "xphone-" + RandomText(6);
        _qrPassword = RandomText(10);
        SourceInitialized += (_, _) => Win32.DarkTitleBar(new WindowInteropHelper(this).Handle);
        _scan = new DispatcherTimer { Interval = TimeSpan.FromSeconds(1.5) };
        _scan.Tick += async (_, _) => await TickAsync();
        SpinAngle.BeginAnimation(RotateTransform.AngleProperty,
            new DoubleAnimation(0, 360, TimeSpan.FromSeconds(1.2)) { RepeatBehavior = RepeatBehavior.Forever });
        Loaded += async (_, _) =>
        {
            QrImage.Source = MakeQr($"WIFI:T:ADB;S:{_qrName};P:{_qrPassword};;");
            _qrShownAt = DateTime.UtcNow;
            CheckVpn(null);
            await _vm.Adb.EnsureServerAsync();
            _scan.Start();
        };
        Closed += (_, _) => _scan.Stop();
    }

    private static string RandomText(int n)
    {
        const string chars = "abcdefghjkmnpqrstuvwxyz23456789";
        return new string(Enumerable.Range(0, n).Select(_ => chars[RandomNumberGenerator.GetInt32(chars.Length)]).ToArray());
    }

    private static ImageSource MakeQr(string text)
    {
        using var gen = new QRCoder.QRCodeGenerator();
        var data = gen.CreateQrCode(text, QRCoder.QRCodeGenerator.ECCLevel.M);
        var m = data.ModuleMatrix;
        var g = new GeometryGroup { FillRule = FillRule.Nonzero };
        for (int y = 0; y < m.Count; y++)
            for (int x = 0; x < m[y].Length; x++)
                if (m[y][x]) g.Children.Add(new RectangleGeometry(new Rect(x, y, 1, 1)));
        var drawing = new DrawingGroup();
        drawing.Children.Add(new GeometryDrawing(Brushes.White, null, new RectangleGeometry(new Rect(0, 0, m.Count, m.Count))));
        drawing.Children.Add(new GeometryDrawing(new SolidColorBrush(Color.FromRgb(0x0B, 0x0B, 0x0E)), null, g));
        var img = new DrawingImage(drawing);
        img.Freeze();
        return img;
    }

    /// <summary>Показать предупреждение, если к телефону компьютер ходит через VPN.</summary>
    private bool CheckVpn(string? phoneIp)
    {
        // Адрес телефона: введённый, либо известный по кабелю.
        phoneIp ??= _vm.Watcher.Current.FirstOrDefault(d => d.IpAddress != null)?.IpAddress;
        var vpn = NetCheck.VpnOnRouteTo(phoneIp) ?? (phoneIp == null ? NetCheck.ActiveVpnName() : null);
        if (vpn == null) { VpnBox.Visibility = Visibility.Collapsed; return false; }
        VpnText.Text = NetCheck.VpnHint(vpn);
        VpnBox.Visibility = Visibility.Visible;
        return true;
    }

    private void Mode_Changed(object sender, RoutedEventArgs e)
    {
        if (QrPanel == null) return;
        bool qr = ModeQr.IsChecked == true;
        QrPanel.Visibility = qr ? Visibility.Visible : Visibility.Collapsed;
        CodePanel.Visibility = qr ? Visibility.Collapsed : Visibility.Visible;
        PairButton.Visibility = qr ? Visibility.Collapsed : Visibility.Visible;
        PairButton.IsDefault = !qr;
        StatusBox.Visibility = Visibility.Collapsed;
        if (qr) _qrShownAt = DateTime.UtcNow;
        else AddressBox.Focus();
    }

    /// <summary>Раз в 1,5 секунды смотрим, кто объявился в сети.</summary>
    private async Task TickAsync()
    {
        if (_busy || _done) return;
        List<MdnsService> services;
        try { services = await _vm.Adb.MdnsServicesAsync(); }
        catch { return; }
        if (_busy || _done) return;

        if (ModeQr.IsChecked == true)
        {
            var svc = services.FirstOrDefault(s => s.IsPairing && s.Name.Equals(_qrName, StringComparison.OrdinalIgnoreCase));
            if (svc != null)
            {
                await PairAsync(svc.Address, _qrPassword);
                return;
            }
            if (DateTime.UtcNow - _qrShownAt > TimeSpan.FromSeconds(30))
            {
                QrWait.Text = "Всё ещё ждём телефон…";
                if (!CheckVpn(null))
                    ShowStatus("Телефон отсканировал код, но не появился? Проверьте, что телефон и компьютер в одной сети и «Отладка по Wi-Fi» включена. " +
                               "Если не выходит — попробуйте способ «Код из 6 цифр».", "info");
            }
        }
        else
        {
            // Окно «с помощью кода» открыто на телефоне — подставим адрес.
            var pairing = services.FirstOrDefault(s => s.IsPairing && !s.Name.StartsWith("xphone-", StringComparison.OrdinalIgnoreCase));
            if (pairing == null) return;
            FoundText.Text = "Нашли телефон в сети: " + pairing.Ip;
            FoundPanel.Visibility = Visibility.Visible;
            if (!_addressTyped && AddressBox.Text != pairing.Address)
            {
                _filling = true;
                AddressBox.Text = pairing.Address;
                _filling = false;
                if (CodeBox.Text.Length == 0) CodeBox.Focus();
            }
        }
    }

    private void AddressBox_TextChanged(object sender, System.Windows.Controls.TextChangedEventArgs e)
    {
        if (!_filling) _addressTyped = AddressBox.Text.Length > 0;
    }

    private void CodeBox_PreviewTextInput(object sender, TextCompositionEventArgs e) => e.Handled = !e.Text.All(char.IsDigit);

    private void CodeBox_KeyDown(object sender, KeyEventArgs e)
    {
        if (e.Key == Key.Enter) Pair_Click(sender, e);
    }

    private void Cancel_Click(object sender, RoutedEventArgs e) => Close();

    private void ShowStatus(string text, string icon)
    {
        StatusText.Text = text;
        StatusIcon.Kind = icon;
        StatusBox.Visibility = Visibility.Visible;
    }

    private async void Pair_Click(object sender, RoutedEventArgs e)
    {
        if (_busy || ModeQr.IsChecked == true) return;
        var address = AddressBox.Text.Trim().Replace(" ", "").Replace(',', '.');
        var code = CodeBox.Text.Trim();

        if (!Regex.IsMatch(address, @"^\d{1,3}(\.\d{1,3}){3}:\d{2,5}$"))
        {
            ShowStatus("Впишите адрес так, как он написан на телефоне: цифры с точками, двоеточие и порт. Например 192.168.1.23:37145.", "circle-alert");
            AddressBox.Focus();
            return;
        }
        if (!Regex.IsMatch(code, @"^\d{6}$"))
        {
            ShowStatus("Код — 6 цифр из окна на телефоне.", "circle-alert");
            CodeBox.Focus();
            return;
        }
        await PairAsync(address, code);
    }

    private async Task PairAsync(string address, string password)
    {
        _busy = true;
        PairButton.IsEnabled = false;
        var ip = address.Split(':')[0];
        bool vpn = CheckVpn(ip);
        ShowStatus("Сопрягаем… Не закрывайте окно на телефоне. Это может занять до 20 секунд.", "loader");
        try
        {
            await _vm.Adb.EnsureServerAsync();
            var (ok, message) = await _vm.Adb.PairAsync(address, password);
            if (!ok)
            {
                ShowStatus(vpn ? "Не получилось: телефон недоступен из-за VPN (см. выше)." : Explain(message), "triangle-alert");
                return;
            }

            _done = true;
            ShowStatus("Готово! Подключаемся к телефону…", "circle-check");
            // После сопряжения телефон объявляет в сети адрес для подключения (порт другой).
            bool connected = false;
            for (int i = 0; i < 8 && !connected; i++)
            {
                var services = await _vm.Adb.MdnsServicesAsync();
                var svc = services.FirstOrDefault(s => s.IsConnect && s.Ip == ip);
                if (svc != null) connected = (await _vm.Adb.ConnectAsync(svc.Address)).ok;
                if (!connected) await Task.Delay(1000);
            }
            await _vm.RefreshAsync();
            ShowStatus(connected
                ? "Готово! Телефон сопряжён и подключён. В следующий раз он найдётся сам — просто включите на нём «Отладку по Wi-Fi»."
                : "Сопряжение прошло. Телефон появится в списке через несколько секунд (если нет — нажмите «Обновить»).",
                "circle-check");
            PairButton.Visibility = Visibility.Collapsed;
            CancelButton.Content = "Закрыть";
            await Task.Delay(connected ? 1800 : 4000);
            Close();
        }
        finally
        {
            _busy = false;
            PairButton.IsEnabled = true;
        }
    }

    private static string Explain(string adbMessage)
    {
        var m = adbMessage.ToLowerInvariant();
        if (m.Contains("wrong password") || m.Contains("protocol fault") || m.Contains("password"))
            return "Код не подошёл. На телефоне снова откройте окно сопряжения и попробуйте ещё раз.";
        if (m.Contains("unable to connect") || m.Contains("failed to connect") || m.Contains("connection refused") || m.Contains("no route"))
            return "Телефон не отвечает по этому адресу. Проверьте адрес и порт (они меняются каждый раз) и что телефон в той же сети.";
        if (m.Contains("не ответил"))
            return "Телефон не ответил вовремя. Проверьте, что телефон и компьютер в одной сети, окно сопряжения на телефоне открыто, и попробуйте ещё раз.";
        return "Не получилось. Откройте на телефоне окно сопряжения заново и попробуйте ещё раз.";
    }
}
