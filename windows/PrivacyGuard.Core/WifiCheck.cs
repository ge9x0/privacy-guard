using System.Net;
using System.Net.Sockets;
using System.Text.RegularExpressions;

namespace PrivacyGuard.Core;

/// <summary>The Wi-Fi the PC is on: encryption (from `netsh wlan show interfaces`) and DNS servers.</summary>
public static class WifiCheck
{
    public sealed record State(string? Ssid, string? Auth, bool Weak, List<(string Ip, string Label)> Dns);
    public const string Unknown = "unknown public server";

    /// <summary>Parses `netsh wlan show interfaces` (English output). Null when not connected to Wi-Fi.</summary>
    public static (string? Ssid, string? Auth)? ParseNetsh(string text)
    {
        string? Get(string key) => Regex.Match(text, $@"^\s*{key}\s*:\s*(.+?)\s*$", RegexOptions.Multiline).Groups[1].Value is { Length: > 0 } v ? v : null;
        var state = Get("State");
        if (state == null || !state.Equals("connected", StringComparison.OrdinalIgnoreCase)) return null;
        return (Get("SSID"), Get("Authentication"));
    }

    public static bool IsWeak(string? auth) => auth != null &&
        (auth.Equals("Open", StringComparison.OrdinalIgnoreCase) || auth.Contains("WEP", StringComparison.OrdinalIgnoreCase));

    static readonly Dictionary<string, string> KnownDns = new()
    {
        ["8.8.8.8"] = "Google", ["8.8.4.4"] = "Google", ["1.1.1.1"] = "Cloudflare", ["1.0.0.1"] = "Cloudflare",
        ["9.9.9.9"] = "Quad9", ["149.112.112.112"] = "Quad9", ["208.67.222.222"] = "OpenDNS", ["208.67.220.220"] = "OpenDNS",
        ["94.140.14.14"] = "AdGuard", ["94.140.15.15"] = "AdGuard",
    };

    public static string DnsLabel(IPAddress a)
    {
        var b = a.GetAddressBytes();
        if (IPAddress.IsLoopback(a)) return "this PC";
        if (a.AddressFamily == AddressFamily.InterNetwork)
        {
            if (b[0] == 10 || (b[0] == 172 && (b[1] & 0xF0) == 16) || (b[0] == 192 && b[1] == 168) || (b[0] == 169 && b[1] == 254)) return "your router (local)";
            if (b[0] == 100 && (b[1] & 0xC0) == 64) return "provider's network";
        }
        else if (a.IsIPv6LinkLocal || a.IsIPv6SiteLocal || (b[0] & 0xFE) == 0xFC) return "your router (local)";
        return KnownDns.TryGetValue(a.ToString(), out var n) ? n : Unknown;
    }
}
