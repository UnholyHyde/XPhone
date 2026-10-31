using System.Diagnostics;
using System.Text;
using XPhone.Adb;
using XPhone.Core;

namespace XPhone.Scrcpy;

/// <summary>Один запуск scrcpy: процесс, его окно и журнал.</summary>
public sealed class ScrcpySession
{
    private readonly AdbClient _adb;
    private readonly StringBuilder _log = new();
    private Process? _process;
    private volatile bool _stopRequested;

    public PhoneDevice Device { get; }
    public IReadOnlyList<string> Args { get; private set; }
    public IntPtr Window { get; private set; }
    public int ProcessId { get; private set; }
    public bool StopRequested => _stopRequested;
    public bool HasExited => _process?.HasExited ?? true;
    public int ExitCode { get; private set; }

    /// <summary>Окно scrcpy появилось — картинка пошла.</summary>
    public event Action<ScrcpySession>? WindowShown;
    /// <summary>scrcpy закрылся (сам или по нашей команде).</summary>
    public event Action<ScrcpySession>? Exited;
    /// <summary>Новая строка журнала.</summary>
    public event Action<ScrcpySession, string>? LogLine;

    public ScrcpySession(AdbClient adb, PhoneDevice device, IReadOnlyList<string> args)
    {
        _adb = adb;
        Device = device;
        Args = args;
    }

    public string Log { get { lock (_log) return _log.ToString(); } }

    private void Append(string line)
    {
        lock (_log)
        {
            _log.AppendLine(line);
            if (_log.Length > 200_000) _log.Remove(0, _log.Length - 150_000);
        }
        LogLine?.Invoke(this, line);
    }

    /// <summary>
    /// Запуск. placeAt — где была картинка прошлого окна (при перезапуске из-за смены настроек):
    /// новое окно сразу открывается там же и того же размера.
    /// </summary>
    public void Start(Win32.RECT? placeAt)
    {
        if (placeAt is { } r)
        {
            // scrcpy считает место и размер окна в пикселях экрана, по картинке (без рамки).
            Args = Args.Concat(new[]
            {
                "--window-x=" + r.Left, "--window-y=" + r.Top,
                "--window-width=" + r.Width, "--window-height=" + r.Height,
            }).ToList();
        }
        var psi = new ProcessStartInfo(_adb.ScrcpyPath)
        {
            UseShellExecute = false,
            CreateNoWindow = true,
            RedirectStandardOutput = true,
            RedirectStandardError = true,
            StandardOutputEncoding = Encoding.UTF8,
            StandardErrorEncoding = Encoding.UTF8,
            WorkingDirectory = _adb.ToolsDir,
        };
        foreach (var a in Args) psi.ArgumentList.Add(a);
        psi.Environment["ADB"] = _adb.AdbPath;
        psi.Environment["SCRCPY_SERVER_PATH"] = Path.Combine(_adb.ToolsDir, "scrcpy-server");
        // Значок окна и картинка «связь потеряна» — XPhone, а не scrcpy.
        var branding = Path.Combine(AppContext.BaseDirectory, "branding");
        psi.Environment["SCRCPY_ICON_DIR"] = Directory.Exists(branding) ? branding : _adb.ToolsDir;

        Append("> scrcpy " + string.Join(" ", Args));
        var p = new Process { StartInfo = psi, EnableRaisingEvents = true };
        p.OutputDataReceived += (_, e) => { if (e.Data != null) Append(e.Data); };
        p.ErrorDataReceived += (_, e) => { if (e.Data != null) Append(e.Data); };
        p.Exited += (_, _) =>
        {
            try { ExitCode = p.ExitCode; } catch { }
            // Дочитать хвост журнала.
            Task.Delay(150).ContinueWith(_ => Exited?.Invoke(this));
        };
        p.Start();
        JobObject.Add(p);
        p.BeginOutputReadLine();
        p.BeginErrorReadLine();
        _process = p;
        ProcessId = p.Id;
        _ = WatchWindowAsync(placeAt);
    }

    private async Task WatchWindowAsync(Win32.RECT? placeAt)
    {
        for (int i = 0; i < 300 && !HasExited; i++) // до 60 секунд
        {
            var h = Win32.FindMainWindow(ProcessId);
            if (h != IntPtr.Zero)
            {
                Window = h;
                // Подстраховка (например, при другом масштабе экрана): если окно всё же открылось не там — сдвинуть.
                if (placeAt is { } r && Win32.GetClientArea(h) is { } now &&
                    (Math.Abs(now.Left - r.Left) > 3 || Math.Abs(now.Top - r.Top) > 3))
                    Win32.MoveClientTo(h, r.Left, r.Top);
                WindowShown?.Invoke(this);
                return;
            }
            await Task.Delay(200);
        }
    }

    /// <summary>Где сейчас картинка окна (для перезапуска на том же месте).</summary>
    public Win32.RECT? CurrentRect() => Win32.GetClientArea(Window);

    public void BringToFront()
    {
        if (Window == IntPtr.Zero) return;
        if (Win32.IsIconic(Window)) Win32.ShowWindow(Window, 9); // SW_RESTORE
        Win32.SetForegroundWindow(Window);
    }

    /// <summary>Закрыть аккуратно (как крестиком), а если не закрылся — завершить.</summary>
    public async Task StopAsync()
    {
        _stopRequested = true;
        var p = _process;
        if (p == null || p.HasExited) return;
        if (Window != IntPtr.Zero) Win32.PostMessage(Window, Win32.WM_CLOSE, IntPtr.Zero, IntPtr.Zero);
        // Ждём сам процесс (не конец его вывода: дочерний adb может держать вывод открытым дольше).
        var sw = Stopwatch.StartNew();
        while (!p.HasExited && sw.ElapsedMilliseconds < 2500) await Task.Delay(50);
        if (!p.HasExited) { try { p.Kill(true); } catch { } }
    }

    public void Kill()
    {
        _stopRequested = true;
        try { if (_process is { HasExited: false }) _process.Kill(true); } catch { }
    }
}
