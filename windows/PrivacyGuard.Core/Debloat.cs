namespace PrivacyGuard.Core;

/// <summary>Pre-installed Store apps that can be removed for the current user. Written for this project.</summary>
public static class Debloat
{
    public enum Level { Safe, Replace, Caution }
    public sealed record Item(string Package, string Name, Level Level, string Note);

    public static readonly Item[] List =
    {
        new("Microsoft.BingNews", "News", Level.Safe, "Microsoft Start news feed."),
        new("Microsoft.BingWeather", "Weather", Level.Safe, "Weather app; the taskbar widget keeps working."),
        new("Microsoft.BingSearch", "Bing Search", Level.Safe, "Bing web search app."),
        new("Microsoft.GetHelp", "Get Help", Level.Safe, "Microsoft support chat."),
        new("Microsoft.Getstarted", "Tips", Level.Safe, "Windows tips."),
        new("Microsoft.MicrosoftSolitaireCollection", "Solitaire Collection", Level.Safe, "Card games with ads."),
        new("Microsoft.MicrosoftOfficeHub", "Microsoft 365 (Office) hub", Level.Safe, "Launcher and Office ads; installed Office keeps working."),
        new("Microsoft.People", "People", Level.Safe, "Old contacts app."),
        new("Microsoft.PowerAutomateDesktop", "Power Automate", Level.Safe, "Automation tool."),
        new("Microsoft.Todos", "Microsoft To Do", Level.Safe, "Task list."),
        new("Microsoft.WindowsFeedbackHub", "Feedback Hub", Level.Safe, "Sends feedback and diagnostics to Microsoft."),
        new("Microsoft.WindowsMaps", "Maps", Level.Safe, "Offline maps app."),
        new("Microsoft.WindowsAlarms", "Clock", Level.Safe, "Alarms, timer, focus sessions."),
        new("Microsoft.SkypeApp", "Skype", Level.Safe, "Skype was shut down in 2025."),
        new("Clipchamp.Clipchamp", "Clipchamp", Level.Safe, "Video editor."),
        new("Microsoft.549981C3F5F10", "Cortana", Level.Safe, "Discontinued assistant."),
        new("MicrosoftCorporationII.QuickAssist", "Quick Assist", Level.Safe, "Remote help tool; scammers often ask victims to open it."),
        new("Microsoft.MicrosoftStickyNotes", "Sticky Notes", Level.Replace, "Notes sync to your Microsoft account."),
        new("Microsoft.WindowsSoundRecorder", "Sound Recorder", Level.Replace, "Removes the built-in recorder."),
        new("Microsoft.ZuneVideo", "Movies & TV", Level.Replace, "Old video player; Media Player plays video too."),
        new("Microsoft.OutlookForWindows", "Outlook (new)", Level.Replace, "Mail app that syncs your accounts through Microsoft's cloud."),
        new("Microsoft.Windows.DevHome", "Dev Home", Level.Safe, "Developer dashboard."),
        new("MSTeams", "Microsoft Teams (personal)", Level.Replace, "Chat app pinned to the taskbar."),
        new("Microsoft.Copilot", "Copilot", Level.Replace, "AI assistant app."),
        new("Microsoft.YourPhone", "Phone Link", Level.Caution, "Links your phone's messages, calls and photos to this PC."),
        new("Microsoft.GamingApp", "Xbox", Level.Caution, "Needed for Game Pass games."),
        new("Microsoft.XboxGamingOverlay", "Xbox Game Bar", Level.Caution, "Win+G overlay and game recording."),
        new("Microsoft.ZuneMusic", "Media Player", Level.Caution, "Default music and video player."),
    };

    /// <summary>Never offered: removing these breaks the Store, updates, security or the shell.</summary>
    public static readonly string[] Never =
    {
        "Microsoft.WindowsStore", "Microsoft.DesktopAppInstaller", "Microsoft.SecHealthUI", "Microsoft.VCLibs", "Microsoft.UI.Xaml",
        "Microsoft.NET.Native", "Microsoft.WindowsAppRuntime", "Microsoft.Windows.ShellExperienceHost", "Microsoft.Windows.StartMenuExperienceHost",
        "MicrosoftWindows.Client", "Microsoft.AAD.BrokerPlugin", "Microsoft.AccountsControl", "Microsoft.WindowsTerminal",
    };

    public static bool Allowed(string package) => List.Any(i => i.Package == package) && !Never.Any(package.StartsWith);
}
