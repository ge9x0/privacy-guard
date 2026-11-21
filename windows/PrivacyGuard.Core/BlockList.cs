namespace PrivacyGuard.Core;

/// <summary>HaGeZi DNS block lists ("onlydomains" format: one domain per line, matching subdomains too).</summary>
public sealed class BlockList
{
    public sealed record Def(string Id, string Name, string File);
    public static readonly Def[] All =
    {
        new("pro", "Ads & trackers", "pro-onlydomains.txt"),
        new("tif", "Malware & phishing", "tif.medium-onlydomains.txt"),
        new("windows", "Windows telemetry", "native.winoffice-onlydomains.txt"),
    };
    public const string Cdn = "https://cdn.jsdelivr.net/gh/hagezi/dns-blocklists@latest/wildcard/";
    public const string GitHub = "https://raw.githubusercontent.com/hagezi/dns-blocklists/main/wildcard/";

    readonly HashSet<string> set;
    public string Id { get; }
    public int Count => set.Count;

    public BlockList(string id, IEnumerable<string> domains) { Id = id; set = new HashSet<string>(domains, StringComparer.OrdinalIgnoreCase); }

    public static BlockList Parse(string id, string text) => new(id, text.Split('\n')
        .Select(l => l.Trim()).Where(l => l.Length > 0 && l[0] != '#' && l.Contains('.') && !l.Contains(' ')));

    public bool Matches(string host) => Trackers.Suffixes(host).Any(set.Contains);
}
