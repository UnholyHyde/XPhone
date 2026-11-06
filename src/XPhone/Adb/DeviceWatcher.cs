using System.Collections.Concurrent;

namespace XPhone.Adb;

/// <summary>
/// Следит за телефонами: по USB — сразу (adb-сервер сам сообщает об изменениях),
/// по Wi-Fi — ищет сопряжённые телефоны в сети и подключается к ним.
/// </summary>
public sealed class DeviceWatcher : IDisposable
{
    private readonly AdbClient _adb;
    private readonly ConcurrentDictionary<string, PhoneDevice> _infoCache = new();
    private readonly SemaphoreSlim _publishLock = new(1, 1);
    private readonly SemaphoreSlim _wifiLock = new(1, 1);
    private CancellationTokenSource? _cts;
    private List<PhoneDevice> _raw = new();
    private readonly ConcurrentDictionary<string, byte> _networkTargets = new();
    public void SetNetworkTargets(IEnumerable<string> targets)
    {
        _networkTargets.Clear();
        foreach (var target in targets.Take(8)) if (NetworkEndpoint.TryParse(target, out var e)) _networkTargets[e.Address] = 0;
    }

    private DateTime _lastWifiScan = DateTime.MinValue;

    /// <summary>Новый список телефонов (вызывается не из потока окна).</summary>
    public event Action<IReadOnlyList<PhoneDevice>>? Changed;
    /// <summary>adb-сервер не запускается.</summary>
    public event Action<string>? Problem;

    public IReadOnlyList<PhoneDevice> Current { get; private set; } = Array.Empty<PhoneDevice>();
    /// <summary>Пока идёт трансляция, сеть не сканируем — меньше нагрузки.</summary>
    public bool Paused { get; set; }
    /// <summary>Искать ли телефон по Wi-Fi в фоне (только если нужного телефона сейчас нет).</summary>
    public Func<bool>? NeedWifiScan { get; set; }

    public DeviceWatcher(AdbClient adb) => _adb = adb;

    public void Start()
    {
        _cts = new CancellationTokenSource();
        var ct = _cts.Token;
        _ = Task.Run(() => TrackLoopAsync(ct));
        _ = Task.Run(() => WifiLoopAsync(ct));
    }

    public void Dispose() => _cts?.Cancel();

    private async Task TrackLoopAsync(CancellationToken ct)
    {
        int fails = 0;
        while (!ct.IsCancellationRequested)
        {
            try
            {
                if (!await _adb.EnsureServerAsync())
                {
                    if (++fails == 2) Problem?.Invoke("Не удалось запустить adb (служба связи с телефоном).");
                    await Task.Delay(3000, ct);
                    continue;
                }
                fails = 0;
                await _adb.TrackDevicesAsync(list => { _raw = list; _ = PublishAsync(); }, ct);
            }
            catch (OperationCanceledException) { return; }
            catch
            {
                // Сервер adb перезапустился или закрылся — подождём и переподключимся.
                try { await Task.Delay(1500, ct); } catch { return; }
            }
        }
    }

    private async Task WifiLoopAsync(CancellationToken ct)
    {
        try
        {
            await Task.Delay(1500, ct);
            await ScanWifiAsync(ct);
            while (!ct.IsCancellationRequested)
            {
                await Task.Delay(5000, ct);
                bool need = !Paused && (NeedWifiScan?.Invoke() ?? false);
                if (need && DateTime.UtcNow - _lastWifiScan > TimeSpan.FromSeconds(5))
                    await ScanWifiAsync(ct);
            }
        }
        catch (OperationCanceledException) { }
    }

    /// <summary>Найти телефоны с «Отладкой по Wi-Fi» и подключиться к сопряжённым.</summary>
    public async Task<List<MdnsService>> ScanWifiAsync(CancellationToken ct = default)
    {
        await _wifiLock.WaitAsync(ct);
        try
        {
            _lastWifiScan = DateTime.UtcNow;
            if (!await _adb.EnsureServerAsync()) return new();
            var services = await _adb.MdnsServicesAsync(ct);
            var known = _raw.Select(d => d.Serial).ToList();
            var knownIps = Current.Where(d => d.IsNetwork && d.IpAddress != null)
                                  .Select(d => d.IpAddress!).ToHashSet();
            foreach (var s in services.Where(x => x.IsConnect))
            {
                bool already = known.Any(k => k.StartsWith(s.Name, StringComparison.OrdinalIgnoreCase)
                                              || k == s.Address
                                              || k.StartsWith(s.Ip + ":", StringComparison.Ordinal))
                               || knownIps.Contains(s.Ip);
                if (already) continue;
                // Подключится, только если этот компьютер уже сопряжён с телефоном.
                await _adb.ConnectAsync(s.Address);
            }
            // Reconnect only addresses the user saved; do not scan a subnet.
            foreach (var target in _networkTargets.Keys)
            {
                if (known.Contains(target) && Current.Any(d => d.Serial == target && d.IsReady)) continue;
                if (!NetworkEndpoint.TryParse(target, out var endpoint) || Core.NetCheck.VpnOnRouteTo(endpoint.Host) != null) continue;
                try
                {
                    using var client = new System.Net.Sockets.TcpClient();
                    using var timeout = CancellationTokenSource.CreateLinkedTokenSource(ct);
                    timeout.CancelAfter(400);
                    await client.ConnectAsync(endpoint.Host, endpoint.Port, timeout.Token);
                    if (_raw.Any(d => d.Serial == endpoint.Address && !d.IsReady)) await _adb.DisconnectAsync(endpoint.Address);
                    await _adb.ConnectAsync(endpoint.Address);
                }
                catch (OperationCanceledException) when (!ct.IsCancellationRequested) { }
                catch (System.Net.Sockets.SocketException) { }
            }
            return services;
        }
        catch (OperationCanceledException) { return new(); }
        catch { return new(); }
        finally { _wifiLock.Release(); }
    }

    /// <summary>Перечитать всё заново (кнопка «Обновить»).</summary>
    public async Task RefreshAsync()
    {
        _infoCache.Clear();
        try
        {
            if (await _adb.EnsureServerAsync()) _raw = await _adb.ListDevicesAsync();
        }
        catch { }
        await PublishAsync();
        await ScanWifiAsync();
    }

    private async Task PublishAsync()
    {
        await _publishLock.WaitAsync();
        try
        {
            var raw = _raw;
            var result = new List<PhoneDevice>();
            foreach (var d in raw)
            {
                if (d.IsReady)
                {
                    if (!_infoCache.TryGetValue(d.Serial, out var info))
                    {
                        await _adb.FillInfoAsync(d);
                        if (d.HardwareSerial != null) _infoCache[d.Serial] = d;
                        info = d;
                        // Игровой панели на телефоне нужен Shizuku — запустим, если он стоит и спит.
                        var dev = d;
                        _ = Task.Run(() => _adb.EnsureShizukuAsync(dev));
                    }
                    else
                    {
                        CopyInfo(info, d);
                    }
                }
                result.Add(d);
            }
            // Удаляем из кэша отключённые.
            foreach (var k in _infoCache.Keys.Where(k => raw.All(r => r.Serial != k)).ToList())
                _infoCache.TryRemove(k, out _);

            // Один телефон по Wi-Fi может быть виден дважды (по имени mDNS и по ip:порт) — оставляем одно.
            var dedup = new List<PhoneDevice>();
            foreach (var d in result)
            {
                if (d.IsNetwork && d.HardwareSerial != null &&
                    dedup.Any(x => x.Transport == d.Transport && x.HardwareSerial == d.HardwareSerial))
                    continue;
                dedup.Add(d);
            }
            // USB сначала.
            dedup = dedup.OrderBy(d => d.Transport).ThenBy(d => d.DisplayName).ToList();
            Current = dedup;
            Changed?.Invoke(dedup);
        }
        finally { _publishLock.Release(); }
    }

    private static void CopyInfo(PhoneDevice from, PhoneDevice to)
    {
        to.HardwareSerial = from.HardwareSerial;
        to.Name = from.Name;
        if (from.Model.Length > 0) to.Model = from.Model;
        to.Manufacturer = from.Manufacturer;
        to.Sdk = from.Sdk;
        to.ScreenShort = from.ScreenShort;
        to.ScreenLong = from.ScreenLong;
        to.IpAddress = from.IpAddress;
        to.Addresses = from.Addresses;
        if (to.IsNetwork) to.Transport = from.Transport;
    }
}
