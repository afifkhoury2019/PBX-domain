using AfifPbx.Calls;
using AfifPbx.Media;
using Microsoft.Extensions.Logging;
using Microsoft.Extensions.Logging.Abstractions;

namespace AfifPbx.Bridge;

/// <summary>
/// A call leg carried over AFIF Phone's persistent WebSocket connection, instead of SIP/RTP. Audio is raw
/// 16-bit PCM at the PBX's native 8 kHz mono rate (see <see cref="Audio"/>) - no codec, no WebRTC, no ICE.
/// The phone reaches the PBX over its own Cloudflare Tunnel hostname (e.g. pbx.powerchain.uk) - no VPN, no
/// relay, no open port, and no third party in between.
///
/// Unlike the old design, this leg does not own a socket: the phone keeps one long-lived
/// <see cref="PhoneConnection"/> open for as long as the app runs, and a <see cref="WsLeg"/> is just this
/// call's slice of it - <see cref="Attach"/> plugs the leg into that connection, <see cref="PushInbound"/>
/// feeds it audio the connection received, and <see cref="Write"/>/<see cref="Hangup"/> send back out through
/// it. The connection survives the leg ending, so the phone can be rung again without reconnecting.
///
/// Mirrors <see cref="AfifPbx.Sip.SipLeg"/>'s role in the call graph: created either by
/// <see cref="AudioBridgeServer.Ring"/> (ringing a phone for an inbound/internal call) or by a phone's "dial"
/// control message (the phone is the caller). Either way, the leg starts in <see cref="LegState.Ringing"/>
/// and becomes <see cref="LegState.Answered"/> once the phone sends an "answer" control message.
/// </summary>
public sealed class WsLeg : CallLeg
{
    public static ILogger Log { get; set; } = NullLogger.Instance;

    private PhoneConnection? _connection;
    private long _rxFrames, _txFrames;

    public override string Kind => "ws";
    /// <summary>Identifies this call to the phone in "ring"/"hangup" control messages.</summary>
    public string CallId { get; set; }

    public WsLeg(string callId, string extension, string number, string name)
    {
        CallId = callId;
        Extension = extension;
        Number = number;
        Name = name;
    }

    /// <summary>Plug this leg into the phone's already-open, already-authenticated connection. Called by
    /// <see cref="PhoneConnection.OfferRing"/> (ringing the phone) and by its "dial" handler (the phone is
    /// calling out) - either way, no new socket is opened; this just points the leg at the existing one.</summary>
    public void Attach(PhoneConnection connection)
    {
        _connection = connection;
        Log.LogInformation("WsLeg {Leg}: attached to phone connection for extension {Extension}", this, Extension);
    }

    /// <summary>Called by <see cref="PhoneConnection.RunAsync"/> when the phone sends an "answer" control
    /// message for this leg - the WebSocket equivalent of a SIP 200 OK.</summary>
    public void MarkAnsweredByPhone() => MarkAnswered();

    /// <summary>Tell the phone the PBX side answered an outbound call, and mark this leg answered so
    /// PCM frames start flowing in both directions.</summary>
    public void NotifyAnswered()
    {
        if (State == LegState.Ended) return;
        if (State != LegState.Answered) MarkAnswered();
        if (_connection is { IsOpen: true } conn)
            _ = conn.SendControlAsync(new { type = "answered", callId = CallId });
    }

    /// <summary>Called by <see cref="PhoneConnection.RunAsync"/> for each binary audio frame it receives while
    /// this leg is the connection's active call.</summary>
    public void PushInbound(byte[] bytes)
    {
        Interlocked.Increment(ref _rxFrames);
        Inbound.Push(Audio.BytesToSamples(bytes));
    }

    public override void SendRinging() { /* the "ring" control message already told the phone it's ringing */ }

    public override Task<bool> AnswerAsync()
    {
        NotifyAnswered();
        return Task.FromResult(State != LegState.Ended);
    }

    public override void Hangup(string reason = "normal")
    {
        if (State == LegState.Ended) return;
        Log.LogInformation("WsLeg {Leg} ended ({Reason}): received {Rx} frames, sent {Tx}", this, reason, _rxFrames, _txFrames);
        MarkEnded(reason);
        // The phone's connection stays open for future calls - just tell it this call is over.
        if (_connection is { IsOpen: true } conn)
            _ = conn.SendControlAsync(new { type = "hangup", callId = CallId, reason });
    }

    public override void Write(short[] frame)
    {
        if (State != LegState.Answered || _connection is not { IsOpen: true } conn) return;
        _txFrames++;
        _ = conn.SendAudioAsync(Audio.SamplesToBytes(frame));
    }
}
