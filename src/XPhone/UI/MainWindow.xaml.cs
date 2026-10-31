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

    private void OnLoaded(object sender, RoutedEventArgs e)
    {
        _vm.Start();
        if (!_vm.Settings.FirstRunDone)
        {
            _vm.Settings.FirstRunDone = true;
            _vm.SaveSoon();
            // Первый запуск: сразу объясняем, что включить на телефоне.
            Dispatcher.BeginInvoke(() => new CheckWindow(_vm, firstRun: true) { Owner = this }.ShowDialog(),
                System.Windows.Threading.DispatcherPriority.ApplicationIdle);
        }
    }

    private void Connect_Click(object sender, RoutedEventArgs e) => _vm.Connect();

    private async void Disconnect_Click(object sender, RoutedEventArgs e) => await _vm.DisconnectAsync();

    private async void Refresh_Click(object sender, RoutedEventArgs e) => await _vm.RefreshAsync();

    private void Pair_Click(object sender, RoutedEventArgs e) =>
        new PairWindow(_vm) { Owner = this }.ShowDialog();

    private void Check_Click(object sender, RoutedEventArgs e) =>
        new CheckWindow(_vm, firstRun: false) { Owner = this }.ShowDialog();

    private void Details_Click(object sender, RoutedEventArgs e) => _vm.ShowDetails = !_vm.ShowDetails;

    private async void SpeedTest_Click(object sender, RoutedEventArgs e) => await _vm.RunSpeedTestAsync();

    private void ApplySpeed_Click(object sender, RoutedEventArgs e) => _vm.ApplySpeed();

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
