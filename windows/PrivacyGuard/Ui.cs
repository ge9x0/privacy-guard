using System;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;
using Microsoft.Win32;

namespace PrivacyGuard.App;

/// <summary>Colours and small building blocks for the screens (built in code, no XAML).</summary>
public static class Ui
{
    public static readonly bool Dark = Registry.CurrentUser.OpenSubKey(@"Software\Microsoft\Windows\CurrentVersion\Themes\Personalize")?.GetValue("AppsUseLightTheme") is int v && v == 0;

    static SolidColorBrush B(string light, string dark) { var b = new SolidColorBrush((Color)ColorConverter.ConvertFromString(Dark ? dark : light)); b.Freeze(); return b; }
    public static readonly SolidColorBrush Bg = B("#F3F4F6", "#14171C"), CardBg = B("#FFFFFF", "#1D2128"), Text = B("#171A1F", "#E8EAED"),
        Muted = B("#5C6370", "#9AA3AE"), Line = B("#E2E5EA", "#2C323B"), Accent = B("#1F5FBF", "#6EA2FF"),
        Red = B("#C62828", "#FF7B72"), RedBg = B("#FDECEA", "#3A1E1E"), Orange = B("#B45309", "#FFB04D"), OrangeBg = B("#FFF4E5", "#3A2A16"),
        Green = B("#2E7D32", "#6CCB77"), GreenBg = B("#E8F5E9", "#1C3322"), ChipBg = B("#EEF1F5", "#262B33");

    public static readonly FontFamily Mono = new("Cascadia Mono, Consolas");

    public static Brush For(Core.Severity s) => s switch { Core.Severity.High => Red, Core.Severity.Medium => Orange, _ => Muted };
    public static Brush BgFor(Core.Severity s) => s switch { Core.Severity.High => RedBg, Core.Severity.Medium => OrangeBg, _ => ChipBg };

    public static StackPanel Stack(double gap = 8) => new() { Margin = new Thickness(0) };

    public static Border Card(StackPanel content) => new()
    {
        Background = CardBg, BorderBrush = Line, BorderThickness = new Thickness(1), CornerRadius = new CornerRadius(10),
        Padding = new Thickness(16, 14, 16, 14), Margin = new Thickness(0, 0, 0, 12), Child = content,
    };

    public static StackPanel Section(string title, string? hint = null)
    {
        var s = new StackPanel();
        s.Children.Add(new TextBlock { Text = title, FontSize = 17, FontWeight = FontWeights.SemiBold, Foreground = Text, Margin = new Thickness(0, 0, 0, 4) });
        if (hint != null) s.Children.Add(Hint(hint));
        return s;
    }

    public static TextBlock T(string text, double size = 13.5, FontWeight? w = null, Brush? fg = null) => new()
    {
        Text = text, FontSize = size, FontWeight = w ?? FontWeights.Normal, Foreground = fg ?? Text, TextWrapping = TextWrapping.Wrap,
    };

    public static TextBlock Hint(string text) => new()
    {
        Text = text, FontSize = 12.5, Foreground = Muted, TextWrapping = TextWrapping.Wrap, Margin = new Thickness(0, 0, 0, 6),
    };

    public static TextBlock MonoT(string text) => new() { Text = text, FontFamily = Mono, FontSize = 12.5, Foreground = Text, TextWrapping = TextWrapping.Wrap };

    public static Button Btn(string text, Action click, bool primary = false, bool enabled = true, string? tip = null)
    {
        var b = new Button
        {
            Content = text, Padding = new Thickness(12, 5, 12, 5), Margin = new Thickness(0, 4, 8, 4), MinHeight = 30,
            Background = primary ? Accent : CardBg, Foreground = primary ? Brushes.White : Text, BorderBrush = primary ? Accent : Line,
            HorizontalAlignment = HorizontalAlignment.Left, IsEnabled = enabled, ToolTip = tip, Cursor = System.Windows.Input.Cursors.Hand,
        };
        b.Click += (_, _) => click();
        return b;
    }

    public static WrapPanel Row(params UIElement[] items)
    {
        var w = new WrapPanel { Orientation = Orientation.Horizontal, VerticalAlignment = VerticalAlignment.Center };
        foreach (var i in items) w.Children.Add(i);
        return w;
    }

    public static Border Banner(string text, Brush fg, Brush bg, UIElement? action = null)
    {
        var s = new StackPanel();
        s.Children.Add(new TextBlock { Text = text, Foreground = fg, FontSize = 13.5, TextWrapping = TextWrapping.Wrap });
        if (action != null) s.Children.Add(action);
        return new Border { Background = bg, CornerRadius = new CornerRadius(8), Padding = new Thickness(12, 9, 12, 9), Margin = new Thickness(0, 4, 0, 6), Child = s };
    }

    public static Border Chip(string text, Brush? fg = null, Brush? bg = null) => new()
    {
        Background = bg ?? ChipBg, CornerRadius = new CornerRadius(10), Padding = new Thickness(8, 1, 8, 2), Margin = new Thickness(0, 2, 6, 2),
        Child = new TextBlock { Text = text, FontSize = 12, Foreground = fg ?? Muted },
    };

    /// <summary>A row: text on the left (title + optional detail), controls on the right.</summary>
    public static Grid Line2(string title, string? detail, params UIElement[] right)
    {
        var g = new Grid { Margin = new Thickness(0, 4, 0, 4) };
        g.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        g.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        var left = new StackPanel { VerticalAlignment = VerticalAlignment.Center };
        left.Children.Add(T(title, 13.5, FontWeights.SemiBold));
        if (!string.IsNullOrEmpty(detail)) left.Children.Add(new TextBlock { Text = detail, FontSize = 12.5, Foreground = Muted, TextWrapping = TextWrapping.Wrap });
        g.Children.Add(left);
        var r = new StackPanel { Orientation = Orientation.Horizontal, VerticalAlignment = VerticalAlignment.Center };
        foreach (var x in right) r.Children.Add(x);
        Grid.SetColumn(r, 1);
        g.Children.Add(r);
        return g;
    }

    public static Separator Sep() => new() { Margin = new Thickness(0, 6, 0, 6), Background = Line };

    public static Border Tile(string number, string label, Brush? fg = null)
    {
        var s = new StackPanel();
        s.Children.Add(new TextBlock { Text = number, FontSize = 24, FontWeight = FontWeights.Bold, Foreground = fg ?? Text });
        s.Children.Add(new TextBlock { Text = label, FontSize = 12, Foreground = Muted, TextWrapping = TextWrapping.Wrap });
        return new Border { Background = Bg, CornerRadius = new CornerRadius(8), Padding = new Thickness(12, 8, 12, 8), Margin = new Thickness(0, 0, 8, 8), Child = s };
    }

    public static CheckBox Check(string text, bool on, Action<bool> changed, bool enabled = true)
    {
        var c = new CheckBox { Content = new TextBlock { Text = text, TextWrapping = TextWrapping.Wrap, Foreground = Text }, IsChecked = on, IsEnabled = enabled, Margin = new Thickness(0, 4, 0, 4), VerticalContentAlignment = VerticalAlignment.Center };
        c.Click += (_, _) => changed(c.IsChecked == true);
        return c;
    }

    public static string Hm(DateTime t) => t.Date == DateTime.Today ? t.ToString("HH:mm") : t.ToString("ddd d MMM, HH:mm");
}
