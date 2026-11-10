using System.IO.Ports;
using System.Text.RegularExpressions;

namespace AfifPbx.Gsm;

public sealed record DonglePorts(string Id, string Name, string AtPort, string AudioPort);

/// <summary>
/// Linux discovery for the Huawei E303. It reads the USB interface number from sysfs
/// instead of WMI: interface 02 is the AT/PC-UI port and interface 01 is the voice port.
/// The udev rules also create /dev/afif-gsm-at and /dev/afif-gsm-voice.
/// </summary>
internal static class LinuxPortDiscovery
{
    private static readonly string[] TtyGlobs = { "/dev/ttyUSB*", "/dev/ttyACM*" };

    internal static IReadOnlyList<DiscoveredPort> Ports()
    {
        var list = new List<DiscoveredPort>();
        foreach (var pattern in TtyGlobs)
        {
            foreach (var path in SafeGlob(pattern))
            {
                var tty = Path.GetFileName(path);
                var (vendor, product, iface, productName) = UsbInfo(tty);
                var name = $"{(string.IsNullOrWhiteSpace(productName) ? "USB serial" : productName)} {vendor}:{product} if{iface}";
                list.Add(new DiscoveredPort(path, name.Trim()));
            }
        }
        // Stable names from 99-afif-pbx.rules take priority.
        foreach (var stable in new[] { "/dev/afif-gsm-at", "/dev/afif-gsm-voice" })
            if (File.Exists(stable) && list.All(p => p.Port != stable))
                list.Add(new DiscoveredPort(stable, Path.GetFileName(stable)));
        return list.OrderBy(p => p.Port).ToList();
    }

    /// <summary>One entry per physical Huawei modem, with AT (if 03) and voice (if 02).</summary>
    internal static IReadOnlyList<DonglePorts> DiscoverDongles()
    {
        if (!OperatingSystem.IsLinux()) return Array.Empty<DonglePorts>();
        var result = new List<DonglePorts>();
        string[] usbDevices;
        try { usbDevices = Directory.GetDirectories("/sys/bus/usb/devices"); }
        catch { return result; }

        foreach (var usbDir in usbDevices)
        {
            if (Read(Path.Combine(usbDir, "idVendor")) != "12d1") continue;
            var id = Path.GetFileName(usbDir);
            var name = Read(Path.Combine(usbDir, "product"));
            if (string.IsNullOrWhiteSpace(name)) name = $"Huawei {id}";
            string at = "", audio = "";
            foreach (var ifaceDir in Directory.GetDirectories(usbDir, "*:1.*"))
            {
                var number = Read(Path.Combine(ifaceDir, "bInterfaceNumber"));
                if (number is not ("02" or "03")) continue;
                string? tty = null;
                try
                {
                    tty = Directory.GetDirectories(ifaceDir, "ttyUSB*")
                        .Select(Path.GetFileName).FirstOrDefault();
                }
                catch { }
                if (string.IsNullOrEmpty(tty)) continue;
                if (number == "03") at = "/dev/" + tty;
                else audio = "/dev/" + tty;
            }
            if (at.Length > 0 || audio.Length > 0)
                result.Add(new DonglePorts(id, name, at, audio));
        }
        return result.OrderBy(d => d.AtPort).ToList();
    }

    internal static IReadOnlyList<string> HuaweiDevices()
    {
        var list = new List<string>();
        foreach (var dev in SafeGlob("/sys/bus/usb/devices/*"))
        {
            try
            {
                var vendor = Read(Path.Combine(dev, "idVendor"));
                if (vendor != "12d1") continue;
                var product = Read(Path.Combine(dev, "idProduct"));
                var name = Read(Path.Combine(dev, "product"));
                list.Add($"{name} [12d1:{product}] {Path.GetFileName(dev)}");
            }
            catch { }
        }
        return list;
    }

    internal static (IModemPorts?, string, string) Open(string configuredAt, string configuredAudio)
    {
        if (!string.IsNullOrWhiteSpace(configuredAt) && !configuredAt.StartsWith("USB:", StringComparison.OrdinalIgnoreCase))
        {
            try { return (new SerialModemPorts(configuredAt, configuredAudio), "starting", ""); }
            catch (UnauthorizedAccessException) { return (null, "port-busy", $"{configuredAt} is in use by another program."); }
            catch (Exception ex) { return (null, "error", $"Could not open {configuredAt}/{configuredAudio}: {ex.Message}"); }
        }

        if (File.Exists("/dev/afif-gsm-at"))
        {
            var audio = File.Exists("/dev/afif-gsm-voice") ? "/dev/afif-gsm-voice" : "";
            try { return (new SerialModemPorts("/dev/afif-gsm-at", audio), "starting", ""); }
            catch (Exception ex) { return (null, "port-busy", $"Could not open /dev/afif-gsm-at: {ex.Message}"); }
        }

        var ports = Ports().Where(p => !p.Port.Contains("afif-gsm", StringComparison.Ordinal)).ToList();
        // E303: interface 00 is AT/PC UI, interface 02 is DIAG/voice.
        string? at = ports.FirstOrDefault(p => Regex.IsMatch(p.Name, @"\bif0?3\b"))?.Port;
        string? audioPort = ports.FirstOrDefault(p => Regex.IsMatch(p.Name, @"\bif0?2\b"))?.Port;

        if (at is null)
        {
            foreach (var p in ports)
            {
                if (Probe(p.Port)) { at = p.Port; break; }
            }
        }
        if (at is not null)
        {
            // Do not guess the voice port from AT responses: the DIAG port does
            // not answer AT. Without a udev symlink, run without audio.
            try { return (new SerialModemPorts(at, audioPort ?? ""), "starting", ""); }
            catch (Exception ex) { return (null, "port-busy", $"Could not open {at}: {ex.Message}"); }
        }

        if (HuaweiDevices().Count > 0)
            return (null, "no-driver", "The Huawei dongle is present but its serial ports are not available. Check the 99-afif-pbx.rules udev rule and usb_modeswitch.");
        return (null, "no-device", "No GSM dongle found. Plug in the Huawei dongle.");
    }

    private static bool Probe(string path)
    {
        try
        {
            using var sp = new SerialPort(path, 115200, Parity.None, 8, StopBits.One)
            {
                Handshake = Handshake.None,
                DtrEnable = true,
                RtsEnable = true,
                ReadTimeout = 1200,
                WriteTimeout = 1200,
            };
            sp.Open();
            sp.DiscardInBuffer();
            sp.Write("AT\r");
            Thread.Sleep(250);
            var reply = sp.ReadExisting();
            return reply.Contains("OK", StringComparison.OrdinalIgnoreCase);
        }
        catch { return false; }
    }

    private static IEnumerable<string> SafeGlob(string pattern)
    {
        try
        {
            var dir = Path.GetDirectoryName(pattern) ?? "/";
            var file = Path.GetFileName(pattern);
            if (!Directory.Exists(dir)) return Array.Empty<string>();
            return Directory.GetFiles(dir, file);
        }
        catch { return Array.Empty<string>(); }
    }

    private static string RealTtyPath(string tty)
    {
        try
        {
            var device = new DirectoryInfo(Path.Combine("/sys/class/tty", tty, "device"));
            return device.ResolveLinkTarget(true)?.FullName ?? device.FullName;
        }
        catch { return ""; }
    }

    private static string FindParentWithFile(string start, string fileName)
    {
        try
        {
            var d = new DirectoryInfo(start);
            while (d is not null)
            {
                if (File.Exists(Path.Combine(d.FullName, fileName))) return d.FullName;
                d = d.Parent;
            }
        }
        catch { }
        return "";
    }

    private static string UsbDirFor(string tty)
    {
        var real = RealTtyPath(tty);
        return string.IsNullOrEmpty(real) ? "" : FindParentWithFile(real, "idVendor");
    }

    private static (string vendor, string product, string iface, string name) UsbInfo(string tty)
    {
        try
        {
            var real = RealTtyPath(tty);
            if (string.IsNullOrEmpty(real)) return ("", "", "", "");
            var ifaceDir = FindParentWithFile(real, "bInterfaceNumber");
            var iface = string.IsNullOrEmpty(ifaceDir) ? "" : Read(Path.Combine(ifaceDir, "bInterfaceNumber"));
            var usbDir = FindParentWithFile(real, "idVendor");
            var vendor = string.IsNullOrEmpty(usbDir) ? "" : Read(Path.Combine(usbDir, "idVendor"));
            var product = string.IsNullOrEmpty(usbDir) ? "" : Read(Path.Combine(usbDir, "idProduct"));
            var name = string.IsNullOrEmpty(usbDir) ? "" : Read(Path.Combine(usbDir, "product"));
            return (vendor, product, iface, name);
        }
        catch { return ("", "", "", ""); }
    }

    private static string Read(string path)
    {
        try { return File.Exists(path) ? File.ReadAllText(path).Trim() : ""; }
        catch { return ""; }
    }
}
