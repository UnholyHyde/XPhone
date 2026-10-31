using System.Windows;
using System.Windows.Interop;
using System.Windows.Media.Animation;
using XPhone.Core;
using XPhone.Diagnostics;

namespace XPhone.UI;

public partial class CheckWindow : Window
{
    private readonly MainViewModel _vm;
    private bool _running;

    public CheckWindow(MainViewModel vm, bool firstRun)
    {
        InitializeComponent();
        _vm = vm;
        if (firstRun)
        {
            HeadTitle.Text = "Добро пожаловать в XPhone";
            HeadText.Text = "Проверим, что на телефоне всё включено. Это займёт несколько секунд.";
        }
        SourceInitialized += (_, _) => Win32.DarkTitleBar(new WindowInteropHelper(this).Handle);
        SpinAngle.BeginAnimation(System.Windows.Media.RotateTransform.AngleProperty,
            new DoubleAnimation(0, 360, TimeSpan.FromSeconds(1.2)) { RepeatBehavior = RepeatBehavior.Forever });
        Loaded += async (_, _) => await RunAsync();
    }

    private async Task RunAsync()
    {
        if (_running) return;
        _running = true;
        AgainButton.IsEnabled = false;
        Progress.Visibility = Visibility.Visible;
        ResultsScroll.Visibility = Visibility.Collapsed;
        try
        {
            var items = await PhoneCheck.RunAsync(_vm, text => Dispatcher.BeginInvoke(() => ProgressText.Text = text));
            Results.ItemsSource = items;
        }
        catch (Exception ex)
        {
            Results.ItemsSource = new[]
            {
                new CheckItem(CheckStatus.Warn, "Проверка не удалась", "Попробуйте ещё раз. Подробности: " + ex.Message),
            };
        }
        finally
        {
            Progress.Visibility = Visibility.Collapsed;
            ResultsScroll.Visibility = Visibility.Visible;
            AgainButton.IsEnabled = true;
            _running = false;
        }
    }

    private async void Again_Click(object sender, RoutedEventArgs e) => await RunAsync();

    private void Close_Click(object sender, RoutedEventArgs e) => Close();
}
