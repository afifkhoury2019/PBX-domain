using System.Net;
using AfifPbx.Calls;
using AfifPbx.Data;
using AfifPbx.Gsm;
using AfifPbx.Media;
using AfifPbx.Sip;
using AfifPbx.Tts;
using Microsoft.Extensions.Logging;
using Microsoft.Extensions.Logging.Abstractions;

namespace AfifPbx;

/// <summary>Everything that makes up a running AFIF-PBX.</summary>
public sealed class PbxEngine : IDisposable
{
    private readonly ILoggerFactory _logs;
    private readonly Func<(IModemPorts?, string, string)>? _modemOpener;

    public ConfigStore Config { get; }
    public Database Db { get; }
    public MediaClock Clock { get; } = new();
    public MediaOps Media { get; }
    public PromptLibrary Prompts { get; }
    public SipServer Sip { get; }
    public AfifPbx.Bridge.AudioBridgeServer? Bridge { get; private set; }
    public HuaweiModem? Modem { get; private set; }
    public IReadOnlyList<DongleRuntime> Dongles { get; private set; } = Array.Empty<DongleRuntime>();
    private readonly object _dongleGate = new();
    private Timer? _hotplug;
    public CallManager Calls { get; private set; } = null!;
    public DateTime StartedUtc { get; } = DateTime.UtcNow;
    public string DataDir => Config.DataDir;

    public event Action<SmsMessage>? SmsReceived;

    /// <param name="modemOpener">Override how the dongle is opened (tests use a simulated modem).</param>
    public PbxEngine(string dataDir, ILoggerFactory? logs = null, ISpeech? speech = null,
        Func<(IModemPorts?, string, string)>? modemOpener = null)
    {
        _logs = logs ?? NullLoggerFactory.Instance;
        _modemOpener = modemOpener;
        Config = new ConfigStore(dataDir);
        AfifPbx.Remote.Secrets.Configure(dataDir);
        Db = new Database(Path.Combine(dataDir, "afif-pbx.db"));
        Media = new MediaOps(Clock);
        Prompts = new PromptLibrary(speech ?? SpeechFactory.Create(() => Config.Current.Settings.TtsVoice), dataDir);
        Sip = new SipServer(Config, _logs.CreateLogger("SIP"));
        var clockLog = _logs.CreateLogger("Media");
        Clock.OnError = ex => clockLog.LogError(ex, "Media error");
    }

    public void Start(IPAddress? sipBind = null)
    {
        var s = Config.Current.Settings;
        Sip.Start(sipBind ?? IPAddress.Any, s.SipPort, s.ExtraSipPorts);
        // loopback transports for phones that come through the built-in secure network (afif-net relays to them)
        Sip.StartRelay(new[] { s.SipPort }.Concat(s.ExtraSipPorts ?? new List<int>()));

        if (s.GsmEnabled)
        {
            var list = new List<DongleRuntime>();
            if (_modemOpener is not null)
            {
                var modem = new HuaweiModem(_modemOpener, _logs.CreateLogger("GSM"))
                    { RxGainDb = s.GsmRxGain, TxGainDb = s.GsmTxGain };
                var runtime = new DongleRuntime
                {
                    Config = new DongleConfig { Id = "default", Name = "GSM" }, Modem = modem
                };
                modem.SmsReceived += sms => OnSms(sms, runtime);
                list.Add(runtime);
            }
            else
            {
                var discovered = OperatingSystem.IsLinux()
                    ? LinuxPortDiscovery.DiscoverDongles()
                    : Array.Empty<DonglePorts>();
                _logs.CreateLogger("GSM").LogInformation("Discovered {Count} dongle(s): {Dongles}",
                    discovered.Count, string.Join(", ", discovered.Select(d => $"{d.Id}={d.AtPort}/{d.AudioPort}")));
                var saved = (s.Dongles ?? new List<DongleConfig>())
                    .Where(d => d.Enabled).ToList();
                // The physically detected devices are authoritative. A stored
                // record whose USB path disappeared must not keep a dead modem.
                var configured = discovered.Select(d =>
                {
                    var cfg = saved.FirstOrDefault(x => x.Id == d.Id);
                    if (cfg is null)
                    {
                        cfg = new DongleConfig
                        {
                            Id = d.Id, Name = d.Name, AtPort = d.AtPort, AudioPort = d.AudioPort,
                            RxGain = s.GsmRxGain, TxGain = s.GsmTxGain
                        };
                    }
                    if (string.IsNullOrWhiteSpace(cfg.AtPort)) cfg.AtPort = d.AtPort;
                    if (string.IsNullOrWhiteSpace(cfg.AudioPort)) cfg.AudioPort = d.AudioPort;
                    return cfg;
                }).ToList();
                if (configured.Count == 0)
                {
                    // No sysfs detection: keep the legacy/configured line.
                    if (saved.Count > 0) configured = saved;
                    else configured.Add(new DongleConfig
                    {
                        Id = "default", Name = "GSM", AtPort = s.GsmAtPort, AudioPort = s.GsmAudioPort,
                        RxGain = s.GsmRxGain, TxGain = s.GsmTxGain
                    });
                }
                foreach (var cfg in configured)
                {
                    var ports = discovered.FirstOrDefault(d => d.Id == cfg.Id);
                    var at = string.IsNullOrWhiteSpace(cfg.AtPort) ? ports?.AtPort ?? "" : cfg.AtPort;
                    var audio = string.IsNullOrWhiteSpace(cfg.AudioPort) ? ports?.AudioPort ?? "" : cfg.AudioPort;
                    var modem = new HuaweiModem(
                        PortDiscovery.Opener(() => (at, audio)), _logs.CreateLogger($"GSM/{cfg.Name}"))
                        { RxGainDb = cfg.RxGain, TxGainDb = cfg.TxGain };
                    var runtime = new DongleRuntime
                    {
                        Config = cfg, Modem = modem, AtPort = at, AudioPort = audio
                    };
                    modem.SmsReceived += sms => OnSms(sms, runtime);
                    list.Add(runtime);
                }
            }
            Dongles = list;
            Modem = list.FirstOrDefault()?.Modem;
        }

        if (s.AudioBridgeEnabled)
        {
            AfifPbx.Bridge.WsLeg.Log = _logs.CreateLogger("Bridge");
            AfifPbx.Bridge.PhoneConnection.Log = _logs.CreateLogger("Bridge");
            AfifPbx.Bridge.AudioBridgeServer.Log = _logs.CreateLogger("Bridge");
            Bridge = new AfifPbx.Bridge.AudioBridgeServer(Config);
            WireBridge(Bridge);
        }

        Calls = new CallManager(Config, Sip, Dongles, Media, Db, Prompts, DataDir,
            _logs.CreateLogger("Calls"), Bridge);

        // SMS ⇄ phones: texts written in AFIF Phone go out on the SIM; SMS that waited for an offline phone go out when it registers
        Sip.TextFromPhone = async (ext, number, text) => await SendSmsAsync(CleanNumber(number), text, ext).ConfigureAwait(false);
        Sip.RegistrationChanged += (ext, registered) => { if (registered) _ = DeliverPendingSmsAsync(ext); };
        _smsRetry = new Timer(_ => _ = DeliverPendingSmsAsync(null), null, TimeSpan.FromSeconds(60), TimeSpan.FromSeconds(60));
        foreach (var d in Dongles) d.Modem.Start();
        if (OperatingSystem.IsLinux())
            _hotplug = new Timer(_ => _ = RescanDonglesAsync(), null,
                TimeSpan.FromSeconds(10), TimeSpan.FromSeconds(10));

        Config.Changed += c =>
        {
            foreach (var d in Dongles)
            {
                var cfg = c.Settings.Dongles?.FirstOrDefault(x => x.Id == d.Id);
                if (cfg is not null)
                {
                    d.Config.InboundDestination = cfg.InboundDestination;
                    d.Config.OutboundPrefix = cfg.OutboundPrefix;
                    d.Config.StripPrefix = cfg.StripPrefix;
                    d.Config.SmsExtension = cfg.SmsExtension;
                }
                d.Modem.RxGainDb = d.Config.RxGain;
                d.Modem.TxGainDb = d.Config.TxGain;
            }
        };
    }

    private Timer? _smsRetry;
    private readonly SemaphoreSlim _smsDelivery = new(1, 1);

    private void OnSms(IncomingSms sms, DongleRuntime? dongle)
    {
        long id = Db.AddSms("in", sms.Sender, sms.Text, "received");
        var msg = Db.Sms(1).FirstOrDefault(m => m.Id == id);
        foreach (var ext in SmsTargets(dongle)) Db.AddSmsPending(id, ext);
        _ = DeliverPendingSmsAsync(null);
        if (msg is not null) SmsReceived?.Invoke(msg);
    }

    /// <summary>Extensions that receive incoming SMS on their phones.</summary>
    private List<string> SmsTargets(DongleRuntime? dongle)
    {
        var c = Config.Current;
        if (!string.IsNullOrWhiteSpace(dongle?.Config.SmsExtension) &&
            c.Extensions.Any(e => e.Enabled && e.Number == dongle.Config.SmsExtension))
            return new List<string> { dongle.Config.SmsExtension };
        var chosen = c.Settings.SmsExtensions ?? new List<string>();
        return c.Extensions.Where(e => e.Enabled && (chosen.Count == 0 || chosen.Contains(e.Number))).Select(e => e.Number).ToList();
    }

    /// <summary>Send waiting SMS to the phones that are registered now (one extension, or all when null).</summary>
    public async Task DeliverPendingSmsAsync(string? ext)
    {
        if (!await _smsDelivery.WaitAsync(0).ConfigureAwait(false))
        {
            // another delivery round is running; try again shortly so nothing is missed
            _ = Task.Delay(3000).ContinueWith(_ => DeliverPendingSmsAsync(ext));
            return;
        }
        try
        {
            foreach (var (pid, target, sms) in Db.SmsPending(ext))
            {
                string text = sms.Text;
                if (DateTime.UtcNow - sms.Time > TimeSpan.FromMinutes(5))
                    text += $"\n\n({sms.Time.ToLocalTime():yyyy-MM-dd HH:mm})"; // it waited for the phone: say when it really arrived
                if (Bridge?.HasDevice(target) == true && Bridge.DeliverSms(target, sms.Number, text, sms.Time))
                {
                    Db.RemoveSmsPending(pid);
                    Db.SetSmsStatus(sms.Id, "delivered");
                    continue;
                }
                if (!Sip.IsRegistered(target)) continue;
                if (await Sip.SendTextAsync(target, sms.Number, text).ConfigureAwait(false))
                {
                    Db.RemoveSmsPending(pid);
                    Db.SetSmsStatus(sms.Id, "delivered");
                }
            }
        }
        catch { }
        finally { _smsDelivery.Release(); }
    }

    private static string CleanNumber(string n) =>
        new string(n.Trim().Where(c => char.IsDigit(c) || c == '+' || c == '*' || c == '#').ToArray());

    public async Task<bool> SendSmsAsync(string number, string text, string? extension = null)
    {
        // The destination is one of our own extensions, not a real phone number - there is no
        // SIM card involved, so deliver it straight to that extension's phone(s) (same path used
        // for a real incoming SMS) instead of handing it to the dongle, where it can only ever
        // fail (an extension number isn't a dialable GSM destination). Covers both the admin
        // panel's "New message" form and a phone sending an internal "SMS" to another extension.
        var targetExt = Config.Current.Extensions.FirstOrDefault(e => e.Enabled && e.Number == number);
        if (targetExt is not null)
        {
            long directId = Db.AddSms("out", number, text, "sending");
            string from = string.IsNullOrWhiteSpace(extension) ? "PBX" : extension;
            bool delivered = Bridge?.HasDevice(number) == true && Bridge.DeliverSms(number, from, text, DateTime.UtcNow);
            if (!delivered && Sip.IsRegistered(number))
                delivered = await Sip.SendTextAsync(number, from, text).ConfigureAwait(false);
            Db.SetSmsStatus(directId, delivered ? "delivered" : "failed");
            return delivered;
        }

        var ready = Dongles.Where(d => d.Modem.IsReady).ToList();
        var dongle = !string.IsNullOrEmpty(extension)
            ? ready.FirstOrDefault(d => d.Config.SmsExtension == extension)
                ?? Dongles.FirstOrDefault(d => d.Config.SmsExtension == extension)
                ?? ready.FirstOrDefault(d => Config.Current.Extensions
                    .Any(e => e.Number == extension && e.DongleId == d.Id))
                ?? ready.FirstOrDefault()
            : ready.FirstOrDefault();
        long id = Db.AddSms("out", number, text, "sending");
        bool ok = dongle is not null && await dongle.Modem.SendSmsAsync(number, text).ConfigureAwait(false);
        Db.SetSmsStatus(id, ok ? "sent" : "failed");
        return ok;
    }

    private void WireBridge(AfifPbx.Bridge.AudioBridgeServer bridge)
    {
        bridge.PhoneRegistered += ext => _ = DeliverPendingSmsAsync(ext);
        bridge.SmsSendRequested += (ext, to, text, id) => _ = Task.Run(async () =>
        {
            bool ok = await SendSmsAsync(CleanNumber(to), text, ext).ConfigureAwait(false);
            bridge.SendSmsResult(ext, id, ok);
        });
        bridge.VoicemailListRequested += ext =>
        {
            var items = Db.Voicemails(ext).Select(v => new
            {
                id = v.Id,
                from = v.Caller,
                time = v.Time.ToString("O"),
                duration = v.DurationSeconds,
                heard = v.Heard
            }).ToList();
            bridge.SendVoicemailList(ext, items);
        };
        bridge.VoicemailAudioRequested += (ext, id) =>
        {
            var vm = Db.Voicemail(id);
            if (vm is null || !string.Equals(vm.Mailbox, ext, StringComparison.OrdinalIgnoreCase))
            {
                bridge.SendVoicemailAudio(ext, id, null);
                return;
            }
            try
            {
                var path = Path.Combine(DataDir, "Voicemail", vm.File);
                var data = File.Exists(path) ? Convert.ToBase64String(File.ReadAllBytes(path)) : null;
                Db.MarkVoicemailHeard(id);
                bridge.SendVoicemailAudio(ext, id, data);
            }
            catch { bridge.SendVoicemailAudio(ext, id, null); }
        };
    }

    /// <summary>Switch the direct WebSocket transport on/off from the dashboard.</summary>
    public void SetAudioBridgeEnabled(bool enabled)
    {
        Config.Update(c => c.Settings.AudioBridgeEnabled = enabled);
        if (enabled)
        {
            if (Bridge is not null) return;
            AfifPbx.Bridge.WsLeg.Log = _logs.CreateLogger("Bridge");
            AfifPbx.Bridge.PhoneConnection.Log = _logs.CreateLogger("Bridge");
            AfifPbx.Bridge.AudioBridgeServer.Log = _logs.CreateLogger("Bridge");
            Bridge = new AfifPbx.Bridge.AudioBridgeServer(Config);
            WireBridge(Bridge);
            Calls.SetBridge(Bridge);
        }
        else
        {
            var old = Bridge;
            Bridge = null;
            Calls.SetBridge(null);
            old?.CloseAll();
        }
    }

    private async Task RescanDonglesAsync()
    {
        try
        {
            var discovered = LinuxPortDiscovery.DiscoverDongles();
            foreach (var ports in discovered)
            {
                lock (_dongleGate)
                {
                    if (Dongles.Any(d => d.Id == ports.Id ||
                        (!string.IsNullOrEmpty(ports.AtPort) && d.AtPort == ports.AtPort))) continue;
                }
                var cfg = Config.Current.Settings.Dongles?.FirstOrDefault(d => d.Id == ports.Id)
                    ?? new DongleConfig
                    {
                        Id = ports.Id, Name = ports.Name, Enabled = true,
                        AtPort = ports.AtPort, AudioPort = ports.AudioPort,
                        RxGain = Config.Current.Settings.GsmRxGain,
                        TxGain = Config.Current.Settings.GsmTxGain
                    };
                var at = string.IsNullOrWhiteSpace(cfg.AtPort) ? ports.AtPort : cfg.AtPort;
                var audio = string.IsNullOrWhiteSpace(cfg.AudioPort) ? ports.AudioPort : cfg.AudioPort;
                var modem = new HuaweiModem(PortDiscovery.Opener(() => (at, audio)),
                    _logs.CreateLogger($"GSM/{cfg.Name}")) { RxGainDb = cfg.RxGain, TxGainDb = cfg.TxGain };
                var runtime = new DongleRuntime
                {
                    Config = cfg, Modem = modem, AtPort = at, AudioPort = audio
                };
                modem.SmsReceived += sms => OnSms(sms, runtime);
                lock (_dongleGate)
                {
                    var list = Dongles.ToList();
                    list.Add(runtime);
                    Dongles = list;
                }
                Calls.AddDongle(runtime);
                modem.Start();
                _logs.CreateLogger("GSM").LogInformation("Hot-plug dongle {Id} {At}/{Audio}",
                    cfg.Id, at, audio);
            }
        }
        catch (Exception ex)
        {
            _logs.CreateLogger("GSM").LogWarning(ex, "Dongle rescan failed");
        }
        await Task.CompletedTask;
    }

    public void Dispose()
    {
        _hotplug?.Dispose();
        _smsRetry?.Dispose();
        foreach (var d in Dongles) d.Modem.Dispose();
        Sip.Dispose();
        Clock.Dispose();
    }
}
