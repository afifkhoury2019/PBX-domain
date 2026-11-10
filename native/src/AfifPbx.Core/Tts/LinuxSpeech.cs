using System.Diagnostics;
using AfifPbx.Media;

namespace AfifPbx.Tts;

/// <summary>
/// Offline Linux speech: Piper when a voice model is installed, espeak-ng as the fallback.
/// Both write a WAV at their native rate; <see cref="Wav"/> converts it to the 8 kHz call format.
/// </summary>
public sealed class LinuxSpeech : ISpeech
{
    private readonly Func<string> _voice;
    private readonly object _lock = new();

    public LinuxSpeech(Func<string> voice) => _voice = voice;

    public short[] Speak(string text)
    {
        lock (_lock)
        {
            var wav = TryPiper(text) ?? TryEspeak(text);
            if (wav is not null)
            {
                try { return Wav.Load(wav); }
                catch { }
                finally { try { File.Delete(wav); } catch { } }
            }
            return FallbackSpeech.Render(text);
        }
    }

    public IReadOnlyList<string> Voices()
    {
        var list = new List<string>();
        foreach (var dir in new[] { "/usr/share/piper/voices", "/usr/local/share/piper/voices" })
        {
            if (!Directory.Exists(dir)) continue;
            foreach (var f in Directory.GetFiles(dir, "*.onnx")) list.Add(Path.GetFileNameWithoutExtension(f));
        }
        try
        {
            var psi = new ProcessStartInfo("espeak-ng", "--voices") { RedirectStandardOutput = true, UseShellExecute = false };
            using var p = Process.Start(psi);
            if (p is not null)
            {
                foreach (var line in p.StandardOutput.ReadToEnd().Split('\n').Skip(1))
                {
                    var cols = line.Split((char[]?)null, StringSplitOptions.RemoveEmptyEntries);
                    if (cols.Length >= 4 && !list.Contains(cols[3])) list.Add(cols[3]);
                }
                p.WaitForExit(5000);
            }
        }
        catch { }
        return list;
    }

    private bool IsArabic(string text) => text.Any(c => c >= '\u0600' && c <= '\u06FF');

    private string? TryPiper(string text)
    {
        var model = ResolvePiperModel(IsArabic(text));
        if (model is null) return null;
        var tmp = Path.Combine(Path.GetTempPath(), "afif-tts-" + Guid.NewGuid().ToString("N") + ".wav");
        try
        {
            var psi = new ProcessStartInfo("piper")
            {
                RedirectStandardInput = true,
                RedirectStandardOutput = true,
                RedirectStandardError = true,
                UseShellExecute = false,
            };
            psi.ArgumentList.Add("--model");
            psi.ArgumentList.Add(model);
            psi.ArgumentList.Add("--output_file");
            psi.ArgumentList.Add(tmp);
            using var p = Process.Start(psi);
            if (p is null) return null;
            p.StandardInput.Write(text);
            p.StandardInput.Close();
            if (!p.WaitForExit(20000)) { try { p.Kill(true); } catch { } return null; }
            return p.ExitCode == 0 && File.Exists(tmp) ? tmp : null;
        }
        catch
        {
            try { File.Delete(tmp); } catch { }
            return null;
        }
    }

    private string? ResolvePiperModel(bool arabic)
    {
        var requested = _voice()?.Trim() ?? "";
        var candidates = new List<string>();
        if (requested.EndsWith(".onnx", StringComparison.OrdinalIgnoreCase)) candidates.Add(requested);
        else if (requested.Length > 0) candidates.Add(requested + ".onnx");

        if (arabic)
        {
            candidates.Add("/usr/share/piper/voices/ar_JO-kareem-medium.onnx");
            candidates.Add("/usr/share/piper/voices/ar_JO-kareem-low.onnx");
        }
        candidates.Add("/usr/share/piper/voices/en_US-amy-medium.onnx");
        candidates.Add("/usr/share/piper/voices/en_US-lessac-medium.onnx");
        candidates.Add("/usr/share/piper/voices/en_GB-alan-medium.onnx");

        foreach (var c in candidates)
        {
            if (File.Exists(c)) return c;
            var inVoices = Path.Combine("/usr/share/piper/voices", Path.GetFileName(c));
            if (File.Exists(inVoices)) return inVoices;
        }
        return null;
    }

    private string? TryEspeak(string text)
    {
        var tmp = Path.Combine(Path.GetTempPath(), "afif-tts-" + Guid.NewGuid().ToString("N") + ".wav");
        try
        {
            var psi = new ProcessStartInfo("espeak-ng")
            {
                RedirectStandardInput = true,
                RedirectStandardOutput = true,
                RedirectStandardError = true,
                UseShellExecute = false,
            };
            var voice = _voice()?.Trim();
            if (string.IsNullOrWhiteSpace(voice)) voice = IsArabic(text) ? "ar" : "en";
            psi.ArgumentList.Add("-v");
            psi.ArgumentList.Add(voice);
            psi.ArgumentList.Add("-s");
            psi.ArgumentList.Add("150");
            psi.ArgumentList.Add("-w");
            psi.ArgumentList.Add(tmp);
            psi.ArgumentList.Add("--stdin");
            using var p = Process.Start(psi);
            if (p is null) return null;
            p.StandardInput.Write(text);
            p.StandardInput.Close();
            if (!p.WaitForExit(15000)) { try { p.Kill(true); } catch { } return null; }
            return p.ExitCode == 0 && File.Exists(tmp) ? tmp : null;
        }
        catch
        {
            try { File.Delete(tmp); } catch { }
            return null;
        }
    }
}
