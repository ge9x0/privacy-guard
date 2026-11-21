using System;
using System.Collections.Generic;
using System.Threading.Tasks;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using System.Windows.Threading;
using PrivacyGuard.Platform;

namespace PrivacyGuard.App;

public sealed class MainWindow : Window
{
    readonly TabControl tabs = new() { BorderThickness = new Thickness(0), Background = Ui.Bg, Padding = new Thickness(0) };
    readonly TextBlock status = new() { Foreground = Ui.Text, FontSize = 13, TextWrapping = TextWrapping.Wrap, VerticalAlignment = VerticalAlignment.Center };
    readonly ProgressBar busy = new() { IsIndeterminate = true, Height = 3, Visibility = Visibility.Hidden, Foreground = Ui.Accent, Background = Ui.Bg, BorderThickness = new Thickness(0) };
    readonly Border statusBar;
    readonly ScrollViewer[] scrolls = new ScrollViewer[4];
    readonly Func<Task<UIElement>>[] builders;
    readonly StackPanel header = new() { Margin = new Thickness(20, 14, 20, 6) };
    Task pending = Task.CompletedTask;
    DateTime lastAuto;

    public Task Pending => pending;
    public int Tab => tabs.SelectedIndex;
    public StackPanel? CurrentPanel => scrolls[Math.Max(0, tabs.SelectedIndex)].Content as StackPanel;

    public MainWindow()
    {
        Title = "Privacy Guard";
        Width = 980; Height = 820; MinWidth = 520; MinHeight = 420;
        Background = Ui.Bg; Foreground = Ui.Text; FontFamily = new FontFamily("Segoe UI");
        try { Icon = BitmapFrame.Create(new Uri("pack://application:,,,/Assets/app.ico")); } catch { }
        WindowStartupLocation = WindowStartupLocation.CenterScreen;

        var tabsDef = new (string Name, Func<Task<UIElement>> Build)[]
        {
            ("Today", () => Screens.Today(this)), ("Activity", () => Screens.Activity(this)),
            ("Apps", () => Screens.AppsTab(this)), ("Blocking", () => Screens.Blocking(this)),
        };
        builders = new Func<Task<UIElement>>[tabsDef.Length];
        for (int i = 0; i < tabsDef.Length; i++)
        {
            builders[i] = tabsDef[i].Build;
            scrolls[i] = new ScrollViewer { VerticalScrollBarVisibility = ScrollBarVisibility.Auto, Padding = new Thickness(20, 8, 20, 20), Background = Ui.Bg };
            tabs.Items.Add(new TabItem { Header = new TextBlock { Text = tabsDef[i].Name, FontSize = 14, Margin = new Thickness(8, 2, 8, 2) }, Content = scrolls[i] });
        }
        tabs.SelectionChanged += (s, e) => { if (e.Source == tabs) Refresh(); };

        statusBar = new Border { Background = Ui.CardBg, BorderBrush = Ui.Line, BorderThickness = new Thickness(0, 1, 0, 0), Padding = new Thickness(20, 8, 20, 8), Child = status, Visibility = Visibility.Collapsed };

        var root = new DockPanel();
        DockPanel.SetDock(header, Dock.Top); root.Children.Add(header);
        DockPanel.SetDock(busy, Dock.Top); root.Children.Add(busy);
        DockPanel.SetDock(statusBar, Dock.Bottom); root.Children.Add(statusBar);
        root.Children.Add(tabs);
        Content = root;
        BuildHeader();
        tabs.SelectedIndex = 0;
    }

    void BuildHeader()
    {
        header.Children.Clear();
        header.Children.Add(new TextBlock { Text = "Privacy Guard", FontSize = 24, FontWeight = FontWeights.Bold, Foreground = Ui.Text });
        header.Children.Add(new TextBlock { Text = DateTime.Now.ToString("dddd, d MMMM"), Foreground = Ui.Muted, FontSize = 13 });
        var chips = new WrapPanel { Margin = new Thickness(0, 6, 0, 0) };
        chips.Children.Add(Program.Admin ? Ui.Chip("Full access (administrator)", Ui.Green, Ui.GreenBg) : Ui.Chip("Limited", Ui.Orange, Ui.OrangeBg));
        chips.Children.Add(Program.Prefs.Monitor ? Ui.Chip("Monitor on", Ui.Green, Ui.GreenBg) : Ui.Chip("Monitor off"));
        chips.Children.Add(Program.Monitor.DnsOn ? Ui.Chip("DNS log on", Ui.Green, Ui.GreenBg) : Ui.Chip("DNS log off"));
        header.Children.Add(chips);
        if (!Program.Admin)
            header.Children.Add(Ui.Banner("Limited: Privacy Guard sees microphone, camera and location use, but not which servers programs contact, and it can't block anything. Full access needs administrator rights.",
                Ui.Orange, Ui.OrangeBg, Ui.Btn("Restart with full access", () => { if (Native.RestartAsAdmin()) Application.Current.Shutdown(); else Say("Windows did not give administrator rights."); }, primary: true)));
    }

    /// <summary>Rebuilds the open tab (its data is loaded in the background).</summary>
    public Task Refresh()
    {
        int i = Math.Max(0, tabs.SelectedIndex);
        var t = Build(i);
        pending = t;
        return t;
    }

    async Task Build(int i)
    {
        try
        {
            var offset = scrolls[i].VerticalOffset;
            var content = await builders[i]();
            scrolls[i].Content = content;
            await Dispatcher.InvokeAsync(() => scrolls[i].ScrollToVerticalOffset(offset), DispatcherPriority.Background);
        }
        catch (Exception e) { scrolls[i].Content = Ui.Banner("This tab could not load: " + e.Message, Ui.Red, Ui.RedBg); Log(e); }
    }

    /// <summary>New records arrived: refresh Today/Activity if the window is open (at most every 3 s).</summary>
    public void OnDataChanged()
    {
        if (!IsVisible || tabs.SelectedIndex > 1 || DateTime.Now - lastAuto < TimeSpan.FromSeconds(3) || busy.Visibility == Visibility.Visible) return;
        lastAuto = DateTime.Now;
        Refresh();
    }

    public void Say(string? msg)
    {
        if (string.IsNullOrEmpty(msg)) return;
        status.Text = msg;
        statusBar.Visibility = Visibility.Visible;
        var t = new DispatcherTimer { Interval = TimeSpan.FromSeconds(8) };
        t.Tick += (_, _) => { t.Stop(); if (status.Text == msg) statusBar.Visibility = Visibility.Collapsed; };
        t.Start();
    }

    /// <summary>Runs an action in the background with a progress bar, shows its message, then refreshes the tab.</summary>
    public void Work(string label, Func<string?> job, bool needsAdmin = false)
    {
        if (needsAdmin && !Program.Admin) { Say("This needs full access: use “Restart with full access” at the top."); return; }
        pending = WorkAsync(label, job);
    }

    async Task WorkAsync(string label, Func<string?> job)
    {
        busy.Visibility = Visibility.Visible;
        status.Text = label + "…"; statusBar.Visibility = Visibility.Visible;
        string? msg;
        try { msg = await Task.Run(job); }
        catch (Exception e) { msg = "Failed: " + e.Message; Log(e); }
        busy.Visibility = Visibility.Hidden;
        BuildHeader();
        await Build(Math.Max(0, tabs.SelectedIndex));
        if (msg != null) Say(msg); else statusBar.Visibility = Visibility.Collapsed;
    }

    public void Select(int i) => tabs.SelectedIndex = i;

    public void Bring()
    {
        Show();
        if (WindowState == WindowState.Minimized) WindowState = WindowState.Normal;
        Activate();
        BuildHeader();
        Refresh();
    }

    protected override void OnClosing(System.ComponentModel.CancelEventArgs e)
    {
        // closing the window keeps the monitor running in the tray; Quit is in the tray menu
        if (!Program.Quitting) { e.Cancel = true; Hide(); }
        base.OnClosing(e);
    }

    public static void Log(Exception e)
    {
        try { System.IO.File.AppendAllText(System.IO.Path.Combine(Prefs.Dir, "errors.log"), $"{DateTime.Now:s} {e}\n"); } catch { }
    }
}
