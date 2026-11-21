using PrivacyGuard.Core;

namespace PrivacyGuard.Platform;

/// <summary>Blocks domains through Privacy Guard's own section of the hosts file. Needs administrator rights.</summary>
public static class Hosts
{
    public static string DefaultPath => Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.System), @"drivers\etc\hosts");

    public static IReadOnlyList<string> Blocked(string? path = null) =>
        File.Exists(path ?? DefaultPath) ? HostsFile.Blocked(File.ReadAllText(path ?? DefaultPath)) : Array.Empty<string>();

    public static void Set(IEnumerable<string> domains, string? path = null)
    {
        path ??= DefaultPath;
        var old = File.Exists(path) ? File.ReadAllText(path) : "";
        var text = HostsFile.WithBlocked(old, domains);
        if (text == old) return;
        // write next to it and swap, so the file is never half written
        var tmp = path + ".pg-tmp";
        File.WriteAllText(tmp, text);
        File.Copy(tmp, path, true);
        File.Delete(tmp);
        if (path == DefaultPath) Native.Run("ipconfig.exe", "/flushdns", 15_000);
    }

    public static void Add(string domain, string? path = null) => Set(Blocked(path).Append(domain), path);
    public static void Remove(string domain, string? path = null) =>
        Set(Blocked(path).Where(d => !d.Equals(domain.Trim().TrimEnd('.'), StringComparison.OrdinalIgnoreCase)), path);
}
