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
    static List<Finding>? scan;
    static DateTime scanned;
    static bool showAllFindings, showCaution;
    static List<Appx.Package>? appx;

    public static async Task<UIElement> AppsTab(MainWindow w)
    {
        var from = Day0.AddDays(-6);
        var (acc, dns, settings) = await Task.Run(() => (S.AccessSince(from), S.DnsSince(from),
            PrivacySettings.All.Select(s => (s, SettingsIO.IsPrivate(s))).ToList()));
        var page = Page();

        // ---- spyware check ----
        var sp = Ui.Section("Spyware check", "Programs that start by themselves, remote control and monitoring software, browser extensions that can read every site, proxies and certificates that can read your traffic, Remote Desktop and antivirus. Red = check it now; most other entries are normal programs, but you should know each one.");
        sp.Children.Add(Ui.Btn(scan == null ? "Scan now" : $"Scan again (last {scanned:HH:mm})", () => w.Work("Scanning", () =>
        {
            scan = SpywareScan.Run(); scanned = DateTime.Now;
            var high = scan.Count(f => f.Severity == Severity.High);
            return high == 0 ? $"Scan done: nothing high-risk among {scan.Count} items." : $"Scan done: {high} item(s) need a look.";
        }), primary: scan == null));
        if (scan != null)
        {
            var important = scan.Where(f => f.Severity > Severity.Info).ToList();
            if (important.Count == 0) sp.Children.Add(Ui.T("Nothing suspicious found.", 13.5, null, Ui.Green));
            foreach (var f in important) sp.Children.Add(FindingView(f));
            var info = scan.Where(f => f.Severity == Severity.Info).ToList();
            if (info.Count > 0)
            {
                if (showAllFindings) foreach (var f in info) sp.Children.Add(FindingView(f));
                sp.Children.Add(Ui.Btn(showAllFindings ? "Show only the ones to check" : $"Show {info.Count} normal items", () => { showAllFindings = !showAllFindings; w.Refresh(); }));
            }
        }
        page.Children.Add(Ui.Card(sp));

        // ---- privacy score per program ----
        var sc = Ui.Section("Privacy score · last 7 days", "100 is clean. Points are lost for alerts and for each tracker company contacted (data brokers the most). Open a program for details.");
        var accessAlerts = Rules.ForAccess(acc).ToList();
        var byKey = new Dictionary<string, (string Name, bool Packaged)>(StringComparer.OrdinalIgnoreCase);
        foreach (var a in acc) byKey[a.App.Key] = (a.App.Name, a.App.Packaged);
        foreach (var q in dns.Where(q => q.Exe != null)) byKey.TryAdd(q.Exe!, (Core.Apps.DisplayName(new AppId(q.Exe!, false)), false));
        var rows = byKey.Select(kv =>
        {
            var al = accessAlerts.Where(a => a.Key == kv.Key).Concat(Rules.ForTrackers(dns.Where(q => kv.Key.Equals(q.Exe, StringComparison.OrdinalIgnoreCase)))).ToList();
            var tr = dns.Where(q => kv.Key.Equals(q.Exe, StringComparison.OrdinalIgnoreCase)).Select(q => Trackers.Classify(q.Name))
                .Where(h => h != null && !Trackers.IsFirstParty(h, kv.Key)).Select(h => h!).ToList();
            return (Key: kv.Key, kv.Value.Name, kv.Value.Packaged, Alerts: al, Trackers: tr, Score: Rules.Score(al, tr));
        }).OrderBy(r => r.Score).ThenByDescending(r => r.Trackers.Count).ToList();
        if (rows.Count == 0) sc.Children.Add(Ui.Hint("Nothing recorded yet."));
        foreach (var r in rows.Take(60))
        {
            var body = new StackPanel { Margin = new Thickness(8, 4, 0, 8) };
            body.Children.Add(Ui.MonoT(r.Key));
            foreach (var a in r.Alerts) body.Children.Add(Ui.T($"• {a.Title}: {a.Text}", 13, null, Ui.For(a.Severity)));
            var caps = acc.Where(a => a.App.Key.Equals(r.Key, StringComparison.OrdinalIgnoreCase)).GroupBy(a => a.Capability).Select(g => $"{Core.Apps.CapabilityName(g.Key)} {g.Count()}×");
            if (caps.Any()) body.Children.Add(Ui.T("Used: " + string.Join(", ", caps), 13));
            foreach (var c in r.Trackers.GroupBy(t => t.Company).OrderByDescending(g => g.First().Weight))
                body.Children.Add(Ui.T($"Tracker: {c.Key} ({c.First().CategoryName}), {c.Count()} requests", 13, null, c.First().Category == "BROKER" ? Ui.Red : Ui.Orange));
            var top = dns.Where(q => r.Key.Equals(q.Exe, StringComparison.OrdinalIgnoreCase)).GroupBy(q => q.Name).OrderByDescending(g => g.Count()).Take(8).Select(g => $"{g.Key} ({g.Count()})");
            if (top.Any()) body.Children.Add(Ui.Hint("Most contacted: " + string.Join(", ", top)));
            var actions = new WrapPanel();
            var key = r.Key;
            if (!r.Packaged)
            {
                actions.Children.Add(Ui.Btn("Block its internet", () => w.Work("Blocking", () => { Firewall.Block(key); return $"{r.Name} can no longer connect to the internet."; }, needsAdmin: true)));
                actions.Children.Add(OpenFolder(key));
            }
            else foreach (var cap in new[] { "microphone", "webcam", "location" })
                    actions.Children.Add(Ui.Btn($"Deny {Core.Apps.CapabilityName(cap).ToLowerInvariant()}", () => w.Work("Denying", () => Deny(key, cap))));
            body.Children.Add(actions);
            var head = new StackPanel { Orientation = Orientation.Horizontal };
            head.Children.Add(new Border
            {
                Width = 40, Height = 28, CornerRadius = new CornerRadius(6), Margin = new Thickness(0, 0, 10, 0),
                Background = r.Score >= 80 ? Ui.GreenBg : r.Score >= 55 ? Ui.OrangeBg : Ui.RedBg,
                Child = new TextBlock { Text = r.Score.ToString(), FontWeight = FontWeights.Bold, HorizontalAlignment = HorizontalAlignment.Center, VerticalAlignment = VerticalAlignment.Center, Foreground = r.Score >= 80 ? Ui.Green : r.Score >= 55 ? Ui.Orange : Ui.Red },
            });
            head.Children.Add(new TextBlock { Text = r.Name, FontSize = 14, FontWeight = FontWeights.SemiBold, VerticalAlignment = VerticalAlignment.Center, Foreground = Ui.Text });
            if (r.Trackers.Count > 0) head.Children.Add(Ui.Chip($"{r.Trackers.Select(t => t.Company).Distinct().Count()} trackers", Ui.Orange, Ui.OrangeBg));
            sc.Children.Add(new Expander { Header = head, Content = body, Margin = new Thickness(0, 2, 0, 2) });
        }
        page.Children.Add(Ui.Card(sc));

        // ---- Windows' own settings ----
        var ws = Ui.Section("Windows privacy settings", "Windows' own ads and data collection. Ticked = the private choice. Changes apply after signing out and in for some of them.");
        foreach (var (s, priv) in settings)
        {
            var set = s;
            var cb = Ui.Check(s.Title + (s.NeedsAdmin && !Program.Admin ? " (needs full access)" : ""), priv,
                on => w.Work("Saving", () => { SettingsIO.Set(set, on); return on ? $"{set.Title}: private." : $"{set.Title}: back to Windows' default."; }, needsAdmin: set.NeedsAdmin),
                enabled: !s.NeedsAdmin || Program.Admin);
            ws.Children.Add(cb);
            ws.Children.Add(new TextBlock { Text = s.Description, FontSize = 12, Foreground = Ui.Muted, Margin = new Thickness(22, -2, 0, 4), TextWrapping = TextWrapping.Wrap });
        }
        ws.Children.Add(Ui.Btn("Make all private", () => w.Work("Saving", () =>
        {
            int n = 0;
            foreach (var s in PrivacySettings.All.Where(s => !s.NeedsAdmin || Program.Admin)) { SettingsIO.Set(s, true); n++; }
            return $"{n} settings set to private.";
        })));
        page.Children.Add(Ui.Card(ws));

        page.Children.Add(Ui.Card(await DebloatCard(w)));
        return page;
    }

    static UIElement FindingView(Finding f)
    {
        var s = new StackPanel();
        s.Children.Add(Ui.T($"{f.Name}", 13.5, FontWeights.SemiBold, Ui.For(f.Severity)));
        s.Children.Add(Ui.T(f.Detail, 13));
        if (f.Path != null)
        {
            s.Children.Add(Ui.MonoT(f.Path));
            s.Children.Add(OpenFolder(f.Path));
        }
        return new Border { Background = Ui.BgFor(f.Severity), CornerRadius = new CornerRadius(8), Padding = new Thickness(12, 8, 12, 6), Margin = new Thickness(0, 4, 0, 4), Child = s };
    }

    static async Task<StackPanel> DebloatCard(MainWindow w)
    {
        var c = Ui.Section("Debloater", "Removes pre-installed Store apps you don't use, for your user only. Restore brings an app back.");
        if (appx == null) c.Children.Add(Ui.Btn("Show removable apps", () => w.Work("Reading installed apps", () => { appx = Appx.Installed(); return null; }), primary: true));
        else
        {
            var installed = appx.ToDictionary(p => p.Name, StringComparer.OrdinalIgnoreCase);
            var items = Debloat.List.Where(i => installed.ContainsKey(i.Package) && (showCaution || i.Level != Debloat.Level.Caution)).ToList();
            if (items.Count == 0) c.Children.Add(Ui.Hint("None of the known removable apps are installed."));
            foreach (var i in items)
            {
                var item = i;
                c.Children.Add(Ui.Line2(i.Name, $"{i.Level} · {i.Note}",
                    i.Level == Debloat.Level.Caution ? Ui.Chip("Caution", Ui.Orange, Ui.OrangeBg) : new TextBlock(),
                    Ui.Btn("Remove", () => w.Work($"Removing {item.Name}", () =>
                    {
                        var r = Appx.Remove(item.Package);
                        if (r.Ok) { P.Removed[item.Package] = installed[item.Package].Family; P.Save(); appx = Appx.Installed(); }
                        return r.Message;
                    }))));
            }
            c.Children.Add(Ui.Btn(showCaution ? "Hide caution apps" : "Also show apps that need caution", () => { showCaution = !showCaution; w.Refresh(); }));
            var removed = P.Removed.Where(kv => !installed.ContainsKey(kv.Key)).ToList();
            if (removed.Count > 0)
            {
                c.Children.Add(Ui.Sep());
                c.Children.Add(Ui.T("Removed", 13.5, FontWeights.SemiBold));
                foreach (var (name, family) in removed)
                    c.Children.Add(Ui.Line2(Debloat.List.FirstOrDefault(x => x.Package == name)?.Name ?? name, name,
                        Ui.Btn("Restore", () => w.Work("Restoring", () =>
                        {
                            var r = Appx.Restore(name, family);
                            P.Removed.Remove(name); P.Save(); appx = Appx.Installed();
                            return r.Message;
                        }))));
            }
        }
        await Task.CompletedTask;
        return c;
    }
}
