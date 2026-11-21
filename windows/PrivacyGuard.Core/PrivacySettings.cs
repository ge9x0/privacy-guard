namespace PrivacyGuard.Core;

/// <summary>Windows' own data collection and ad settings, as registry values. "Private" values come from Windows' documented settings and policies.</summary>
public static class PrivacySettings
{
    public sealed record Value(bool Machine, string Path, string Name, int Private, int? Default);
    public sealed record Setting(string Id, string Title, string Description, Value[] Values)
    {
        public bool NeedsAdmin => Values.Any(v => v.Machine);
    }

    const string Cdm = @"Software\Microsoft\Windows\CurrentVersion\ContentDeliveryManager";

    public static readonly Setting[] All =
    {
        new("adid", "Advertising ID", "Apps can use a per-user ID to show you personalised ads.",
            new[] { new Value(false, @"Software\Microsoft\Windows\CurrentVersion\AdvertisingInfo", "Enabled", 0, 1) }),
        new("tailored", "Tailored experiences", "Microsoft uses your diagnostic data for personalised tips and ads.",
            new[] { new Value(false, @"Software\Microsoft\Windows\CurrentVersion\Privacy", "TailoredExperiencesWithDiagnosticDataEnabled", 0, 1) }),
        new("language", "Websites see your language list", "Websites can read your language list to pick content.",
            new[] { new Value(false, @"Control Panel\International\User Profile", "HttpAcceptLanguageOptOut", 1, 0) }),
        new("launches", "Track app launches", "Windows records which apps you start to rank Start and search.",
            new[] { new Value(false, @"Software\Microsoft\Windows\CurrentVersion\Explorer\Advanced", "Start_TrackProgs", 0, 1) }),
        new("suggestions", "Suggestions and ads in Start and Settings", "Recommended apps, suggested content and promotions.",
            new[] { new Value(false, Cdm, "SystemPaneSuggestionsEnabled", 0, 1), new Value(false, Cdm, "SubscribedContent-338388Enabled", 0, 1),
                    new Value(false, Cdm, "SubscribedContent-338393Enabled", 0, 1), new Value(false, Cdm, "SubscribedContent-353694Enabled", 0, 1),
                    new Value(false, Cdm, "SubscribedContent-353696Enabled", 0, 1) }),
        new("tips", "Tips and \"get even more out of Windows\"", "Notifications that suggest Microsoft products.",
            new[] { new Value(false, Cdm, "SubscribedContent-338389Enabled", 0, 1), new Value(false, Cdm, "SoftLandingEnabled", 0, 1),
                    new Value(false, @"Software\Microsoft\Windows\CurrentVersion\UserProfileEngagement", "ScoobeSystemSettingEnabled", 0, 1) }),
        new("lockscreen", "Fun facts and ads on the lock screen", "Spotlight text and promotions on the lock screen.",
            new[] { new Value(false, Cdm, "RotatingLockScreenOverlayEnabled", 0, 1), new Value(false, Cdm, "SubscribedContent-338387Enabled", 0, 1) }),
        new("bing", "Web results in Start search", "What you type in Start is also sent to Bing.",
            new[] { new Value(false, @"Software\Policies\Microsoft\Windows\Explorer", "DisableSearchBoxSuggestions", 1, null) }),
        new("typing", "Inking and typing personalisation", "Windows collects what you type and write to improve suggestions.",
            new[] { new Value(false, @"Software\Microsoft\InputPersonalization", "RestrictImplicitInkCollection", 1, 0),
                    new Value(false, @"Software\Microsoft\InputPersonalization", "RestrictImplicitTextCollection", 1, 0) }),
        new("recall", "Recall snapshots", "On Copilot+ PCs, Recall saves screenshots of everything you do.",
            new[] { new Value(false, @"Software\Policies\Microsoft\Windows\WindowsAI", "DisableAIDataAnalysis", 1, null) }),
        new("activity", "Activity history", "Windows records the apps, files and sites you use.",
            new[] { new Value(true, @"SOFTWARE\Policies\Microsoft\Windows\System", "PublishUserActivities", 0, null),
                    new Value(true, @"SOFTWARE\Policies\Microsoft\Windows\System", "UploadUserActivities", 0, null) }),
        new("telemetry", "Diagnostic data: required only", "Sends only the required diagnostic data to Microsoft, not the optional data.",
            new[] { new Value(true, @"SOFTWARE\Policies\Microsoft\Windows\DataCollection", "AllowTelemetry", 1, null) }),
    };

    /// <summary>Private when every value has its private setting. `read` returns null for a missing value.</summary>
    public static bool IsPrivate(Setting s, Func<Value, int?> read) => s.Values.All(v => read(v) == v.Private);
}
