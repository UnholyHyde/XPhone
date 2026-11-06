using System.Collections.ObjectModel;
using System.Windows;
using System.Windows.Threading;
using XPhone.Adb;
using XPhone.Audio;
using XPhone.Features;
using XPhone.Scrcpy;
using XPhone.Settings;

namespace XPhone.UI;

public enum ConnState { Idle, Connecting, Connected }

/// <summary>Строка в списке телефонов.</summary>
public sealed class DeviceItem
{
    public required PhoneDevice Device { get; init; }
    public required bool IsRemembered { get; init; }
    public string Serial => Device.Serial;
    public string Title => Device.DisplayName;
    public string Icon => Device.Transport == Transport.Usb ? "usb" : Device.Transport == Transport.Wifi ? "wifi" : "cable";
    public bool IsReady => Device.IsReady;
    public string Subtitle
    {
        get
        {
            if (!Device.IsReady) return $"{Device.TransportText} · {Device.StateText}";
            var model = Device.Model.Length > 0 && Device.Model != Device.DisplayName ? " · " + Device.Model : "";
            var ip = Device.IsNetwork && Device.IpAddress != null ? " · " + Device.IpAddress : "";
            return Device.TransportText + model + ip;
        }
    }
}

public sealed class MainViewModel : Observable, IDisposable
{
    public AdbClient Adb { get; }
    public DeviceWatcher Watcher { get; }
    public AppSettings Settings { get; }
    public ScrcpySession? Session { get; private set; }

    private readonly Dispatcher _ui;
    private readonly DispatcherTimer _saveTimer;
    private readonly DispatcherTimer _restartTimer;
    private readonly DispatcherTimer _audioTimer;
    private bool _suppressAuto;          // пользователь сам нажал «Отключить»
    private bool _windowShown;
    private bool _warnedThisSession;
    private int _autoRetries;
    private DateTime _lastAudio = DateTime.MinValue;
    private DateTime _connectedAt;
    private bool _loadingProfile;

    public ObservableCollection<DeviceItem> Devices { get; } = new();

    public MainViewModel()
    {
        _ui = Application.Current.Dispatcher;
        Settings = SettingsStore.Load();
        Settings.NetworkAddresses ??= new();
        Adb = AdbClient.FromAppFolder();
        Watcher = new DeviceWatcher(Adb) { NeedWifiScan = NeedWifiScan };
        Watcher.SetNetworkTargets(Settings.NetworkAddresses);
        Watcher.Changed += list => _ui.BeginInvoke(() => OnDevices(list));
        Watcher.Problem += msg => _ui.BeginInvoke(() =>
            ShowBanner(new HumanMessage(msg, "Перезапустите XPhone. Если не помогает — переустановите программу: не хватает файлов в папке tools."), null));

        _saveTimer = new DispatcherTimer { Interval = TimeSpan.FromMilliseconds(500) };
        _saveTimer.Tick += (_, _) => { _saveTimer.Stop(); SettingsStore.Save(Settings); };
        _restartTimer = new DispatcherTimer { Interval = TimeSpan.FromMilliseconds(700) };
        _restartTimer.Tick += async (_, _) => { _restartTimer.Stop(); await RestartAsync(); };
        _audioTimer = new DispatcherTimer { Interval = TimeSpan.FromMilliseconds(600) };
        _audioTimer.Tick += (_, _) => PollAudio();

        // Связь с приложением XPhone на телефоне: оно может само начать и остановить трансляцию.
        Link = new Sync.PhoneLink(Adb) { StateFor = StateLine };
        Link.Command += (key, cmd) => _ui.BeginInvoke(() => OnPhoneCommand(key, cmd));
    }

    public Sync.PhoneLink Link { get; }

    /// <summary>Что сказать телефону о трансляции.</summary>
    private string StateLine(string key)
    {
        var s = Session;
        if (s == null || s.Device.Key != key) return "STATE idle";
        return State == ConnState.Connected ? "STATE streaming" : State == ConnState.Connecting ? "STATE connecting" : "STATE idle";
    }

    private void OnPhoneCommand(string key, string cmd)
    {
        if (cmd == "START")
        {
            if (Session != null && Session.Device.Key == key && State != ConnState.Idle) return;
            var item = Devices.Where(x => x.IsReady && x.Device.Key == key).OrderBy(x => x.Device.Transport).FirstOrDefault();
            if (item == null) return;
            Selected = item;
            _suppressAuto = false;
            Connect();
        }
        else if (cmd == "STOP")
        {
            if (Session != null && Session.Device.Key == key) _ = DisconnectAsync();
        }
    }

    public void Start()
    {
        if (!Adb.ToolsPresent)
        {
            ShowBanner(new HumanMessage("Не хватает файлов программы",
                "В папке XPhone нет папки tools со scrcpy и adb. Распакуйте XPhone заново целиком."), null);
            return;
        }
        Watcher.Start();
    }

    private bool NeedWifiScan()
    {
        // Ищем по сети, только если нет ни одного готового телефона по Wi-Fi.
        return !Watcher.Current.Any(d => d.IsReady && d.IsNetwork);
    }

    // ── Список телефонов ────────────────────────────────────────────────

    private DeviceItem? _selected;
    public DeviceItem? Selected
    {
        get => _selected;
        set
        {
            if (!Set(ref _selected, value)) return;
            _speedError = null;
            RaiseProfile();
            RaiseState();
            RaiseSpeed();
        }
    }

    public bool NoDevices => Devices.Count == 0;

    private bool _isRefreshing;
    public bool IsRefreshing { get => _isRefreshing; private set => Set(ref _isRefreshing, value); }

    private void OnDevices(IReadOnlyList<PhoneDevice> list)
    {
        var selSerial = Selected?.Serial;
        var selKey = Selected?.Device.HardwareSerial;
        Devices.Clear();
        foreach (var d in list)
            Devices.Add(new DeviceItem
            {
                Device = d,
                IsRemembered = Settings.RememberPhone && d.HardwareSerial != null && d.HardwareSerial == Settings.RememberedPhone,
            });

        // Тот же телефон остаётся выбранным; иначе — запомненный; иначе — первый готовый.
        DeviceItem? pick =
            Devices.FirstOrDefault(x => x.Serial == selSerial)
            ?? (selKey != null ? Devices.FirstOrDefault(x => x.Device.HardwareSerial == selKey && x.IsReady) : null)
            ?? Devices.Where(x => x.IsRemembered && x.IsReady).OrderBy(x => x.Device.Transport).FirstOrDefault()
            ?? Devices.FirstOrDefault(x => x.IsReady)
            ?? Devices.FirstOrDefault();
        _selected = null;
        Selected = pick;
        Raise(nameof(NoDevices));
        Link.Sync(list);

        // Запомненного телефона нет совсем — значит, он «ушёл», и при возвращении можно подключаться снова.
        if (Settings.RememberedPhone != null && Devices.All(x => x.Device.HardwareSerial != Settings.RememberedPhone))
            _suppressAuto = false;

        // Подсказка, если телефон ждёт разрешения.
        if (Selected is { IsReady: false } s && State == ConnState.Idle && (s.Device.State is "unauthorized" or "authorizing"))
            ShowBanner(new HumanMessage("Подтвердите отладку на телефоне",
                "Разблокируйте телефон и нажмите «Разрешить» в окне «Разрешить отладку?». Поставьте галочку «Всегда разрешать с этого компьютера».", false), null);
        else if (BannerTitle == "Подтвердите отладку на телефоне")
            HideBanner();

        TryAutoConnect();
    }

    public async Task RefreshAsync()
    {
        if (IsRefreshing) return;
        IsRefreshing = true;
        try { await Watcher.RefreshAsync(); }
        finally { IsRefreshing = false; }
    }

    private void TryAutoConnect()
    {
        if (!Settings.AutoConnect || !Settings.RememberPhone || Settings.RememberedPhone == null) return;
        if (Session != null || _suppressAuto || _autoRetries >= 3) return;
        var item = Devices.Where(x => x.IsReady && x.Device.HardwareSerial == Settings.RememberedPhone)
                          .OrderBy(x => x.Device.Transport).FirstOrDefault();
        if (item == null) return;
        Selected = item;
        _autoRetries++;
        Connect();
    }

    // ── Подключение ─────────────────────────────────────────────────────

    private ConnState _state = ConnState.Idle;
    public ConnState State
    {
        get => _state;
        private set
        {
            if (!Set(ref _state, value)) return;
            RaiseState();
            Link.SendAll(StateLine);
        }
    }

    public bool IsConnected => State == ConnState.Connected;
    public bool IsConnecting => State == ConnState.Connecting;
    public bool IsIdle => State == ConnState.Idle;
    public bool SelectedIsSession => Session != null && Selected != null && Session.Device.Serial == Selected.Serial;
    public bool CanConnect => Selected is { IsReady: true } && State != ConnState.Connecting;
    public bool CanDisconnect => Session != null;

    public string ConnectText => State switch
    {
        ConnState.Connecting => "Подключение…",
        ConnState.Connected when SelectedIsSession => "Показать окно",
        ConnState.Connected => "Переключиться",
        _ => "Подключить",
    };
    public string ConnectIcon => State == ConnState.Connected && SelectedIsSession ? "monitor" : "cast";

    public string StatusTitle => State switch
    {
        ConnState.Connected => "Подключено",
        ConnState.Connecting => "Подключение…",
        _ => "Не подключено",
    };

    public string StatusDetail
    {
        get
        {
            if (Session != null && State != ConnState.Idle)
            {
                var d = Session.Device;
                var p = Settings.ProfileFor(d.HardwareSerial);
                return $"{d.DisplayName} · {d.TransportText} · {ScrcpyArgs.Describe(d, p)}";
            }
            if (NoDevices) return "Подключите телефон кабелем или по Wi-Fi";
            return Selected is { IsReady: true } ? "Нажмите «Подключить»" : "Телефон не готов";
        }
    }

    private void RaiseState() => Raise(nameof(IsConnected), nameof(IsConnecting), nameof(IsIdle), nameof(CanConnect),
        nameof(CanDisconnect), nameof(ConnectText), nameof(ConnectIcon), nameof(StatusTitle), nameof(StatusDetail),
        nameof(SelectedIsSession), nameof(AudioLive), nameof(ShowAudioLive), nameof(CanRunSpeed), nameof(SpeedText), nameof(HasSpeedText));

    /// <summary>Кнопка «Подключить».</summary>
    public async void Connect()
    {
        if (State == ConnState.Connected && SelectedIsSession) { Session!.BringToFront(); return; }
        var item = Selected;
        if (item == null) return;
        if (!item.IsReady)
        {
            ShowBanner(new HumanMessage("Телефон ещё не готов",
                "Разблокируйте телефон и разрешите отладку. Затем нажмите «Подключить»."), null);
            return;
        }
        if (!ExtensionPoints.License.IsAllowed(out var reason))
        {
            ShowBanner(new HumanMessage("Нужен ключ", reason ?? "Введите лицензионный ключ."), null);
            return;
        }
        _suppressAuto = false;
        if (Session != null) await StopSessionAsync();
        StartSession(item.Device, null);
    }

    private async void StartSession(PhoneDevice device, Core.Win32.RECT? placeAt)
    {
        var profile = Settings.ProfileFor(device.HardwareSerial);
        var s = new ScrcpySession(Adb, device, ScrcpyArgs.Build(device, profile));
        s.WindowShown += x => _ui.BeginInvoke(() => OnWindowShown(x));
        s.Exited += x => _ui.BeginInvoke(() => OnExited(x));
        s.LogLine += (x, line) =>
        {
            var w = HumanErrors.ForWarning(line, x.Device);
            if (w != null) _ui.BeginInvoke(() =>
            {
                if (x != Session || _warnedThisSession) return;
                _warnedThisSession = true;
                ShowBanner(w, x.Log);
            });
        };
        Session = s;
        _windowShown = false;
        _warnedThisSession = false;
        Watcher.Paused = true;
        if (placeAt == null) HideBanner();
        State = ConnState.Connecting;
        var privacyReady = await Link.PreparePrivacyAsync(device.Key);
        if (Session != s || s.StopRequested) return;
        if (!privacyReady) {
            Session = null; State = ConnState.Idle; Watcher.Paused = false;
            ShowBanner(new HumanMessage("Защита уведомлений не подтверждена",
                "Проверьте Shizuku и настройки защиты в XPhone на телефоне, затем повторите подключение."), "Телефон не подтвердил подготовку уведомлений перед захватом экрана.");
            return;
        }
        try { s.Start(placeAt); }
        catch (Exception ex)
        {
            Session = null;
            State = ConnState.Idle;
            Watcher.Paused = false;
            ShowBanner(new HumanMessage("Не удалось запустить scrcpy",
                "Проверьте, что папка tools рядом с XPhone.exe на месте и антивирус её не трогал."), ex.ToString());
        }
    }

    private void OnWindowShown(ScrcpySession s)
    {
        if (s != Session) return;
        _windowShown = true;
        _autoRetries = 0;
        _connectedAt = DateTime.UtcNow;
        _lastAudio = DateTime.MinValue;
        State = ConnState.Connected;
        if (Settings.RememberPhone && s.Device.HardwareSerial != null)
        {
            Settings.RememberedPhone = s.Device.HardwareSerial;
            Settings.RememberedPhoneName = s.Device.DisplayName;
            SaveSoon();
        }
        _audioTimer.Start();
        RaiseState();
    }

    private void OnExited(ScrcpySession s)
    {
        if (s != Session) return;
        Session = null;
        _audioTimer.Stop();
        Watcher.Paused = false;
        State = ConnState.Idle;
        if (s.StopRequested) return;

        // Человек сам закрыл окно трансляции (крестиком) — это не ошибка, просто отключаемся.
        if (_windowShown && s.ExitCode == 0 && !s.Log.Contains("disconnect", StringComparison.OrdinalIgnoreCase))
        {
            _suppressAuto = true;
            HideBanner();
            return;
        }

        var msg = HumanErrors.ForExit(s.Log, s.Device, _windowShown);
        ShowBanner(msg, s.Log);
        Link.Send(s.Device.Key, "MSG " + msg.Title);
        // Работало и оборвалось — при «Подключаться автоматически» вернёмся, когда телефон снова появится.
        if (_windowShown) _autoRetries = 0;
        TryAutoConnect();
    }

    /// <summary>Кнопка «Отключить».</summary>
    public async Task DisconnectAsync()
    {
        _suppressAuto = true;
        await StopSessionAsync();
        HideBanner();
    }

    private async Task StopSessionAsync()
    {
        var s = Session;
        if (s == null) return;
        _restartTimer.Stop();
        await s.StopAsync();
        if (Session == s)
        {
            Session = null;
            _audioTimer.Stop();
            Watcher.Paused = false;
            State = ConnState.Idle;
        }
    }

    /// <summary>Настройки поменялись во время трансляции — перезапускаем scrcpy на том же месте экрана.</summary>
    private async Task RestartAsync()
    {
        var s = Session;
        if (s == null || State != ConnState.Connected) return;
        var rect = s.CurrentRect();
        var device = s.Device;
        // Старое окно больше не «текущее»: его закрытие не должно считаться концом трансляции.
        Session = null;
        _audioTimer.Stop();
        await s.StopAsync();
        if (Session != null) return; // пока закрывали, уже подключили заново
        StartSession(device, rect);
    }

    // ── Звук ────────────────────────────────────────────────────────────

    public bool ShowAudioLive => State == ConnState.Connected && Session != null &&
                                 Settings.ProfileFor(Session.Device.HardwareSerial).Audio != AudioMode.Off;

    public bool AudioFlowing => DateTime.UtcNow - _lastAudio < TimeSpan.FromSeconds(2);

    public string AudioLive => AudioFlowing ? "Звук идёт" : "Тишина";

    private void PollAudio()
    {
        var s = Session;
        if (s == null || State != ConnState.Connected) return;
        var peak = AudioMeter.PeakForProcess(s.ProcessId);
        if (peak is > 0.002f) _lastAudio = DateTime.UtcNow;
        Raise(nameof(AudioLive), nameof(AudioFlowing), nameof(ShowAudioLive));
    }

    public TimeSpan ConnectedFor => State == ConnState.Connected ? DateTime.UtcNow - _connectedAt : TimeSpan.Zero;

    // ── Сообщения ───────────────────────────────────────────────────────

    private string _bannerTitle = "", _bannerHint = "", _bannerLog = "";
    private bool _bannerVisible, _bannerIsError, _showDetails;
    public string BannerTitle { get => _bannerTitle; private set => Set(ref _bannerTitle, value); }
    public string BannerHint { get => _bannerHint; private set => Set(ref _bannerHint, value); }
    public string BannerLog { get => _bannerLog; private set { Set(ref _bannerLog, value); Raise(nameof(HasLog)); } }
    public bool HasLog => !string.IsNullOrWhiteSpace(BannerLog);
    public bool BannerVisible { get => _bannerVisible; private set => Set(ref _bannerVisible, value); }
    public bool BannerIsError { get => _bannerIsError; private set => Set(ref _bannerIsError, value); }
    public bool ShowDetails { get => _showDetails; set => Set(ref _showDetails, value); }

    public void ShowBanner(HumanMessage m, string? log)
    {
        BannerTitle = m.Title;
        BannerHint = m.Hint;
        BannerIsError = m.IsError;
        BannerLog = log ?? "";
        ShowDetails = false;
        BannerVisible = true;
    }

    public void HideBanner() => BannerVisible = false;

    // ── Настройки текущего телефона ─────────────────────────────────────

    /// <summary>Чьи настройки показываем: выбранного телефона, а если его нет — запомненного.</summary>
    private string? ProfileKey => Selected?.Device.HardwareSerial
        ?? (Settings.RememberPhone ? Settings.RememberedPhone : null);

    public PhoneProfile Profile => Settings.ProfileFor(ProfileKey);

    public string ProfileOwner =>
        Selected?.Device.HardwareSerial != null ? "Настройки для: " + Selected.Device.DisplayName :
        ProfileKey != null ? "Настройки для: " + (Settings.RememberedPhoneName ?? "запомненный телефон") :
        "Настройки для новых телефонов";

    private void RaiseProfile()
    {
        _loadingProfile = true;
        Raise(nameof(Quality), nameof(CustomLines), nameof(IsCustom), nameof(Fps), nameof(Bitrate), nameof(BitrateText),
            nameof(LowLatency), nameof(Audio), nameof(AudioNote), nameof(TurnScreenOff), nameof(StayAwake), nameof(Borderless),
            nameof(AlwaysOnTop), nameof(WindowTitle), nameof(ProfileOwner), nameof(StatusDetail),
            nameof(QualityLimitNote), nameof(HasQualityLimitNote));
        _loadingProfile = false;
    }

    /// <summary>Изменение настройки: сохранить и, если идёт трансляция этого телефона, перезапустить scrcpy.</summary>
    private void Changed(bool needsRestart = true, params string[] also)
    {
        if (_loadingProfile) return;
        Raise(also);
        Raise(nameof(StatusDetail), nameof(BitrateText), nameof(QualityLimitNote), nameof(HasQualityLimitNote));
        RaiseSpeed();
        SaveSoon();
        if (needsRestart && Session != null && Selected != null &&
            Session.Device.HardwareSerial == Selected.Device.HardwareSerial && State == ConnState.Connected)
        {
            _restartTimer.Stop();
            _restartTimer.Start();
        }
    }

    public QualityMode Quality
    {
        get => Profile.Quality;
        set { if (Profile.Quality == value) return; Profile.Quality = value; Changed(true, nameof(Quality), nameof(IsCustom)); }
    }
    public bool IsCustom => Profile.Quality == QualityMode.Custom;

    /// <summary>Подсказка, если телефон сам отдаёт картинку меньше выбранной.</summary>
    public string QualityLimitNote
    {
        get
        {
            var d = Selected?.Device;
            if (!ScrcpyArgs.PhoneLimitsQuality(d, Profile)) return "";
            var where = d!.IsSamsung
                ? " Чтобы было выше: на телефоне Настройки → Дисплей → Разрешение экрана → FHD+ или WQHD+."
                : " Чтобы было выше, увеличьте разрешение экрана в настройках дисплея телефона.";
            return $"Телефон сейчас отдаёт максимум {d.ScreenShort}p — так выбрано разрешение экрана на самом телефоне.{where}";
        }
    }
    public bool HasQualityLimitNote => QualityLimitNote.Length > 0;

    public string CustomLines
    {
        get => Profile.CustomLines.ToString();
        set
        {
            if (!int.TryParse(value, out var v) || v < 240 || v > 2160 || v == Profile.CustomLines) return;
            Profile.CustomLines = v;
            Changed(Profile.Quality == QualityMode.Custom, nameof(CustomLines));
        }
    }

    public int Fps
    {
        get => Profile.Fps;
        set { if (Profile.Fps == value) return; Profile.Fps = value; Changed(true, nameof(Fps)); }
    }

    /// <summary>Ползунок: 0 — авто, дальше 2…40 Мбит/с.</summary>
    public double Bitrate
    {
        get => Profile.BitrateMbps;
        set
        {
            int v = (int)Math.Round(value);
            if (v == 1) v = 2;
            if (Profile.BitrateMbps == v) return;
            Profile.BitrateMbps = v;
            Changed(true, nameof(Bitrate));
        }
    }

    // ── Проверка Wi-Fi ──────────────────────────────────────────────────

    private readonly Dictionary<string, WifiSpeedResult> _speedResults = new();
    private bool _speedBusy;
    private string _speedProgress = "";
    private string? _speedError;

    public bool ShowWifiTest => Selected is { IsReady: true } s && s.Device.IsNetwork;
    public bool SpeedBusy { get => _speedBusy; private set { Set(ref _speedBusy, value); RaiseSpeed(); } }
    public bool CanRunSpeed => ShowWifiTest && !SpeedBusy && Session == null;
    public string SpeedButtonText => SpeedBusy ? _speedProgress : (SpeedResult != null ? "Проверить ещё раз" : "Проверить сеть");
    private WifiSpeedResult? SpeedResult => Selected != null && _speedResults.TryGetValue(Selected.Serial, out var r) ? r : null;
    public bool HasSpeedText => SpeedText.Length > 0;
    public bool CanApplySpeed => SpeedResult is { } r && r.RecommendedMbps != Profile.BitrateMbps;
    public string ApplySpeedText => SpeedResult is { } r ? $"Поставить {r.RecommendedMbps} Мбит/с" : "";

    public string SpeedText
    {
        get
        {
            if (!ShowWifiTest) return "";
            if (_speedError != null) return _speedError;
            if (SpeedResult is not { } r)
                return Session != null
                    ? "Проверка скорости — когда трансляция выключена (иначе она мешает измерению)."
                    : "Узнайте, какой битрейт выдержит ваша сеть и можно ли включать «Минимальную задержку». Займёт ~15 секунд.";
            var lat = r.LowLatencyLevel switch
            {
                0 => "можно включать.",
                1 => "можно, но иногда возможны рывки. Если заметите — выключите.",
                _ => "лучше не включать: связь неровная, будут рывки и щелчки звука.",
            };
            var need = ScrcpyArgs.AutoBitrateMbps(Selected?.Device, Profile);
            var tight = r.RecommendedMbps < need
                ? $" Для {ScrcpyArgs.EffectiveLines(Selected?.Device, Profile)}p {Profile.Fps} FPS этого маловато — снизьте FPS или качество."
                : "";
            var loss = r.Lost > 0 ? $", потеряно {r.Lost} из {r.Sent}" : "";
            var lines = new List<string>
            {
                $"Скорость: {r.Mbps:0} Мбит/с · задержка {r.PingMs:0} мс (разброс {r.JitterMs:0} мс{loss}).",
            };
            var links = new List<string>();
            if (Selected?.Device.Transport == Transport.Lan) links.Add("телефон — LAN (Ethernet)");
            if (r.Phone is { } w) links.Add($"телефон — Wi-Fi {w.Band}, сигнал {w.SignalText}" + (w.Rssi != 0 ? $" ({w.Rssi} дБм)" : ""));
            if (r.PcLinkMbps > 0) links.Add($"компьютер — {(r.PcOnWifi ? "Wi-Fi" : "кабель")} {FormatLink(r.PcLinkMbps)}");
            if (links.Count > 0) lines.Add("Связь: " + string.Join(", ", links) + ".");
            lines.Add($"Битрейт: до {r.RecommendedMbps} Мбит/с.{tight}");
            lines.Add($"Минимальная задержка: {lat}");

            // Советы, что улучшить.
            if (r.Phone is { Is24: true }) lines.Add("Совет: телефон на 2,4 ГГц — подключите его к сети 5 ГГц, будет быстрее и ровнее.");
            if (r.Phone is { WeakSignal: true }) lines.Add("Совет: сигнал слабый — ближе к роутеру будет стабильнее.");
            if (r.PcLinkMbps is > 0 and <= 100 && !r.PcOnWifi)
                lines.Add("Совет: компьютер подключён к роутеру на 100 Мбит/с — это потолок. Кабель Cat5e/Cat6 и порт роутера на 1 Гбит/с снимут ограничение.");
            if (r.PcOnWifi) lines.Add("Совет: компьютер тоже по Wi-Fi — по кабелю к роутеру будет заметно стабильнее.");
            if (r.ViaAdb) lines.Add("Скорость измерена через adb — реальная может быть выше.");
            if (Session != null) lines.Add("Проверить ещё раз можно, когда трансляция выключена.");
            return string.Join("\n", lines);
        }
    }

    private static string FormatLink(long mbps) => mbps >= 1000 ? $"{mbps / 1000.0:0.#} Гбит/с" : $"{mbps} Мбит/с";

    private void RaiseSpeed() => Raise(nameof(ShowWifiTest), nameof(CanRunSpeed), nameof(SpeedButtonText), nameof(SpeedText),
        nameof(HasSpeedText), nameof(CanApplySpeed), nameof(ApplySpeedText));

    public async Task RunSpeedTestAsync()
    {
        var item = Selected;
        if (item == null || !CanRunSpeed) return;
        _speedError = null;
        _speedProgress = "Проверяем…";
        SpeedBusy = true;
        try
        {
            var (result, error) = await WifiSpeedTest.RunAsync(Adb, item.Device, text =>
                _ui.BeginInvoke(() => { _speedProgress = text; Raise(nameof(SpeedButtonText)); }));
            if (result != null) _speedResults[item.Serial] = result;
            else _speedError = error;
        }
        catch (Exception ex) { _speedError = "Проверка не удалась: " + ex.Message; }
        finally { SpeedBusy = false; }
    }

    public void ApplySpeed()
    {
        if (SpeedResult is { } r) Bitrate = r.RecommendedMbps;
        RaiseSpeed();
    }

    public string BitrateText => Profile.BitrateMbps == 0
        ? $"Авто (≈{ScrcpyArgs.AutoBitrateMbps(Selected?.Device, Profile)} Мбит/с)"
        : $"{Profile.BitrateMbps} Мбит/с";

    public bool LowLatency
    {
        get => Profile.LowLatency;
        set { if (Profile.LowLatency == value) return; Profile.LowLatency = value; Changed(true, nameof(LowLatency)); }
    }

    public AudioMode Audio
    {
        get => Profile.Audio;
        set { if (Profile.Audio == value) return; Profile.Audio = value; Changed(true, nameof(Audio), nameof(AudioNote), nameof(ShowAudioLive)); }
    }

    public string AudioNote => Profile.Audio switch
    {
        AudioMode.PcOnly => "Звук телефона (и игр с голосовым чатом) идёт на компьютер, сам телефон молчит. Микрофон телефона работает как обычно. " +
                            "Редкие приложения запрещают захват звука — их будет слышно только на телефоне.",
        AudioMode.Both => "Нужен Android 13 или новее. Некоторые приложения запрещают захват звука — тогда их слышно только на телефоне.",
        _ => "Звук не передаётся, телефон звучит как обычно.",
    };

    public bool TurnScreenOff
    {
        get => Profile.TurnScreenOff;
        set { if (Profile.TurnScreenOff == value) return; Profile.TurnScreenOff = value; Changed(true, nameof(TurnScreenOff)); }
    }

    public bool StayAwake
    {
        get => Profile.StayAwake;
        set { if (Profile.StayAwake == value) return; Profile.StayAwake = value; Changed(true, nameof(StayAwake)); }
    }

    public bool Borderless
    {
        get => Profile.Borderless;
        set { if (Profile.Borderless == value) return; Profile.Borderless = value; Changed(true, nameof(Borderless)); }
    }

    public bool AlwaysOnTop
    {
        get => Profile.AlwaysOnTop;
        set { if (Profile.AlwaysOnTop == value) return; Profile.AlwaysOnTop = value; Changed(true, nameof(AlwaysOnTop)); }
    }

    public string WindowTitle
    {
        get => Profile.WindowTitle;
        set { if (Profile.WindowTitle == value) return; Profile.WindowTitle = value; Changed(true, nameof(WindowTitle)); }
    }

    // ── Общие настройки ─────────────────────────────────────────────────

    public bool RememberPhone
    {
        get => Settings.RememberPhone;
        set
        {
            if (Settings.RememberPhone == value) return;
            Settings.RememberPhone = value;
            if (!value) { Settings.RememberedPhone = null; Settings.RememberedPhoneName = null; Settings.AutoConnect = false; }
            else if (Session?.Device.HardwareSerial is { } k) { Settings.RememberedPhone = k; Settings.RememberedPhoneName = Session.Device.DisplayName; }
            else if (Selected?.Device.HardwareSerial is { } k2) { Settings.RememberedPhone = k2; Settings.RememberedPhoneName = Selected.Device.DisplayName; }
            Raise(nameof(RememberPhone), nameof(AutoConnect), nameof(RememberedText));
            SaveSoon();
        }
    }

    public bool AutoConnect
    {
        get => Settings.AutoConnect;
        set
        {
            if (Settings.AutoConnect == value) return;
            Settings.AutoConnect = value;
            if (value && !Settings.RememberPhone) RememberPhone = true;
            if (value && Settings.RememberedPhone == null && Selected?.Device.HardwareSerial is { } k)
            {
                Settings.RememberedPhone = k;
                Settings.RememberedPhoneName = Selected.Device.DisplayName;
            }
            _autoRetries = 0;
            Raise(nameof(AutoConnect), nameof(RememberedText));
            SaveSoon();
            TryAutoConnect();
        }
    }

    public string RememberedText => Settings.RememberPhone && Settings.RememberedPhoneName != null
        ? "Запомнен: " + Settings.RememberedPhoneName
        : "Телефон запомнится после первого подключения";

    public void RememberNetworkAddress(string address)
    {
        if (!NetworkEndpoint.TryParse(address, out var endpoint)) return;
        Settings.NetworkAddresses.Remove(endpoint.Address);
        Settings.NetworkAddresses.Insert(0, endpoint.Address);
        Settings.NetworkAddresses = Settings.NetworkAddresses.Take(8).ToList();
        Watcher.SetNetworkTargets(Settings.NetworkAddresses); SaveSoon();
    }
    public void ForgetNetworkAddress(string address)
    {
        Settings.NetworkAddresses.Remove(address);
        Watcher.SetNetworkTargets(Settings.NetworkAddresses); SaveSoon();
    }

    public void SaveSoon()
    {
        _saveTimer.Stop();
        _saveTimer.Start();
    }

    public void Dispose()
    {
        _saveTimer.Stop();
        SettingsStore.Save(Settings);
        Watcher.Dispose();
        Link.Dispose();
        Session?.Kill();
        Adb.KillServerIfOurs();
    }
}
