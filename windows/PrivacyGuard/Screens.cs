using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Threading.Tasks;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Controls.Primitives;
using PrivacyGuard.Core;
using PrivacyGuard.Platform;

namespace PrivacyGuard.App;

/// <summary>The four tabs. Each loads its data in the background, then builds its cards.</summary>
public static partial class Screens
{
    static Prefs P => Program.Prefs;
    static Store S => Program.Store;
    static DateTime Day0 => DateTime.Today;

    static StackPanel Page() => new();

    // ============================== TODAY ==============================
    sealed record TodayData(List<ConsentStore.Entry> InUse, List<Access> Access, List<DnsQuery> Dns, List<Alert> Alerts,
        WifiCheck.State? Wifi, bool DnsEnabled, bool Autostart, long DbBytes, DateTime? Since);

    public static async Task<UIElement> Today(MainWindow w)
    {
        var d = await Task.Run(() =>
        {
            var acc = S.AccessSince(Day0);
            var dns = S.DnsSince(Day0);
            var alerts = Rules.ForAccess(acc).Concat(Rules.ForTrackers(dns)).OrderByDescending(a => a.Severity).ToList();
            var first = S.AccessSince(DateTime.Now.AddDays(-61)).FirstOrDefault()?.Start;
            long size = 0; try { size = new FileInfo(Prefs.DbPath).Length; } catch { }
            return new TodayData(Program.Monitor.InUse(), acc, dns, alerts, SafeWifi(), DnsLog.Enabled(), SafeAutostart(), size, first);
        });
        var page = Page();

        foreach (var e in d.InUse)
            page.Children.Add(Ui.Banner($"{Apps.CapabilityName(e.Capability)} in use now: {e.App.Name} (since {e.Start:HH:mm})", Ui.Red, Ui.RedBg));

        // numbers
        var trackerReq = d.Dns.Count(q => Trackers.Classify(q.Name) is { } h && !Trackers.IsFirstParty(h, q.Exe ?? ""));
        var tiles = new WrapPanel();
        tiles.Children.Add(Ui.Tile(d.Alerts.Count(a => a.Severity == Severity.High).ToString(), "High", Ui.Red));
        tiles.Children.Add(Ui.Tile(d.Alerts.Count(a => a.Severity == Severity.Medium).ToString(), "Medium", Ui.Orange));
        tiles.Children.Add(Ui.Tile($"{trackerReq:N0}", "Tracker requests"));
        tiles.Children.Add(Ui.Tile(d.Access.Count(a => a.Capability == "microphone").ToString(), "Microphone uses"));
        tiles.Children.Add(Ui.Tile(d.Access.Count(a => a.Capability == "webcam").ToString(), "Camera uses"));
        tiles.Children.Add(Ui.Tile(d.Access.Count(a => a.Capability == "location").ToString(), "Location uses"));
        page.Children.Add(tiles);
        if (!P.Monitor || (!d.DnsEnabled && Program.Admin))
            page.Children.Add(Ui.Banner("Setup incomplete: some data will be missing.", Ui.Orange, Ui.OrangeBg));

        // alerts
        var al = Ui.Section("Alerts", "Microphone, camera or screen used in the background, at night or while you were away; personal data read in the background; data brokers contacted. An alert means “worth a look”, not proof.");
        if (d.Alerts.Count == 0) al.Children.Add(Ui.T("No suspicious access today.", 13.5, null, Ui.Green));
        foreach (var a in d.Alerts.Take(30)) al.Children.Add(AlertView(w, a));
        page.Children.Add(Ui.Card(al));

        // trackers today
        page.Children.Add(Ui.Card(TrackersCard(w, d.Dns, d.DnsEnabled)));

        // Wi-Fi
        if (d.Wifi is { } wf)
        {
            var c = Ui.Section("Wi-Fi check");
            if (wf.Weak) c.Children.Add(Ui.Banner($"{wf.Ssid}: {wf.Auth}. Anyone nearby can see traffic that is not encrypted, including the names of the sites you open. Avoid logging in to sites without https, or use a VPN here.", Ui.Red, Ui.RedBg));
            else c.Children.Add(Ui.T($"{wf.Ssid} · encryption: {wf.Auth}", 13.5, null, Ui.Green));
            foreach (var (ip, label) in wf.Dns) c.Children.Add(Ui.T($"DNS: {ip} · {label}", 13, null, label == WifiCheck.Unknown ? Ui.Orange : Ui.Text));
            if (wf.Dns.Any(x => x.Label == WifiCheck.Unknown))
                c.Children.Add(Ui.Hint("This network sends your DNS questions to a server that is neither your router nor a known provider. It is often your internet provider, which is normal; on a public network it can be used to see or change which sites you open."));
            page.Children.Add(Ui.Card(c));
        }

        page.Children.Add(Ui.Card(SetupCard(w, d)));

        // history
        var h = Ui.Section("History");
        h.Children.Add(Ui.T(d.Since is { } s ? $"Recording since {s:d MMMM yyyy}. Stored on this PC: {d.DbBytes / 1024.0 / 1024.0:0.0} MB." : "Nothing recorded yet."));
        h.Children.Add(Ui.Hint("Windows itself keeps only the last use per program, so Privacy Guard writes down every use while it runs. Activity is kept 60 days, the DNS log 30 days."));
        h.Children.Add(TwoStep("Clear history", "Delete everything", () => w.Work("Clearing", () => { S.Clear(); return "History cleared."; })));
        page.Children.Add(Ui.Card(h));
        return page;
    }

    static WifiCheck.State? SafeWifi() { try { return Wifi.Current(); } catch { return null; } }
    static bool SafeAutostart() { try { return StartWithWindows.On(); } catch { return false; } }

    static UIElement AlertView(MainWindow w, Alert a)
    {
        var s = new StackPanel();
        s.Children.Add(Ui.T($"{a.App} · {a.Title}", 13.5, FontWeights.SemiBold, Ui.For(a.Severity)));
        s.Children.Add(Ui.T(a.Text, 13));
        var actions = new WrapPanel();
        if (a.Key != null && a.Key.Contains('\\'))
        {
            var exe = a.Key;
            actions.Children.Add(Ui.Btn("Block its internet", () => w.Work("Blocking", () => { Firewall.Block(exe); return $"{a.App} can no longer connect to the internet. Undo it under Blocking."; }, needsAdmin: true)));
            actions.Children.Add(OpenFolder(exe));
        }
        else if (a.Key != null && (a.Title.Contains("Camera") || a.Title.Contains("Microphone") || a.Title.Contains("Location")))
        {
            var pkg = a.Key;
            foreach (var cap in new[] { "microphone", "webcam", "location" }.Where(c => a.Title.Contains(Apps.CapabilityName(c))))
                actions.Children.Add(Ui.Btn($"Deny {Apps.CapabilityName(cap).ToLowerInvariant()}", () => w.Work("Denying", () => Deny(pkg, cap))));
        }
        if (actions.Children.Count > 0) s.Children.Add(actions);
        return new Border { Background = Ui.BgFor(a.Severity), CornerRadius = new CornerRadius(8), Padding = new Thickness(12, 8, 12, 6), Margin = new Thickness(0, 4, 0, 4), Child = s };
    }

    static string Deny(string pkg, string cap)
    {
        new ConsentStore().SetPackaged(pkg, cap, false);
        P.Denied.Add($"{pkg}|{cap}"); P.Save();
        return $"{Apps.DisplayName(new AppId(pkg, true))} can no longer use the {Apps.CapabilityName(cap).ToLowerInvariant()}. Restore it under Blocking.";
    }

    static Button OpenFolder(string path) =>
        Ui.Btn("Show in folder", () => { if (File.Exists(path)) System.Diagnostics.Process.Start("explorer.exe", $"/select,\"{path}\""); }, tip: path);

    static StackPanel TrackersCard(MainWindow w, List<DnsQuery> dns, bool dnsOn)
    {
        var c = Ui.Section("Trackers today", "Ad networks, data brokers and analytics companies contacted today, with the programs that contacted them.");
        if (!dnsOn) { c.Children.Add(Ui.T("Turn on the DNS log (Setup below) to see who each program talks to.", 13, null, Ui.Orange)); return c; }
        var hits = dns.Select(q => (q, h: Trackers.Classify(q.Name))).Where(x => x.h != null && !Trackers.IsFirstParty(x.h, x.q.Exe ?? "")).ToList();
        if (hits.Count == 0) c.Children.Add(Ui.T("No connections to known trackers today.", 13.5, null, Ui.Green));
        var blocked = Hosts.Blocked().ToHashSet(StringComparer.OrdinalIgnoreCase);
        foreach (var g in hits.GroupBy(x => x.h!.Company).OrderByDescending(g => g.First().h!.Weight).ThenByDescending(g => g.Count()))
        {
            var h = g.First().h!;
            var apps = g.Where(x => x.q.Exe != null).GroupBy(x => x.q.Exe!).OrderByDescending(x => x.Count()).Select(x => Apps.DisplayName(new AppId(x.Key, false))).Take(5);
            var domains = g.Select(x => Trackers.MatchedDomain(x.q.Name)).Where(x => x != null).Select(x => x!).Distinct().ToList();
            var isBlocked = domains.All(blocked.Contains);
            c.Children.Add(Ui.Line2($"{h.Company} · {g.Count():N0} requests", $"{h.CategoryName} · {string.Join(", ", apps)}",
                isBlocked ? Ui.Chip("blocked", Ui.Green, Ui.GreenBg)
                          : Ui.Btn("Block", () => w.Work("Blocking", () => { Hosts.Set(Hosts.Blocked().Concat(domains)); return $"{h.Company} blocked for every program."; }, needsAdmin: true))));
        }
        var ms = dns.Count(q => Trackers.IsMicrosoftTelemetry(q.Name));
        if (ms > 0) c.Children.Add(Ui.Hint($"Windows telemetry: {ms:N0} requests today to Microsoft's diagnostic servers (see Apps → Windows privacy settings)."));
        return c;
    }

    static StackPanel SetupCard(MainWindow w, TodayData d)
    {
        var c = Ui.Section("Setup");
        c.Children.Add(Ui.Line2("1. Full access", Program.Admin ? "Running as administrator: the DNS log, the firewall and domain blocking work."
            : "Needed for the DNS log and for blocking. Windows asks for permission once.",
            Program.Admin ? Ui.Chip("Done", Ui.Green, Ui.GreenBg) : Ui.Btn("Restart with full access", () => { if (Native.RestartAsAdmin()) Application.Current.Shutdown(); }, primary: true)));
        c.Children.Add(Ui.Line2("2. DNS log", "Windows' own log of every lookup, with the program that made it. Off by default.",
            d.DnsEnabled ? Ui.Chip("On", Ui.Green, Ui.GreenBg) : Ui.Btn("Turn on", () => w.Work("Turning on the DNS log", () => Program.Monitor.StartDns(true) ? "The DNS log is on." : "Windows did not allow it."), primary: true, enabled: Program.Admin)));
        c.Children.Add(Ui.Check("3. Record continuously (microphone, camera, location and DNS, in the background)", P.Monitor, on =>
        {
            P.Monitor = on; P.Save();
            if (on) Program.Monitor.Start(Program.Admin); else Program.Monitor.Dispose();
            w.Say(on ? "Recording." : "Recording stopped. Windows' own last-use records are still shown.");
        }));
        c.Children.Add(Ui.Check("4. Start with Windows (in the tray, with full access, no prompt at every logon)", d.Autostart, on =>
            w.Work("Saving", () => StartWithWindows.Set(on, Environment.ProcessPath!) ? (on ? "Privacy Guard starts with Windows." : "Privacy Guard no longer starts with Windows.") : "Windows did not allow it.", needsAdmin: true)));
        c.Children.Add(Ui.Check("Notify when the microphone, camera or screen capture is used in the background or while I'm away", P.NotifySensors, on => { P.NotifySensors = on; P.Save(); }));
        c.Children.Add(Ui.Check("Notify when a program contacts a data broker", P.NotifyBrokers, on => { P.NotifyBrokers = on; P.Save(); }));
        return c;
    }

    /// <summary>A button that asks for a second click before doing something that can't be undone.</summary>
    static Button TwoStep(string label, string confirm, Action act)
    {
        Button? b = null;
        bool armed = false;
        b = Ui.Btn(label, () =>
        {
            if (!armed) { armed = true; b!.Content = confirm + "?"; b.Foreground = Ui.Red; return; }
            act();
        });
        return b;
    }
}
