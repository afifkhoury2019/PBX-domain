using System.Net.WebSockets;
using System.Text;
using System.Text.Json;
using AfifPbx.Media;
using Microsoft.Extensions.Logging;
using Microsoft.Extensions.Logging.Abstractions;

namespace AfifPbx.Bridge;

/// <summary>
/// One phone's persistent WebSocket connection to the PBX - opened once when AFIF Phone starts (and kept open
/// by its foreground service, reconnecting on drop) rather than once per call. A single socket carries both
/// signaling (small JSON text frames: ring/answer/hangup/dial/ping) and, while a call is active, raw PCM audio
/// (binary frames) - no Firebase, no FCM, no per-call socket. See docs/ARCHITECTURE.md.
///
/// This replaces the old per-call model where a fresh WebSocket opened for each call and a Firestore write
/// woke the phone via FCM; here the phone is always reachable because it is always connected, exactly like a
/// SIP phone's persistent registration.
/// </summary>
public sealed class PhoneConnection
{
    public static ILogger Log { get; set; } = NullLogger.Instance;

    public string Extension { get; }
    public bool IsOpen => _socket.State == WebSocketState.Open;

    private readonly WebSocket _socket;
    private readonly AudioBridgeServer _registry;
    private readonly CancellationTokenSource _cts = new();
    private readonly SemaphoreSlim _sendLock = new(1, 1);
    private WsLeg? _activeLeg;

    public PhoneConnection(string extension, WebSocket socket, AudioBridgeServer registry)
    {
        Extension = extension;
        _socket = socket;
        _registry = registry;
    }

    /// <summary>Send the "ring" offer for a new inbound call and start its ring-timeout clock. The leg stays
    /// in <see cref="AfifPbx.Calls.LegState.Ringing"/> until the phone answers (see <see cref="HandleControlMessage"/>)
    /// or the timeout elapses.</summary>
    public void OfferRing(WsLeg leg, int ringSeconds)
    {
        _activeLeg = leg;
        leg.Attach(this);
        leg.Ended += (_, _) => { if (ReferenceEquals(_activeLeg, leg)) _activeLeg = null; };
        _ = SendControlAsync(new { type = "ring", callId = leg.CallId, number = leg.Number, name = leg.Name });
        _ = RingTimeoutAsync(leg, ringSeconds);
    }

    private async Task RingTimeoutAsync(WsLeg leg, int ringSeconds)
    {
        await Task.WhenAny(Task.Delay(TimeSpan.FromSeconds(ringSeconds)), leg.EndedTask).ConfigureAwait(false);
        if (leg.State == Calls.LegState.Ringing) leg.Hangup("no answer");
    }

    public async Task SendControlAsync(object payload)
    {
        var bytes = Encoding.UTF8.GetBytes(JsonSerializer.Serialize(payload));
        await _sendLock.WaitAsync().ConfigureAwait(false);
        try { if (IsOpen) await _socket.SendAsync(bytes, WebSocketMessageType.Text, true, CancellationToken.None).ConfigureAwait(false); }
        catch (WebSocketException) { /* socket is going away; RunAsync's loop will notice */ }
        finally { _sendLock.Release(); }
    }

    public async Task SendAudioAsync(byte[] frame)
    {
        await _sendLock.WaitAsync().ConfigureAwait(false);
        try { if (IsOpen) await _socket.SendAsync(frame, WebSocketMessageType.Binary, true, CancellationToken.None).ConfigureAwait(false); }
        catch (WebSocketException) { }
        finally { _sendLock.Release(); }
    }

    /// <summary>Runs for the lifetime of the connection, demultiplexing text (control) and binary (audio)
    /// frames. Returns when the phone disconnects. Called once by <see cref="AudioBridgeServer.AcceptAsync"/>.</summary>
    public async Task RunAsync()
    {
        var buffer = new byte[Audio.FrameBytes * 4];
        try
        {
            while (!_cts.IsCancellationRequested && IsOpen)
            {
                using var ms = new MemoryStream();
                WebSocketReceiveResult result;
                WebSocketMessageType type;
                do
                {
                    result = await _socket.ReceiveAsync(buffer, _cts.Token).ConfigureAwait(false);
                    if (result.MessageType == WebSocketMessageType.Close) return;
                    type = result.MessageType;
                    ms.Write(buffer, 0, result.Count);
                } while (!result.EndOfMessage);

                if (type == WebSocketMessageType.Text)
                    HandleControlMessage(Encoding.UTF8.GetString(ms.ToArray()));
                else if (type == WebSocketMessageType.Binary && _activeLeg is { State: Calls.LegState.Answered } leg)
                {
                    if (ms.Length >= 2) leg.PushInbound(ms.ToArray());
                }
            }
        }
        catch (OperationCanceledException) { }
        catch (WebSocketException ex) { Log.LogInformation("Phone {Extension}: connection ended ({Message})", Extension, ex.Message); }
        catch (Exception ex) { Log.LogWarning(ex, "Phone {Extension}: receive loop failed", Extension); }
        finally { _activeLeg?.Hangup("phone disconnected"); }
    }

    private void HandleControlMessage(string json)
    {
        try
        {
            using var doc = JsonDocument.Parse(json);
            var type = doc.RootElement.TryGetProperty("type", out var t) ? t.GetString() : null;
            switch (type)
            {
                case "answer":
                    _activeLeg?.MarkAnsweredByPhone();
                    break;
                case "hangup":
                case "reject":
                case "busy":
                    _activeLeg?.Hangup(type);
                    break;
                case "dial":
                    {
                        var number = doc.RootElement.TryGetProperty("number", out var n) ? n.GetString() ?? "" : "";
                        var leg = new WsLeg(Guid.NewGuid().ToString("N"), Extension, number, "");
                        leg.Attach(this);
                        _activeLeg = leg;
                        leg.Ended += (_, _) => { if (ReferenceEquals(_activeLeg, leg)) _activeLeg = null; };
                        _registry.RaiseOutboundCallRequested(leg, number);
                        break;
                    }
                case "ping":
                    _ = SendControlAsync(new { type = "pong" });
                    break;
            }
        }
        catch (Exception ex) { Log.LogWarning(ex, "Phone {Extension}: bad control message", Extension); }
    }

    public void Close(string reason)
    {
        _cts.Cancel();
        if (IsOpen) _ = _socket.CloseAsync(WebSocketCloseStatus.NormalClosure, reason, CancellationToken.None);
    }
}
