using System.Windows;
using System.Windows.Controls;
using System.Windows.Interop;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using System.Windows.Threading;
using System.Runtime.InteropServices;

namespace XPhone.UI;

internal sealed class GifBackdrop : Image, IDisposable
{
    private readonly System.Drawing.Bitmap _bitmap;
    private readonly DispatcherTimer _timer = new();
    private readonly int _frames;
    private readonly byte[] _delays;
    private int _frame;

    internal GifBackdrop(string path)
    {
        _bitmap = new System.Drawing.Bitmap(path);
        try
        {
            Stretch = Stretch.UniformToFill;
            _frames = _bitmap.GetFrameCount(System.Drawing.Imaging.FrameDimension.Time);
            _delays = _bitmap.PropertyIdList.Contains(0x5100) ? _bitmap.GetPropertyItem(0x5100)?.Value ?? [] : [];
            _timer.Tick += (_, _) => DrawFrame();
            DrawFrame();
        }
        catch { Dispose(); throw; }
    }

    private void DrawFrame()
    {
        _bitmap.SelectActiveFrame(System.Drawing.Imaging.FrameDimension.Time, _frame);
        var handle = _bitmap.GetHbitmap();
        try { Source = Imaging.CreateBitmapSourceFromHBitmap(handle, IntPtr.Zero, Int32Rect.Empty, BitmapSizeOptions.FromEmptyOptions()); }
        finally { DeleteObject(handle); }
        if (_frames <= 1) return;
        int offset = _frame * 4;
        int delay = offset + 4 <= _delays.Length ? BitConverter.ToInt32(_delays, offset) : 10;
        _timer.Interval = TimeSpan.FromMilliseconds(Math.Clamp(delay, 2, 6000) * 10);
        _frame = (_frame + 1) % _frames;
        _timer.Start();
    }

    public void Dispose()
    {
        _timer.Stop();
        _bitmap.Dispose();
    }

    [DllImport("gdi32.dll")] private static extern bool DeleteObject(IntPtr handle);
}
