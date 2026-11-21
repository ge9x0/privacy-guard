using System.Text.RegularExpressions;

namespace PrivacyGuard.Core;

/// <summary>What the spyware check looks at on Windows (the counterpart of accessibility services, device admin etc. on Android).</summary>
public enum Kind { Autostart, Task, Service, RemoteAccess, Monitoring, Extension, Proxy, RootCertificate, RemoteDesktop, Antivirus, Admin }

public sealed record Finding(Kind Kind, Severity Severity, string Name, string Detail, string? Path = null);

/// <summary>A program that starts by itself: Run keys, Startup folder, scheduled tasks, services.</summary>
public sealed record Autostart(Kind Kind, string Name, string Source, string Command, string? Path, bool? Signed, string? Signer);

public sealed record BrowserExtension(string Browser, string Id, string Name, string[] Permissions, bool FromStore);

public static class Spyware
{
    // Commercial monitoring, keyloggers and stalkerware for Windows (names as they appear in paths, services or signers).
    static readonly Regex Monitoring = new(
        @"(flexispy|mspy|spyrix|refog|spyagent|spytech|kidlogger|revealer|ardamax|hoverwatch|actual\s?keylogger|elite\s?keylogger|" +
        @"best\s?free\s?keylogger|webwatcher|teramind|activtrak|veriato|interguard|hubstaff|kickidler|staffcop|cleverControl|" +
        @"spyshelter|realtime-spy|pcpandora|netvizor|snoopza|keylogger)", RegexOptions.IgnoreCase);

    // Remote control tools: normal for IT support, dangerous when someone else installed them.
    static readonly Regex Remote = new(
        @"(anydesk|teamviewer|rustdesk|screenconnect|connectwise|splashtop|ammyy|ultravnc|tightvnc|realvnc|winvnc|tvnserver|" +
        @"remotepc|logmein|gotoassist|atera|ninjarmm|meshagent|chrome\s?remote\s?desktop|remoting_host|supremo|dwservice|radmin|netsupport|aeroadmin)",
        RegexOptions.IgnoreCase);

    static readonly Regex UserWritable = new(@"\\(AppData|Temp|ProgramData|Users\\Public|Downloads)\\", RegexOptions.IgnoreCase);

    // Interception products that add their own root certificate.
    static readonly Regex Mitm = new(@"(fiddler|charles|mitmproxy|burp|portswigger|proxyman|httptoolkit|superfish|eDellRoot|komodia)", RegexOptions.IgnoreCase);

    static readonly string[] RiskyPerms = { "<all_urls>", "webRequest", "webRequestBlocking", "debugger", "nativeMessaging", "proxy", "cookies", "history", "clipboardRead", "desktopCapture", "tabCapture", "management" };

    static readonly Regex UserDir = new(@"([a-z]:\\users\\)[^\\]+", RegexOptions.IgnoreCase);

    public static Finding Classify(Autostart a)
    {
        // the user's own folder name is left out: "C:\Users\runneradmin\" must not look like Radmin
        var text = UserDir.Replace($"{a.Name} {a.Command} {a.Signer}", @"$1");
        if (Monitoring.IsMatch(text))
            return new Finding(Kind.Monitoring, Severity.High, a.Name, "Monitoring or keylogger software: it can record your screen, keys and messages. Remove it unless you installed it on purpose.", a.Path);
        if (Remote.IsMatch(text))
            return new Finding(Kind.RemoteAccess, a.Signed == false || UserWritable.IsMatch(a.Path ?? "") ? Severity.High : Severity.Medium, a.Name,
                "Remote control software that starts with Windows. Someone with its ID and password can see and control this PC.", a.Path);
        if (a.Signed == false && a.Path != null)
            return new Finding(a.Kind, UserWritable.IsMatch(a.Path) ? Severity.High : Severity.Medium, a.Name,
                $"Starts by itself ({a.Source}) and has no valid digital signature" + (UserWritable.IsMatch(a.Path) ? ", from a folder any program can write to." : "."), a.Path);
        return new Finding(a.Kind, Severity.Info, a.Name, $"Starts by itself ({a.Source})" + (a.Signer != null ? $", signed by {a.Signer}." : "."), a.Path);
    }

    public static Finding Classify(BrowserExtension e)
    {
        var risky = e.Permissions.Where(p => RiskyPerms.Contains(p) || p.Contains("://*/") || p == "*://*/*").Distinct().ToList();
        var broad = e.Permissions.Any(p => p is "<all_urls>" or "*://*/*" or "http://*/*" or "https://*/*");
        var sev = broad && risky.Count >= 2 ? (e.FromStore ? Severity.Medium : Severity.High) : e.FromStore ? Severity.Info : Severity.Medium;
        var detail = (broad ? "Can read and change every website you open" : "Limited access") +
                     (risky.Count > 0 ? $" ({string.Join(", ", risky.Take(5))})" : "") +
                     (e.FromStore ? "." : ". Not installed from the browser's store.");
        return new Finding(Kind.Extension, sev, $"{e.Name} ({e.Browser})", detail);
    }

    public static Finding? Proxy(bool enabled, string? server, string? pacUrl)
    {
        if (!string.IsNullOrWhiteSpace(pacUrl))
            return new Finding(Kind.Proxy, Severity.Medium, "Proxy script", $"Web traffic is routed by a script from {pacUrl}. If you didn't set this up, someone may be reading your traffic.");
        if (enabled && !string.IsNullOrWhiteSpace(server))
            return new Finding(Kind.Proxy, Severity.Medium, "Proxy server", $"Web traffic goes through {server}. If you didn't set this up, someone may be reading your traffic.");
        return null;
    }

    public static Finding RootCert(string subject, string issuer, bool userStore)
    {
        if (Mitm.IsMatch(subject + issuer))
            return new Finding(Kind.RootCertificate, Severity.High, subject, "A root certificate from a traffic interception tool: it can read HTTPS traffic.");
        return new Finding(Kind.RootCertificate, userStore ? Severity.Medium : Severity.Info, subject,
            userStore ? "A root certificate you (or a program) added: it can be used to read HTTPS traffic." : "A root certificate trusted by the whole PC.");
    }

    public static Finding? RemoteDesktop(bool enabled) => enabled
        ? new Finding(Kind.RemoteDesktop, Severity.Medium, "Remote Desktop is on", "Anyone with an account password on this PC can log in from the network. Turn it off in Settings → System → Remote Desktop if you don't use it.")
        : null;

    public static Finding? Antivirus(bool? realtimeOn) => realtimeOn == false
        ? new Finding(Kind.Antivirus, Severity.High, "Real-time protection is off", "Microsoft Defender is not scanning programs as they start. Turn it on in Windows Security, unless another antivirus is installed.")
        : null;
}
