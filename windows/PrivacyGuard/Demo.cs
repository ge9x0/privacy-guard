using System;
using PrivacyGuard.Core;

namespace PrivacyGuard.App;

/// <summary>Example data for screenshots and videos (`--selftest` only). Program names are made up; tracker companies are real.</summary>
public static class Demo
{
    public static void Seed(Store s)
    {
        var t = DateTime.Today;
        var now = DateTime.Now;
        const string notes = @"C:\Users\demo\AppData\Roaming\QuickNotes\QuickNotes.exe";
        const string vpn = @"C:\Users\demo\AppData\Local\FreeVPN\FreeVPN.exe";
        const string zoom = @"C:\Program Files\Zoom\bin\Zoom.exe";
        const string chrome = @"C:\Program Files\Google\Chrome\Application\chrome.exe";
        var a = (string key, bool pkg, string cap, DateTime start, int minutes, bool front, bool away) =>
            s.Upsert(new Access(new AppId(key, pkg), cap, start, start.AddMinutes(minutes), front, away));

        a(notes, false, "microphone", t.AddHours(2).AddMinutes(14), 6, false, true);
        a(notes, false, "microphone", t.AddHours(3).AddMinutes(40), 4, false, true);
        a(notes, false, "webcam", t.AddHours(2).AddMinutes(15), 1, false, true);
        a(vpn, false, "location", now.AddMinutes(-190), 1, false, false);
        a(vpn, false, "location", now.AddMinutes(-95), 1, false, false);
        a(vpn, false, "location", now.AddMinutes(-20), 1, false, false);
        a(zoom, false, "microphone", now.AddMinutes(-150), 42, true, false);
        a(zoom, false, "webcam", now.AddMinutes(-150), 42, true, false);
        a("Microsoft.WindowsCamera_8wekyb3d8bbwe", true, "webcam", now.AddMinutes(-60), 2, true, false);
        a("Microsoft.BingWeather_8wekyb3d8bbwe", true, "location", now.AddMinutes(-45), 1, true, false);

        var d = (string exe, string name, int n, int minutesAgo) =>
        {
            for (int i = 0; i < n; i++) s.Add(new DnsQuery(now.AddMinutes(-minutesAgo - i * 3), 4000 + exe.Length, exe, name));
        };
        d(notes, "sdk.x-mode.io", 14, 300);
        d(notes, "t.appsflyersdk.com", 9, 290);
        d(notes, "api.mixpanel.com", 22, 280);
        d(vpn, "control.kochava.com", 11, 200);
        d(vpn, "d.applovin.com", 37, 180);
        d(vpn, "ms.applvn.com", 12, 170);
        d(chrome, "securepubads.g.doubleclick.net", 48, 120);
        d(chrome, "www.google-analytics.com", 31, 110);
        d(chrome, "static.criteo.net", 17, 100);
        d(chrome, "cdn.taboola.com", 9, 90);
        d(chrome, "www.wikipedia.org", 6, 80);
        d(zoom, "zoom.us", 20, 150);
        d(@"C:\Windows\System32\svchost.exe", "v10.events.data.microsoft.com", 64, 240);
    }
}
