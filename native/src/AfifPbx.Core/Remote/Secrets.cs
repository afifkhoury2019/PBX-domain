using System.Security.Cryptography;
using System.Text;

namespace AfifPbx.Remote;

/// <summary>
/// Stores small secrets (the DNS API token) encrypted for this machine.
/// Windows keeps DPAPI; Linux uses AES-GCM with a 32-byte key in
/// <c>&lt;data&gt;/.key</c> (mode 0600). Older <c>plain:</c> values are accepted.
/// </summary>
public static class Secrets
{
    private static readonly byte[] Entropy = Encoding.UTF8.GetBytes("AFIF-PBX bridge");
    private static readonly object Gate = new();
    private static string _dataDir = "";
    private static byte[]? _key;

    /// <summary>Call once at startup with the PBX data directory.</summary>
    public static void Configure(string dataDir)
    {
        lock (Gate)
        {
            _dataDir = dataDir;
            _key = null;
        }
    }

    private static string KeyPath()
    {
        var dir = string.IsNullOrWhiteSpace(_dataDir)
            ? Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.UserProfile), ".afif-pbx")
            : _dataDir;
        Directory.CreateDirectory(dir);
        return Path.Combine(dir, ".key");
    }

    private static byte[] Key()
    {
        lock (Gate)
        {
            if (_key is not null) return _key;
            var path = KeyPath();
            if (File.Exists(path))
            {
                var existing = File.ReadAllBytes(path);
                if (existing.Length == 32) return _key = existing;
            }
            var key = RandomNumberGenerator.GetBytes(32);
            File.WriteAllBytes(path, key);
            if (!OperatingSystem.IsWindows())
            {
                try { File.SetUnixFileMode(path, UnixFileMode.UserRead | UnixFileMode.UserWrite); } catch { }
            }
            return _key = key;
        }
    }

    public static string Protect(string secret)
    {
        if (string.IsNullOrEmpty(secret)) return "";
        var bytes = Encoding.UTF8.GetBytes(secret);
        if (OperatingSystem.IsWindows())
            return "dpapi:" + Convert.ToBase64String(ProtectedData.Protect(bytes, Entropy, DataProtectionScope.LocalMachine));

        var nonce = RandomNumberGenerator.GetBytes(12);
        var tag = new byte[16];
        var cipher = new byte[bytes.Length];
        using (var aes = new AesGcm(Key(), 16))
            aes.Encrypt(nonce, bytes, cipher, tag);
        var packed = new byte[nonce.Length + tag.Length + cipher.Length];
        Buffer.BlockCopy(nonce, 0, packed, 0, nonce.Length);
        Buffer.BlockCopy(tag, 0, packed, nonce.Length, tag.Length);
        Buffer.BlockCopy(cipher, 0, packed, nonce.Length + tag.Length, cipher.Length);
        return "aes:" + Convert.ToBase64String(packed);
    }

    public static string Unprotect(string stored)
    {
        try
        {
            if (string.IsNullOrEmpty(stored)) return "";
            if (stored.StartsWith("dpapi:") && OperatingSystem.IsWindows())
                return Encoding.UTF8.GetString(ProtectedData.Unprotect(Convert.FromBase64String(stored[6..]), Entropy, DataProtectionScope.LocalMachine));
            if (stored.StartsWith("aes:") && !OperatingSystem.IsWindows())
            {
                var packed = Convert.FromBase64String(stored[4..]);
                if (packed.Length < 28) return "";
                var nonce = packed.AsSpan(0, 12);
                var tag = packed.AsSpan(12, 16);
                var cipher = packed.AsSpan(28);
                var plain = new byte[cipher.Length];
                using var aes = new AesGcm(Key(), 16);
                aes.Decrypt(nonce, cipher, tag, plain);
                return Encoding.UTF8.GetString(plain);
            }
            if (stored.StartsWith("plain:")) return Encoding.UTF8.GetString(Convert.FromBase64String(stored[6..]));
        }
        catch { }
        return "";
    }
}
