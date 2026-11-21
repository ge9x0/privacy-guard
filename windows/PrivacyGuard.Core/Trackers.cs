using System.Reflection;
using System.Text.Json;

namespace PrivacyGuard.Core;

/// <summary>Known tracker, ad, attribution and data-broker domains (shared with the Android app and the iPhone page).</summary>
public static class Trackers
{
    public sealed record Hit(string Company, string Category, string CategoryName, int Weight);

    static readonly Dictionary<string, (string Company, string Cat)> List;
    static readonly Dictionary<string, string> Cats;
    static readonly Dictionary<string, int> Weights;

    static Trackers()
    {
        using var s = Assembly.GetExecutingAssembly().GetManifestResourceStream("trackers.json")!;
        using var doc = JsonDocument.Parse(s);
        var r = doc.RootElement;
        Cats = r.GetProperty("cats").EnumerateObject().ToDictionary(p => p.Name, p => p.Value.GetString()!);
        Weights = r.GetProperty("weight").EnumerateObject().ToDictionary(p => p.Name, p => p.Value.GetInt32());
        List = r.GetProperty("list").EnumerateObject().ToDictionary(p => p.Name,
            p => (p.Value[0].GetString()!, p.Value[1].GetString()!), StringComparer.OrdinalIgnoreCase);
    }

    public static IReadOnlyCollection<string> Domains => List.Keys;

    /// <summary>All parent names of a host: a.b.example.com -> [a.b.example.com, b.example.com, example.com].</summary>
    public static IEnumerable<string> Suffixes(string host)
    {
        var p = host.Trim().TrimEnd('.').ToLowerInvariant().Split('.');
        for (int i = 0; i < p.Length - 1; i++) yield return string.Join('.', p[i..]);
    }

    /// <summary>The list entry that matched (e.g. "x-mode.io" for sdk.x-mode.io), to block the whole company domain.</summary>
    public static string? MatchedDomain(string host) => Suffixes(host).FirstOrDefault(List.ContainsKey);

    /// <summary>Domains of ad networks, analytics and data brokers: safe to block everywhere (not Meta/Google/TikTok content).</summary>
    public static IEnumerable<string> Blockable => List.Where(kv => kv.Value.Cat is "BROKER" or "ATTR" or "ADS" or "ANALYTICS").Select(kv => kv.Key);

    public static Hit? Classify(string host)
    {
        foreach (var s in Suffixes(host))
            if (List.TryGetValue(s, out var t))
                return new Hit(t.Company, t.Cat, Cats.GetValueOrDefault(t.Cat, t.Cat), Weights.GetValueOrDefault(t.Cat, 1));
        return null;
    }

    /// <summary>A Microsoft program talking to Microsoft's own telemetry is still telemetry, but Meta inside Facebook is first party.</summary>
    public static bool IsFirstParty(Hit h, string exeOrPackage) => h.Category switch
    {
        "META" => exeOrPackage.Contains("facebook", StringComparison.OrdinalIgnoreCase) || exeOrPackage.Contains("whatsapp", StringComparison.OrdinalIgnoreCase) || exeOrPackage.Contains("instagram", StringComparison.OrdinalIgnoreCase),
        "GOOGLE" => exeOrPackage.Contains("google", StringComparison.OrdinalIgnoreCase) || exeOrPackage.EndsWith("chrome.exe", StringComparison.OrdinalIgnoreCase),
        "BYTEDANCE" => exeOrPackage.Contains("tiktok", StringComparison.OrdinalIgnoreCase),
        _ => false,
    };

    /// <summary>Microsoft's telemetry and ad endpoints (Windows itself). Shown separately; blocking them can break Windows Update.</summary>
    public static readonly string[] MicrosoftTelemetry =
    {
        "vortex.data.microsoft.com", "v10.events.data.microsoft.com", "v20.events.data.microsoft.com",
        "watson.telemetry.microsoft.com", "telecommand.telemetry.microsoft.com", "settings-win.data.microsoft.com",
        "self.events.data.microsoft.com", "mobile.events.data.microsoft.com", "browser.events.data.msn.com",
        "arc.msn.com", "ris.api.iris.microsoft.com", "g.live.com", "ads.msn.com", "adsdk.microsoft.com",
    };
    public static bool IsMicrosoftTelemetry(string host) =>
        Suffixes(host).Any(s => MicrosoftTelemetry.Contains(s)) || host.EndsWith(".events.data.microsoft.com", StringComparison.OrdinalIgnoreCase);
}
