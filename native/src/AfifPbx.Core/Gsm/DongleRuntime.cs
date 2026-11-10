using AfifPbx.Data;

namespace AfifPbx.Gsm;

/// <summary>A configured modem plus its live driver and resolved ports.</summary>
public sealed class DongleRuntime
{
    public required DongleConfig Config { get; init; }
    public required HuaweiModem Modem { get; init; }
    public string AtPort { get; init; } = "";
    public string AudioPort { get; init; } = "";
    public string Id => Config.Id;
}
