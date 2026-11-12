using System.Collections.Concurrent;
using System.Net.WebSockets;
using AfifPbx.Calls;
using AfifPbx.Data;
using Microsoft.Extensions.Logging;
using Microsoft.Extensions.Logging.Abstractions;

namespace AfifPbx.Bridge;

/// <summary>
/// The phone-side counterpart to <see cref="AfifPbx.Sip.SipServer"/>: instead of SIP/RTP, AFIF Phone reaches
/// the PBX over a plain, persistent WebSocket to this machine's own domain (e.g.
/// wss://pbx.powerchain.uk/register/{extension}), riding the Cloudflare Tunnel that is already running - no
/// VPN, no relay, no open port, and no third party in between. The phone authenticates
/// with the same extension/secret pair a SIP phone would use (see <see cref="PbxConfig"/>'s
/// <see cref="Extension"/> list) and keeps the socket open for as long as the app runs, so the PBX can ring it
/// at any time without needing a push notification to wake it up. See docs/ARCHITECTURE.md.
///
/// Mirrors <see cref="AfifPbx.Sip.SipServer"/>'s shape on purpose: <see cref="Ring"/> matches
/// <c>SipServer.Ring</c>'s signature so <see cref="AfifPbx.Calls.CallManager"/> can call both side by side and
/// merge the resulting legs, and <see cref="IncomingCall"/> matches <c>SipServer.IncomingCall</c> so a
/// phone-initiated (outbound) call plugs into the same <c>HandleCallAsync</c> path a SIP phone's call would.
/// </summary>
public sealed class AudioBridgeServer
{
    public static ILogger Log { get; set; } = NullLogger.Instance;

    private readonly ConfigStore _config;
    private readonly ConcurrentDictionary<string, PhoneConnection> _phones = new();
    private readonly ConcurrentDictionary<string, (WsLeg Leg, CancellationTokenSource Cts)> _pendingHandoff = new();

    /// <summary>How long a call leg is kept alive, unattached to any socket, after its phone's connection
    /// drops - giving the app's own reconnect (or a brief cellular/tunnel blip) a chance to resume the call
    /// instead of it being hung up on every transient disconnect.</summary>
    private static readonly TimeSpan ReconnectGraceWindow = TimeSpan.FromSeconds(25);

    /// <summary>Raised when a phone dials out through the PBX (e.g. to the GSM line) rather than being rung
    /// by it. Carries the leg representing the phone and the number it asked to dial.</summary>
    public event Action<CallLeg, string>? IncomingCall;

    /// <summary>A phone registered over the WebSocket; pending SMS can now be delivered.</summary>
    public event Action<string>? PhoneRegistered;

    /// <summary>A phone asked to send an SMS: extension, destination, text, client id.</summary>
    public event Action<string, string, string, string>? SmsSendRequested;

    public event Action<string>? VoicemailListRequested;
    public event Action<string, long>? VoicemailAudioRequested;

    public AudioBridgeServer(ConfigStore config)
    {
        _config = config;
    }

    internal void RaiseOutboundCallRequested(WsLeg leg, string dialed) => IncomingCall?.Invoke(leg, dialed);
    internal void RaiseSmsSend(string extension, string to, string text, string id) =>
        SmsSendRequested?.Invoke(extension, to, text, id);

    public void SendSmsResult(string extension, string id, bool ok)
    {
        if (_phones.TryGetValue(extension, out var conn) && conn.IsOpen)
            conn.SendSmsResult(id, ok);
    }

    internal void RaiseVoicemailList(string extension) => VoicemailListRequested?.Invoke(extension);
    internal void RaiseVoicemailAudio(string extension, long id) => VoicemailAudioRequested?.Invoke(extension, id);

    public void SendVoicemailList(string extension, object items)
    {
        if (_phones.TryGetValue(extension, out var conn) && conn.IsOpen)
            _ = conn.SendControlAsync(new { type = "voicemails", items });
    }

    public void SendVoicemailAudio(string extension, long id, string? data)
    {
        if (_phones.TryGetValue(extension, out var conn) && conn.IsOpen)
            _ = conn.SendControlAsync(new { type = "voicemail-audio", id, data });
    }

    /// <summary>Deliver one SMS to a registered phone. False when that extension is offline.</summary>
    public bool DeliverSms(string extension, string from, string text, DateTime time)
    {
        if (!_phones.TryGetValue(extension, out var conn) || !conn.IsOpen) return false;
        conn.SendSms(from, text, time);
        return true;
    }

    /// <summary>True when the extension has a phone with an open, registered WebSocket - i.e. there is
    /// somewhere to ring it right now. Never touches the network; just a dictionary lookup.</summary>
    public bool HasDevice(string extension) => _phones.TryGetValue(extension, out var conn) && conn.IsOpen;
    public int ConnectedCount => _phones.Values.Count(c => c.IsOpen);

    /// <summary>Ring an extension's phone over the audio bridge. Mirrors <c>SipServer.Ring</c>: returns
    /// immediately with a leg per device the extension owns (today, at most one), already in
    /// <see cref="LegState.Ringing"/>; the leg becomes <see cref="LegState.Answered"/> once the phone replies
    /// with an "answer" control message over its already-open socket, or ends on timeout like any other leg.</summary>
    public List<CallLeg> Ring(string extension, string callerNumber, string callerName, int ringSeconds)
    {
        if (!_phones.TryGetValue(extension, out var conn) || !conn.IsOpen)
            return new List<CallLeg>(); // no phone registered for this extension right now

        var leg = new WsLeg(Guid.NewGuid().ToString("N"), extension, callerNumber, callerName);
        conn.OfferRing(leg, ringSeconds);
        return new List<CallLeg> { leg };
    }

    /// <summary>Called by the <c>/register/{extension}</c> WebSocket endpoint (wired in Program.cs) once
    /// ASP.NET Core has accepted the connection. Verifies the extension's secret (sent as the
    /// Sec-WebSocket-Protocol header - same credential a SIP phone registers with) and, if it matches, keeps
    /// the socket as this extension's standing connection until it disconnects. A bad secret or unknown
    /// extension closes the socket at once.</summary>
    public async Task AcceptAsync(WebSocket socket, string extension, string? secret)
    {
        var ext = _config.Current.Extensions.FirstOrDefault(e => e.Number == extension && e.Enabled);
        if (ext is null || string.IsNullOrEmpty(secret) || ext.Secret != secret)
        {
            Log.LogInformation("Rejecting phone registration for extension {Extension}: bad or unknown secret", extension);
            await CloseAsync(socket, WebSocketCloseStatus.PolicyViolation, "unauthorized").ConfigureAwait(false);
            return;
        }

        // A phone reconnecting (app restart, network blip) replaces its own prior connection. If that prior
        // connection (or a since-dropped one still in its post-disconnect grace window) was mid-call, carry
        // the call straight over to this new socket instead of letting it be hung up.
        WsLeg? handoffLeg = null;
        if (_phones.TryRemove(extension, out var old))
        {
            handoffLeg = old.TakeActiveLeg();
            old.Close("replaced by new connection");
        }
        if (handoffLeg is null && _pendingHandoff.TryRemove(extension, out var pending))
        {
            pending.Cts.Cancel();
            if (pending.Leg.State != Calls.LegState.Ended) handoffLeg = pending.Leg;
        }

        var conn = new PhoneConnection(extension, socket, this);
        _phones[extension] = conn;
        Log.LogInformation("Phone registered for extension {Extension}", extension);
        if (handoffLeg is not null)
        {
            conn.AdoptActiveLeg(handoffLeg);
            handoffLeg.Attach(conn);
            Log.LogInformation("Call carried over to new connection for extension {Extension} after reconnect", extension);
        }
        PhoneRegistered?.Invoke(extension);
        try
        {
            await conn.RunAsync().ConfigureAwait(false); // blocks for the life of the connection
        }
        finally
        {
            _phones.TryRemove(new KeyValuePair<string, PhoneConnection>(extension, conn));
            Log.LogInformation("Phone disconnected for extension {Extension}", extension);
        }
    }

    /// <summary>Called by <see cref="PhoneConnection"/> when its socket ends while it was carrying a call.
    /// Rather than hanging the call up immediately - which would end it on every brief network wobble - this
    /// holds the leg, unattached, for <see cref="ReconnectGraceWindow"/> so a quick reconnect (the app's own
    /// auto-reconnect, a cellular handover, a momentary Cloudflare Tunnel hiccup) can reclaim it via
    /// <see cref="AcceptAsync"/>. If nothing reclaims it in time, the call ends exactly as it used to.</summary>
    internal void HoldForReconnect(string extension, WsLeg leg)
    {
        var cts = new CancellationTokenSource();
        _pendingHandoff[extension] = (leg, cts);
        leg.Ended += (_, _) =>
        {
            cts.Cancel();
            _pendingHandoff.TryRemove(new KeyValuePair<string, (WsLeg, CancellationTokenSource)>(extension, (leg, cts)));
        };
        _ = Task.Run(async () =>
        {
            try { await Task.Delay(ReconnectGraceWindow, cts.Token).ConfigureAwait(false); }
            catch (OperationCanceledException) { return; }
            if (_pendingHandoff.TryRemove(extension, out var entry) && ReferenceEquals(entry.Leg, leg))
            {
                Log.LogInformation("Phone {Extension} did not reconnect within {Seconds}s; ending the call",
                    extension, ReconnectGraceWindow.TotalSeconds);
                leg.Hangup("phone disconnected");
            }
        });
    }

    /// <summary>Close every phone socket, e.g. when the domain transport is switched off.</summary>
    public void CloseAll()
    {
        foreach (var conn in _phones.Values.ToList())
        {
            try { conn.Close("domain transport disabled"); } catch { }
        }
        _phones.Clear();

        // Don't let calls linger in their reconnect grace window once the transport itself has been turned
        // off - there is no socket coming back to reclaim them.
        foreach (var (leg, cts) in _pendingHandoff.Values.ToList())
        {
            try { cts.Cancel(); } catch { }
            if (leg.State != Calls.LegState.Ended) leg.Hangup("domain transport disabled");
        }
        _pendingHandoff.Clear();
    }

    private static async Task CloseAsync(WebSocket socket, WebSocketCloseStatus status, string reason)
    {
        try { if (socket.State == WebSocketState.Open) await socket.CloseAsync(status, reason, CancellationToken.None).ConfigureAwait(false); }
        catch { /* already gone */ }
    }
}
