using System.Text.Json;

namespace PrivacyGuard.Platform;

/// <summary>Store apps (Appx packages) of the current user: list, remove, restore.</summary>
public static class Appx
{
    public sealed record Package(string Name, string Family, string Version);

    public static List<Package> Installed()
    {
        var (code, o) = Native.PowerShell("Get-AppxPackage | Select-Object Name,PackageFamilyName,Version | ConvertTo-Json -Compress");
        if (code != 0 || o.Trim().Length == 0) return new();
        try
        {
            using var doc = JsonDocument.Parse(o.Trim());
            var arr = doc.RootElement.ValueKind == JsonValueKind.Array ? doc.RootElement.EnumerateArray().ToList() : new List<JsonElement> { doc.RootElement };
            return arr.Select(e => new Package(e.GetProperty("Name").GetString()!, e.GetProperty("PackageFamilyName").GetString()!, e.GetProperty("Version").GetString() ?? "")).ToList();
        }
        catch { return new(); }
    }

    static string Q(string s) => s.Replace("'", "''");

    /// <summary>Removes the app for this user. Its files stay while Windows keeps it provisioned, so Restore can bring it back.</summary>
    public static (bool Ok, string Message) Remove(string name)
    {
        if (!Core.Debloat.Allowed(name)) return (false, "This app is not on the list of apps that are safe to remove.");
        var (code, o) = Native.PowerShell($"Get-AppxPackage -Name '{Q(name)}' | Remove-AppxPackage -ErrorAction Stop");
        return code == 0 ? (true, "Removed for your user. Restore brings it back.") : (false, "Windows refused: " + Short(o));
    }

    /// <summary>Re-registers the app from its files; if they are gone, opens it in the Microsoft Store.</summary>
    public static (bool Ok, string Message) Restore(string name, string family)
    {
        var (code, o) = Native.PowerShell(
            $"$p = Get-AppxPackage -AllUsers -Name '{Q(name)}' | Select-Object -First 1; " +
            "if ($p) { Add-AppxPackage -DisableDevelopmentMode -Register (Join-Path $p.InstallLocation 'AppxManifest.xml') -ErrorAction Stop } else { exit 3 }");
        if (code == 0) return (true, "Restored.");
        Native.Open($"ms-windows-store://pdp/?PFN={family}");
        return (false, "Its files are gone, so the Microsoft Store was opened to reinstall it.");
    }

    static string Short(string s) => s.Trim().Split('\n').FirstOrDefault()?.Trim() ?? "";
}
