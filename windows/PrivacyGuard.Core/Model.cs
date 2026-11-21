namespace PrivacyGuard.Core;

/// <summary>A program as Windows names it in the privacy records: a path for desktop programs, a package for Store apps.</summary>
public sealed record AppId(string Key, bool Packaged)
{
    /// <summary>Desktop program path, e.g. C:\Program Files\Zoom\bin\Zoom.exe (null for Store apps).</summary>
    public string? Path => Packaged ? null : Key;
    public string Name => Apps.DisplayName(this);
}

/// <summary>One use of a sensitive capability (microphone, camera, location...). Stop null = still in use.</summary>
public sealed record Access(AppId App, string Capability, DateTime Start, DateTime? Stop,
    bool InFront = true, bool UserAway = false)
{
    public TimeSpan? Duration => Stop is { } s ? s - Start : null;
}

/// <summary>A DNS lookup made by a program.</summary>
public sealed record DnsQuery(DateTime Time, int Pid, string? Exe, string Name, bool Blocked = false);

public enum Severity { Info, Medium, High }

/// <summary>Key: the program's path (desktop) or package name (Store app), for actions like blocking its internet.</summary>
public sealed record Alert(Severity Severity, string App, string Title, string Text, string? Key = null);
