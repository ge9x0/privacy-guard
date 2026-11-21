namespace PrivacyGuard.Core;

/// <summary>
/// Domain blocking through the Windows hosts file: Privacy Guard owns one marked section and never touches the rest.
/// The hosts file has no wildcards, so each blocked domain is listed with its www. form.
/// </summary>
public static class HostsFile
{
    public const string Begin = "# >>> Privacy Guard (managed section, do not edit)";
    public const string End = "# <<< Privacy Guard";

    public static IReadOnlyList<string> Blocked(string hosts)
    {
        var (b, e) = Section(hosts.Split('\n'));
        if (b < 0) return Array.Empty<string>();
        return hosts.Split('\n')[(b + 1)..e].Select(l => l.Trim().Split(' ', StringSplitOptions.RemoveEmptyEntries))
            .Where(p => p.Length == 2 && p[0] == "0.0.0.0" && !p[1].StartsWith("www.")).Select(p => p[1]).ToList();
    }

    /// <summary>Returns the hosts text with our section replaced by `domains` (or removed when empty).</summary>
    public static string WithBlocked(string hosts, IEnumerable<string> domains)
    {
        var lines = hosts.Replace("\r\n", "\n").Split('\n').ToList();
        var (b, e) = Section(lines.ToArray());
        if (b >= 0) lines.RemoveRange(b, e - b + 1);
        while (lines.Count > 0 && lines[^1].Trim().Length == 0) lines.RemoveAt(lines.Count - 1);
        var d = domains.Select(x => x.Trim().ToLowerInvariant().TrimEnd('.')).Where(Valid).Distinct().OrderBy(x => x).ToList();
        if (d.Count > 0)
        {
            lines.Add("");
            lines.Add(Begin);
            foreach (var x in d)
            {
                lines.Add($"0.0.0.0 {x}");
                if (!x.StartsWith("www.")) lines.Add($"0.0.0.0 www.{x}");
            }
            lines.Add(End);
        }
        return string.Join("\r\n", lines) + "\r\n";
    }

    public static bool Valid(string d) =>
        d.Length is > 2 and < 254 && d.Contains('.') && d.All(c => char.IsAsciiLetterOrDigit(c) || c is '.' or '-') && !d.StartsWith('.') && !d.StartsWith('-') && !d.EndsWith('-') && !d.Contains("..");

    static (int, int) Section(string[] lines)
    {
        int b = Array.FindIndex(lines, l => l.TrimEnd('\r') == Begin);
        if (b < 0) return (-1, -1);
        int e = Array.FindIndex(lines, b, l => l.TrimEnd('\r') == End);
        return (b, e < 0 ? lines.Length - 1 : e);
    }
}
