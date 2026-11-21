using System;
using System.IO;
using System.Threading;
using System.Windows;
using PrivacyGuard.Core;
using PrivacyGuard.Platform;

namespace PrivacyGuard.App;

public static class Program
{
    public static Prefs Prefs = null!;
    public static Store Store = null!;
    public static Lists Lists = null!;
    public static ActivityMonitor Monitor = null!;
    public static bool Admin;
    public static bool Quitting;

    [STAThread]
    public static int Main(string[] args)
    {
        var selftest = Array.IndexOf(args, "--selftest");
        var tray = Array.IndexOf(args, "--tray") >= 0;
        if (selftest >= 0) Prefs.Dir = Path.Combine(Path.GetTempPath(), "PrivacyGuard-selftest");

        // one copy at a time: a second start brings the first window to the front
        using var mutex = new Mutex(true, selftest >= 0 ? "PrivacyGuard.selftest" : "PrivacyGuard.single", out var first);
        using var show = new EventWaitHandle(false, EventResetMode.AutoReset, "PrivacyGuard.show", out _);
        if (!first) { show.Set(); return 0; }

        Directory.CreateDirectory(Prefs.Dir);
        Admin = Native.IsAdmin();
        Prefs = Prefs.Load();
        Store = new Store(Prefs.DbPath);
        Lists = new Lists(Prefs);
        Monitor = new ActivityMonitor(Store, Lists);

        var app = new Application { ShutdownMode = ShutdownMode.OnExplicitShutdown };
        // the window is built before app.Run(); without this, awaits in it would continue on a background thread
        SynchronizationContext.SetSynchronizationContext(new System.Windows.Threading.DispatcherSynchronizationContext(app.Dispatcher));
        app.Exit += (_, _) => Quitting = true;
        app.SessionEnding += (_, _) => Quitting = true;
        app.DispatcherUnhandledException += (_, e) => { MainWindow.Log(e.Exception); e.Handled = true; SelfTest.Errors++; };
        var win = new MainWindow();
        var trayIcon = new Tray(win);
        Microsoft.Win32.SystemEvents.SessionSwitch += (_, e) =>
            Native.Locked = e.Reason is Microsoft.Win32.SessionSwitchReason.SessionLock or Microsoft.Win32.SessionSwitchReason.ConsoleDisconnect or Microsoft.Win32.SessionSwitchReason.RemoteDisconnect;

        // the self-test shows only the example data, not the build server's own programs
        if (Prefs.Monitor && selftest < 0) Monitor.Start(enableDns: Admin);
        Monitor.SensorOn += a => app.Dispatcher.BeginInvoke(() => trayIcon.SensorOn(a));
        Monitor.Broker += (q, h) => app.Dispatcher.BeginInvoke(() => trayIcon.Broker(q, h));
        Monitor.Changed += () => app.Dispatcher.BeginInvoke(win.OnDataChanged);

        new Thread(() => { while (show.WaitOne()) app.Dispatcher.BeginInvoke(win.Bring); }) { IsBackground = true }.Start();

        int code = 0;
        if (selftest >= 0)
        {
            var outDir = args.Length > selftest + 1 ? args[selftest + 1] : Path.Combine(Path.GetTempPath(), "pg-shots");
            app.Startup += async (_, _) => { code = await SelfTest.Run(win, outDir); app.Shutdown(); };
        }
        else if (!tray) app.Startup += (_, _) => win.Bring();

        // weekly list update in the background
        if (Prefs.Lists.Count > 0 && DateTime.Now - Prefs.ListsUpdated > TimeSpan.FromDays(7) && selftest < 0)
            _ = Lists.Update();

        app.Run();
        trayIcon.Dispose();
        Monitor.Dispose();
        Store.Dispose();
        return code;
    }
}
