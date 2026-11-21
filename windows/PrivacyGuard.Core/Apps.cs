using System.Text.RegularExpressions;

namespace PrivacyGuard.Core;

/// <summary>Names for programs and for the capabilities in Windows' privacy records (CapabilityAccessManager\ConsentStore).</summary>
public static class Apps
{
    static readonly Dictionary<string, string> Known = new(StringComparer.OrdinalIgnoreCase)
    {
        ["msedge"] = "Microsoft Edge", ["chrome"] = "Google Chrome", ["firefox"] = "Firefox", ["brave"] = "Brave",
        ["opera"] = "Opera", ["ms-teams"] = "Microsoft Teams", ["teams"] = "Microsoft Teams", ["zoom"] = "Zoom",
        ["discord"] = "Discord", ["slack"] = "Slack", ["skype"] = "Skype", ["obs64"] = "OBS Studio",
        ["whatsapp"] = "WhatsApp", ["telegram"] = "Telegram", ["signal"] = "Signal", ["steam"] = "Steam",
        ["spotify"] = "Spotify", ["explorer"] = "File Explorer", ["svchost"] = "Windows service host",
        ["SearchHost"] = "Windows Search", ["SearchApp"] = "Windows Search", ["SystemSettings"] = "Settings",
        ["WindowsCamera"] = "Camera", ["WindowsSoundRecorder"] = "Sound Recorder", ["WindowsMaps"] = "Maps",
        ["People"] = "People", ["windowscommunicationsapps"] = "Mail and Calendar", ["OutlookForWindows"] = "Outlook",
        ["YourPhone"] = "Phone Link", ["BingWeather"] = "Weather", ["Windows.Photos"] = "Photos",
        ["ScreenSketch"] = "Snipping Tool", ["Copilot"] = "Copilot",
    };

    /// <summary>ConsentStore writes desktop paths with '#' instead of '\'.</summary>
    public static AppId FromConsentKey(string key, bool nonPackaged) =>
        nonPackaged ? new AppId(key.Replace('#', '\\'), false) : new AppId(key, true);

    public static string DisplayName(AppId a)
    {
        string stem;
        if (a.Packaged)
        {
            // Microsoft.WindowsCamera_8wekyb3d8bbwe -> WindowsCamera ; 5319275A.WhatsAppDesktop_cv1g1gvanyjgm -> WhatsAppDesktop
            var family = a.Key.Split('_')[0];
            var parts = family.Split('.');
            stem = parts.Length > 1 ? string.Join('.', parts.Skip(1)) : family;
            if (Known.TryGetValue(stem, out var k1)) return k1;
            stem = stem.Replace("Desktop", "");
            if (Known.TryGetValue(stem, out var k3)) return k3;
        }
        else
        {
            stem = System.IO.Path.GetFileNameWithoutExtension(a.Key.Replace('/', '\\').Split('\\').Last());
            if (Known.TryGetValue(stem, out var k2)) return k2;
        }
        stem = Regex.Replace(stem, "([a-z])([A-Z])", "$1 $2").Replace('_', ' ').Replace('-', ' ').Trim();
        return stem.Length == 0 ? a.Key : char.ToUpperInvariant(stem[0]) + stem[1..];
    }

    static readonly Dictionary<string, string> Caps = new(StringComparer.OrdinalIgnoreCase)
    {
        ["microphone"] = "Microphone", ["webcam"] = "Camera", ["location"] = "Location", ["contacts"] = "Contacts",
        ["appointments"] = "Calendar", ["phoneCall"] = "Phone calls", ["phoneCallHistory"] = "Call history",
        ["chat"] = "Messages", ["email"] = "Email", ["radios"] = "Radios (Bluetooth, Wi-Fi)",
        ["bluetoothSync"] = "Bluetooth devices", ["userDataTasks"] = "Tasks", ["documentsLibrary"] = "Documents",
        ["picturesLibrary"] = "Pictures", ["videosLibrary"] = "Videos", ["broadFileSystemAccess"] = "All files",
        ["graphicsCaptureProgrammatic"] = "Screen capture", ["graphicsCaptureWithoutBorder"] = "Screen capture (no border)",
        ["userNotificationListener"] = "Notifications", ["userAccountInformation"] = "Account info",
        ["activity"] = "Activity history", ["humanPresence"] = "Presence sensing", ["gazeInput"] = "Eye tracking",
        ["cellularData"] = "Mobile data", ["wifiData"] = "Wi-Fi data", ["downloadsFolder"] = "Downloads",
        ["musicLibrary"] = "Music", ["systemAIModels"] = "Windows AI models",
    };
    public static string CapabilityName(string c) => Caps.TryGetValue(c, out var n) ? n : c;

    /// <summary>Capabilities that record you directly (alerts at night, when away, in the background).</summary>
    public static readonly HashSet<string> Sensors = new(StringComparer.OrdinalIgnoreCase)
        { "microphone", "webcam", "graphicsCaptureProgrammatic", "graphicsCaptureWithoutBorder" };

    /// <summary>Personal data: worth knowing about when a program that is not on screen reads it.</summary>
    public static readonly HashSet<string> Personal = new(StringComparer.OrdinalIgnoreCase)
        { "location", "contacts", "appointments", "phoneCallHistory", "chat", "email", "userAccountInformation", "userNotificationListener" };

    /// <summary>Programs whose job is long microphone/camera sessions (calls, recording, streaming).</summary>
    static readonly Regex Calls = new(
        @"(teams|zoom|skype|discord|slack|webex|whatsapp|telegram|signal|obs\d*|audacity|meet|facetime|viber|soundrecorder|voicerecorder|windowscamera|msedge|chrome|firefox|brave|opera)",
        RegexOptions.IgnoreCase);
    public static bool IsCallApp(AppId a) => Calls.IsMatch(a.Key);

    /// <summary>Windows' own components (they may use the sensors for system features).</summary>
    public static bool IsSystem(AppId a) =>
        a.Packaged ? a.Key.StartsWith("Microsoft.Windows", StringComparison.OrdinalIgnoreCase)
                     || a.Key.StartsWith("MicrosoftWindows.", StringComparison.OrdinalIgnoreCase)
                   : a.Key.StartsWith(@"C:\Windows\", StringComparison.OrdinalIgnoreCase);
}
