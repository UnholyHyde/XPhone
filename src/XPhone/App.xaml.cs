using System.Diagnostics;
using System.Windows;
using XPhone.Core;
using XPhone.UI;

namespace XPhone;

public partial class App : Application
{
    private Mutex? _single;

    protected override void OnStartup(StartupEventArgs e)
    {
        // Только одна копия XPhone: вторая просто показывает первую.
        _single = new Mutex(true, @"Local\XPhone.SingleInstance", out bool first);
        if (!first)
        {
            var me = Process.GetCurrentProcess();
            foreach (var p in Process.GetProcessesByName(me.ProcessName).Where(p => p.Id != me.Id))
            {
                var h = p.MainWindowHandle;
                if (h == IntPtr.Zero) continue;
                if (Win32.IsIconic(h)) Win32.ShowWindow(h, 9);
                Win32.SetForegroundWindow(h);
            }
            Shutdown();
            return;
        }

        DispatcherUnhandledException += (_, ex) =>
        {
            ex.Handled = true;
            MessageBox.Show("Что-то пошло не так, но XPhone продолжает работать.\n\nПодробности: " + ex.Exception.Message,
                "XPhone", MessageBoxButton.OK, MessageBoxImage.Warning);
        };

        base.OnStartup(e);
        MainWindow = new MainWindow();
        MainWindow.Show();
    }

    protected override void OnExit(ExitEventArgs e)
    {
        try { _single?.ReleaseMutex(); } catch { }
        base.OnExit(e);
    }
}
