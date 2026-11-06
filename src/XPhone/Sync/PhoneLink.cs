using System.Collections.Concurrent;
using System.Net;
using System.Net.Sockets;
using System.Text;
using XPhone.Adb;
using XPhone.Core;

namespace XPhone.Sync;

/// <summary>
/// Связь с приложением XPhone на телефоне. Идёт через adb (adb reverse): телефон подключается к
/// 127.0.0.1:47190 у себя, а попадает в XPhone на ПК. Не нужны открытые порты и брандмауэр.
/// Строки текста: телефон → ПК: HELLO, START, STOP; ПК → телефон: PC имя, STATE состояние, MSG текст.
/// </summary>
public sealed class PhoneLink : IDisposable
{
    public const int PhonePort = 47190;

    private sealed class Endpoint
    {
        public required TcpListener Listener;
        public int Port;
        public readonly HashSet<string> ReversedSerials = new();
        public readonly List<StreamWriter> Clients = new();
        public CancellationTokenSource Cts = new();
        public bool PrivacyProtocol;
        public readonly ConcurrentDictionary<string, TaskCompletionSource<bool>> PrivacyRequests = new();
    }

    private readonly AdbClient _adb;
    private readonly ConcurrentDictionary<string, Endpoint> _endpoints = new();

    /// <summary>Команда от телефона: (ключ телефона, команда).</summary>
    public event Action<string, string>? Command;
    /// <summary>Что сказать телефону сразу после подключения.</summary>
    public Func<string, string>? StateFor { get; set; }

    public PhoneLink(AdbClient adb) => _adb = adb;

    /// <summary>Подстроить связь под текущий список телефонов.</summary>
    public void Sync(IReadOnlyList<PhoneDevice> devices)
    {
        var ready = devices.Where(d => d.IsReady && d.HardwareSerial != null).ToList();
        foreach (var d in ready)
        {
            var ep = _endpoints.GetOrAdd(d.Key, _ => Open(d.Key));
            lock (ep.ReversedSerials)
            {
                if (!ep.ReversedSerials.Add(d.Serial)) continue;
            }
            var serial = d.Serial;
            _ = Task.Run(async () =>
            {
                var r = await _adb.RunAsync(8000, "-s", serial, "reverse", $"tcp:{PhonePort}", $"tcp:{ep.Port}");
                if (!r.Ok) lock (ep.ReversedSerials) ep.ReversedSerials.Remove(serial);
            });
        }
        // Пропавшие подключения — забыть (adb сам убирает reverse при отключении).
        foreach (var (key, ep) in _endpoints)
        {
            lock (ep.ReversedSerials)
                ep.ReversedSerials.RemoveWhere(s => !ready.Any(d => d.Serial == s));
            if (!ready.Any(d => d.Key == key) && _endpoints.TryRemove(key, out var gone)) Close(gone);
        }
    }

    private Endpoint Open(string key)
    {
        var l = new TcpListener(IPAddress.Loopback, 0);
        l.Start();
        var ep = new Endpoint { Listener = l, Port = ((IPEndPoint)l.LocalEndpoint).Port };
        _ = AcceptLoopAsync(key, ep);
        return ep;
    }

    private async Task AcceptLoopAsync(string key, Endpoint ep)
    {
        var ct = ep.Cts.Token;
        while (!ct.IsCancellationRequested)
        {
            TcpClient c;
            try { c = await ep.Listener.AcceptTcpClientAsync(ct); }
            catch { return; }
            _ = HandleAsync(key, ep, c, ct);
        }
    }

    private async Task HandleAsync(string key, Endpoint ep, TcpClient c, CancellationToken ct)
    {
        using var _ = c;
        var stream = c.GetStream();
        var reader = new StreamReader(stream, new UTF8Encoding(false));
        var writer = new StreamWriter(stream, new UTF8Encoding(false)) { AutoFlush = true, NewLine = "\n" };
        lock (ep.Clients) ep.Clients.Add(writer);
        try
        {
            var state = StateFor?.Invoke(key);
            lock (writer)
            {
                writer.WriteLine("PC " + Environment.MachineName);
                if (state != null) writer.WriteLine(state);
            }
            while (!ct.IsCancellationRequested)
            {
                var line = await reader.ReadLineAsync(ct);
                if (line == null) break;
                line = line.Trim();
                if (line.StartsWith("HELLO", StringComparison.Ordinal)) { ep.PrivacyProtocol = line.EndsWith(" XPHONE2", StringComparison.Ordinal); continue; }
                if (line.StartsWith("PRIVACY ", StringComparison.Ordinal)) {
                    var parts = line.Split(' ', StringSplitOptions.RemoveEmptyEntries);
                    if (parts.Length == 3 && ep.PrivacyRequests.TryRemove(parts[1], out var waiting))
                        waiting.TrySetResult(parts[2] is "protected" or "disabled");
                    continue;
                }
                if (line.Length == 0 || line == "PING") continue;
                Command?.Invoke(key, line);
            }
        }
        catch { }
        finally { lock (ep.Clients) ep.Clients.Remove(writer); }
    }

    /// <summary>New APKs acknowledge notification protection before capture starts.</summary>
    public async Task<bool> PreparePrivacyAsync(string key)
    {
        if (!_endpoints.TryGetValue(key, out var ep) || !ep.PrivacyProtocol) return true;
        var id = Guid.NewGuid().ToString("N");
        var completion = new TaskCompletionSource<bool>(TaskCreationOptions.RunContinuationsAsynchronously);
        ep.PrivacyRequests[id] = completion;
        try {
            Send(key, "PREPARE_PRIVACY " + id);
            return await completion.Task.WaitAsync(TimeSpan.FromSeconds(8));
        } catch { return false; }
        finally { ep.PrivacyRequests.TryRemove(id, out _); }
    }

    /// <summary>Сообщить телефону (всем его подключениям).</summary>
    public void Send(string key, string line)
    {
        if (!_endpoints.TryGetValue(key, out var ep)) return;
        List<StreamWriter> clients;
        lock (ep.Clients) clients = ep.Clients.ToList();
        foreach (var w in clients)
        {
            try { lock (w) w.WriteLine(line); } catch { }
        }
    }

    public void SendAll(Func<string, string> lineFor)
    {
        foreach (var key in _endpoints.Keys) Send(key, lineFor(key));
    }

    private static void Close(Endpoint ep)
    {
        try { ep.Cts.Cancel(); } catch { }
        try { ep.Listener.Stop(); } catch { }
        lock (ep.Clients)
        {
            foreach (var w in ep.Clients) try { w.Dispose(); } catch { }
            ep.Clients.Clear();
        }
    }

    public void Dispose()
    {
        foreach (var ep in _endpoints.Values) Close(ep);
        _endpoints.Clear();
    }
}
