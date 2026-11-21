namespace PrivacyGuard.Platform;

/// <summary>Starts Privacy Guard at logon with full rights, through a scheduled task (no UAC prompt at every logon).</summary>
public static class StartWithWindows
{
    const string Task = "Privacy Guard";

    public static bool On() => Native.Run("schtasks.exe", $"/query /tn \"{Task}\"", 15_000).Code == 0;

    public static bool Set(bool on, string exe)
    {
        var r = on
            ? Native.Run("schtasks.exe", $"/create /tn \"{Task}\" /tr \"\\\"{exe}\\\" --tray\" /sc onlogon /rl highest /f", 15_000)
            : Native.Run("schtasks.exe", $"/delete /tn \"{Task}\" /f", 15_000);
        return r.Code == 0;
    }
}
