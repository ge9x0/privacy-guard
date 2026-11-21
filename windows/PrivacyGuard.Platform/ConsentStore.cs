using Microsoft.Win32;
using PrivacyGuard.Core;
using System.Runtime.InteropServices;

namespace PrivacyGuard.Platform;

/// <summary>
/// Windows' own record of who used the microphone, camera, location... (Settings → Privacy → "Recent activity").
/// HKCU\Software\Microsoft\Windows\CurrentVersion\CapabilityAccessManager\ConsentStore\&lt;capability&gt;\[NonPackaged\]&lt;app&gt;
/// with LastUsedTimeStart / LastUsedTimeStop (FILETIME). Stop = 0 while in use. Only the last use per app is kept,
/// so the monitor reads it on every change and keeps the history itself.
/// </summary>
public sealed class ConsentStore
{
    public const string DefaultPath = @"Software\Microsoft\Windows\CurrentVersion\CapabilityAccessManager\ConsentStore";
    readonly RegistryKey root; readonly string path;

    public ConsentStore(RegistryKey? root = null, string path = DefaultPath) { this.root = root ?? Registry.CurrentUser; this.path = path; }

    public sealed record Entry(AppId App, string Capability, DateTime Start, DateTime? Stop)
    {
        public bool InUse => Stop == null;
    }

    public List<Entry> Read()
    {
        var r = new List<Entry>();
        using var cs = root.OpenSubKey(path);
        if (cs == null) return r;
        foreach (var cap in cs.GetSubKeyNames())
        {
            using var ck = cs.OpenSubKey(cap);
            if (ck == null) continue;
            foreach (var app in ck.GetSubKeyNames())
            {
                if (app.Equals("NonPackaged", StringComparison.OrdinalIgnoreCase))
                {
                    using var np = ck.OpenSubKey(app);
                    if (np == null) continue;
                    foreach (var exe in np.GetSubKeyNames()) Add(np, exe, true);
                }
                else Add(ck, app, false);
            }
            void Add(RegistryKey parent, string name, bool nonPackaged)
            {
                using var k = parent.OpenSubKey(name);
                if (k?.GetValue("LastUsedTimeStart") is not long s || s <= 0) return;
                var e = k.GetValue("LastUsedTimeStop") is long t ? t : 0;
                r.Add(new Entry(Apps.FromConsentKey(name, nonPackaged), cap, DateTime.FromFileTime(s), e > 0 ? DateTime.FromFileTime(e) : null));
            }
        }
        return r;
    }

    /// <summary>Store apps can be denied one capability ("Deny"); "Allow" gives it back.</summary>
    public void SetPackaged(string package, string capability, bool allow)
    {
        using var k = root.CreateSubKey($@"{path}\{capability}\{package}");
        k.SetValue("Value", allow ? "Allow" : "Deny", RegistryValueKind.String);
    }

    public string? PackagedValue(string package, string capability)
    {
        using var k = root.OpenSubKey($@"{path}\{capability}\{package}");
        return k?.GetValue("Value") as string;
    }

    /// <summary>Desktop programs can only be allowed or denied as a group (Settings: "Let desktop apps access ...").</summary>
    public void SetDesktopApps(string capability, bool allow)
    {
        using var k = root.CreateSubKey($@"{path}\{capability}\NonPackaged");
        k.SetValue("Value", allow ? "Allow" : "Deny", RegistryValueKind.String);
    }

    public bool DesktopAppsAllowed(string capability)
    {
        using var k = root.OpenSubKey($@"{path}\{capability}\NonPackaged");
        return (k?.GetValue("Value") as string) != "Deny";
    }

    [DllImport("advapi32.dll")] static extern int RegNotifyChangeKeyValue(IntPtr key, bool subtree, int filter, IntPtr ev, bool async);

    /// <summary>Calls `changed` whenever anything under the ConsentStore changes (a program starts or stops using something).</summary>
    public IDisposable Watch(Action changed)
    {
        var cts = new CancellationTokenSource();
        var t = new Thread(() =>
        {
            using var k = root.OpenSubKey(path);
            if (k == null) return;
            using var ev = new AutoResetEvent(false);
            while (!cts.IsCancellationRequested)
            {
                // name, values, subkeys and attributes; subtree; signal the event
                if (RegNotifyChangeKeyValue(k.Handle.DangerousGetHandle(), true, 0x1 | 0x4, ev.SafeWaitHandle.DangerousGetHandle(), true) != 0) return;
                if (WaitHandle.WaitAny(new[] { ev, cts.Token.WaitHandle }) == 0) { try { changed(); } catch { } }
            }
        }) { IsBackground = true, Name = "pg-consent" };
        t.Start();
        return new Stop(cts);
    }

    sealed class Stop(CancellationTokenSource c) : IDisposable { public void Dispose() => c.Cancel(); }
}
