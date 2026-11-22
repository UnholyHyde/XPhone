using System.Runtime.InteropServices;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Interop;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using System.Windows.Threading;
using XPhone.Core;
using XPhone.Scrcpy;
using XPhone.Settings;

namespace XPhone.UI;

internal sealed class StreamWindow : Window
{
    private readonly ScrcpySession _session;
    private readonly Grid _layout = new() { ClipToBounds = true };
    private readonly VideoHost _video;
    private readonly DispatcherTimer _layoutTimer = new() { Interval = TimeSpan.FromMilliseconds(50) };
    private FrameworkElement? _backgroundMedia;

    internal bool Attached => _video.Attached;
    internal event Action? BackgroundFailed;

    internal StreamWindow(ScrcpySession session, PhoneProfile profile, Win32.RECT bounds)
    {
        _session = session;
        Title = session.Title;
        WindowStartupLocation = WindowStartupLocation.Manual;
        WindowStyle = profile.Borderless ? WindowStyle.None : WindowStyle.SingleBorderWindow;
        Topmost = profile.AlwaysOnTop;
        MinWidth = 160;
        MinHeight = 120;
        Background = new SolidColorBrush(Color.FromRgb(34, 34, 34));
        _video = new VideoHost(session.Window)
        {
            HorizontalAlignment = HorizontalAlignment.Center,
            VerticalAlignment = VerticalAlignment.Center,
            Width = 1,
            Height = 1,
        };
        _layout.Children.Add(_video);
        Content = _layout;
        SourceInitialized += (_, _) =>
        {
            var hwnd = new WindowInteropHelper(this).Handle;
            Win32.DarkTitleBar(hwnd);
            Win32.RestoreWindowRect(hwnd, bounds);
        };
        _layout.SizeChanged += (_, _) => FitVideo();
        _layoutTimer.Tick += (_, _) => FitVideo();
        Loaded += (_, _) =>
        {
            FitVideo();
            _layoutTimer.Start();
        };
        Closed += (_, _) =>
        {
            _layoutTimer.Stop();
            SetBackdrop(null);
            _video.Dispose();
        };
    }

    private void FitVideo()
    {
        double width = _layout.ActualWidth;
        double height = _layout.ActualHeight;
        double aspect = _session.VideoAspect;
        if (width <= 0 || height <= 0) return;
        if (aspect > 0)
        {
            width = Math.Min(width, height * aspect);
            height = Math.Min(height, width / aspect);
        }
        if (_video.Width != width) _video.Width = width;
        if (_video.Height != height) _video.Height = height;
        _video.Fit();
    }

    internal void SetBackdrop(string? path)
    {
        if (_backgroundMedia is MediaElement oldVideo) oldVideo.Close();
        if (_backgroundMedia is GifBackdrop oldGif) oldGif.Dispose();
        if (_backgroundMedia != null) _layout.Children.Remove(_backgroundMedia);
        _backgroundMedia = null;
        if (path == null) return;
        try
        {
            var uri = new Uri(path, UriKind.Absolute);
            if (!uri.IsFile || uri.IsUnc || !File.Exists(path)) throw new IOException();
            switch (Path.GetExtension(path).ToLowerInvariant())
            {
                case ".mp4":
                    var video = new MediaElement
                    {
                        LoadedBehavior = MediaState.Manual, UnloadedBehavior = MediaState.Close,
                        Source = uri, IsMuted = true, Stretch = Stretch.UniformToFill,
                    };
                    video.MediaEnded += (_, _) => { video.Position = TimeSpan.Zero; video.Play(); };
                    video.MediaFailed += (_, _) =>
                    {
                        if (_backgroundMedia != video) return;
                        SetBackdrop(null);
                        BackgroundFailed?.Invoke();
                    };
                    video.Loaded += (_, _) => video.Play();
                    _backgroundMedia = video;
                    break;
                case ".gif":
                    _backgroundMedia = new GifBackdrop(path);
                    break;
                default:
                    var bitmap = new BitmapImage();
                    bitmap.BeginInit();
                    bitmap.CacheOption = BitmapCacheOption.OnLoad;
                    bitmap.UriSource = uri;
                    bitmap.EndInit();
                    bitmap.Freeze();
                    _backgroundMedia = new Image { Source = bitmap, Stretch = Stretch.UniformToFill };
                    break;
            }
            _backgroundMedia.IsHitTestVisible = false;
            _layout.Children.Insert(0, _backgroundMedia);
        }
        catch (Exception)
        {
            SetBackdrop(null);
            BackgroundFailed?.Invoke();
        }
    }

    private sealed class VideoHost(IntPtr video) : HwndHost
    {
        private IntPtr _host;
        internal bool Attached { get; private set; }

        protected override HandleRef BuildWindowCore(HandleRef parent)
        {
            _host = Win32.CreateVideoHost(parent.Handle);
            if (_host == IntPtr.Zero) throw new System.ComponentModel.Win32Exception();
            Attached = Win32.AttachVideo(video, _host);
            return new HandleRef(this, _host);
        }

        protected override void DestroyWindowCore(HandleRef hwnd)
        {
            Win32.DestroyWindow(hwnd.Handle);
            _host = IntPtr.Zero;
        }

        internal void Fit()
        {
            if (Attached && _host != IntPtr.Zero) Win32.FitVideoHost(_host, video);
        }
    }
}
