using System.Diagnostics.Eventing.Reader;
using PrivacyGuard.Core;

namespace PrivacyGuard.Platform;

/// <summary>
/// Every DNS lookup with the process that made it, from Windows' DNS client log
/// (Microsoft-Windows-DNS-Client/Operational, event 3008 "query completed"). The log is off by default;
/// turning it on needs administrator rights once.
/// </summary>
public sealed class DnsLog : IDisposable
{
    public const string Channel = "Microsoft-Windows-DNS-Client/Operational";
    const string Query = "*[System[(EventID=3008)]]";
    EventLogWatcher? watcher;

    public static bool Enabled()
    {
        try { using var c = new EventLogConfiguration(Channel); return c.IsEnabled; } catch { return false; }
    }

    /// <summary>Turns the log on with room for about a day of lookups. Needs administrator rights.</summary>
    public static bool Enable()
    {
        try
        {
            using var c = new EventLogConfiguration(Channel);
            c.IsEnabled = true;
            if (c.MaximumSizeInBytes < 32L << 20) c.MaximumSizeInBytes = 32L << 20;
            c.SaveChanges();
            return true;
        }
        catch { return Native.Run("wevtutil.exe", $"sl {Channel} /e:true /ms:33554432").Code == 0; }
    }

    static DnsQuery? From(EventRecord e)
    {
        var name = e.Properties.Count > 0 ? e.Properties[0].Value as string : null;
        if (string.IsNullOrWhiteSpace(name)) return null;
        var pid = e.ProcessId ?? 0;
        return new DnsQuery(e.TimeCreated ?? DateTime.Now, pid, Native.ProcessPath(pid), name.TrimEnd('.'));
    }

    /// <summary>Lookups already in the log (after a restart of the app).</summary>
    public static IEnumerable<DnsQuery> Since(DateTime from)
    {
        var ms = (long)Math.Max(1000, (DateTime.Now - from).TotalMilliseconds);
        var q = new EventLogQuery(Channel, PathType.LogName, $"*[System[(EventID=3008) and TimeCreated[timediff(@SystemTime) <= {ms}]]]");
        using var r = new EventLogReader(q);
        for (var e = r.ReadEvent(); e != null; e = r.ReadEvent())
            using (e) if (From(e) is { } d) yield return d;
    }

    public void Start(Action<DnsQuery> onQuery)
    {
        watcher = new EventLogWatcher(new EventLogQuery(Channel, PathType.LogName, Query));
        watcher.EventRecordWritten += (_, a) =>
        {
            if (a.EventRecord == null) return;
            using var e = a.EventRecord;
            if (From(e) is { } d) try { onQuery(d); } catch { }
        };
        watcher.Enabled = true;
    }

    public void Dispose() { if (watcher != null) { watcher.Enabled = false; watcher.Dispose(); } }
}
