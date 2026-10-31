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
    [DllImport("dwmapi.dll")] private static extern int DwmSetWindowAttribute(IntPtr hwnd, int attr, ref int value, int size);

    public const uint WM_CLOSE = 0x0010;

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
