namespace AfifPbx.Tts;

/// <summary>Chooses the platform speech engine: SAPI on Windows, Piper/espeak-ng on Linux.</summary>
public static class SpeechFactory
{
    public static ISpeech Create(Func<string> voice) =>
        OperatingSystem.IsWindows() ? new WindowsSpeech(voice) : new LinuxSpeech(voice);
}
