using System;
using System.Linq;
using System.Threading.Tasks;
using System.Windows;
using System.Windows.Controls;
using PrivacyGuard.Core;
using PrivacyGuard.Platform;

namespace PrivacyGuard.App;

public static partial class Screens
{
    public static async Task<UIElement> Blocking(MainWindow w)
    {
        var (fwOn, programs, domains) = await Task.Run(() =>
        {
            bool on = false; var progs = new System.Collections.Generic.List<string>();
            try { on = Firewall.On(); progs = Firewall.Blocked(); } catch { }
            return (on, progs, Hosts.Blocked().ToList());
        });
        var page = Page();
        if (!Program.Admin) page.Children.Add(Ui.Banner("Blocking needs full access (administrator rights). Use “Restart with full access” at the top.", Ui.Orange, Ui.OrangeBg));

        // ---- programs without internet ----
        var fw = Ui.Section("Programs without internet", "A Windows Firewall rule cuts all of a program's traffic, including hard-coded IP addresses and its own encrypted DNS.");
        if (!fwOn && Program.Admin) fw.Children.Add(Ui.Banner("Windows Firewall is off for this network, so these rules have no effect. Turn it on in Windows Security → Firewall & network protection.", Ui.Red, Ui.RedBg));
        if (programs.Count == 0) fw.Children.Add(Ui.Hint("None."));
        foreach (var exe in programs)
        {
            var e = exe;
            fw.Children.Add(Ui.Line2(Core.Apps.DisplayName(new AppId(e, false)), e,
                Ui.Btn("Remove", () => w.Work("Removing", () => { Firewall.Unblock(e); return $"{Core.Apps.DisplayName(new AppId(e, false))} can use the internet again."; }, needsAdmin: true))));
        }
        var pick = Ui.Btn("Block a program…", () =>
        {
            var d = new Microsoft.Win32.OpenFileDialog { Filter = "Programs (*.exe)|*.exe", Title = "Choose the program to cut off from the internet" };
            if (d.ShowDialog() == true) { var f = d.FileName; w.Work("Blocking", () => { Firewall.Block(f); return $"{Core.Apps.DisplayName(new AppId(f, false))} can no longer connect to the internet."; }, needsAdmin: true); }
        }, enabled: Program.Admin);
        pick.Tag = "dialog";
        fw.Children.Add(pick);
        page.Children.Add(Ui.Card(fw));

        // ---- blocked domains ----
        var bd = Ui.Section("Blocked domains", "Blocked for every program through Privacy Guard's own section of the Windows hosts file (the rest of the file is never touched). Browsers with “secure DNS” turned on skip the hosts file: turn it off in the browser's settings for these blocks to apply there.");
        var box = new TextBox { MinWidth = 260, Padding = new Thickness(6, 4, 6, 4), Margin = new Thickness(0, 4, 8, 4), Background = Ui.Bg, Foreground = Ui.Text, BorderBrush = Ui.Line, ToolTip = "e.g. ads.example.com" };
        bd.Children.Add(Ui.Row(box, Ui.Btn("Block domain", () =>
        {
            var dname = box.Text.Trim().ToLowerInvariant().Replace("https://", "").Replace("http://", "").Split('/')[0];
            if (!HostsFile.Valid(dname)) { w.Say("That is not a valid domain name, e.g. ads.example.com"); return; }
            w.Work("Blocking", () => { Hosts.Add(dname); return $"{dname} blocked for every program."; }, needsAdmin: true);
        }, enabled: Program.Admin)));
        var known = Trackers.Blockable.ToList();
        var missing = known.Where(k => !domains.Contains(k, StringComparer.OrdinalIgnoreCase)).ToList();
        bd.Children.Add(missing.Count == 0
            ? Ui.T($"All {known.Count} known ad, analytics and data broker domains are blocked.", 13, null, Ui.Green)
            : Ui.Btn($"Block all known ad, analytics and data broker companies ({missing.Count} domains)", () =>
                w.Work("Blocking", () => { Hosts.Set(domains.Concat(missing)); return $"{missing.Count} tracker domains blocked."; }, needsAdmin: true), enabled: Program.Admin));
        foreach (var dm in domains.OrderBy(x => x))
        {
            var d = dm; var t = Trackers.Classify(d);
            bd.Children.Add(Ui.Line2(d, t != null ? $"{t.Company} · {t.CategoryName}" : null,
                Ui.Btn("Remove", () => w.Work("Removing", () => { Hosts.Remove(d); return $"{d} allowed again."; }, needsAdmin: true))));
        }
        page.Children.Add(Ui.Card(bd));

        // ---- block lists (for flagging) ----
        var bl = Ui.Section("Block lists", "HaGeZi lists mark lookups in the DNS log (Activity → Flagged). They are too large for the hosts file, so they flag rather than block; block what you find with one click there.");
        foreach (var d in BlockList.All)
        {
            var def = d;
            var n = Program.Lists.Count(d.Id);
            bl.Children.Add(Ui.Check($"{d.Name}" + (n > 0 ? $" · {n:N0} domains" : P.Lists.Contains(d.Id) ? " · not downloaded yet" : ""), P.Lists.Contains(d.Id), on =>
            {
                if (on) P.Lists.Add(def.Id); else P.Lists.Remove(def.Id);
                P.Save(); Program.Lists.Reload(); w.Refresh();
            }));
        }
        bl.Children.Add(Ui.Btn("Update lists now", () => w.Work("Downloading lists", () => Program.Lists.Update().GetAwaiter().GetResult())));
        if (P.ListsUpdated > DateTime.MinValue) bl.Children.Add(Ui.Hint($"Updated {P.ListsUpdated:d MMMM, HH:mm}. Lists update weekly by themselves."));
        page.Children.Add(Ui.Card(bl));

        // ---- permissions ----
        var pm = Ui.Section("Limited permissions", "Store apps can be denied the camera, microphone or location one by one (Apps tab). Desktop programs can only be allowed or denied as a group: unticking also stops Zoom, Teams, browsers and other call programs.");
        var cs = new ConsentStore();
        foreach (var cap in new[] { "microphone", "webcam", "location" })
        {
            var c = cap;
            pm.Children.Add(Ui.Check($"Let desktop programs use the {Core.Apps.CapabilityName(cap).ToLowerInvariant()}", cs.DesktopAppsAllowed(cap),
                on => w.Work("Saving", () => { cs.SetDesktopApps(c, on); return on ? "Allowed." : $"Desktop programs can no longer use the {Core.Apps.CapabilityName(c).ToLowerInvariant()}."; })));
        }
        if (P.Denied.Count > 0) { pm.Children.Add(Ui.Sep()); pm.Children.Add(Ui.T("Denied to Store apps", 13.5, FontWeights.SemiBold)); }
        foreach (var k in P.Denied.ToList())
        {
            var parts = k.Split('|'); if (parts.Length != 2) continue;
            var (pkg, cap) = (parts[0], parts[1]);
            pm.Children.Add(Ui.Line2($"{Core.Apps.DisplayName(new AppId(pkg, true))} · {Core.Apps.CapabilityName(cap)}", pkg,
                Ui.Btn("Restore", () => w.Work("Restoring", () => { cs.SetPackaged(pkg, cap, true); P.Denied.Remove(k); P.Save(); return "Allowed again."; }))));
        }
        page.Children.Add(Ui.Card(pm));
        return page;
    }
}
