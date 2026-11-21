using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Threading.Tasks;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Controls.Primitives;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using System.Windows.Threading;

namespace PrivacyGuard.App;

/// <summary>
/// `PrivacyGuard.exe --selftest &lt;folder&gt;`: opens every tab, saves a picture of it, presses every button
/// (except ones that open a file dialog), and reports failures. Used by the Windows build on GitHub Actions.
/// </summary>
public static class SelfTest
{
    public static int Errors;

    public static async Task<int> Run(MainWindow w, string dir)
    {
        Directory.CreateDirectory(dir);
        var log = new List<string>();
        var deadline = DateTime.Now.AddMinutes(6);
        var logFile = Path.Combine(dir, "selftest.log");
        w.Show();
        string[] names = { "today", "activity", "apps", "blocking" };
        for (int t = 0; t < names.Length; t++)
        {
            w.Select(t);
            await Settle(w);
            Shot(w, Path.Combine(dir, $"{t + 1}-{names[t]}.png"));
            var pressed = new HashSet<string>();
            for (int round = 0; round < 40 && DateTime.Now < deadline; round++)
            {
                // buttons are rebuilt after each press, so look them up again every time
                var b = Buttons(w.CurrentPanel).FirstOrDefault(x => !pressed.Contains(Label(x)));
                if (b == null) break;
                pressed.Add(Label(b));
                int before = Errors;
                try { b.RaiseEvent(new RoutedEventArgs(ButtonBase.ClickEvent)); await Settle(w); }
                catch (Exception e) { Errors++; log.Add($"FAIL [{names[t]}] {Label(b)}: {e.Message}"); }
                log.Add($"{(Errors > before ? "FAIL" : "ok  ")} [{names[t]}] {Label(b)}");
                File.WriteAllLines(logFile, log);
            }
            if (w.Tab != t) w.Select(t);
            await Settle(w);
            Shot(w, Path.Combine(dir, $"{t + 1}-{names[t]}-after.png"));
        }
        log.Add($"errors: {Errors}");
        if (DateTime.Now >= deadline) { Errors++; log.Add("FAIL: took longer than 6 minutes"); }
        File.WriteAllLines(logFile, log);
        var errFile = Path.Combine(Platform.Prefs.Dir, "errors.log");
        if (File.Exists(errFile)) File.Copy(errFile, Path.Combine(dir, "errors.log"), true);
        return Errors == 0 ? 0 : 1;
    }

    // the text without times/counts, so a rebuilt "Scan again (last 12:01)" counts as the same button
    static string Label(Button b) => System.Text.RegularExpressions.Regex.Replace(b.Content as string ?? b.Content?.ToString() ?? "?", @"[\d:]+", "#");

    static IEnumerable<Button> Buttons(DependencyObject? root)
    {
        if (root == null) yield break;
        var q = new Queue<DependencyObject>(); q.Enqueue(root);
        while (q.Count > 0)
        {
            var o = q.Dequeue();
            if (o is Button b && b.IsEnabled && b.IsVisible && b.Tag as string != "dialog") yield return b;
            if (o is Expander e && !e.IsExpanded) { e.IsExpanded = true; }
            int n = VisualTreeHelper.GetChildrenCount(o);
            for (int i = 0; i < n; i++) q.Enqueue(VisualTreeHelper.GetChild(o, i));
        }
    }

    static async Task Settle(MainWindow w)
    {
        for (int i = 0; i < 3; i++)
        {
            try { await w.Pending.WaitAsync(TimeSpan.FromSeconds(90)); } catch (Exception e) { Errors++; MainWindow.Log(e); }
            await w.Dispatcher.InvokeAsync(() => { }, DispatcherPriority.ApplicationIdle);
            await Task.Delay(150);
        }
    }

    /// <summary>Saves the whole tab (not just the visible part) as a PNG.</summary>
    static void Shot(MainWindow w, string file)
    {
        try
        {
            var panel = w.CurrentPanel;
            if (panel == null) return;
            double width = Math.Max(600, panel.ActualWidth);
            panel.Measure(new Size(width, double.PositiveInfinity));
            var h = Math.Min(12000, panel.DesiredSize.Height + 16);
            var bmp = new RenderTargetBitmap((int)width + 16, (int)h, 96, 96, PixelFormats.Pbgra32);
            var dv = new DrawingVisual();
            using (var ctx = dv.RenderOpen())
            {
                ctx.DrawRectangle(Ui.Bg, null, new Rect(0, 0, width + 16, h));
                ctx.DrawRectangle(new VisualBrush(panel) { Stretch = Stretch.None, AlignmentX = AlignmentX.Left, AlignmentY = AlignmentY.Top, ViewboxUnits = BrushMappingMode.Absolute, Viewbox = new Rect(0, 0, width, panel.DesiredSize.Height) },
                    null, new Rect(8, 8, width, panel.DesiredSize.Height));
            }
            bmp.Render(dv);
            var enc = new PngBitmapEncoder();
            enc.Frames.Add(BitmapFrame.Create(bmp));
            using var fs = File.Create(file);
            enc.Save(fs);
        }
        catch (Exception e) { MainWindow.Log(e); }
    }
}
