using System.Diagnostics;
using System.Security.Cryptography.X509Certificates;
using System.Text.Json;
using System.Text.RegularExpressions;
using Microsoft.Win32;
using PrivacyGuard.Core;

namespace PrivacyGuard.Platform;

/// <summary>Collects what can watch you on Windows: programs that start by themselves, browser extensions, proxies, certificates, Remote Desktop, Defender.</summary>
public static class SpywareScan
{
    public static List<Finding> Run()
    {
        var f = new List<Finding>();
        void Try(Action a) { try { a(); } catch (Exception e) { Debug.WriteLine(e); } }
        Try(() => f.AddRange(Autostarts().Select(Spyware.Classify)));
        Try(() => f.AddRange(RunningRemoteTools()));
        Try(() => f.AddRange(Extensions().Select(Spyware.Classify)));
        Try(() => { if (ProxyFinding() is { } p) f.Add(p); });
        Try(() => f.AddRange(Certificates()));
        Try(() => { if (Spyware.RemoteDesktop(RdpEnabled()) is { } r) f.Add(r); });
        Try(() => { if (Spyware.Antivirus(DefenderRealtime()) is { } d) f.Add(d); });
        // one entry per item (a program can be in a Run key and running at the same time)
        return f.GroupBy(x => (x.Kind, x.Name, x.Path)).Select(g => g.OrderByDescending(x => x.Severity).First())
            .OrderByDescending(x => x.Severity).ThenBy(x => x.Name).ToList();
    }

    // ---------- programs that start by themselves ----------
    public static List<Autostart> Autostarts()
    {
        var l = new List<Autostart>();
        foreach (var (hive, hn) in new[] { (Registry.CurrentUser, "HKCU"), (Registry.LocalMachine, "HKLM") })
            foreach (var sub in new[] { @"Software\Microsoft\Windows\CurrentVersion\Run", @"Software\Microsoft\Windows\CurrentVersion\RunOnce", @"Software\WOW6432Node\Microsoft\Windows\CurrentVersion\Run" })
            {
                using var k = hive.OpenSubKey(sub);
                if (k == null) continue;
                foreach (var n in k.GetValueNames())
                    if (k.GetValue(n) is string cmd) l.Add(Make(Kind.Autostart, n, $@"{hn}\...\{sub.Split('\\').Last()}", cmd));
            }
        foreach (var dir in new[] { Environment.GetFolderPath(Environment.SpecialFolder.Startup), Environment.GetFolderPath(Environment.SpecialFolder.CommonStartup) })
            if (Directory.Exists(dir))
                foreach (var file in Directory.GetFiles(dir).Where(x => !x.EndsWith("desktop.ini", StringComparison.OrdinalIgnoreCase)))
                    l.Add(Make(Kind.Autostart, Path.GetFileNameWithoutExtension(file), "Startup folder", ShortcutTarget(file) ?? file));
        l.AddRange(Tasks());
        l.AddRange(Services());
        return l;
    }

    static Autostart Make(Kind kind, string name, string source, string cmd)
    {
        var path = ExeOf(cmd);
        var (signed, signer) = path != null && File.Exists(path) ? Signature.Check(path) : ((bool?)null, (string?)null);
        if (path != null && !File.Exists(path)) signed = null;
        return new Autostart(kind, name, source, cmd, path, signed, signer);
    }

    /// <summary>The program in a command line: "C:\a b\x.exe" -arg, C:\x.exe arg, %ProgramFiles%\..., rundll32 a.dll,Entry.</summary>
    public static string? ExeOf(string cmd)
    {
        cmd = Environment.ExpandEnvironmentVariables(cmd.Trim());
        if (cmd.StartsWith('"')) { var e = cmd.IndexOf('"', 1); return e > 1 ? cmd[1..e] : null; }
        var m = Regex.Match(cmd, @"^(.+?\.(exe|com|bat|cmd|ps1|vbs|js|scr))(\s|$)", RegexOptions.IgnoreCase);
        var exe = m.Success ? m.Groups[1].Value : cmd.Split(' ')[0];
        if (Regex.IsMatch(Path.GetFileName(exe), "^rundll32(\\.exe)?$", RegexOptions.IgnoreCase))
        {
            var dll = Regex.Match(cmd, @"rundll32(?:\.exe)?\s+""?([^"",]+\.dll)", RegexOptions.IgnoreCase);
            if (dll.Success) return dll.Groups[1].Value;
        }
        if (!exe.Contains('\\'))
        {
            var sys = Path.Combine(Environment.SystemDirectory, exe.EndsWith(".exe", StringComparison.OrdinalIgnoreCase) ? exe : exe + ".exe");
            return File.Exists(sys) ? sys : exe;
        }
        return exe;
    }

    static string? ShortcutTarget(string lnk)
    {
        if (!lnk.EndsWith(".lnk", StringComparison.OrdinalIgnoreCase)) return null;
        try
        {
            dynamic sh = Activator.CreateInstance(Type.GetTypeFromProgID("WScript.Shell")!)!;
            dynamic s = sh.CreateShortcut(lnk);
            string t = s.TargetPath, a = s.Arguments;
            return string.IsNullOrEmpty(t) ? null : $"\"{t}\" {a}".Trim();
        }
        catch { return null; }
    }

    /// <summary>Scheduled tasks not written by Microsoft (schtasks CSV, English column names on every language).</summary>
    static IEnumerable<Autostart> Tasks()
    {
        var (code, outp) = Native.Run("schtasks.exe", "/query /fo csv /v", 60_000);
        if (code != 0) yield break;
        var rows = Csv(outp).ToList();
        if (rows.Count < 2) yield break;
        var h = rows[0];
        int iName = h.IndexOf("TaskName"), iRun = h.IndexOf("Task To Run"), iAuthor = h.IndexOf("Author"), iState = h.IndexOf("Scheduled Task State");
        if (iName < 0 || iRun < 0) yield break;
        var seen = new HashSet<string>();
        foreach (var r in rows.Skip(1))
        {
            if (r.Count <= Math.Max(iName, iRun) || r[iName] == "TaskName") continue;
            var name = r[iName]; var run = r[iRun];
            if (name.StartsWith(@"\Microsoft\", StringComparison.OrdinalIgnoreCase) || run is "" or "COM handler" || !seen.Add(name)) continue;
            if (iState >= 0 && r.Count > iState && r[iState].Equals("Disabled", StringComparison.OrdinalIgnoreCase)) continue;
            yield return Make(Kind.Task, name.TrimStart('\\'), "Scheduled task" + (iAuthor >= 0 && r.Count > iAuthor && r[iAuthor] is { Length: > 0 } au && au != "N/A" ? $" by {au}" : ""), run);
        }
    }

    static IEnumerable<List<string>> Csv(string text)
    {
        foreach (var line in text.Split('\n'))
        {
            var l = line.TrimEnd('\r');
            if (l.Length == 0) continue;
            var cells = new List<string>(); var cur = new System.Text.StringBuilder(); bool q = false;
            for (int i = 0; i < l.Length; i++)
            {
                var c = l[i];
                if (q) { if (c == '"' && i + 1 < l.Length && l[i + 1] == '"') { cur.Append('"'); i++; } else if (c == '"') q = false; else cur.Append(c); }
                else if (c == '"') q = true; else if (c == ',') { cells.Add(cur.ToString()); cur.Clear(); } else cur.Append(c);
            }
            cells.Add(cur.ToString());
            yield return cells;
        }
    }

    /// <summary>Automatic services whose program is outside Windows' own folder.</summary>
    static IEnumerable<Autostart> Services()
    {
        using var sk = Registry.LocalMachine.OpenSubKey(@"SYSTEM\CurrentControlSet\Services");
        if (sk == null) yield break;
        var win = Environment.GetFolderPath(Environment.SpecialFolder.Windows);
        foreach (var n in sk.GetSubKeyNames())
        {
            using var k = sk.OpenSubKey(n);
            if (k == null || k.GetValue("Start") is not int start || start > 2) continue;   // boot/system/automatic
            if (k.GetValue("Type") is not int type || (type & 0x30) == 0) continue;            // Win32 services only (not drivers)
            if (k.GetValue("ImagePath") is not string img) continue;
            var exe = ExeOf(img);
            if (exe == null || exe.StartsWith(win, StringComparison.OrdinalIgnoreCase) || exe.StartsWith(@"\SystemRoot\", StringComparison.OrdinalIgnoreCase)) continue;
            var display = k.GetValue("DisplayName") as string;
            yield return Make(Kind.Service, display is { Length: > 0 } && !display.StartsWith('@') ? display : n, "Service", img);
        }
    }

    static IEnumerable<Finding> RunningRemoteTools()
    {
        foreach (var p in Process.GetProcesses())
        {
            string name; try { name = p.ProcessName; } catch { continue; }
            var path = Native.ProcessPath(p.Id);
            var a = new Autostart(Kind.RemoteAccess, name, "running now", path ?? name, path, null, null);
            var f = Spyware.Classify(a);
            if (f.Kind is Kind.RemoteAccess or Kind.Monitoring) yield return f with { Detail = f.Detail.Replace("that starts with Windows", "running right now") };
        }
    }

    // ---------- browser extensions ----------
    public static List<BrowserExtension> Extensions()
    {
        var l = new List<BrowserExtension>();
        var local = Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData);
        foreach (var (browser, dir) in new[] { ("Chrome", @"Google\Chrome\User Data"), ("Edge", @"Microsoft\Edge\User Data"), ("Brave", @"BraveSoftware\Brave-Browser\User Data"), ("Vivaldi", @"Vivaldi\User Data") })
        {
            var root = Path.Combine(local, dir);
            if (!Directory.Exists(root)) continue;
            foreach (var profile in Directory.GetDirectories(root).Where(d => Path.GetFileName(d) is "Default" || Path.GetFileName(d).StartsWith("Profile ")))
            {
                var ext = Path.Combine(profile, "Extensions");
                if (!Directory.Exists(ext)) continue;
                foreach (var idDir in Directory.GetDirectories(ext))
                {
                    var ver = Directory.GetDirectories(idDir).OrderBy(x => x).LastOrDefault();
                    var mf = ver == null ? null : Path.Combine(ver, "manifest.json");
                    if (mf == null || !File.Exists(mf)) continue;
                    try
                    {
                        using var doc = JsonDocument.Parse(File.ReadAllText(mf), new JsonDocumentOptions { CommentHandling = JsonCommentHandling.Skip, AllowTrailingCommas = true });
                        var m = doc.RootElement;
                        var name = Localize(m.TryGetProperty("name", out var nm) ? nm.GetString() ?? "" : "", ver!, m);
                        if (name.Length == 0) continue;
                        var perms = new List<string>();
                        foreach (var key in new[] { "permissions", "host_permissions", "optional_permissions" })
                            if (m.TryGetProperty(key, out var p) && p.ValueKind == JsonValueKind.Array)
                                perms.AddRange(p.EnumerateArray().Where(x => x.ValueKind == JsonValueKind.String).Select(x => x.GetString()!));
                        if (m.TryGetProperty("content_scripts", out var cs) && cs.ValueKind == JsonValueKind.Array)
                            foreach (var c in cs.EnumerateArray())
                                if (c.TryGetProperty("matches", out var mt)) perms.AddRange(mt.EnumerateArray().Select(x => x.GetString() ?? ""));
                        var upd = m.TryGetProperty("update_url", out var u) ? u.GetString() ?? "" : "";
                        var store = upd.Contains("clients2.google.com") || upd.Contains("edge.microsoft.com");
                        l.Add(new BrowserExtension($"{browser}{(Path.GetFileName(profile) == "Default" ? "" : $", {Path.GetFileName(profile)}")}", Path.GetFileName(idDir), name, perms.Distinct().ToArray(), store));
                    }
                    catch { }
                }
            }
        }
        // Firefox
        var ff = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), @"Mozilla\Firefox\Profiles");
        if (Directory.Exists(ff))
            foreach (var prof in Directory.GetDirectories(ff))
            {
                var ej = Path.Combine(prof, "extensions.json");
                if (!File.Exists(ej)) continue;
                try
                {
                    using var doc = JsonDocument.Parse(File.ReadAllText(ej));
                    foreach (var a in doc.RootElement.GetProperty("addons").EnumerateArray())
                    {
                        if (a.GetProperty("location").GetString() != "app-profile" || a.GetProperty("type").GetString() != "extension") continue;
                        var name = a.GetProperty("defaultLocale").GetProperty("name").GetString() ?? a.GetProperty("id").GetString()!;
                        var perms = new List<string>();
                        if (a.TryGetProperty("userPermissions", out var up) && up.ValueKind == JsonValueKind.Object)
                            foreach (var key in new[] { "permissions", "origins" })
                                if (up.TryGetProperty(key, out var p)) perms.AddRange(p.EnumerateArray().Select(x => x.GetString() ?? ""));
                        var signed = a.TryGetProperty("signedState", out var ss) && ss.ValueKind == JsonValueKind.Number && ss.GetInt32() >= 2;
                        l.Add(new BrowserExtension("Firefox", a.GetProperty("id").GetString()!, name, perms.ToArray(), signed));
                    }
                }
                catch { }
            }
        return l;
    }

    static string Localize(string name, string dir, JsonElement manifest)
    {
        var m = Regex.Match(name, "^__MSG_(.+)__$");
        if (!m.Success) return name;
        var loc = manifest.TryGetProperty("default_locale", out var d) ? d.GetString() ?? "en" : "en";
        foreach (var l in new[] { loc, "en", "en_US" })
        {
            var f = Path.Combine(dir, "_locales", l, "messages.json");
            if (!File.Exists(f)) continue;
            using var doc = JsonDocument.Parse(File.ReadAllText(f));
            foreach (var p in doc.RootElement.EnumerateObject())
                if (p.Name.Equals(m.Groups[1].Value, StringComparison.OrdinalIgnoreCase) && p.Value.TryGetProperty("message", out var msg))
                    return msg.GetString() ?? name;
        }
        return m.Groups[1].Value;
    }

    // ---------- network interception ----------
    static Finding? ProxyFinding()
    {
        using var k = Registry.CurrentUser.OpenSubKey(@"Software\Microsoft\Windows\CurrentVersion\Internet Settings");
        return Spyware.Proxy(k?.GetValue("ProxyEnable") is int e && e == 1, k?.GetValue("ProxyServer") as string, k?.GetValue("AutoConfigURL") as string);
    }

    /// <summary>Root certificates added for this user (registry list), plus interception tools in the machine store.</summary>
    static IEnumerable<Finding> Certificates()
    {
        using var reg = Registry.CurrentUser.OpenSubKey(@"Software\Microsoft\SystemCertificates\Root\Certificates");
        var userThumbs = reg?.GetSubKeyNames().ToHashSet(StringComparer.OrdinalIgnoreCase) ?? new HashSet<string>();
        using (var s = new X509Store(StoreName.Root, StoreLocation.CurrentUser))
        {
            s.Open(OpenFlags.ReadOnly);
            foreach (var c in s.Certificates)
                if (userThumbs.Contains(c.Thumbprint)) yield return Spyware.RootCert(c.GetNameInfo(X509NameType.SimpleName, false), c.Issuer, true);
        }
        using (var s = new X509Store(StoreName.Root, StoreLocation.LocalMachine))
        {
            s.Open(OpenFlags.ReadOnly);
            foreach (var c in s.Certificates)
            {
                var f = Spyware.RootCert(c.GetNameInfo(X509NameType.SimpleName, false), c.Issuer, false);
                if (f.Severity == Severity.High) yield return f;
            }
        }
    }

    static bool RdpEnabled()
    {
        using var k = Registry.LocalMachine.OpenSubKey(@"SYSTEM\CurrentControlSet\Control\Terminal Server");
        return k?.GetValue("fDenyTSConnections") is int v && v == 0;
    }

    public static bool? DefenderRealtime()
    {
        var (code, o) = Native.PowerShell("(Get-MpComputerStatus).RealTimeProtectionEnabled", 30_000);
        if (code != 0) return null;
        return o.Trim().Equals("True", StringComparison.OrdinalIgnoreCase) ? true : o.Trim().Equals("False", StringComparison.OrdinalIgnoreCase) ? false : null;
    }
}
