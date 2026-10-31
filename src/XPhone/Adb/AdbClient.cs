using System.Net.Sockets;
using System.Text;
using System.Text.RegularExpressions;
using XPhone.Core;

namespace XPhone.Adb;

public sealed record MdnsService(string Name, string Type, string Address)
{
    public bool IsPairing => Type.Contains("pairing", StringComparison.OrdinalIgnoreCase);
    public bool IsConnect => Type.Contains("connect", StringComparison.OrdinalIgnoreCase);
    public string Ip => Address.Split(':')[0];
}

/// <summary>
/// Работа с adb из папки tools/. Команды — через adb.exe, список телефонов — напрямую через сокет
/// adb-сервера (127.0.0.1:5037), без лишних процессов.
/// </summary>
public sealed class AdbClient
{
    public string ToolsDir { get; }
    public string AdbPath { get; }
    public string ScrcpyPath { get; }
    /// <summary>Сервер adb запустили мы — значит, и остановим при выходе.</summary>
    public bool StartedServer { get; private set; }

    private const int Port = 5037;
    private readonly SemaphoreSlim _serverLock = new(1, 1);

    public AdbClient(string toolsDir)
    {
        ToolsDir = toolsDir;
        AdbPath = Path.Combine(toolsDir, "adb.exe");
        ScrcpyPath = Path.Combine(toolsDir, "scrcpy.exe");
    }

    public static AdbClient FromAppFolder() => new(Path.Combine(AppContext.BaseDirectory, "tools"));

    public bool ToolsPresent => File.Exists(AdbPath) && File.Exists(ScrcpyPath)
                                && File.Exists(Path.Combine(ToolsDir, "scrcpy-server"));

    public Task<ProcessResult> RunAsync(int timeoutMs, params string[] args) =>
        ProcessRunner.RunAsync(AdbPath, args, timeoutMs);

    // ── Сервер ──────────────────────────────────────────────────────────

    public async Task<bool> IsServerRunningAsync()
    {
        try
        {
            using var c = new TcpClient();
            using var cts = new CancellationTokenSource(400);
            await c.ConnectAsync("127.0.0.1", Port, cts.Token);
            return true;
        }
        catch { return false; }
    }

    public async Task<bool> EnsureServerAsync()
    {
        await _serverLock.WaitAsync();
        try
        {
            if (await IsServerRunningAsync()) return true;
            if (!File.Exists(AdbPath)) return false;
            // Без перенаправления вывода: сервер-демон иначе держит трубы открытыми.
            var psi = new System.Diagnostics.ProcessStartInfo(AdbPath, "start-server")
            {
                UseShellExecute = false,
                CreateNoWindow = true,
                WorkingDirectory = ToolsDir,
            };
            psi.Environment["ADB_MDNS_OPENSCREEN"] = "1";
            using var p = System.Diagnostics.Process.Start(psi);
            if (p != null) await p.WaitForExitAsync(new CancellationTokenSource(20000).Token).ContinueWith(_ => { });
            StartedServer = true;
            return await IsServerRunningAsync();
        }
        catch { return false; }
        finally { _serverLock.Release(); }
    }

    public void KillServerIfOurs()
    {
        if (!StartedServer) return;
        try { RunAsync(3000, "kill-server").Wait(3500); } catch { }
    }

    // ── Протокол adb-сервера ────────────────────────────────────────────

    private static async Task SendAsync(NetworkStream s, string cmd, CancellationToken ct)
    {
        var body = Encoding.UTF8.GetBytes(cmd);
        var head = Encoding.ASCII.GetBytes(body.Length.ToString("x4"));
        await s.WriteAsync(head, ct);
        await s.WriteAsync(body, ct);
    }

    private static async Task<byte[]> ReadExactAsync(NetworkStream s, int n, CancellationToken ct)
    {
        var buf = new byte[n];
        int got = 0;
        while (got < n)
        {
            int r = await s.ReadAsync(buf.AsMemory(got, n - got), ct);
            if (r == 0) throw new IOException("adb-сервер закрыл соединение");
            got += r;
        }
        return buf;
    }

    private static async Task<string> ReadBlockAsync(NetworkStream s, CancellationToken ct)
    {
        var len = Convert.ToInt32(Encoding.ASCII.GetString(await ReadExactAsync(s, 4, ct)), 16);
        return len == 0 ? "" : Encoding.UTF8.GetString(await ReadExactAsync(s, len, ct));
    }

    private static async Task ExpectOkayAsync(NetworkStream s, CancellationToken ct)
    {
        var status = Encoding.ASCII.GetString(await ReadExactAsync(s, 4, ct));
        if (status == "OKAY") return;
        var msg = status == "FAIL" ? await ReadBlockAsync(s, ct) : status;
        throw new IOException("adb: " + msg);
    }

    /// <summary>Список подключений одним запросом.</summary>
    public async Task<List<PhoneDevice>> ListDevicesAsync(CancellationToken ct = default)
    {
        using var c = new TcpClient();
        await c.ConnectAsync("127.0.0.1", Port, ct);
        var s = c.GetStream();
        await SendAsync(s, "host:devices-l", ct);
        await ExpectOkayAsync(s, ct);
        return ParseDevices(await ReadBlockAsync(s, ct));
    }

    /// <summary>Следит за подключениями: adb-сервер сам присылает новый список при каждом изменении.</summary>
    public async Task TrackDevicesAsync(Action<List<PhoneDevice>> onChange, CancellationToken ct)
    {
        using var c = new TcpClient();
        await c.ConnectAsync("127.0.0.1", Port, ct);
        var s = c.GetStream();
        await SendAsync(s, "host:track-devices-l", ct);
        await ExpectOkayAsync(s, ct);
        while (!ct.IsCancellationRequested)
            onChange(ParseDevices(await ReadBlockAsync(s, ct)));
    }

    public static List<PhoneDevice> ParseDevices(string text)
    {
        var list = new List<PhoneDevice>();
        foreach (var raw in text.Split('\n'))
        {
            var line = raw.Trim();
            if (line.Length == 0 || line.StartsWith("List of", StringComparison.Ordinal)) continue;
            var parts = Regex.Split(line, @"\s+");
            if (parts.Length < 2) continue;
            var serial = parts[0];
            var state = parts[1];
            string model = "";
            bool usbField = false;
            foreach (var p in parts.Skip(2))
            {
                if (p.StartsWith("model:", StringComparison.Ordinal)) model = p[6..].Replace('_', ' ');
                if (p.StartsWith("usb:", StringComparison.Ordinal)) usbField = true;
            }
            var transport = !usbField && PhoneDevice.LooksLikeWifi(serial) ? Transport.Wifi : Transport.Usb;
            list.Add(new PhoneDevice { Serial = serial, State = state, Transport = transport, Model = model });
        }
        return list;
    }

    // ── Сведения о телефоне ─────────────────────────────────────────────

    public async Task FillInfoAsync(PhoneDevice d, CancellationToken ct = default)
    {
        const string sep = "@@XP@@";
        var cmd = string.Join($"; echo {sep}; ", new[]
        {
            "getprop ro.serialno",
            "settings get global device_name",
            "getprop ro.product.model",
            "getprop ro.build.version.sdk",
            "getprop ro.product.manufacturer",
            "wm size",
            "ip -o -4 addr show wlan0",
        });
        var r = await ProcessRunner.RunAsync(AdbPath, new[] { "-s", d.Serial, "shell", cmd }, 8000, ct: ct);
        if (r.TimedOut || string.IsNullOrEmpty(r.Output)) return;
        var f = r.Output.Replace("\r", "").Split(sep).Select(x => x.Trim()).ToArray();
        string Get(int i) => i < f.Length && f[i] != "null" ? f[i] : "";

        if (Get(0).Length > 0) d.HardwareSerial = Get(0);
        if (Get(1).Length > 0) d.Name = Get(1);
        if (Get(2).Length > 0) d.Model = Get(2);
        if (int.TryParse(Get(3), out var sdk)) d.Sdk = sdk;
        if (Get(4).Length > 0) d.Manufacturer = Get(4);

        // «Physical size: 1440x3120» и, возможно, «Override size: 1080x2340» — берём последнюю.
        var sizes = Regex.Matches(Get(5), @"(\d+)x(\d+)");
        if (sizes.Count > 0)
        {
            var m = sizes[^1];
            int a = int.Parse(m.Groups[1].Value), b = int.Parse(m.Groups[2].Value);
            d.ScreenShort = Math.Min(a, b);
            d.ScreenLong = Math.Max(a, b);
        }
        var ip = Regex.Match(Get(6), @"inet (\d+\.\d+\.\d+\.\d+)");
        if (ip.Success) d.IpAddress = ip.Groups[1].Value;
    }

    // ── Wi-Fi ───────────────────────────────────────────────────────────

    /// <summary>Телефоны с включённой «Отладкой по Wi-Fi» в локальной сети (через mDNS).</summary>
    public async Task<List<MdnsService>> MdnsServicesAsync(CancellationToken ct = default)
    {
        var r = await ProcessRunner.RunAsync(AdbPath, new[] { "mdns", "services" }, 6000, ct: ct);
        var list = new List<MdnsService>();
        foreach (Match m in Regex.Matches(r.Output, @"^\s*(\S+)\s+(_adb[\w.-]*)\s+(\d+\.\d+\.\d+\.\d+:\d+)", RegexOptions.Multiline))
            list.Add(new MdnsService(m.Groups[1].Value, m.Groups[2].Value.TrimEnd('.'), m.Groups[3].Value));
        return list;
    }

    public async Task<(bool ok, string message)> ConnectAsync(string address)
    {
        var r = await RunAsync(8000, "connect", address);
        var o = r.Output.ToLowerInvariant();
        bool ok = o.Contains("connected to") && !o.Contains("cannot") && !o.Contains("failed");
        return (ok, r.Output);
    }

    public Task<ProcessResult> DisconnectAsync(string address) => RunAsync(5000, "disconnect", address);

    /// <summary>Сопряжение по коду из «Отладки по Wi-Fi» (один раз на компьютер).</summary>
    public async Task<(bool ok, string message)> PairAsync(string address, string code)
    {
        var r = await RunAsync(20000, "pair", address, code);
        var o = r.Output.ToLowerInvariant();
        bool ok = o.Contains("successfully paired");
        return (ok, r.TimedOut ? "Телефон не ответил вовремя." : r.Output);
    }
}
