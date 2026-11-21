using System.Diagnostics;
using System.Runtime.InteropServices;
using System.Security.Principal;
using System.Text;

namespace PrivacyGuard.Platform;

/// <summary>Small Win32 helpers: elevation, process paths, the window in front, idle time, running commands.</summary>
public static class Native
{
    public static bool IsAdmin()
    {
        using var id = WindowsIdentity.GetCurrent();
        return new WindowsPrincipal(id).IsInRole(WindowsBuiltInRole.Administrator);
    }

    [DllImport("kernel32.dll", SetLastError = true)] static extern IntPtr OpenProcess(uint access, bool inherit, int pid);
    [DllImport("kernel32.dll", SetLastError = true, CharSet = CharSet.Unicode)]
    static extern bool QueryFullProcessImageName(IntPtr h, int flags, StringBuilder name, ref int size);
    [DllImport("kernel32.dll")] static extern bool CloseHandle(IntPtr h);
    const uint QueryLimited = 0x1000;

    /// <summary>Full path of a running process (works for most processes without reading their memory).</summary>
    public static string? ProcessPath(int pid)
    {
        if (pid <= 4) return null;
        var h = OpenProcess(QueryLimited, false, pid);
        if (h == IntPtr.Zero) return null;
        try
        {
            var sb = new StringBuilder(1024); int n = sb.Capacity;
            return QueryFullProcessImageName(h, 0, sb, ref n) ? sb.ToString(0, n) : null;
        }
        finally { CloseHandle(h); }
    }

    [DllImport("user32.dll")] static extern IntPtr GetForegroundWindow();
    [DllImport("user32.dll")] static extern uint GetWindowThreadProcessId(IntPtr hWnd, out int pid);

    /// <summary>Path of the program whose window is in front.</summary>
    public static string? ForegroundPath()
    {
        var w = GetForegroundWindow();
        if (w == IntPtr.Zero) return null;
        GetWindowThreadProcessId(w, out var pid);
        return ProcessPath(pid);
    }

    [StructLayout(LayoutKind.Sequential)] struct LastInput { public uint Size; public uint Time; }
    [DllImport("user32.dll")] static extern bool GetLastInputInfo(ref LastInput li);

    /// <summary>Time since the last keyboard or mouse input.</summary>
    public static TimeSpan Idle()
    {
        var li = new LastInput { Size = (uint)Marshal.SizeOf<LastInput>() };
        if (!GetLastInputInfo(ref li)) return TimeSpan.Zero;
        return TimeSpan.FromMilliseconds(unchecked((uint)Environment.TickCount - li.Time));
    }

    /// <summary>Set by the app when the session is locked (SystemEvents.SessionSwitch).</summary>
    public static volatile bool Locked;
    public static bool UserAway => Locked || Idle() > TimeSpan.FromMinutes(5);

    /// <summary>Runs a program without a window and returns its output (stdout + stderr) and exit code.</summary>
    public static (int Code, string Output) Run(string exe, string args, int timeoutMs = 60_000)
    {
        var psi = new ProcessStartInfo(exe, args)
        {
            UseShellExecute = false, CreateNoWindow = true, RedirectStandardOutput = true, RedirectStandardError = true,
            StandardOutputEncoding = Encoding.UTF8,
        };
        using var p = Process.Start(psi)!;
        var err = p.StandardError.ReadToEndAsync();
        var outp = p.StandardOutput.ReadToEndAsync();
        if (!p.WaitForExit(timeoutMs)) { try { p.Kill(true); } catch { } return (-1, "timed out"); }
        return (p.ExitCode, outp.Result + err.Result);
    }

    public static (int Code, string Output) PowerShell(string script, int timeoutMs = 120_000) =>
        Run("powershell.exe", "-NoProfile -NonInteractive -ExecutionPolicy Bypass -Command \"[Console]::OutputEncoding=[Text.Encoding]::UTF8; " +
            script.Replace("\"", "\\\"") + "\"", timeoutMs);

    public static void Open(string uri)
    {
        try { Process.Start(new ProcessStartInfo(uri) { UseShellExecute = true }); } catch { }
    }

    /// <summary>Restarts this program as administrator (UAC prompt). False when the user said no.</summary>
    public static bool RestartAsAdmin(string args = "")
    {
        try
        {
            Process.Start(new ProcessStartInfo(Environment.ProcessPath!, args) { UseShellExecute = true, Verb = "runas" });
            return true;
        }
        catch { return false; }
    }
}
