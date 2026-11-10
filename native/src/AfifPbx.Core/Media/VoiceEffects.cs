using SoundTouch;

namespace AfifPbx.Media;

/// <summary>Original AFIF voice effects, applied to the SIP caller's uplink audio.</summary>
public enum VoiceEffect
{
    Natural,
    Robot,
    Deep,
    Chipmunk,
    Echo,
    Baby,
    Woman,
    OldMan
}

public static class VoiceEffects
{
    public static readonly string[] Names =
    {
        "natural", "robot", "deep", "chipmunk", "echo", "baby", "woman", "oldman"
    };

    public static bool TryParse(string? value, out VoiceEffect effect)
    {
        effect = VoiceEffect.Natural;
        if (string.IsNullOrWhiteSpace(value)) return false;
        var key = value.Trim().ToLowerInvariant().Replace("_", "").Replace("-", "").Replace(" ", "");
        switch (key)
        {
            case "natural": effect = VoiceEffect.Natural; return true;
            case "robot": effect = VoiceEffect.Robot; return true;
            case "deep": effect = VoiceEffect.Deep; return true;
            case "chipmunk": effect = VoiceEffect.Chipmunk; return true;
            case "echo": effect = VoiceEffect.Echo; return true;
            case "baby": effect = VoiceEffect.Baby; return true;
            case "woman": effect = VoiceEffect.Woman; return true;
            case "oldman": effect = VoiceEffect.OldMan; return true;
            default: return false;
        }
    }
}

/// <summary>
/// Per-call stateful processor. Pitch effects use SoundTouch (LGPL) so the
/// original duration is preserved; robot/echo and tone shaping are AFIF code.
/// </summary>
public sealed class VoiceEffectProcessor : IDisposable
{
    private readonly VoiceEffect _effect;
    private readonly SoundTouchProcessor? _pitch;
    private readonly Queue<short> _pending = new();
    private readonly short[] _delay = new short[Audio.SampleRate * 140 / 1000];
    private int _delayPos;
    private double _phase;
    private double _lp;
    private double _tremolo;
    private readonly double _lpAlpha;
    private readonly double _tremoloHz;

    public VoiceEffectProcessor(VoiceEffect effect)
    {
        _effect = effect;
        switch (effect)
        {
            case VoiceEffect.Deep:
                _pitch = NewPitch(-6.0);
                _lpAlpha = 0.12;
                break;
            case VoiceEffect.Chipmunk:
                _pitch = NewPitch(8.0);
                break;
            case VoiceEffect.Baby:
                _pitch = NewPitch(10.0);
                _lpAlpha = 0.55;
                break;
            case VoiceEffect.Woman:
                _pitch = NewPitch(4.0);
                _lpAlpha = 0.45;
                break;
            case VoiceEffect.OldMan:
                _pitch = NewPitch(-4.0);
                _lpAlpha = 0.10;
                _tremoloHz = 4.2;
                break;
            default:
                _lpAlpha = 0.5;
                break;
        }
    }

    private static SoundTouchProcessor NewPitch(double semitones)
    {
        var p = new SoundTouchProcessor
        {
            SampleRate = Audio.SampleRate,
            Channels = 1,
            PitchSemiTones = semitones,
            Tempo = 1.0
        };
        return p;
    }

    public short[] Process(ReadOnlySpan<short> input)
    {
        var output = new short[input.Length];
        if (_effect == VoiceEffect.Natural)
        {
            input.CopyTo(output);
            return output;
        }

        if (_pitch is not null)
        {
            var floats = new float[input.Length];
            for (int i = 0; i < input.Length; i++) floats[i] = input[i] / 32768f;
            _pitch.PutSamples(floats, floats.Length);
            var outFloats = new float[4096];
            int got = _pitch.ReceiveSamples(outFloats, outFloats.Length);
            for (int i = 0; i < got; i++)
            {
                double v = outFloats[i];
                if (_effect is VoiceEffect.Deep or VoiceEffect.OldMan or VoiceEffect.Woman or VoiceEffect.Baby)
                {
                    _lp += _lpAlpha * (v - _lp);
                    v = _lp;
                }
                if (_effect == VoiceEffect.OldMan)
                {
                    _tremolo += 2 * Math.PI * _tremoloHz / Audio.SampleRate;
                    v *= 0.78 + 0.22 * Math.Sin(_tremolo);
                }
                v = Math.Clamp(v, -1.0, 1.0);
                _pending.Enqueue((short)(v * 32767));
            }
            return Drain(input.Length);
        }

        if (_effect == VoiceEffect.Robot)
        {
            for (int i = 0; i < input.Length; i++)
            {
                _phase += 2 * Math.PI * 55 / Audio.SampleRate;
                double mod = 0.5 + 0.5 * Math.Sin(_phase);
                output[i] = (short)Math.Clamp(input[i] * mod * 0.95, short.MinValue, short.MaxValue);
            }
            return output;
        }

        if (_effect == VoiceEffect.Echo)
        {
            for (int i = 0; i < input.Length; i++)
            {
                short delayed = _delay[_delayPos];
                double v = input[i] * 0.72 + delayed * 0.45;
                _delay[_delayPos] = (short)Math.Clamp(v, short.MinValue, short.MaxValue);
                _delayPos = (_delayPos + 1) % _delay.Length;
                output[i] = (short)Math.Clamp(v, short.MinValue, short.MaxValue);
            }
            return output;
        }

        input.CopyTo(output);
        return output;
    }

    private short[] Drain(int length)
    {
        var result = new short[length];
        for (int i = 0; i < length; i++)
            result[i] = _pending.Count > 0 ? _pending.Dequeue() : (short)0;
        return result;
    }

    public void Dispose() { }
}
