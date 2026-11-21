namespace PrivacyGuard.Core;

/// <summary>Turns raw records into "worth a look" alerts and a privacy score per program (same ideas as the Android app).</summary>
public static class Rules
{
    public const int NightEndHour = 6;
    public static readonly TimeSpan LongSession = TimeSpan.FromMinutes(15);

    public static bool IsNight(DateTime local) => local.Hour < NightEndHour;

    public static IEnumerable<Alert> ForAccess(IEnumerable<Access> all)
    {
        foreach (var g in all.GroupBy(a => a.App))
        {
            var app = g.Key;
            var name = app.Name;
            bool sys = Apps.IsSystem(app), calls = Apps.IsCallApp(app);
            var sensors = g.Where(a => Apps.Sensors.Contains(a.Capability)).ToList();

            var away = sensors.Where(a => a.UserAway).ToList();
            if (away.Count > 0)
                yield return new Alert(Severity.High, name, $"{Caps(away)} while you were away",
                    $"{Times(away.Count)} while the PC was idle or locked, last at {away.Max(a => a.Start):g}.", app.Key);

            var hidden = sensors.Where(a => !a.InFront && !a.UserAway && !calls && !sys).ToList();
            if (hidden.Count > 0)
                yield return new Alert(Severity.High, name, $"{Caps(hidden)} used in the background",
                    $"{Times(hidden.Count)} while its window was not in front, last at {hidden.Max(a => a.Start):g}.", app.Key);

            var night = sensors.Where(a => IsNight(a.Start) && !a.UserAway && !calls).ToList();
            if (night.Count > 0)
                yield return new Alert(Severity.High, name, $"{Caps(night)} used at night",
                    $"{Times(night.Count)} between 00:00 and 06:00, last at {night.Max(a => a.Start):g}.", app.Key);

            if (!calls)
                foreach (var c in new[] { "microphone", "webcam", "graphicsCaptureProgrammatic" })
                {
                    var l = g.Where(a => a.Capability == c && a.Duration >= LongSession).ToList();
                    if (l.Count > 0)
                        yield return new Alert(c == "webcam" ? Severity.Medium : Severity.High, name,
                            $"Long {Apps.CapabilityName(c).ToLowerInvariant()} use",
                            $"{Sessions(l.Count)} of 15 minutes or more, the longest {Dur(l.Max(a => a.Duration!.Value))}.", app.Key);
                }

            var personal = g.Where(a => Apps.Personal.Contains(a.Capability) && (!a.InFront || a.UserAway) && !sys).ToList();
            if (personal.Count > 0)
                yield return new Alert(Severity.Medium, name, $"{Caps(personal)} read in the background",
                    $"{Times(personal.Count)} while it was not on screen.", app.Key);
        }
    }

    public static IEnumerable<Alert> ForTrackers(IEnumerable<DnsQuery> q)
    {
        foreach (var g in q.Where(x => x.Exe != null).GroupBy(x => x.Exe!, StringComparer.OrdinalIgnoreCase))
        {
            var brokers = g.Select(x => Trackers.Classify(x.Name)).Where(h => h?.Category == "BROKER").Select(h => h!.Company).Distinct().ToList();
            if (brokers.Count > 0)
                yield return new Alert(Severity.High, Apps.DisplayName(new AppId(g.Key, false)), "Contacted a data broker",
                    $"{string.Join(", ", brokers)}: companies that buy and sell location or personal data.", g.Key);
        }
    }

    /// <summary>100 is clean; points are lost for alerts and for each tracker company contacted (brokers the most).</summary>
    public static int Score(IEnumerable<Alert> alerts, IEnumerable<Trackers.Hit> companies)
    {
        int s = 100;
        foreach (var a in alerts) s -= a.Severity == Severity.High ? 15 : a.Severity == Severity.Medium ? 7 : 0;
        foreach (var c in companies.DistinctBy(c => c.Company)) s -= 4 * c.Weight;
        return Math.Clamp(s, 0, 100);
    }

    static string Caps(IEnumerable<Access> a) => string.Join(", ", a.Select(x => Apps.CapabilityName(x.Capability)).Distinct());
    static string Times(int n) => n == 1 ? "Once" : $"{n} times";
    static string Sessions(int n) => n == 1 ? "1 session" : $"{n} sessions";
    public static string Dur(TimeSpan t) => t.TotalMinutes < 1 ? "under 1 min" : t.TotalHours < 1 ? $"{(int)t.TotalMinutes} min" : $"{(int)t.TotalHours} h {t.Minutes} min";
}
