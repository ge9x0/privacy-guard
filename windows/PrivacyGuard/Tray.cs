using System;
using System.Drawing;
using System.Windows.Forms;
using PrivacyGuard.Core;

namespace PrivacyGuard.App;

/// <summary>The icon next to the clock: open the window, quit, and notifications.</summary>
public sealed class Tray : IDisposable
{
    readonly NotifyIcon icon;
    readonly MainWindow win;

    public Tray(MainWindow w)
    {
        win = w;
        var menu = new ContextMenuStrip();
        menu.Items.Add("Open Privacy Guard", null, (_, _) => win.Bring());
        menu.Items.Add("Quit", null, (_, _) => { Program.Quitting = true; System.Windows.Application.Current.Shutdown(); });
        icon = new NotifyIcon
        {
            Icon = LoadIcon(), Text = "Privacy Guard", Visible = true, ContextMenuStrip = menu,
        };
        icon.DoubleClick += (_, _) => win.Bring();
        icon.BalloonTipClicked += (_, _) => win.Bring();
    }

    static Icon LoadIcon()
    {
        try
        {
            var s = System.Windows.Application.GetResourceStream(new Uri("pack://application:,,,/Assets/app.ico"))?.Stream;
            if (s != null) return new Icon(s, SystemInformation.SmallIconSize);
        }
        catch { }
        return SystemIcons.Shield;
    }

    /// <summary>Microphone, camera or screen capture started. Windows shows its own indicator; we speak up when it is unusual.</summary>
    public void SensorOn(Access a)
    {
        if (!Program.Prefs.NotifySensors) return;
        var what = Apps.CapabilityName(a.Capability);
        if (a.UserAway)
            Show($"{what} turned on while you were away", $"{a.App.Name} started using the {what.ToLowerInvariant()} while the PC was idle or locked.", ToolTipIcon.Warning);
        else if (!a.InFront && !Apps.IsCallApp(a.App) && !Apps.IsSystem(a.App))
            Show($"{what} used in the background", $"{a.App.Name} started using the {what.ToLowerInvariant()} while its window was not in front.", ToolTipIcon.Warning);
    }

    public void Broker(DnsQuery q, Trackers.Hit h)
    {
        if (!Program.Prefs.NotifyBrokers) return;
        Show($"{Apps.DisplayName(new AppId(q.Exe!, false))} contacted a data broker",
            $"{h.Company} ({q.Name}) buys and sells location or personal data.", ToolTipIcon.Warning);
    }

    public void Show(string title, string text, ToolTipIcon kind = ToolTipIcon.Info) => icon.ShowBalloonTip(10_000, title, text, kind);

    public void Dispose() { icon.Visible = false; icon.Dispose(); }
}
