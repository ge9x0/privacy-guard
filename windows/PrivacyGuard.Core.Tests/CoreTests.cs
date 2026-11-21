using System.Net;
using PrivacyGuard.Core;
using Xunit;

public class CoreTests
{
    static readonly AppId Zoom = Apps.FromConsentKey(@"C:#Program Files#Zoom#bin#Zoom.exe", true);
    static readonly AppId Odd = Apps.FromConsentKey(@"C:#Users#me#AppData#Roaming#updater#svc_helper.exe", true);
    static readonly AppId Camera = Apps.FromConsentKey("Microsoft.WindowsCamera_8wekyb3d8bbwe", false);

    [Fact] public void Names()
    {
        Assert.Equal(@"C:\Program Files\Zoom\bin\Zoom.exe", Zoom.Path);
        Assert.Equal("Zoom", Zoom.Name);
        Assert.Equal("Camera", Camera.Name);
        Assert.Equal("Svc helper", Odd.Name);
        Assert.Equal("WhatsApp", Apps.DisplayName(new AppId("5319275A.WhatsAppDesktop_cv1g1gvanyjgm", true)));
        Assert.Equal("Microphone", Apps.CapabilityName("microphone"));
        Assert.True(Apps.IsCallApp(Zoom));
        Assert.False(Apps.IsCallApp(Odd));
        Assert.True(Apps.IsSystem(new AppId(@"C:\Windows\System32\svchost.exe", false)));
    }

    [Fact] public void AccessAlerts()
    {
        var day = new DateTime(2026, 11, 20);
        var a = new List<Access>
        {
            new(Odd, "microphone", day.AddHours(2).AddMinutes(41), day.AddHours(3), InFront: false),   // night, background, long
            new(Odd, "webcam", day.AddHours(14), day.AddHours(14).AddMinutes(1), UserAway: true),     // away
            new(Zoom, "microphone", day.AddHours(1), day.AddHours(2)),                                // a call app at night: fine
            new(Odd, "contacts", day.AddHours(15), day.AddHours(15), InFront: false),
        };
        var al = Rules.ForAccess(a).ToList();
        Assert.Contains(al, x => x.App == "Svc helper" && x.Title.Contains("background"));
        Assert.Contains(al, x => x.App == "Svc helper" && x.Title.Contains("at night"));
        Assert.Contains(al, x => x.App == "Svc helper" && x.Title.Contains("away"));
        Assert.Contains(al, x => x.App == "Svc helper" && x.Title.StartsWith("Long microphone"));
        Assert.Contains(al, x => x.Title == "Contacts read in the background");
        Assert.DoesNotContain(al, x => x.App == "Zoom");
        Assert.True(Rules.Score(al.Where(x => x.App == "Svc helper"), Array.Empty<Trackers.Hit>()) < 40);
    }

    [Fact] public void TrackerList()
    {
        Assert.Equal("BROKER", Trackers.Classify("sdk.x-mode.io")!.Category);
        Assert.Equal("Data / location broker", Trackers.Classify("api.cuebiq.com")!.CategoryName);
        Assert.Null(Trackers.Classify("example.com"));
        Assert.True(Trackers.IsFirstParty(Trackers.Classify("graph.facebook.com")!, @"C:\x\WhatsApp.exe"));
        Assert.True(Trackers.IsMicrosoftTelemetry("v10.events.data.microsoft.com"));
        Assert.True(Trackers.Domains.Count > 100);
        var q = new[] { new DnsQuery(DateTime.Now, 1, @"C:\a\weather.exe", "sdk.x-mode.io") };
        Assert.Single(Rules.ForTrackers(q));
    }

    [Fact] public void Lists()
    {
        var l = BlockList.Parse("pro", "# comment\ndoubleclick.net\nads.example.org\n\nbad line here\n");
        Assert.Equal(2, l.Count);
        Assert.True(l.Matches("x.y.doubleclick.net"));
        Assert.False(l.Matches("example.org"));
    }

    [Fact] public void Hosts()
    {
        var orig = "# Copyright\r\n127.0.0.1 localhost\r\n";
        var h = HostsFile.WithBlocked(orig, new[] { "Tracker.example", "bad..x", "ads.example.com" });
        Assert.Contains("0.0.0.0 tracker.example", h);
        Assert.Contains("0.0.0.0 www.tracker.example", h);
        Assert.DoesNotContain("bad..x", h);
        Assert.Equal(new[] { "ads.example.com", "tracker.example" }, HostsFile.Blocked(h).OrderBy(x => x));
        Assert.Contains("127.0.0.1 localhost", h);
        Assert.Equal(orig, HostsFile.WithBlocked(h, Array.Empty<string>()));       // removing restores the file
        Assert.Equal(h, HostsFile.WithBlocked(HostsFile.WithBlocked(h, new[] { "x.org" }), new[] { "tracker.example", "ads.example.com" }));
    }

    [Fact] public void Wifi()
    {
        var t = "    Name                   : Wi-Fi\r\n    State                  : connected\r\n    SSID                   : CafeFree\r\n    Authentication         : Open\r\n";
        var p = WifiCheck.ParseNetsh(t)!.Value;
        Assert.Equal("CafeFree", p.Ssid);
        Assert.True(WifiCheck.IsWeak(p.Auth));
        Assert.False(WifiCheck.IsWeak("WPA2-Personal"));
        Assert.Null(WifiCheck.ParseNetsh("    State                  : disconnected\r\n"));
        Assert.Equal("your router (local)", WifiCheck.DnsLabel(IPAddress.Parse("192.168.1.1")));
        Assert.Equal("Cloudflare", WifiCheck.DnsLabel(IPAddress.Parse("1.1.1.1")));
        Assert.Equal(WifiCheck.Unknown, WifiCheck.DnsLabel(IPAddress.Parse("203.0.113.9")));
    }

    [Fact] public void SpywareRules()
    {
        Assert.Equal(Kind.Monitoring, Spyware.Classify(new Autostart(Kind.Autostart, "Spyrix Agent", "Run", "spyrix.exe", null, true, "Spyrix")).Kind);
        var rd = Spyware.Classify(new Autostart(Kind.Service, "AnyDesk", "service", "\"C:\\Program Files\\AnyDesk\\AnyDesk.exe\" --service", @"C:\Program Files\AnyDesk\AnyDesk.exe", true, "philandro"));
        Assert.Equal((Kind.RemoteAccess, Severity.Medium), (rd.Kind, rd.Severity));
        var u = Spyware.Classify(new Autostart(Kind.Autostart, "upd", "Run", "x", @"C:\Users\me\AppData\Roaming\upd.exe", false, null));
        Assert.Equal(Severity.High, u.Severity);
        Assert.Equal(Severity.Info, Spyware.Classify(new Autostart(Kind.Autostart, "OneDrive", "Run", "x", @"C:\Program Files\Microsoft OneDrive\OneDrive.exe", true, "Microsoft Corporation")).Severity);
        Assert.Equal(Severity.High, Spyware.Classify(new BrowserExtension("Chrome", "abc", "Helper", new[] { "<all_urls>", "webRequest", "cookies" }, false)).Severity);
        Assert.Equal(Severity.Info, Spyware.Classify(new BrowserExtension("Chrome", "abc", "Dark mode", new[] { "storage" }, true)).Severity);
        Assert.Equal(Severity.High, Spyware.RootCert("DO_NOT_TRUST_FiddlerRoot", "DO_NOT_TRUST_FiddlerRoot", false).Severity);
        Assert.NotNull(Spyware.Proxy(true, "10.0.0.5:8080", null));
        Assert.Null(Spyware.Proxy(false, "10.0.0.5:8080", null));
    }

    [Fact] public void DebloatAndSettings()
    {
        Assert.True(Debloat.Allowed("Microsoft.BingNews"));
        Assert.False(Debloat.Allowed("Microsoft.WindowsStore"));
        Assert.DoesNotContain(Debloat.List, i => Debloat.Never.Any(n => i.Package.StartsWith(n)));
        var s = PrivacySettings.All.First(x => x.Id == "adid");
        Assert.True(PrivacySettings.IsPrivate(s, _ => 0));
        Assert.False(PrivacySettings.IsPrivate(s, _ => null));
        Assert.Contains(PrivacySettings.All, x => x.NeedsAdmin);
    }

    [Fact] public void StoreRoundTrip()
    {
        var path = Path.Combine(Path.GetTempPath(), $"pg-test-{Guid.NewGuid():N}.db");
        using (var st = new Store(path))
        {
            var t = DateTime.Now.AddMinutes(-5);
            st.Upsert(new Access(Odd, "microphone", t, null, InFront: false));
            st.Upsert(new Access(Odd, "microphone", t, t.AddMinutes(2), InFront: true));   // end time filled, InFront kept
            Assert.True(st.Has(Odd, "microphone", t));
            var a = Assert.Single(st.AccessSince(DateTime.Now.AddHours(-1)));
            Assert.False(a.InFront);
            Assert.InRange(a.Duration!.Value.TotalSeconds, 119, 121);
            st.Add(new DnsQuery(DateTime.Now, 42, @"C:\a\b.exe", "Tracker.Example."));
            Assert.Equal("tracker.example", Assert.Single(st.DnsSince(DateTime.Now.AddHours(-1))).Name);
            st.Prune(); st.Clear();
            Assert.Empty(st.DnsSince(DateTime.MinValue.AddYears(1)));
        }
        Microsoft.Data.Sqlite.SqliteConnection.ClearAllPools();
        File.Delete(path);
    }
}
