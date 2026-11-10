using System.Collections.Concurrent;
using System.Net;
using System.Net.Sockets;
using System.Security.Cryptography;
using System.Text.Json;
using AfifPbx;
using AfifPbx.Data;
using AfifPbx.Gsm;
using AfifPbx.Media;
using AfifPbx.Server;
using AfifPbx.Sip;
using Microsoft.AspNetCore.Http.Json;

// ---------------------------------------------------------------- start-up
string dataDir = ConfigStore.DefaultDataDir();
for (int i = 0; i < args.Length - 1; i++) if (args[i] == "--data") dataDir = args[i + 1];
Directory.CreateDirectory(dataDir);

var logProvider = new PbxLogProvider(dataDir);
using var loggerFactory = LoggerFactory.Create(b => b.AddProvider(logProvider).SetMinimumLevel(LogLevel.Information));
var startLog = loggerFactory.CreateLogger("AFIF-PBX");
string version = typeof(Program).Assembly.GetName().Version?.ToString(3) ?? "2.0.0";
startLog.LogInformation("AFIF-PBX {Version} starting (data: {Dir})", version, dataDir);

var engine = new PbxEngine(dataDir, loggerFactory);
try { engine.Start(); }
catch (SocketException ex)
{
    startLog.LogError("SIP port {Port} is in use by another program: {Message}", engine.Config.Current.Settings.SipPort, ex.Message);
    throw;
}

// HTTP port: the configured one, or 8080 if something (IIS, Skype...) already holds it
int httpPort = engine.Config.Current.Settings.HttpPort;
if (!PortFree(httpPort))
{
    startLog.LogWarning("Port {Port} is in use; the web panel will use 8080", httpPort);
    httpPort = PortFree(8080) ? 8080 : 8088;
}
File.WriteAllText(Path.Combine(dataDir, "http-port.txt"), httpPort.ToString());

// the built-in secure network (Tailscale engine inside AFIF-PBX — nothing installed in Windows)
var tailscale = new AfifPbx.Remote.TailscaleManager(dataDir, loggerFactory.CreateLogger("Tailscale"), sip: engine.Sip,
    rtpRange: () => (engine.Config.Current.Settings.TailnetRtpStart, engine.Config.Current.Settings.TailnetRtpEnd));
tailscale.Start();
// Telegram: incoming SMS and missed GSM calls go to your bot
var telegram = new AfifPbx.Remote.TelegramForwarder(dataDir, loggerFactory.CreateLogger("Telegram"));
engine.SmsReceived += m => telegram.Sms(m.Number, m.Text, m.Time);
engine.Calls.CallLogged += r => telegram.Call(r);
telegram.Start();
Firewall.Ensure(startLog);

var builder = WebApplication.CreateBuilder(new WebApplicationOptions
{
    Args = args,
    ContentRootPath = AppContext.BaseDirectory,
    WebRootPath = Path.Combine(AppContext.BaseDirectory, "wwwroot"),
});
if (OperatingSystem.IsWindows())
    builder.Host.UseWindowsService(o => o.ServiceName = "AFIF-PBX");
else
    builder.Host.UseSystemd();
builder.Logging.ClearProviders();
builder.Logging.AddProvider(logProvider);
builder.Logging.SetMinimumLevel(LogLevel.Warning);
builder.WebHost.ConfigureKestrel(k =>
{
    k.ListenAnyIP(httpPort); // IPv4 and IPv6 — browsers often try [::1] for "localhost" first
    k.Limits.MaxRequestBodySize = 50 * 1024 * 1024;
});
builder.Services.Configure<JsonOptions>(o =>
{
    o.SerializerOptions.PropertyNamingPolicy = JsonNamingPolicy.CamelCase;
    o.SerializerOptions.WriteIndented = false;
});

var app = builder.Build();
var sessions = new ConcurrentDictionary<string, DateTime>();
const string Cookie = "afif_session";

app.Lifetime.ApplicationStopping.Register(() =>
{
    startLog.LogInformation("AFIF-PBX stopping");
    tailscale.Dispose();
    telegram.Dispose();
    engine.Dispose();
});

app.UseWebSockets();

// Direct WebSocket registration used by AFIF Phone over Cloudflare Tunnel.
app.Map("/register/{extension}", async (HttpContext ctx, string extension) =>
{
    if (engine.Bridge is null) { ctx.Response.StatusCode = 503; return; }
    if (!ctx.WebSockets.IsWebSocketRequest) { ctx.Response.StatusCode = 400; return; }
    var secret = ctx.WebSockets.WebSocketRequestedProtocols.FirstOrDefault();
    using var socket = await ctx.WebSockets.AcceptWebSocketAsync(secret);
    await engine.Bridge.AcceptAsync(socket, extension, secret);
});

// ---------------------------------------------------------------- auth
app.Use(async (ctx, next) =>
{
    var path = ctx.Request.Path.Value ?? "";
    if (path.StartsWith("/api/") && path != "/api/login" && !path.StartsWith("/api/local/"))
    {
        bool ok = ctx.Request.Cookies.TryGetValue(Cookie, out var token) && token is not null
                  && sessions.TryGetValue(token, out var exp) && exp > DateTime.UtcNow;
        if (!ok) { ctx.Response.StatusCode = 401; return; }
        sessions[token!] = DateTime.UtcNow.AddHours(12);
    }
    if (path.StartsWith("/api/local/") && !IPAddress.IsLoopback(ctx.Connection.RemoteIpAddress ?? IPAddress.None))
    {
        ctx.Response.StatusCode = 403; return;
    }
    await next();
});

app.UseDefaultFiles();
app.UseStaticFiles(new StaticFileOptions
{
    OnPrepareResponse = c => c.Context.Response.Headers.CacheControl = "no-cache",
});

// AFIF Phone posts crash reports and diagnostic logs here. It is reachable
// without an admin cookie so a crashing app can still upload, but only from
// the LAN / secure-network ranges.
app.MapPost("/client-log", async (HttpRequest req) =>
{
    var ip = req.HttpContext.Connection.RemoteIpAddress ?? IPAddress.None;
    if (!IsPrivateAddress(ip)) return Results.StatusCode(403);
    using var reader = new StreamReader(req.Body, System.Text.Encoding.UTF8);
    string body = await reader.ReadToEndAsync();
    if (body.Length == 0) return Results.BadRequest();
    if (body.Length > 2_000_000) return Results.StatusCode(413);
    var dir = Path.Combine(dataDir, "Logs");
    Directory.CreateDirectory(dir);
    var file = Path.Combine(dir, $"client-{DateTime.Now:yyyyMMdd-HHmmss}-{Guid.NewGuid():N}.log");
    await File.WriteAllTextAsync(file, body);
    return Results.Ok(new { saved = Path.GetFileName(file) });
});

// AFIF Phone selects a voice effect here. Private-network only; the extension
// is validated against the PBX configuration and applies to that extension's
// next bridged call.
app.MapPost("/client-voice", async (HttpRequest req) =>
{
    var ip = req.HttpContext.Connection.RemoteIpAddress ?? IPAddress.None;
    if (!IsPrivateAddress(ip)) return Results.StatusCode(403);
    using var reader = new StreamReader(req.Body, System.Text.Encoding.UTF8);
    string body = await reader.ReadToEndAsync();
    if (body.Length == 0 || body.Length > 16_384) return Results.BadRequest();
    try
    {
        using var doc = JsonDocument.Parse(body);
        var root = doc.RootElement;
        string ext = root.TryGetProperty("extension", out var e) ? e.GetString() ?? "" : "";
        string name = root.TryGetProperty("effect", out var f) ? f.GetString() ?? "" : "";
        if (!VoiceEffects.TryParse(name, out var effect))
            return Results.Json(new { error = "unknown effect" }, statusCode: 400);
        if (!engine.Calls.SetVoiceEffect(ext, effect))
            return Results.Json(new { error = "unknown extension" }, statusCode: 404);
        startLog.LogInformation("Client voice effect: extension {Extension} -> {Effect} from {Ip}", ext, effect, ip);
        return Results.Ok(new { extension = ext, effect = name.ToLowerInvariant() });
    }
    catch (JsonException)
    {
        return Results.BadRequest();
    }
});

// GET form for clients behind Cloudflare's POST/WAF policy.
app.MapGet("/client-voice", (HttpRequest req, string? extension, string? effect) =>
{
    var ip = req.HttpContext.Connection.RemoteIpAddress ?? IPAddress.None;
    if (!IsPrivateAddress(ip)) return Results.StatusCode(403);
    if (!VoiceEffects.TryParse(effect, out var parsed))
        return Results.Json(new { error = "unknown effect" }, statusCode: 400);
    var ext = (extension ?? "").Trim();
    if (!engine.Calls.SetVoiceEffect(ext, parsed))
        return Results.Json(new { error = "unknown extension" }, statusCode: 404);
    startLog.LogInformation("Client voice effect (GET): extension {Extension} -> {Effect} from {Ip}", ext, parsed, ip);
    return Results.Ok(new { extension = ext, effect = (effect ?? "").ToLowerInvariant() });
});

var api = app.MapGroup("/api");

api.MapGet("/diagnostics", () =>
{
    var sb = new System.Text.StringBuilder();
    sb.AppendLine("AFIF-PBX diagnostics");
    sb.AppendLine("time: " + DateTime.Now.ToString("O"));
    sb.AppendLine("version: " + typeof(Program).Assembly.GetName().Version);
    sb.AppendLine("status: " + System.Text.Json.JsonSerializer.Serialize(StatusDto()));
    sb.AppendLine();
    var dir = Path.Combine(dataDir, "Logs");
    if (Directory.Exists(dir))
    {
        foreach (var f in Directory.GetFiles(dir, "*.log").OrderByDescending(File.GetLastWriteTimeUtc).Take(5))
        {
            sb.AppendLine($"===== {Path.GetFileName(f)} =====");
            try
            {
                var lines = File.ReadAllLines(f);
                sb.AppendLine(string.Join('\n', lines.TakeLast(300)));
            }
            catch (Exception ex) { sb.AppendLine("read failed: " + ex.Message); }
            sb.AppendLine();
        }
    }
    return Results.Text(sb.ToString(), "text/plain; charset=utf-8");
});

static bool IsPrivateAddress(IPAddress ip)
{
    if (IPAddress.IsLoopback(ip)) return true;
    if (ip.IsIPv4MappedToIPv6) ip = ip.MapToIPv4();
    var b = ip.GetAddressBytes();
    if (b.Length == 4)
    {
        return b[0] == 10
            || (b[0] == 172 && b[1] >= 16 && b[1] <= 31)
            || (b[0] == 192 && b[1] == 168)
            || (b[0] == 100 && b[1] >= 64 && b[1] <= 127) // Tailscale/CGNAT
            || (b[0] == 169 && b[1] == 254);
    }
    return b.Length == 16 && (b[0] == 0xfc || b[0] == 0xfd || (b[0] == 0xfe && (b[1] & 0xc0) == 0x80));
}

var loginFailures = new ConcurrentDictionary<string, (int Count, DateTime Until)>();
api.MapPost("/login", (LoginRequest req, HttpContext ctx) =>
{
    string ip = ctx.Connection.RemoteIpAddress?.ToString() ?? "?";
    if (loginFailures.TryGetValue(ip, out var f) && f.Count >= 5 && f.Until > DateTime.UtcNow)
        return Results.Json(new { error = $"Too many wrong passwords. Try again in {(int)Math.Ceiling((f.Until - DateTime.UtcNow).TotalMinutes)} minutes." }, statusCode: 429);
    var s = engine.Config.Current.Settings;
    if (!string.Equals(req.User?.Trim(), s.AdminUser, StringComparison.OrdinalIgnoreCase) || !Passwords.Verify(req.Password ?? "", s.AdminPasswordHash))
    {
        Thread.Sleep(600);
        var cur = loginFailures.GetValueOrDefault(ip);
        loginFailures[ip] = (cur.Until < DateTime.UtcNow ? 1 : cur.Count + 1, DateTime.UtcNow.AddMinutes(15));
        startLog.LogWarning("Failed panel login from {Ip}", ip);
        return Results.Json(new { error = "Wrong user name or password" }, statusCode: 401);
    }
    loginFailures.TryRemove(ip, out _);
    var token = Convert.ToHexString(RandomNumberGenerator.GetBytes(24));
    sessions[token] = DateTime.UtcNow.AddHours(12);
    bool https = ctx.Request.IsHttps || ctx.Request.Headers["X-Forwarded-Proto"].FirstOrDefault() == "https";
    ctx.Response.Cookies.Append(Cookie, token, new CookieOptions { HttpOnly = true, SameSite = SameSiteMode.Strict, Secure = https });
    return Results.Ok(new { user = s.AdminUser, mustChangePassword = s.AdminMustChangePassword });
});

api.MapPost("/logout", (HttpContext ctx) =>
{
    if (ctx.Request.Cookies.TryGetValue(Cookie, out var t) && t is not null) sessions.TryRemove(t, out _);
    ctx.Response.Cookies.Delete(Cookie);
    return Results.Ok();
});

api.MapGet("/me", () => new
{
    user = engine.Config.Current.Settings.AdminUser,
    mustChangePassword = engine.Config.Current.Settings.AdminMustChangePassword,
    version,
});

api.MapPost("/password", (PasswordRequest req) =>
{
    var s = engine.Config.Current.Settings;
    if (!Passwords.Verify(req.Current ?? "", s.AdminPasswordHash)) return Results.Json(new { error = "Current password is wrong" }, statusCode: 400);
    if (string.IsNullOrWhiteSpace(req.New) || req.New.Length < 6) return Results.Json(new { error = "Use at least 6 characters" }, statusCode: 400);
    engine.Config.Update(c =>
    {
        c.Settings.AdminPasswordHash = Passwords.Hash(req.New);
        c.Settings.AdminMustChangePassword = false;
        if (!string.IsNullOrWhiteSpace(req.User)) c.Settings.AdminUser = req.User.Trim();
    });
    return Results.Ok();
});

// ---------------------------------------------------------------- dashboard
object StatusDto() => new
{
    version,
    startedUtc = engine.StartedUtc,
    uptimeSeconds = (long)(DateTime.UtcNow - engine.StartedUtc).TotalSeconds,
    sipPort = engine.Config.Current.Settings.SipPort,
    httpPort,
    addresses = SipServer.LocalAddresses(),
    extensions = engine.Config.Current.Extensions.Select(e => new
    {
        e.Number, e.Name, e.Enabled,
        registered = engine.Sip.IsRegistered(e.Number),
        devices = engine.Sip.Bindings(e.Number).Select(b => new { b.UserAgent, address = b.RemoteEndPoint.ToString(), expires = b.ExpiresUtc }),
    }),
    modem = engine.Modem?.Status,
    gsmEnabled = engine.Config.Current.Settings.GsmEnabled,
    activeCalls = ActiveCallsDto(),
    newVoicemails = engine.Db.Voicemails().Count(v => !v.Heard),
    recentCalls = engine.Db.Calls(8),
    recentSms = engine.Db.Sms(5),
};

object ActiveCallsDto() => engine.Calls.ActiveCalls.Select(c => new
{
    c.Id, c.From, c.FromName, c.To, c.Direction, c.State, c.ConnectedTo, c.Start, c.Answer, recording = !string.IsNullOrEmpty(c.Recording),
}).ToList();

api.MapGet("/status", StatusDto);
app.MapGet("/api/local/status", () => new
{
    running = true,
    version,
    httpPort,
    sipPort = engine.Config.Current.Settings.SipPort,
    addresses = SipServer.LocalAddresses(),
    registered = engine.Config.Current.Extensions.Count(e => engine.Sip.IsRegistered(e.Number)),
    extensions = engine.Config.Current.Extensions.Count,
    activeCalls = engine.Calls.ActiveCalls.Count,
    modem = engine.Modem?.Status,
    newVoicemails = engine.Db.Voicemails().Count(v => !v.Heard),
});
app.MapPost("/api/local/gsm/reconnect", () => { engine.Modem?.Reconnect(); return Results.Ok(); });

// ---------------------------------------------------------------- extensions
api.MapGet("/extensions", () => engine.Config.Current.Extensions.OrderBy(e => e.Number, NumberOrder.Instance));
api.MapPost("/extensions", (Extension ext) =>
{
    var err = ValidateNumber(ext.Number, null);
    if (err is not null) return Results.Json(new { error = err }, statusCode: 400);
    if (string.IsNullOrWhiteSpace(ext.Secret)) ext.Secret = Passwords.NewSecret();
    engine.Config.Update(c => c.Extensions.Add(ext));
    return Results.Ok(ext);
});
api.MapPut("/extensions/{number}", (string number, Extension ext) =>
{
    var err = ValidateNumber(ext.Number, number);
    if (err is not null) return Results.Json(new { error = err }, statusCode: 400);
    if (string.IsNullOrWhiteSpace(ext.Secret)) ext.Secret = Passwords.NewSecret();
    bool found = false;
    engine.Config.Update(c =>
    {
        int i = c.Extensions.FindIndex(e => e.Number == number);
        if (i >= 0) { c.Extensions[i] = ext; found = true; }
        if (number != ext.Number) RenameReferences(c, "ext:" + number, "ext:" + ext.Number, number, ext.Number);
    });
    return found ? Results.Ok(ext) : Results.NotFound();
});
api.MapDelete("/extensions/{number}", (string number) =>
{
    engine.Config.Update(c =>
    {
        c.Extensions.RemoveAll(e => e.Number == number);
        foreach (var rg in c.RingGroups) rg.Members.Remove(number);
    });
    return Results.Ok();
});

// ---------------------------------------------------------------- ring groups
api.MapGet("/ringgroups", () => engine.Config.Current.RingGroups.OrderBy(r => r.Number, NumberOrder.Instance));
api.MapPost("/ringgroups", (RingGroup rg) =>
{
    var err = ValidateNumber(rg.Number, null);
    if (err is not null) return Results.Json(new { error = err }, statusCode: 400);
    engine.Config.Update(c => c.RingGroups.Add(rg));
    return Results.Ok(rg);
});
api.MapPut("/ringgroups/{number}", (string number, RingGroup rg) =>
{
    var err = ValidateNumber(rg.Number, number);
    if (err is not null) return Results.Json(new { error = err }, statusCode: 400);
    engine.Config.Update(c =>
    {
        int i = c.RingGroups.FindIndex(r => r.Number == number);
        if (i >= 0) c.RingGroups[i] = rg;
        if (number != rg.Number) RenameReferences(c, "rg:" + number, "rg:" + rg.Number, null, null);
    });
    return Results.Ok(rg);
});
api.MapDelete("/ringgroups/{number}", (string number) =>
{
    engine.Config.Update(c => c.RingGroups.RemoveAll(r => r.Number == number));
    return Results.Ok();
});

// ---------------------------------------------------------------- IVRs
api.MapGet("/ivrs", () => engine.Config.Current.Ivrs.OrderBy(r => r.Number, NumberOrder.Instance));
api.MapPost("/ivrs", (Ivr ivr) =>
{
    var err = ValidateNumber(ivr.Number, null);
    if (err is not null) return Results.Json(new { error = err }, statusCode: 400);
    engine.Config.Update(c => c.Ivrs.Add(ivr));
    return Results.Ok(ivr);
});
api.MapPut("/ivrs/{number}", (string number, Ivr ivr) =>
{
    var err = ValidateNumber(ivr.Number, number);
    if (err is not null) return Results.Json(new { error = err }, statusCode: 400);
    engine.Config.Update(c =>
    {
        int i = c.Ivrs.FindIndex(r => r.Number == number);
        if (i >= 0) c.Ivrs[i] = ivr;
        if (number != ivr.Number) RenameReferences(c, "ivr:" + number, "ivr:" + ivr.Number, null, null);
    });
    return Results.Ok(ivr);
});
api.MapDelete("/ivrs/{number}", (string number) =>
{
    engine.Config.Update(c => c.Ivrs.RemoveAll(r => r.Number == number));
    return Results.Ok();
});

// ---------------------------------------------------------------- settings
api.MapGet("/settings", () =>
{
    var s = engine.Config.Get().Settings;
    s.AdminPasswordHash = "";
    return s;
});
api.MapPut("/settings", (PbxSettings incoming) =>
{
    bool restartNeeded = false;
    engine.Config.Update(c =>
    {
        var cur = c.Settings;
        incoming.ExtraSipPorts ??= new();
        restartNeeded = cur.SipPort != incoming.SipPort || !cur.ExtraSipPorts.SequenceEqual(incoming.ExtraSipPorts) || cur.HttpPort != incoming.HttpPort || cur.GsmEnabled != incoming.GsmEnabled
                        || cur.RtpPortStart != incoming.RtpPortStart || cur.RtpPortEnd != incoming.RtpPortEnd;
        incoming.AdminPasswordHash = cur.AdminPasswordHash;
        incoming.AdminUser = cur.AdminUser;
        incoming.AdminMustChangePassword = cur.AdminMustChangePassword;
        bool reopen = cur.GsmAtPort != incoming.GsmAtPort || cur.GsmAudioPort != incoming.GsmAudioPort;
        if (cur.TtsVoice != incoming.TtsVoice) engine.Prompts.ClearCache();
        c.Settings = incoming;
        if (reopen) engine.Modem?.Reconnect();
    });
    return Results.Ok(new { restartNeeded });
});

// ---------------------------------------------------------------- GSM line
api.MapGet("/gsm", () => new
{
    enabled = engine.Config.Current.Settings.GsmEnabled,
    status = engine.Modem?.Status,
    ports = PortDiscovery.ListAllPorts(),
    huaweiDevices = PortDiscovery.HuaweiDevices(),
});
api.MapPost("/gsm/reconnect", () => { engine.Modem?.Reconnect(); return Results.Ok(); });
api.MapGet("/dongles", () => engine.Dongles.Select(d => new
{
    id = d.Id, name = d.Config.Name, atPort = d.AtPort, audioPort = d.AudioPort,
    state = d.Modem.Status.State, message = d.Modem.Status.Message,
    callState = d.Modem.Status.CallState, operatorName = d.Modem.Status.Operator,
    registration = d.Modem.Status.Registration, signal = d.Modem.Status.SignalPercent,
    inboundDestination = d.Config.InboundDestination,
    outboundPrefix = d.Config.OutboundPrefix,
    smsExtension = d.Config.SmsExtension
}));
api.MapPost("/gsm/ussd", async (UssdRequest req) =>
{
    if (engine.Modem is null) return Results.Json(new { error = "GSM line is off" }, statusCode: 400);
    var reply = await engine.Modem.SendUssdAsync(req.Code ?? "");
    return reply is null ? Results.Json(new { error = "No reply from the network" }, statusCode: 504) : Results.Ok(new { reply });
});

// ---------------------------------------------------------------- SMS
api.MapGet("/sms", () => engine.Db.Sms());
api.MapPost("/sms", async (SmsRequest req) =>
{
    if (string.IsNullOrWhiteSpace(req.Number) || string.IsNullOrWhiteSpace(req.Text))
        return Results.Json(new { error = "Number and message are required" }, statusCode: 400);
    bool ok = await engine.SendSmsAsync(req.Number.Trim(), req.Text);
    return ok ? Results.Ok() : Results.Json(new { error = "The dongle could not send the SMS" }, statusCode: 502);
});
api.MapDelete("/sms/{id:long}", (long id) => { engine.Db.DeleteSms(id); return Results.Ok(); });

// ---------------------------------------------------------------- voicemail
api.MapGet("/voicemail", (string? mailbox) => engine.Db.Voicemails(string.IsNullOrWhiteSpace(mailbox) ? null : mailbox));
api.MapGet("/voicemail/{id:long}/audio", (long id) =>
{
    var m = engine.Db.Voicemail(id);
    var path = m is null ? null : SafePath(engine.Calls.VoicemailDir, m.File);
    return path is null || !File.Exists(path) ? Results.NotFound() : Results.File(path, "audio/wav", Path.GetFileName(path), enableRangeProcessing: true);
});
api.MapPost("/voicemail/{id:long}/heard", (long id) => { engine.Db.MarkVoicemailHeard(id); return Results.Ok(); });
api.MapDelete("/voicemail/{id:long}", (long id) => { engine.Calls.DeleteVoicemail(id); return Results.Ok(); });

// ---------------------------------------------------------------- recordings
api.MapGet("/recordings", () =>
{
    var dir = engine.Calls.RecordingsDir;
    if (!Directory.Exists(dir)) return Results.Ok(Array.Empty<object>());
    var byFile = engine.Db.Calls(5000).Where(c => !string.IsNullOrEmpty(c.Recording)).GroupBy(c => c.Recording).ToDictionary(g => g.Key, g => g.First());
    var list = Directory.GetFiles(dir, "*.wav", SearchOption.AllDirectories)
        .Select(f => new FileInfo(f))
        .OrderByDescending(f => f.LastWriteTimeUtc)
        .Take(1000)
        .Select(f =>
        {
            var rel = Path.GetRelativePath(dir, f.FullName).Replace('\\', '/');
            byFile.TryGetValue(rel, out var call);
            return new
            {
                path = rel, size = f.Length, time = f.LastWriteTimeUtc,
                seconds = (int)Math.Max(0, (f.Length - 44) / 4 / Audio.SampleRate),
                from = call?.FromNumber ?? "", to = call?.ToNumber ?? "", direction = call?.Direction ?? "",
            };
        });
    return Results.Ok(list);
});
api.MapGet("/recordings/file", (string path) =>
{
    var full = SafePath(engine.Calls.RecordingsDir, path);
    return full is null || !File.Exists(full) ? Results.NotFound() : Results.File(full, "audio/wav", Path.GetFileName(full), enableRangeProcessing: true);
});
api.MapDelete("/recordings", (string path) =>
{
    var full = SafePath(engine.Calls.RecordingsDir, path);
    if (full is null || !File.Exists(full)) return Results.NotFound();
    File.Delete(full);
    engine.Db.ClearRecording(path);
    return Results.Ok();
});

// ---------------------------------------------------------------- call log / live calls
api.MapGet("/calls", (string? search, int? limit) => engine.Db.Calls(Math.Clamp(limit ?? 300, 1, 5000), search));
api.MapGet("/calls/active", ActiveCallsDto);
api.MapPost("/calls/{id}/hangup", (string id) => engine.Calls.HangupCall(id) ? Results.Ok() : Results.NotFound());

// ---------------------------------------------------------------- prompts / speech
api.MapGet("/prompts", () => engine.Prompts.Files());
api.MapPost("/prompts", async (HttpRequest req) =>
{
    if (!req.HasFormContentType) return Results.BadRequest();
    var form = await req.ReadFormAsync();
    var file = form.Files.FirstOrDefault();
    if (file is null) return Results.Json(new { error = "Choose a WAV file" }, statusCode: 400);
    using var ms = new MemoryStream();
    await file.CopyToAsync(ms);
    try { return Results.Ok(new { name = engine.Prompts.SaveUpload(file.FileName, ms.ToArray()) }); }
    catch (Exception ex) { return Results.Json(new { error = ex.Message }, statusCode: 400); }
});
api.MapDelete("/prompts/{name}", (string name) => { engine.Prompts.Delete(name); return Results.Ok(); });
api.MapGet("/prompts/{name}/audio", (string name) =>
{
    var full = SafePath(engine.Prompts.PromptDir, name);
    return full is null || !File.Exists(full) ? Results.NotFound() : Results.File(full, "audio/wav");
});
api.MapPost("/speech/preview", (SpeechRequest req) =>
{
    var samples = engine.Prompts.Say(string.IsNullOrWhiteSpace(req.Text) ? "Welcome to AFIF PBX." : req.Text);
    return Results.File(Wav.ToBytes(samples), "audio/wav");
});
api.MapGet("/voices", () => engine.Prompts.Voices());

// ---------------------------------------------------------------- logs & backup
api.MapGet("/logs", () => logProvider.Recent.Reverse().Take(500));
api.MapGet("/backup", () =>
{
    var json = File.ReadAllBytes(Path.Combine(dataDir, "config.json"));
    return Results.File(json, "application/json", $"afif-pbx-config-{DateTime.Now:yyyyMMdd}.json");
});
api.MapPost("/restore", async (HttpRequest req) =>
{
    using var reader = new StreamReader(req.Body);
    var text = await reader.ReadToEndAsync();
    PbxConfig? cfg;
    try { cfg = JsonSerializer.Deserialize<PbxConfig>(text, new JsonSerializerOptions { PropertyNamingPolicy = JsonNamingPolicy.CamelCase }); }
    catch (Exception ex) { return Results.Json(new { error = "Not a valid AFIF-PBX backup: " + ex.Message }, statusCode: 400); }
    if (cfg is null) return Results.BadRequest();
    engine.Config.Update(c =>
    {
        var keepAdmin = c.Settings;
        c.Extensions = cfg.Extensions; c.RingGroups = cfg.RingGroups; c.Ivrs = cfg.Ivrs;
        cfg.Settings.AdminPasswordHash = keepAdmin.AdminPasswordHash;
        cfg.Settings.AdminUser = keepAdmin.AdminUser;
        cfg.Settings.AdminMustChangePassword = keepAdmin.AdminMustChangePassword;
        c.Settings = cfg.Settings;
    });
    return Results.Ok();
});

// ---------------------------------------------------------------- Tailscale
api.MapGet("/tailscale", async () =>
{
    var st = await tailscale.StatusAsync();
    var s = engine.Config.Current.Settings;
    return Results.Ok(new { status = st, sipPorts = engine.Sip.Ports, mainSipPort = s.SipPort });
});
api.MapPost("/tailscale/connect", async (TailscaleConnectRequest req) =>
{
    var (ok, msg, url) = await tailscale.ConnectAsync(req.AuthKey, req.Hostname);
    return Results.Json(new { ok, message = msg, authUrl = url }, statusCode: ok ? 200 : 400);
});
api.MapPost("/tailscale/logout", async () =>
{
    var (ok, msg) = await tailscale.LogoutAsync();
    return Results.Json(new { ok, message = msg }, statusCode: ok ? 200 : 400);
});
api.MapPost("/tailscale/domain", async (TailscaleDomainRequest req) =>
{
    var (ok, log) = await tailscale.SetDomainAsync(req.Domain ?? "", req.ApiToken);
    return Results.Json(new { ok, log }, statusCode: ok ? 200 : 400);
});
api.MapDelete("/tailscale/domain", () => { tailscale.ClearDomain(); return Results.Ok(); });

// ---------------------------------------------------------------- Telegram
api.MapGet("/telegram", () => telegram.Status());
api.MapPost("/telegram", (TelegramRequest req) =>
{
    var chats = (req.ChatIds ?? "").Split(new[] { ',', ';', ' ', '\n' }, StringSplitOptions.RemoveEmptyEntries);
    if (req.Enabled && !telegram.Status().HasToken && string.IsNullOrWhiteSpace(req.Token))
        return Results.Json(new { error = "Enter the bot token" }, statusCode: 400);
    if (chats.Any(c => !System.Text.RegularExpressions.Regex.IsMatch(c, @"^(-?\d+|@[A-Za-z0-9_]{5,})$")))
        return Results.Json(new { error = "Chat IDs are numbers like 1655852624 (or @channelname)" }, statusCode: 400);
    if (!string.IsNullOrWhiteSpace(req.Token) && !System.Text.RegularExpressions.Regex.IsMatch(req.Token.Trim(), @"^\d+:[A-Za-z0-9_-]{30,}$"))
        return Results.Json(new { error = "That does not look like a bot token (123456789:ABC…)" }, statusCode: 400);
    telegram.Configure(req.Enabled, req.Token, chats, req.ForwardSms ?? true, req.ForwardMissedCalls ?? true);
    return Results.Ok(telegram.Status());
});
api.MapPost("/telegram/test", async () =>
{
    var (ok, msg) = await telegram.TestAsync();
    return Results.Json(new { ok, message = msg }, statusCode: ok ? 200 : 400);
});

// SPA fallback
app.MapFallbackToFile("index.html");

startLog.LogInformation("Web panel on http://localhost:{Port}/  — SIP on port {Sip}", httpPort, engine.Config.Current.Settings.SipPort);
app.Run();

// ---------------------------------------------------------------- helpers
string? ValidateNumber(string? number, string? current)
{
    if (string.IsNullOrWhiteSpace(number) || !number.All(char.IsDigit) || number.Length is < 2 or > 6)
        return "Use 2–6 digits for the number";
    if (number == current) return null;
    var c = engine.Config.Current;
    if (c.Extensions.Any(e => e.Number == number) || c.RingGroups.Any(r => r.Number == number) || c.Ivrs.Any(i => i.Number == number))
        return $"{number} is already used";
    return null;
}

static void RenameReferences(PbxConfig c, string oldDest, string newDest, string? oldExt, string? newExt)
{
    string Fix(string d) => d == oldDest ? newDest : d;
    c.Settings.GsmInboundDestination = Fix(c.Settings.GsmInboundDestination);
    foreach (var e in c.Extensions) e.NoAnswerDestination = Fix(e.NoAnswerDestination);
    foreach (var r in c.RingGroups)
    {
        r.FailoverDestination = Fix(r.FailoverDestination);
        if (oldExt is not null && newExt is not null) r.Members = r.Members.Select(m => m == oldExt ? newExt : m).ToList();
    }
    foreach (var i in c.Ivrs)
    {
        i.TimeoutDestination = Fix(i.TimeoutDestination);
        foreach (var k in i.Options.Keys.ToList()) i.Options[k] = Fix(i.Options[k]);
    }
}

static string? SafePath(string root, string relative)
{
    if (string.IsNullOrWhiteSpace(relative)) return null;
    var full = Path.GetFullPath(Path.Combine(root, relative));
    var rootFull = Path.GetFullPath(root) + Path.DirectorySeparatorChar;
    return full.StartsWith(rootFull, StringComparison.OrdinalIgnoreCase) ? full : null;
}

static bool PortFree(int port)
{
    foreach (var addr in new[] { IPAddress.Any, IPAddress.IPv6Any })
    {
        try
        {
            var l = new TcpListener(addr, port);
            if (addr.Equals(IPAddress.IPv6Any)) l.Server.DualMode = false;
            l.Start();
            l.Stop();
        }
        catch (SocketException ex) when (ex.SocketErrorCode == SocketError.AddressFamilyNotSupported) { }
        catch { return false; }
    }
    // something answering on localhost (e.g. a WSL Apache) also counts as taken
    try
    {
        using var c = new TcpClient(AddressFamily.InterNetworkV6);
        if (c.ConnectAsync(IPAddress.IPv6Loopback, port).Wait(300) && c.Connected) return false;
    }
    catch { }
    return true;
}

record LoginRequest(string? User, string? Password);
record PasswordRequest(string? Current, string? New, string? User);
record SmsRequest(string? Number, string? Text);
record UssdRequest(string? Code);
record SpeechRequest(string? Text);
record TelegramRequest(bool Enabled, string? Token, string? ChatIds, bool? ForwardSms, bool? ForwardMissedCalls);
record TailscaleConnectRequest(string? AuthKey, string? Hostname);
record TailscaleDomainRequest(string? Domain, string? ApiToken);

/// <summary>
/// Keeps the Windows Firewall rules for AFIF-PBX right: phones on the local network may reach SIP, RTP and the
/// panel; the secure network engine (afif-net, encrypted WireGuard) may receive its peer-to-peer packets.
/// Phones on the secure network come in through afif-net, so the PBX itself is not opened to anything else.
/// </summary>
static class Firewall
{
    public const string RuleName = "AFIF-PBX Phone System";
    public const string Remote = "LocalSubnet";
    public const string EngineRule = "AFIF-PBX Secure Network";

    public static void Ensure(ILogger log)
    {
        if (!OperatingSystem.IsWindows() || Environment.ProcessPath is not string exe) return;
        try
        {
            int Run(string args)
            {
                var psi = new System.Diagnostics.ProcessStartInfo("netsh.exe", args) { UseShellExecute = false, CreateNoWindow = true, RedirectStandardOutput = true };
                using var p = System.Diagnostics.Process.Start(psi)!;
                p.StandardOutput.ReadToEnd();
                p.WaitForExit(15000);
                return p.ExitCode;
            }
            // replace any older rule (the first versions allowed the local network only)
            Run($"advfirewall firewall delete rule name=\"{RuleName}\"");
            int code = Run($"advfirewall firewall add rule name=\"{RuleName}\" dir=in action=allow program=\"{exe}\" remoteip={Remote} profile=any enable=yes");
            var engineExe = Path.Combine(AppContext.BaseDirectory, "afif-net.exe");
            Run($"advfirewall firewall delete rule name=\"{EngineRule}\"");
            if (File.Exists(engineExe))
                Run($"advfirewall firewall add rule name=\"{EngineRule}\" dir=in action=allow program=\"{engineExe}\" protocol=udp profile=any enable=yes");
            if (code == 0) log.LogInformation("Firewall: AFIF-PBX open to the local network; secure network engine allowed");
            else log.LogWarning("Firewall rule could not be updated (netsh exit {Code})", code);
        }
        catch (Exception ex) { log.LogWarning("Firewall rule could not be updated: {Message}", ex.Message); }
    }
}

sealed class NumberOrder : IComparer<string>
{
    public static readonly NumberOrder Instance = new();
    public int Compare(string? a, string? b) =>
        long.TryParse(a, out var x) && long.TryParse(b, out var y) ? x.CompareTo(y) : string.CompareOrdinal(a, b);
}
