using System.Net.NetworkInformation;
using PrivacyGuard.Core;

namespace PrivacyGuard.Platform;

public static class Wifi
{
    /// <summary>The Wi-Fi the PC is connected to now, or null.</summary>
    public static WifiCheck.State? Current()
    {
        var (code, o) = Native.Run("netsh.exe", "wlan show interfaces", 15_000);
        if (code != 0 || WifiCheck.ParseNetsh(o) is not { } w) return null;
        var nic = NetworkInterface.GetAllNetworkInterfaces()
            .FirstOrDefault(n => n.NetworkInterfaceType == NetworkInterfaceType.Wireless80211 && n.OperationalStatus == OperationalStatus.Up);
        var dns = nic?.GetIPProperties().DnsAddresses.Select(a => (a.ToString(), WifiCheck.DnsLabel(a))).ToList() ?? new();
        return new WifiCheck.State(w.Ssid, w.Auth, WifiCheck.IsWeak(w.Auth), dns);
    }
}
