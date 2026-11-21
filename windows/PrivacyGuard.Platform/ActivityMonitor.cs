using PrivacyGuard.Core;

namespace PrivacyGuard.Platform;

/// <summary>
/// Runs in the background: records every use of the microphone, camera, location... as it happens (Windows keeps only
/// the last one), with whether the program was in front and whether you were at the PC, and every DNS lookup.
/// </summary>
public sealed class ActivityMonitor : IDisposable
{
    public event Action<Access>? SensorOn;
    public event Action<DnsQuery, Trackers.Hit>? Broker;
    public event Action? Changed;

    public Store Store { get; }
    public Lists Lists { get; }
    public bool DnsOn { get; private set; }
    readonly ConsentStore consent;
    readonly DateTime started = DateTime.Now;
    readonly object gate = new();
    IDisposable? watch; DnsLog? dns; Timer? timer;
    HashSet<string> hostsBlocked = new(StringComparer.OrdinalIgnoreCase);
    readonly HashSet<string> brokerSeen = new();

    public ActivityMonitor(Store store, Lists lists, ConsentStore? consent = null)
    {
        Store = store; Lists = lists; this.consent = consent ?? new ConsentStore();
    }

    public void Start(bool enableDns)
    {
        Sync();
        watch = consent.Watch(Sync);
        // backup in case a change notification is missed; also refreshes the hosts list and prunes daily
        int ticks = 0;
        timer = new Timer(_ =>
        {
            Sync();
            if (ticks++ % 4 == 0) hostsBlocked = Hosts.Blocked().ToHashSet(StringComparer.OrdinalIgnoreCase);
            if (ticks % 5760 == 0) Store.Prune();
        }, null, 15_000, 15_000);
        hostsBlocked = Hosts.Blocked().ToHashSet(StringComparer.OrdinalIgnoreCase);
        StartDns(enableDns);
        Store.Prune();
    }

    public bool StartDns(bool enable)
    {
        if (dns != null) return true;
        if (!DnsLog.Enabled() && !(enable && Native.IsAdmin() && DnsLog.Enable())) return DnsOn = false;
        try
        {
            // what was logged while Privacy Guard was not running (up to a day)
            var last = Store.DnsSince(DateTime.Now.AddDays(-1), 1).FirstOrDefault()?.Time ?? DateTime.Now.AddDays(-1);
            foreach (var q in DnsLog.Since(last.AddSeconds(1))) Record(q, false);
            dns = new DnsLog();
            dns.Start(q => Record(q, true));
            return DnsOn = true;
        }
        catch { return DnsOn = false; }
    }

    void Record(DnsQuery q, bool live)
    {
        var blocked = Trackers.Suffixes(q.Name).Any(hostsBlocked.Contains);
        Store.Add(q with { Blocked = blocked });
        if (!live || q.Exe == null) return;
        if (Trackers.Classify(q.Name) is { Category: "BROKER" } h && brokerSeen.Add($"{q.Exe}|{h.Company}|{DateTime.Today:yyyyMMdd}"))
            Broker?.Invoke(q, h);
    }

    /// <summary>Reads Windows' records and stores what is new.</summary>
    public void Sync()
    {
        lock (gate)
        {
            bool any = false;
            List<ConsentStore.Entry> entries;
            try { entries = consent.Read(); } catch { return; }
            foreach (var e in entries)
            {
                if (Store.Has(e.App, e.Capability, e.Start))
                {
                    if (e.Stop != null) Store.Upsert(new Access(e.App, e.Capability, e.Start, e.Stop));
                    continue;
                }
                // a use that began before Privacy Guard started: whether it was in front is unknown, so no background alert
                bool known = e.Start >= started.AddSeconds(-5);
                var a = new Access(e.App, e.Capability, e.Start, e.Stop,
                    InFront: !known || InFront(e.App), UserAway: known && e.InUse && Native.UserAway);
                Store.Upsert(a);
                any = true;
                if (known && e.InUse && Apps.Sensors.Contains(e.Capability)) SensorOn?.Invoke(a);
            }
            if (any) Changed?.Invoke();
        }
    }

    /// <summary>Whether the program's window is the one in front. Unknown cases count as "in front" (no alert).</summary>
    static bool InFront(AppId app)
    {
        var fg = Native.ForegroundPath();
        if (fg == null) return true;
        if (app.Path != null) return fg.Equals(app.Path, StringComparison.OrdinalIgnoreCase);
        // Store apps: their window belongs to ApplicationFrameHost; otherwise compare the package folder
        if (fg.EndsWith("ApplicationFrameHost.exe", StringComparison.OrdinalIgnoreCase)) return true;
        return fg.Contains(app.Key.Split('_')[0], StringComparison.OrdinalIgnoreCase);
    }

    /// <summary>Programs using the microphone/camera/screen right now.</summary>
    public List<ConsentStore.Entry> InUse()
    {
        try { return consent.Read().Where(e => e.InUse && Apps.Sensors.Contains(e.Capability)).ToList(); } catch { return new(); }
    }

    public void Dispose() { watch?.Dispose(); timer?.Dispose(); dns?.Dispose(); }
}
