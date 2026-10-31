using System.Diagnostics;
using System.Globalization;
using System.Net.NetworkInformation;
using System.Net.Sockets;
using System.Text.RegularExpressions;
using XPhone.Core;

namespace XPhone.Adb;

/// <summary>Что телефон сам говорит о своём Wi-Fi (cmd wifi status).</summary>
public sealed record PhoneWifiInfo(int FrequencyMhz, int Rssi, int TxLinkMbps)
{
    public bool Is24 => FrequencyMhz is > 0 and < 3000;
    public string Band => FrequencyMhz switch { 0 => "?", < 3000 => "2,4 ГГц", < 5900 => "5 ГГц", _ => "6 ГГц" };
    public string SignalText => Rssi switch
    {
        0 => "неизвестен",
        >= -55 => "отличный",
        >= -67 => "хороший",
        >= -75 => "слабый",
        _ => "очень слабый",
    };
    public bool WeakSignal => Rssi is < -67 and not 0;
}

/// <summary>Результат проверки Wi-Fi между компьютером и телефоном.</summary>
public sealed record WifiSpeedResult(double Mbps, double PingMs, double JitterMs, int Lost, int Sent,
    PhoneWifiInfo? Phone, long PcLinkMbps, string PcLinkName, bool PcOnWifi, bool ViaAdb)
{
    /// <summary>Битрейт с запасом: половина измеренной скорости (Wi-Fi «плавает»), 4…40 Мбит/с.</summary>
    public int RecommendedMbps => (int)Math.Clamp(Math.Floor(Mbps * 0.5), 4, 40);

    /// <summary>0 — можно включать «Минимальную задержку», 1 — можно, но возможны рывки, 2 — лучше не включать.</summary>
    public int LowLatencyLevel =>
        Lost == 0 && PingMs <= 12 && JitterMs <= 5 ? 0 :
        Lost <= 1 && PingMs <= 30 && JitterMs <= 15 ? 1 : 2;
}

/// <summary>
/// Проверка Wi-Fi. Скорость — напрямую по сети в сторону компьютера (туда же, куда идёт трансляция):
/// телефон отдаёт 24 МБ случайных данных встроенным nc. Если не вышло — через adb (он медленнее из-за шифрования).
/// Задержка — пингами сразу после замера скорости, с разогревом: в простое Wi-Fi телефона «спит», и пинги врут.
/// </summary>
public static class WifiSpeedTest
{
    private const string RemoteFile = "/data/local/tmp/xphone_speedtest";
    private const int SizeMb = 24;

    public static string? PhoneIp(PhoneDevice d)
    {
        if (d.IpAddress != null) return d.IpAddress;
        var m = Regex.Match(d.Serial, @"^(\d+\.\d+\.\d+\.\d+):\d+$");
        return m.Success ? m.Groups[1].Value : null;
    }

    public static async Task<(WifiSpeedResult? result, string? error)> RunAsync(AdbClient adb, PhoneDevice d, Action<string> progress)
    {
        var ip = PhoneIp(d);
        if (ip == null) return (null, "Не удалось узнать адрес телефона в сети.");
        if (NetCheck.VpnOnRouteTo(ip) is { } vpn) return (null, NetCheck.VpnHint(vpn));

        progress("Готовим проверку…");
        var phoneWifi = await ReadPhoneWifiAsync(adb, d);
        var (pcName, pcMbps, pcWifi) = NetCheck.LinkTo(ip);

        try
        {
            var prep = await adb.RunAsync(20000, "-s", d.Serial, "shell",
                $"dd if=/dev/urandom of={RemoteFile} bs=1048576 count={SizeMb} 2>/dev/null; echo ok");
            if (!prep.Output.Contains("ok")) return (null, "Не получилось подготовить проверку на телефоне.");

            // ── Скорость ──
            progress("Меряем скорость…");
            bool viaAdb = false;
            double? mbps = await DirectTcpMbpsAsync(adb, d, ip);
            if (mbps == null)
            {
                viaAdb = true;
                mbps = await AdbPullMbpsAsync(adb, d);
                if (mbps == null) return (null, "Не получилось измерить скорость: телефон оборвал связь.");
            }

            // ── Задержка (радио уже «проснулось») ──
            progress("Меряем задержку…");
            var (ping, jitter, lost, sent) = await PingAsync(adb, d, ip);
            if (ping == null) return (null, "Телефон не отвечает по Wi-Fi. Проверьте, что он в той же сети.");

            return (new WifiSpeedResult(mbps.Value, ping.Value, jitter, lost, sent, phoneWifi, pcMbps, pcName, pcWifi, viaAdb), null);
        }
        finally
        {
            _ = adb.RunAsync(5000, "-s", d.Serial, "shell", "rm -f " + RemoteFile);
        }
    }

    /// <summary>Телефон слушает порт и отдаёт файл; компьютер подключается и читает до конца.</summary>
    private static async Task<double?> DirectTcpMbpsAsync(AdbClient adb, PhoneDevice d, string ip)
    {
        int port = Random.Shared.Next(40000, 50000);
        // adb shell висит, пока nc не отдаст файл — не ждём его.
        var server = adb.RunAsync(30000, "-s", d.Serial, "shell", $"nc -l -p {port} -q 1 < {RemoteFile}");
        try
        {
            TcpClient? c = null;
            for (int i = 0; i < 15 && c == null; i++)
            {
                await Task.Delay(200); // ждём, пока nc на телефоне начнёт слушать
                var attempt = new TcpClient { ReceiveBufferSize = 1 << 20 };
                try
                {
                    using var cts = new CancellationTokenSource(1500);
                    await attempt.ConnectAsync(ip, port, cts.Token);
                    c = attempt;
                }
                catch { attempt.Dispose(); }
            }
            if (c == null) return null;
            using var _client = c;

            var s = c.GetStream();
            var buf = new byte[1 << 20];
            long total = 0;
            var sw = Stopwatch.StartNew();
            using var limit = new CancellationTokenSource(25000);
            int n;
            while ((n = await s.ReadAsync(buf, limit.Token)) > 0) total += n;
            sw.Stop();
            if (total < SizeMb * 1048576L * 0.9) return null; // оборвалось — не доверяем
            return total * 8.0 / 1_000_000 / Math.Max(0.05, sw.Elapsed.TotalSeconds);
        }
        catch { return null; }
        finally { _ = server; }
    }

    private static async Task<double?> AdbPullMbpsAsync(AdbClient adb, PhoneDevice d)
    {
        var local = Path.Combine(Path.GetTempPath(), "xphone_speedtest.bin");
        try
        {
            var sw = Stopwatch.StartNew();
            var pull = await adb.RunAsync(40000, "-s", d.Serial, "pull", RemoteFile, local);
            sw.Stop();
            if (pull.TimedOut) return SizeMb * 8.0 / 40;
            if (!pull.Ok) return null;
            var m = Regex.Match(pull.Output, @"\((\d+) bytes in ([\d.]+)s\)");
            return m.Success
                ? double.Parse(m.Groups[1].Value, CultureInfo.InvariantCulture) * 8 / 1_000_000 /
                  Math.Max(0.01, double.Parse(m.Groups[2].Value, CultureInfo.InvariantCulture))
                : SizeMb * 1.048576 * 8 / Math.Max(0.01, sw.Elapsed.TotalSeconds);
        }
        finally { try { File.Delete(local); } catch { } }
    }

    private static async Task<(double? ping, double jitter, int lost, int sent)> PingAsync(AdbClient adb, PhoneDevice d, string ip)
    {
        var rtts = new List<double>();
        int sent = 30, lost = 0;
        using (var ping = new Ping())
        {
            // Разогрев: частые пинги будят Wi-Fi телефона, их не считаем.
            for (int i = 0; i < 15; i++)
            {
                try { await ping.SendPingAsync(ip, 300); } catch { }
                await Task.Delay(10);
            }
            for (int i = 0; i < sent; i++)
            {
                try
                {
                    var r = await ping.SendPingAsync(ip, 600);
                    if (r.Status == IPStatus.Success) rtts.Add(r.RoundtripTime); else lost++;
                }
                catch { lost++; }
                await Task.Delay(30);
            }
        }
        if (rtts.Count == 0)
        {
            // Телефон не отвечает на пинг — меряем через adb (чуть хуже, но показательно).
            lost = 0; sent = 10;
            for (int i = 0; i < sent; i++)
            {
                var sw = Stopwatch.StartNew();
                var r = await adb.RunAsync(3000, "-s", d.Serial, "shell", "true");
                if (r.Ok) rtts.Add(sw.Elapsed.TotalMilliseconds / 2); else lost++;
            }
            if (rtts.Count == 0) return (null, 0, lost, sent);
        }
        double jitter = rtts.Count > 1 ? rtts.Zip(rtts.Skip(1), (a, b) => Math.Abs(a - b)).Average() : 0;
        return (rtts.Average(), jitter, lost, sent);
    }

    private static async Task<PhoneWifiInfo?> ReadPhoneWifiAsync(AdbClient adb, PhoneDevice d)
    {
        var r = await adb.RunAsync(6000, "-s", d.Serial, "shell", "cmd wifi status");
        int Get(string pattern)
        {
            var m = Regex.Match(r.Output, pattern);
            return m.Success && int.TryParse(m.Groups[1].Value, out var v) ? v : 0;
        }
        int freq = Get(@"Frequency: (\d+)MHz"), rssi = Get(@"RSSI: (-?\d+)"), tx = Get(@"Tx Link speed: (\d+)Mbps");
        return freq == 0 && rssi == 0 ? null : new PhoneWifiInfo(freq, rssi, tx);
    }
}
