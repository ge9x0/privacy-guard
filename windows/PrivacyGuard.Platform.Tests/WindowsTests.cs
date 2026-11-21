using System.Net;
using Microsoft.Win32;
using PrivacyGuard.Core;
using PrivacyGuard.Platform;
using Xunit;
using Xunit.Abstractions;

// Runs on a real Windows (GitHub Actions, administrator). Each test undoes what it changes.
public class WindowsTests(ITestOutputHelper log)
{
    const string TestPath = @"Software\PrivacyGuardTest\ConsentStore";

    [Fact] public void Elevated() => Assert.True(Native.IsAdmin(), "the Windows tests need administrator rights");

    [Fact] public void ConsentStoreReadsAndWatches()
    {
        Registry.CurrentUser.DeleteSubKeyTree(@"Software\PrivacyGuardTest", false);
        try
        {
            var start = DateTime.Now.AddMinutes(-3).ToFileTime();
            using (var k = Registry.CurrentUser.CreateSubKey($@"{TestPath}\microphone\NonPackaged\C:#Users#me#AppData#Roaming#x#spy.exe"))
            { k.SetValue("LastUsedTimeStart", start, RegistryValueKind.QWord); k.SetValue("LastUsedTimeStop", 0L, RegistryValueKind.QWord); }
            using (var k = Registry.CurrentUser.CreateSubKey($@"{TestPath}\webcam\Microsoft.WindowsCamera_8wekyb3d8bbwe"))
            { k.SetValue("LastUsedTimeStart", start, RegistryValueKind.QWord); k.SetValue("LastUsedTimeStop", DateTime.Now.ToFileTime(), RegistryValueKind.QWord); }

            var cs = new ConsentStore(Registry.CurrentUser, TestPath);
            var e = cs.Read();
            Assert.Equal(2, e.Count);
            var mic = e.Single(x => x.Capability == "microphone");
            Assert.True(mic.InUse);
            Assert.Equal(@"C:\Users\me\AppData\Roaming\x\spy.exe", mic.App.Path);
            Assert.Equal("Camera", e.Single(x => x.Capability == "webcam").App.Name);

            cs.SetPackaged("Microsoft.WindowsCamera_8wekyb3d8bbwe", "webcam", false);
            Assert.Equal("Deny", cs.PackagedValue("Microsoft.WindowsCamera_8wekyb3d8bbwe", "webcam"));
            cs.SetDesktopApps("microphone", false);
            Assert.False(cs.DesktopAppsAllowed("microphone"));

            using var fired = new ManualResetEventSlim();
            using (cs.Watch(() => fired.Set()))
            {
                Thread.Sleep(300);
                using var k = Registry.CurrentUser.OpenSubKey($@"{TestPath}\microphone\NonPackaged\C:#Users#me#AppData#Roaming#x#spy.exe", true)!;
                k.SetValue("LastUsedTimeStop", DateTime.Now.ToFileTime(), RegistryValueKind.QWord);
                Assert.True(fired.Wait(5000), "no change notification");
            }

            // the monitor stores it, with the end time once it stops
            var db = Path.Combine(Path.GetTempPath(), $"pg-{Guid.NewGuid():N}.db");
            using (var store = new Store(db))
            using (var mon = new ActivityMonitor(store, new Lists(new Prefs()), cs))
            {
                mon.Sync();
                var a = store.AccessSince(DateTime.Now.AddHours(-1));
                Assert.Equal(2, a.Count);
                Assert.All(a, x => Assert.NotNull(x.Stop));
            }
        }
        finally { Registry.CurrentUser.DeleteSubKeyTree(@"Software\PrivacyGuardTest", false); }
    }

    [Fact] public void ProcessAndDesktop()
    {
        var me = Native.ProcessPath(Environment.ProcessId);
        log.WriteLine($"self={me} fg={Native.ForegroundPath()} idle={Native.Idle()}");
        Assert.NotNull(me);
        Assert.True(File.Exists(me));
        Assert.True(Native.Idle() >= TimeSpan.Zero);
    }

    [Fact] public void FirewallBlockAndUnblock()
    {
        var exe = Path.Combine(Environment.SystemDirectory, "curl.exe");
        try
        {
            Firewall.Block(exe);
            Assert.Contains(exe, Firewall.Blocked(), StringComparer.OrdinalIgnoreCase);
            Firewall.Block(exe);    // twice: still one entry
            Assert.Single(Firewall.Blocked(), x => x.Equals(exe, StringComparison.OrdinalIgnoreCase));
            log.WriteLine($"firewall on: {Firewall.On()}");
        }
        finally { Firewall.Unblock(exe); }
        Assert.DoesNotContain(exe, Firewall.Blocked(), StringComparer.OrdinalIgnoreCase);
    }

    [Fact] public void HostsBlockingWorks()
    {
        var before = File.ReadAllText(Hosts.DefaultPath);
        const string d = "blocked-by-privacyguard.test";
        try
        {
            Hosts.Add(d);
            Assert.Contains(d, Hosts.Blocked());
            var ips = Dns.GetHostAddresses(d);
            log.WriteLine(string.Join(",", ips.Select(i => i.ToString())));
            Assert.Contains(IPAddress.Any, ips);
            Hosts.Remove(d);
            Assert.DoesNotContain(d, Hosts.Blocked());
        }
        finally { Hosts.Set(Array.Empty<string>()); }
        Assert.Equal(before.TrimEnd(), File.ReadAllText(Hosts.DefaultPath).TrimEnd());
    }

    [Fact] public void DnsLogShowsTheProcess()
    {
        Assert.True(DnsLog.Enable(), "could not turn on the DNS client log");
        Assert.True(DnsLog.Enabled());
        var name = $"pg-{Guid.NewGuid():N}.example.com";
        using var got = new ManualResetEventSlim();
        DnsQuery? hit = null;
        using var dl = new DnsLog();
        dl.Start(q => { if (q.Name.Equals(name, StringComparison.OrdinalIgnoreCase)) { hit = q; got.Set(); } });
        Thread.Sleep(500);
        try { Dns.GetHostAddresses(name); } catch { }
        Assert.True(got.Wait(15_000), "the lookup did not appear in the DNS log");
        log.WriteLine($"{hit!.Name} pid={hit.Pid} exe={hit.Exe}");
        Assert.Contains(DnsLog.Since(DateTime.Now.AddMinutes(-2)), q => q.Name.Equals(name, StringComparison.OrdinalIgnoreCase));
    }

    [Fact] public void SpywareScanFindsATestEntry()
    {
        const string run = @"Software\Microsoft\Windows\CurrentVersion\Run";
        var fake = Path.Combine(Path.GetTempPath(), "pgtest-updater.exe");
        File.WriteAllBytes(fake, new byte[] { 0x4D, 0x5A });   // unsigned
        try
        {
            using (var k = Registry.CurrentUser.OpenSubKey(run, true)!) k.SetValue("PGTest Updater", $"\"{fake}\" --silent");
            var f = SpywareScan.Run();
            foreach (var x in f.Take(30)) log.WriteLine($"{x.Severity} {x.Kind} {x.Name}: {x.Detail}");
            var t = Assert.Single(f, x => x.Name == "PGTest Updater");
            Assert.Equal(Severity.High, t.Severity);   // unsigned, from a user-writable folder
            Assert.Contains(f, x => x.Kind == Kind.Service);
            Assert.NotNull(SpywareScan.DefenderRealtime());
            Assert.Equal(@"C:\Windows\System32\cmd.exe", SpywareScan.ExeOf(@"cmd /c echo"), ignoreCase: true);
            Assert.Equal(@"C:\Program Files\A B\x.exe", SpywareScan.ExeOf("\"C:\\Program Files\\A B\\x.exe\" -arg"));
            Assert.True(Signature.Check(Path.Combine(Environment.SystemDirectory, "notepad.exe")).Signed);
            Assert.False(Signature.Check(fake).Signed);
        }
        finally
        {
            using (var k = Registry.CurrentUser.OpenSubKey(run, true)!) k.DeleteValue("PGTest Updater", false);
            File.Delete(fake);
        }
    }

    [Fact] public void StoreApps()
    {
        var l = Appx.Installed();
        log.WriteLine($"{l.Count} packages: " + string.Join(", ", l.Take(15).Select(p => p.Name)));
        Assert.NotEmpty(l);
        Assert.False(Appx.Remove("Microsoft.WindowsStore").Ok);   // never offered
    }

    [Fact] public void PrivacySettingsRoundTrip()
    {
        var s = PrivacySettings.All.First(x => x.Id == "adid");
        var before = SettingsIO.Read(s.Values[0]);
        try
        {
            SettingsIO.Set(s, true);
            Assert.True(SettingsIO.IsPrivate(s));
            SettingsIO.Set(s, false);
            Assert.False(SettingsIO.IsPrivate(s));
            var m = PrivacySettings.All.First(x => x.Id == "telemetry");
            var mb = SettingsIO.Read(m.Values[0]);
            SettingsIO.Set(m, true); Assert.True(SettingsIO.IsPrivate(m));
            if (mb == null) SettingsIO.Set(m, false);
        }
        finally
        {
            using var k = Registry.CurrentUser.CreateSubKey(s.Values[0].Path);
            if (before is { } b) k.SetValue(s.Values[0].Name, b, RegistryValueKind.DWord); else k.DeleteValue(s.Values[0].Name, false);
        }
    }

    [Fact] public void WifiAndAutostart()
    {
        var w = Wifi.Current();   // no Wi-Fi on a build server: null, but it must not fail
        log.WriteLine($"wifi: {w?.Ssid} {w?.Auth}");
        var exe = Path.Combine(Environment.SystemDirectory, "notepad.exe");
        try
        {
            Assert.True(StartWithWindows.Set(true, exe));
            Assert.True(StartWithWindows.On());
        }
        finally { StartWithWindows.Set(false, exe); }
        Assert.False(StartWithWindows.On());
    }
}
