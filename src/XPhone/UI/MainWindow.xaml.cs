using System.Windows;
using System.Windows.Input;
using System.Windows.Interop;
using XPhone.Core;

namespace XPhone.UI;

public partial class MainWindow : Window
{
    private readonly MainViewModel _vm;

    public MainWindow()
    {
        InitializeComponent();
        _vm = new MainViewModel();
        DataContext = _vm;

        if (_vm.Settings.WindowLeft is { } l && _vm.Settings.WindowTop is { } t && IsOnScreen(l, t))
        {
            WindowStartupLocation = WindowStartupLocation.Manual;
            Left = l;
            Top = t;
        }

        SourceInitialized += (_, _) => Win32.DarkTitleBar(new WindowInteropHelper(this).Handle);
        Loaded += OnLoaded;
        Closing += (_, _) =>
        {
            if (WindowState == WindowState.Normal)
            {
                _vm.Settings.WindowLeft = Left;
                _vm.Settings.WindowTop = Top;
            }
        };
        Closed += (_, _) => _vm.Dispose();
    }

    private static bool IsOnScreen(double l, double t) =>
        l >= SystemParameters.VirtualScreenLeft - 50 && t >= SystemParameters.VirtualScreenTop - 10 &&
        l < SystemParameters.VirtualScreenLeft + SystemParameters.VirtualScreenWidth - 100 &&
        t < SystemParameters.VirtualScreenTop + SystemParameters.VirtualScreenHeight - 100;

    private void ChooseBackdrop_Click(object sender, RoutedEventArgs e)
    {
        var picker = new Microsoft.Win32.OpenFileDialog
        {
            Title = "Фон трансляции",
            Filter = "Картинки, GIF и видео|*.png;*.jpg;*.jpeg;*.bmp;*.gif;*.mp4",
        };
        if (picker.ShowDialog(this) == true) _vm.SetStreamBackdrop(picker.FileName);
    }

    private void ClearBackdrop_Click(object sender, RoutedEventArgs e) => _vm.SetStreamBackdrop(null);

    private async void OnLoaded(object sender, RoutedEventArgs e)
    {
        Loaded -= OnLoaded;
        _vm.Start();
        if (!_vm.Settings.FirstRunDone)
        {
            _vm.Settings.FirstRunDone = true;
            _vm.SaveSoon();
            // Первый запуск: сразу объясняем, что включить на телефоне.
            _ = Dispatcher.BeginInvoke(() => new CheckWindow(_vm, firstRun: true) { Owner = this }.ShowDialog(),
                System.Windows.Threading.DispatcherPriority.ApplicationIdle);
        }
        await Task.Delay(1800);
        if (!IsLoaded) return;
        AppUpdates.CleanInstallers();
        if (AppUpdates.TakeInstalledNotes() is { } notes)
            ShowUpdateNotes(notes);
        await CheckUpdates(false);
    }

    private void ShowUpdateNotes(string notes)
    {
        var body = new System.Windows.Controls.TextBox
        {
            Text = string.IsNullOrWhiteSpace(notes) ? "В релизе нет списка изменений." : notes,
            IsReadOnly = true, TextWrapping = TextWrapping.Wrap, Padding = new Thickness(12),
            VerticalScrollBarVisibility = System.Windows.Controls.ScrollBarVisibility.Auto
        };
        var window = new Window
        {
            Title = $"Что нового в XPhone {AppUpdates.Installed.ToString(3)}",
            Owner = this, Width = 540, Height = 380, Content = body,
            WindowStartupLocation = WindowStartupLocation.CenterOwner, ShowInTaskbar = false
        };
        window.PreviewKeyDown += (_, e) => { if (e.Key == Key.Escape) window.Close(); };
        window.ShowDialog();
    }

    private async void Updates_Click(object sender, RoutedEventArgs e) => await CheckUpdates(true);

    private async Task CheckUpdates(bool manual)
    {
        if (!UpdateButton.IsEnabled || !IsLoaded) return;
        UpdateButton.IsEnabled = false;
        try
        {
            var update = await AppUpdates.CheckAsync();
            if (!IsLoaded) return;
            if (update is null)
            {
                if (manual) MessageBox.Show(this, "У вас последняя версия XPhone для Windows.", "Обновления XPhone");
                return;
            }

            UpdateButton.Content = "Есть обновление";
            string? scope = AppUpdates.InstallScope();
            string action = scope is null
                ? "Эта копия запущена из папки. Открыть релизы GitHub, чтобы скачать новую версию?"
                : "Скачать и установить поверх текущей версии? XPhone перезапустится, активная трансляция остановится.";
            if (MessageBox.Show(this, $"Доступна версия {update.Version.ToString(3)}.\n\n{action}",
                    "Обновления XPhone", MessageBoxButton.YesNo, MessageBoxImage.Information) != MessageBoxResult.Yes)
                return;
            if (scope is null)
            {
                System.Diagnostics.Process.Start(new System.Diagnostics.ProcessStartInfo(
                    "https://github.com/UnholyHyde/XPhone/releases") { UseShellExecute = true });
                return;
            }
            UpdateButton.Content = "Загрузка…";
            await AppUpdates.InstallAsync(update, scope);
            Application.Current.Shutdown();
        }
        catch (Exception)
        {
            if (IsLoaded && (manual || Equals(UpdateButton.Content, "Загрузка…")))
                MessageBox.Show(this, "Не удалось проверить или установить обновление. Проверьте доступ к GitHub и попробуйте ещё раз.",
                    "Обновления XPhone", MessageBoxButton.OK, MessageBoxImage.Warning);
            if (Equals(UpdateButton.Content, "Загрузка…")) UpdateButton.Content = "Есть обновление";
        }
        finally { UpdateButton.IsEnabled = true; }
    }

    private void Connect_Click(object sender, RoutedEventArgs e) => _vm.Connect();

    private async void Disconnect_Click(object sender, RoutedEventArgs e) => await _vm.DisconnectAsync();

    private async void Refresh_Click(object sender, RoutedEventArgs e) => await _vm.RefreshAsync();

    private void Pair_Click(object sender, RoutedEventArgs e) =>
        new PairWindow(_vm) { Owner = this }.ShowDialog();

    private void Network_Click(object sender, RoutedEventArgs e) =>
        new NetworkWindow(_vm) { Owner = this }.ShowDialog();

    private void Check_Click(object sender, RoutedEventArgs e) =>
        new CheckWindow(_vm, firstRun: false) { Owner = this }.ShowDialog();

    private void Details_Click(object sender, RoutedEventArgs e) => _vm.ShowDetails = !_vm.ShowDetails;

    private async void SpeedTest_Click(object sender, RoutedEventArgs e) => await _vm.RunSpeedTestAsync();

    private void ApplySpeed_Click(object sender, RoutedEventArgs e) => _vm.ApplySpeed();

    private void Support_Click(object sender, RoutedEventArgs e) => new SupportWindow { Owner = this }.ShowDialog();

    private void CloseBanner_Click(object sender, RoutedEventArgs e) => _vm.HideBanner();

    private void DeviceList_MouseDoubleClick(object sender, MouseButtonEventArgs e)
    {
        if (_vm.CanConnect) _vm.Connect();
    }

    private void CustomBox_KeyDown(object sender, KeyEventArgs e)
    {
        // Enter — применить значение сразу.
        if (e.Key == Key.Enter && sender is System.Windows.Controls.TextBox tb)
            tb.GetBindingExpression(System.Windows.Controls.TextBox.TextProperty)?.UpdateSource();
    }

    private void Digits_PreviewTextInput(object sender, TextCompositionEventArgs e) =>
        e.Handled = !e.Text.All(char.IsDigit);
}
