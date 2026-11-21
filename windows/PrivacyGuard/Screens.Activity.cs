using System;
using System.Collections.Generic;
using System.Linq;
using System.Threading.Tasks;
using System.Windows;
using System.Windows.Controls;
using PrivacyGuard.Core;
using PrivacyGuard.Platform;

namespace PrivacyGuard.App;

public static partial class Screens
{
    static bool week, flaggedOnly = true;
    static string query = "";
    static int dnsShown = 100;

    public static async Task<UIElement> Activity(MainWindow w)
    {
        var from = week ? Day0.AddDays(-6) : Day0;
        var (acc, dns, blocked) = await Task.Run(() => (S.AccessSince(from), S.DnsSince(from), Hosts.Blocked().ToHashSet(StringComparer.OrdinalIgnoreCase)));
        var page = Page();
        page.Children.Add(Ui.Row(
            Ui.Btn("Today", () => { week = false; w.Refresh(); }, primary: !week),
            Ui.Btn("Last 7 days", () => { week = true; w.Refresh(); }, primary: week)));

        // all activity by capability
        var c = Ui.Section("All activity", "Every use by every program, on screen or not, grouped by what was used. Open a group to see the programs.");
        if (acc.Count == 0) c.Children.Add(Ui.Hint("Nothing recorded yet."));
        foreach (var g in acc.GroupBy(a => a.Capability).OrderByDescending(g => Apps.Sensors.Contains(g.Key)).ThenByDescending(g => g.Count()))
        {
            var body = new StackPanel { Margin = new Thickness(8, 4, 0, 4) };
            foreach (var ag in g.GroupBy(a => a.App).OrderByDescending(x => x.Max(a => a.Start)))
            {
                var total = ag.Where(a => a.Duration != null).Aggregate(TimeSpan.Zero, (t, a) => t + a.Duration!.Value);
                var flags = new List<string>();
                if (ag.Any(a => !a.InFront)) flags.Add($"{ag.Count(a => !a.InFront)}× in the background");
                if (ag.Any(a => a.UserAway)) flags.Add($"{ag.Count(a => a.UserAway)}× while away");
                if (ag.Any(a => Rules.IsNight(a.Start))) flags.Add("at night");
                body.Children.Add(Ui.Line2(ag.Key.Name,
                    $"{ag.Count()}× · {(total > TimeSpan.Zero ? Rules.Dur(total) + " · " : "")}last {Ui.Hm(ag.Max(a => a.Start))}" + (flags.Count > 0 ? " · " + string.Join(", ", flags) : ""),
                    flags.Count > 0 ? Ui.Chip("look", Ui.Orange, Ui.OrangeBg) : new TextBlock()));
            }
            c.Children.Add(new Expander
            {
                Header = new TextBlock { Text = $"{Apps.CapabilityName(g.Key)} · {g.Count()} uses · {g.Select(a => a.App).Distinct().Count()} programs", FontSize = 14, FontWeight = FontWeights.SemiBold, Foreground = Ui.Text },
                Content = body, Margin = new Thickness(0, 2, 0, 2), Foreground = Ui.Text,
            });
        }
        page.Children.Add(Ui.Card(c));

        // DNS log
        var d = Ui.Section("DNS log", "Every server name a program asked for. Flagged shows known trackers, block-list matches and Windows telemetry.");
        if (!DnsLog.Enabled()) d.Children.Add(Ui.T("The DNS log is off. Turn it on in Today → Setup (needs full access).", 13, null, Ui.Orange));
        var search = new TextBox { Text = query, MinWidth = 260, Padding = new Thickness(6, 4, 6, 4), Margin = new Thickness(0, 4, 8, 4), Background = Ui.Bg, Foreground = Ui.Text, BorderBrush = Ui.Line, ToolTip = "Search programs or servers" };
        var timer = new System.Windows.Threading.DispatcherTimer { Interval = TimeSpan.FromMilliseconds(400) };
        timer.Tick += (_, _) => { timer.Stop(); query = search.Text; dnsShown = 100; w.Refresh(); };
        search.TextChanged += (_, _) => { timer.Stop(); timer.Start(); };
        d.Children.Add(Ui.Row(
            Ui.Btn("Flagged", () => { flaggedOnly = true; dnsShown = 100; w.Refresh(); }, primary: flaggedOnly),
            Ui.Btn("All", () => { flaggedOnly = false; dnsShown = 100; w.Refresh(); }, primary: !flaggedOnly),
            new TextBlock { Text = "Search:", VerticalAlignment = VerticalAlignment.Center, Margin = new Thickness(8, 0, 6, 0), Foreground = Ui.Muted }, search));

        var rows = dns.Select(q => (q, tr: Trackers.Classify(q.Name), list: Program.Lists.Match(q.Name), ms: Trackers.IsMicrosoftTelemetry(q.Name)))
            .Where(x => !flaggedOnly || x.tr != null || x.list != null || x.ms || x.q.Blocked)
            .Where(x => query.Length == 0 || x.q.Name.Contains(query, StringComparison.OrdinalIgnoreCase) || (x.q.Exe ?? "").Contains(query, StringComparison.OrdinalIgnoreCase))
            .ToList();
        // the same name asked many times by one program: one row with a count
        var grouped = rows.GroupBy(x => (x.q.Exe, x.q.Name)).Select(g => (g.First(), n: g.Count())).ToList();
        if (grouped.Count == 0) d.Children.Add(Ui.Hint(dns.Count == 0 ? "No lookups recorded yet." : "Nothing matches."));
        foreach (var ((q, tr, list, ms), n) in grouped.Take(dnsShown))
        {
            var app = q.Exe != null ? Apps.DisplayName(new AppId(q.Exe, false)) : $"process {q.Pid}";
            var tags = new WrapPanel();
            if (tr != null) tags.Children.Add(Ui.Chip(tr.Company, tr.Category == "BROKER" ? Ui.Red : Ui.Orange, tr.Category == "BROKER" ? Ui.RedBg : Ui.OrangeBg));
            if (list != null) tags.Children.Add(Ui.Chip(list + " list", Ui.Orange, Ui.OrangeBg));
            if (ms) tags.Children.Add(Ui.Chip("Windows telemetry"));
            var isBlocked = Trackers.Suffixes(q.Name).Any(blocked.Contains);
            if (isBlocked) tags.Children.Add(Ui.Chip("blocked", Ui.Green, Ui.GreenBg));
            var name = q.Name;
            var left = new StackPanel();
            left.Children.Add(Ui.MonoT(name));
            left.Children.Add(new TextBlock { Text = $"{Ui.Hm(q.Time)} · {app}" + (n > 1 ? $" · {n}×" : ""), FontSize = 12, Foreground = Ui.Muted });
            left.Children.Add(tags);
            var g = new Grid { Margin = new Thickness(0, 3, 0, 3) };
            g.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
            g.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
            g.Children.Add(left);
            var btn = isBlocked
                ? Ui.Btn("Allow", () => w.Work("Allowing", () => { foreach (var s in Trackers.Suffixes(name).Where(blocked.Contains)) Hosts.Remove(s); return $"{name} allowed again."; }, needsAdmin: true))
                : Ui.Btn("Block", () => w.Work("Blocking", () => { Hosts.Add(name); return $"{name} blocked for every program."; }, needsAdmin: true));
            Grid.SetColumn(btn, 1);
            g.Children.Add(btn);
            d.Children.Add(g);
        }
        if (grouped.Count > dnsShown) d.Children.Add(Ui.Btn($"Show {Math.Min(100, grouped.Count - dnsShown)} more", () => { dnsShown += 100; w.Refresh(); }));
        page.Children.Add(Ui.Card(d));
        return page;
    }
}
