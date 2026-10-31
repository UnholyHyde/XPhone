using System.Globalization;
using System.Text.RegularExpressions;
using System.Windows;
using System.Windows.Documents;
using System.Windows.Media;

namespace XPhone.UI;

/// <summary>Иконки Lucide (ISC, см. licenses/lucide-LICENSE.txt) — те же, что в программе пульта.</summary>
public static class LucideIcons
{
    private static readonly Dictionary<string, string> Paths = new()
    {
        ["smartphone"] = "M7 2h10a2 2 0 0 1 2 2v16a2 2 0 0 1 -2 2h-10a2 2 0 0 1 -2 -2v-16a2 2 0 0 1 2 -2zM12 18h.01",
        ["refresh-cw"] = "M3 12a9 9 0 0 1 9-9 9.75 9.75 0 0 1 6.74 2.74L21 8M21 3v5h-5M21 12a9 9 0 0 1-9 9 9.75 9.75 0 0 1-6.74-2.74L3 16M8 16H3v5",
        ["wifi"] = "M12 20h.01M2 8.82a15 15 0 0 1 20 0M5 12.859a10 10 0 0 1 14 0M8.5 16.429a5 5 0 0 1 7 0",
        ["wifi-off"] = "M12 20h.01M8.5 16.429a5 5 0 0 1 7 0M5 12.859a10 10 0 0 1 5.17-2.69M19 12.859a10 10 0 0 0-2.007-1.523M2 8.82a15 15 0 0 1 4.177-2.643M22 8.82a15 15 0 0 0-11.288-3.764M2 2l20 20",
        ["usb"] = "M9 7a1 1 0 1 0 2 0a1 1 0 1 0 -2 0M3 20a1 1 0 1 0 2 0a1 1 0 1 0 -2 0M4.7 19.3 19 5M21 3l-3 1 2 2ZM9.26 7.68 5 12l2 5M10 14l5 2 3.5-3.5M18 12l1-1 1 1-1 1Z",
        ["cast"] = "M2 8V6a2 2 0 0 1 2-2h16a2 2 0 0 1 2 2v12a2 2 0 0 1-2 2h-6M2 12a9 9 0 0 1 8 8M2 16a5 5 0 0 1 4 4M2 20L2.01 20",
        ["square"] = "M5 3h14a2 2 0 0 1 2 2v14a2 2 0 0 1 -2 2h-14a2 2 0 0 1 -2 -2v-14a2 2 0 0 1 2 -2z",
        ["link"] = "M10 13a5 5 0 0 0 7.54.54l3-3a5 5 0 0 0-7.07-7.07l-1.72 1.71M14 11a5 5 0 0 0-7.54-.54l-3 3a5 5 0 0 0 7.07 7.07l1.71-1.71",
        ["volume-2"] = "M11 4.702a.705.705 0 0 0-1.203-.498L6.413 7.587A1.4 1.4 0 0 1 5.416 8H3a1 1 0 0 0-1 1v6a1 1 0 0 0 1 1h2.416a1.4 1.4 0 0 1 .997.413l3.383 3.384A.705.705 0 0 0 11 19.298zM16 9a5 5 0 0 1 0 6M19.364 18.364a9 9 0 0 0 0-12.728",
        ["volume-x"] = "M11 4.702a.705.705 0 0 0-1.203-.498L6.413 7.587A1.4 1.4 0 0 1 5.416 8H3a1 1 0 0 0-1 1v6a1 1 0 0 0 1 1h2.416a1.4 1.4 0 0 1 .997.413l3.383 3.384A.705.705 0 0 0 11 19.298zM22 9L16 15M16 9L22 15",
        ["info"] = "M2 12a10 10 0 1 0 20 0a10 10 0 1 0 -20 0M12 16v-4M12 8h.01",
        ["check"] = "M20 6 9 17l-5-5",
        ["x"] = "M18 6 6 18M6 6l12 12",
        ["triangle-alert"] = "M21.73 18l-8-14a2 2 0 0 0-3.48 0l-8 14A2 2 0 0 0 4 21h16a2 2 0 0 0 1.73-3M12 9v4M12 17h.01",
        ["circle-alert"] = "M2 12a10 10 0 1 0 20 0a10 10 0 1 0 -20 0M12 8L12 12M12 16L12.01 16",
        ["circle-check"] = "M2 12a10 10 0 1 0 20 0a10 10 0 1 0 -20 0M9 12l2 2 4-4",
        ["stethoscope"] = "M11 2v2M5 2v2M5 3H4a2 2 0 0 0-2 2v4a6 6 0 0 0 12 0V5a2 2 0 0 0-2-2h-1M8 15a6 6 0 0 0 12 0v-3M18 10a2 2 0 1 0 4 0a2 2 0 1 0 -4 0",
        ["shield-check"] = "M20 13c0 5-3.5 7.5-7.66 8.95a1 1 0 0 1-.67-.01C7.5 20.5 4 18 4 13V6a1 1 0 0 1 1-1c2 0 4.5-1.2 6.24-2.72a1.17 1.17 0 0 1 1.52 0C14.51 3.81 17 5 19 5a1 1 0 0 1 1 1zM9 12l2 2 4-4",
        ["chevron-down"] = "M6 9l6 6 6-6",
        ["monitor"] = "M4 3h16a2 2 0 0 1 2 2v10a2 2 0 0 1 -2 2h-16a2 2 0 0 1 -2 -2v-10a2 2 0 0 1 2 -2zM8 21L16 21M12 17L12 21",
        ["eye-off"] = "M10.733 5.076a10.744 10.744 0 0 1 11.205 6.575 1 1 0 0 1 0 .696 10.747 10.747 0 0 1-1.444 2.49M14.084 14.158a3 3 0 0 1-4.242-4.242M17.479 17.499a10.75 10.75 0 0 1-15.417-5.151 1 1 0 0 1 0-.696 10.75 10.75 0 0 1 4.446-5.143M2 2l20 20",
        ["zap"] = "M4 14a1 1 0 0 1-.78-1.63l9.9-10.2a.5.5 0 0 1 .86.46l-1.92 6.02A1 1 0 0 0 13 10h7a1 1 0 0 1 .78 1.63l-9.9 10.2a.5.5 0 0 1-.86-.46l1.92-6.02A1 1 0 0 0 11 14z",
        ["loader"] = "M12 2v4M16.2 7.8l2.9-2.9M18 12h4M16.2 16.2l2.9 2.9M12 18v4M4.9 19.1l2.9-2.9M2 12h4M4.9 4.9l2.9 2.9",
        ["search"] = "M3 11a8 8 0 1 0 16 0a8 8 0 1 0 -16 0M21 21l-4.3-4.3",
        ["scan"] = "M3 7V5a2 2 0 0 1 2-2h2M17 3h2a2 2 0 0 1 2 2v2M21 17v2a2 2 0 0 1-2 2h-2M7 21H5a2 2 0 0 1-2-2v-2",
        ["power"] = "M12 2v10M18.4 6.6a9 9 0 1 1-12.77.04",
        ["plug"] = "M12 22v-5M9 8V2M15 8V2M18 8v5a4 4 0 0 1-4 4h-4a4 4 0 0 1-4-4V8Z",
    };

    private static readonly Dictionary<string, Geometry> Cache = new();

    public static IEnumerable<string> Names => Paths.Keys;

    public static Geometry? Get(string? name)
    {
        if (name == null || !Paths.TryGetValue(name, out var d)) return null;
        if (Cache.TryGetValue(name, out var g)) return g;
        g = Geometry.Parse(Normalize(d));
        g.Freeze();
        Cache[name] = g;
        return g;
    }

    /// <summary>SVG-пути пишут числа слитно («.705.705», «0-1.2») — WPF так не понимает, расставляем пробелы.</summary>
    private static string Normalize(string d)
    {
        var tokens = Regex.Matches(d, @"[MmLlHhVvCcSsQqTtAaZz]|[-+]?(?:\d*\.\d+|\d+\.?)(?:[eE][-+]?\d+)?");
        return string.Join(" ", tokens.Select(t => t.Value));
    }
}

/// <summary>Иконка Lucide: линии 1.7, скруглённые концы, цвет — как у текста.</summary>
public sealed class Icon : FrameworkElement
{
    public static readonly DependencyProperty KindProperty = DependencyProperty.Register(
        nameof(Kind), typeof(string), typeof(Icon),
        new FrameworkPropertyMetadata(null, FrameworkPropertyMetadataOptions.AffectsRender));

    public static readonly DependencyProperty SizeProperty = DependencyProperty.Register(
        nameof(Size), typeof(double), typeof(Icon),
        new FrameworkPropertyMetadata(18.0, FrameworkPropertyMetadataOptions.AffectsMeasure | FrameworkPropertyMetadataOptions.AffectsRender));

    public static readonly DependencyProperty ForegroundProperty = TextElement.ForegroundProperty.AddOwner(
        typeof(Icon), new FrameworkPropertyMetadata(Brushes.White, FrameworkPropertyMetadataOptions.Inherits | FrameworkPropertyMetadataOptions.AffectsRender));

    public static readonly DependencyProperty StrokeThicknessProperty = DependencyProperty.Register(
        nameof(StrokeThickness), typeof(double), typeof(Icon),
        new FrameworkPropertyMetadata(1.7, FrameworkPropertyMetadataOptions.AffectsRender));

    public string? Kind { get => (string?)GetValue(KindProperty); set => SetValue(KindProperty, value); }
    public double Size { get => (double)GetValue(SizeProperty); set => SetValue(SizeProperty, value); }
    public Brush Foreground { get => (Brush)GetValue(ForegroundProperty); set => SetValue(ForegroundProperty, value); }
    public double StrokeThickness { get => (double)GetValue(StrokeThicknessProperty); set => SetValue(StrokeThicknessProperty, value); }

    public Icon()
    {
        SnapsToDevicePixels = true;
        IsHitTestVisible = false;
    }

    protected override System.Windows.Size MeasureOverride(System.Windows.Size _) => new(Size, Size);

    protected override void OnRender(DrawingContext dc)
    {
        var g = LucideIcons.Get(Kind);
        if (g == null) return;
        double k = Size / 24.0;
        var pen = new Pen(Foreground, StrokeThickness)
        {
            StartLineCap = PenLineCap.Round,
            EndLineCap = PenLineCap.Round,
            LineJoin = PenLineJoin.Round,
        };
        dc.PushTransform(new ScaleTransform(k, k));
        dc.DrawGeometry(null, pen, g);
        dc.Pop();
    }
}

/// <summary>Скругление кнопки задаётся в стиле: ui:Btn.Radius.</summary>
public static class Btn
{
    public static readonly DependencyProperty RadiusProperty = DependencyProperty.RegisterAttached(
        "Radius", typeof(CornerRadius), typeof(Btn), new FrameworkPropertyMetadata(new CornerRadius(10)));
    public static CornerRadius GetRadius(DependencyObject o) => (CornerRadius)o.GetValue(RadiusProperty);
    public static void SetRadius(DependencyObject o, CornerRadius v) => o.SetValue(RadiusProperty, v);
}

/// <summary>Для привязок: значение == параметр (радиокнопки «сегменты»).</summary>
public sealed class EqualsConverter : System.Windows.Data.IValueConverter
{
    public object Convert(object value, Type t, object parameter, CultureInfo c) =>
        string.Equals(value?.ToString(), parameter?.ToString(), StringComparison.Ordinal);

    public object ConvertBack(object value, Type t, object parameter, CultureInfo c)
    {
        if (value is not true) return System.Windows.Data.Binding.DoNothing;
        if (t.IsEnum) return Enum.Parse(t, parameter.ToString()!);
        if (t == typeof(int)) return int.Parse(parameter.ToString()!, CultureInfo.InvariantCulture);
        return parameter;
    }
}

public sealed class BoolToVisibility : System.Windows.Data.IValueConverter
{
    public bool Invert { get; set; }
    public object Convert(object value, Type t, object p, CultureInfo c) =>
        (value is true) ^ Invert ? Visibility.Visible : Visibility.Collapsed;
    public object ConvertBack(object value, Type t, object p, CultureInfo c) => throw new NotSupportedException();
}
