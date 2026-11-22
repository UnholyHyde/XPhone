using System.Runtime.InteropServices;
using System.Text;

namespace XPhone.Core;

/// <summary>Немного WinAPI: найти окно scrcpy, запомнить и вернуть его место, тёмный заголовок.</summary>
public static class Win32
{
    [StructLayout(LayoutKind.Sequential)]
    public struct RECT { public int Left, Top, Right, Bottom; public int Width => Right - Left; public int Height => Bottom - Top; }

    private delegate bool EnumWindowsProc(IntPtr hWnd, IntPtr lParam);

    [DllImport("user32.dll")] private static extern bool EnumWindows(EnumWindowsProc cb, IntPtr lParam);
    [DllImport("user32.dll")] private static extern uint GetWindowThreadProcessId(IntPtr hWnd, out uint pid);
    [DllImport("user32.dll")] private static extern bool IsWindowVisible(IntPtr hWnd);
    [DllImport("user32.dll")] private static extern int GetWindowTextLength(IntPtr hWnd);
    [DllImport("user32.dll", CharSet = CharSet.Unicode)] private static extern int GetClassName(IntPtr hWnd, StringBuilder sb, int max);
    [DllImport("user32.dll")] public static extern bool GetWindowRect(IntPtr hWnd, out RECT rect);
    [DllImport("user32.dll")] private static extern bool SetWindowPos(IntPtr hWnd, IntPtr after, int x, int y, int cx, int cy, uint flags);
    [DllImport("user32.dll")] public static extern bool PostMessage(IntPtr hWnd, uint msg, IntPtr w, IntPtr l);
    [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr hWnd);
    [DllImport("user32.dll")] public static extern bool ShowWindow(IntPtr hWnd, int cmd);
    [DllImport("user32.dll")] public static extern bool IsIconic(IntPtr hWnd);
    [DllImport("user32.dll")] private static extern bool IsZoomed(IntPtr hWnd);
    [DllImport("user32.dll")] private static extern IntPtr MonitorFromWindow(IntPtr hWnd, uint flags);
    [DllImport("user32.dll")] private static extern bool GetMonitorInfoW(IntPtr monitor, ref MONITORINFO info);
    [DllImport("dwmapi.dll")] private static extern int DwmSetWindowAttribute(IntPtr hwnd, int attr, ref int value, int size);

    public const uint WM_CLOSE = 0x0010;

    [StructLayout(LayoutKind.Sequential)]
    private struct MONITORINFO { public uint Size; public RECT Monitor, Work; public uint Flags; }

    internal static RECT? GetNormalWindowRect(IntPtr hwnd)
    {
        if (IsIconic(hwnd) || IsZoomed(hwnd) || !GetWindowRect(hwnd, out var rect)) return null;
        var info = new MONITORINFO { Size = (uint)Marshal.SizeOf<MONITORINFO>() };
        if (GetMonitorInfoW(MonitorFromWindow(hwnd, 2), ref info) && rect.Equals(info.Monitor))
            return null; // Полноэкранный режим SDL.
        return rect;
    }

    internal static bool RestoreWindowRect(IntPtr hwnd, RECT rect)
    {
        const uint SWP_NOZORDER = 0x0004, SWP_NOACTIVATE = 0x0010, SWP_ASYNCWINDOWPOS = 0x4000;
        return SetWindowPos(hwnd, IntPtr.Zero, rect.Left, rect.Top, rect.Width, rect.Height,
            SWP_NOZORDER | SWP_NOACTIVATE | SWP_ASYNCWINDOWPOS);
    }

    /// <summary>Видимое окно процесса (окно scrcpy).</summary>
    public static IntPtr FindMainWindow(int pid)
    {
        IntPtr found = IntPtr.Zero;
        EnumWindows((h, _) =>
        {
            GetWindowThreadProcessId(h, out var wpid);
            if (wpid != pid || !IsWindowVisible(h)) return true;
            var cls = new StringBuilder(64);
            GetClassName(h, cls, cls.Capacity);
            if (cls.ToString() == "ConsoleWindowClass") return true;
            if (GetWindowTextLength(h) == 0) return true;
            found = h;
            return false;
        }, IntPtr.Zero);
        return found;
    }

    [StructLayout(LayoutKind.Sequential)] private struct POINT { public int X, Y; }
    [DllImport("user32.dll")] private static extern bool GetClientRect(IntPtr hWnd, out RECT rect);
    [DllImport("user32.dll")] private static extern bool ClientToScreen(IntPtr hWnd, ref POINT p);

    [DllImport("user32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
    private static extern IntPtr CreateWindowEx(int exStyle, string cls, string title, int style,
        int x, int y, int width, int height, IntPtr parent, IntPtr menu, IntPtr instance, IntPtr args);
    [DllImport("user32.dll")] internal static extern bool DestroyWindow(IntPtr hwnd);
    [DllImport("user32.dll")] private static extern IntPtr SetParent(IntPtr hwnd, IntPtr parent);
    [DllImport("user32.dll")] private static extern IntPtr GetParent(IntPtr hwnd);
    [DllImport("user32.dll", EntryPoint = "GetWindowLongW")] private static extern int GetWindowLong(IntPtr hwnd, int index);
    [DllImport("user32.dll", EntryPoint = "SetWindowLongW")] private static extern int SetWindowLong(IntPtr hwnd, int index, int value);

    internal static IntPtr CreateVideoHost(IntPtr parent) => CreateWindowEx(0, "static", "",
        0x56000000, 0, 0, 1, 1, parent, IntPtr.Zero, IntPtr.Zero, IntPtr.Zero);

    internal static bool AttachVideo(IntPtr video, IntPtr host)
    {
        const int styleIndex = -16;
        const int child = 0x40000000;
        const int decorations = unchecked((int)0x80CF0000);
        int style = GetWindowLong(video, styleIndex);
        SetWindowLong(video, styleIndex, (style & ~decorations) | child);
        SetParent(video, host);
        if (GetParent(video) == host) return true;
        SetWindowLong(video, styleIndex, style);
        return false;
    }

    internal static void FitVideoHost(IntPtr host, IntPtr video)
    {
        if (!GetClientRect(host, out var target) || target.Width <= 0 || target.Height <= 0 ||
            !GetWindowRect(video, out var actual)) return;
        var origin = new POINT();
        if (!ClientToScreen(host, ref origin)) return;
        if (actual.Left == origin.X && actual.Top == origin.Y &&
            actual.Width == target.Width && actual.Height == target.Height) return;
        SetWindowPos(video, IntPtr.Zero, 0, 0, target.Width, target.Height, 0x4034);
    }

    /// <summary>Где на экране сама картинка окна (без рамки и заголовка), в пикселях.</summary>
    public static RECT? GetClientArea(IntPtr hWnd)
    {
        if (hWnd == IntPtr.Zero || IsIconic(hWnd) || !GetClientRect(hWnd, out var c) || c.Width < 50) return null;
        var p = new POINT();
        if (!ClientToScreen(hWnd, ref p)) return null;
        return new RECT { Left = p.X, Top = p.Y, Right = p.X + c.Width, Bottom = p.Y + c.Height };
    }

    /// <summary>Сдвинуть окно так, чтобы его картинка начиналась в точке (x, y). Размер не меняем.</summary>
    public static void MoveClientTo(IntPtr hWnd, int x, int y)
    {
        if (GetClientArea(hWnd) is not { } cur || !GetWindowRect(hWnd, out var outer)) return;
        const uint SWP_NOSIZE = 0x0001, SWP_NOZORDER = 0x0004, SWP_NOACTIVATE = 0x0010;
        SetWindowPos(hWnd, IntPtr.Zero, outer.Left + (x - cur.Left), outer.Top + (y - cur.Top), 0, 0,
            SWP_NOSIZE | SWP_NOZORDER | SWP_NOACTIVATE);
    }

    /// <summary>Тёмный заголовок окна (Windows 10/11) в тон шапке программы.</summary>
    public static void DarkTitleBar(IntPtr hwnd)
    {
        int on = 1;
        DwmSetWindowAttribute(hwnd, 20, ref on, 4);          // DWMWA_USE_IMMERSIVE_DARK_MODE
        int caption = 0x00120E0E;                              // #0E0E12 (COLORREF = 0x00BBGGRR)
        DwmSetWindowAttribute(hwnd, 35, ref caption, 4);      // DWMWA_CAPTION_COLOR (Windows 11)
        int border = 0x002B2323;                               // #23232B
        DwmSetWindowAttribute(hwnd, 34, ref border, 4);       // DWMWA_BORDER_COLOR
        int text = 0x00EBE7E6;                                 // #E6E7EB
        DwmSetWindowAttribute(hwnd, 36, ref text, 4);         // DWMWA_TEXT_COLOR
    }
}
