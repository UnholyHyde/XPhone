using System.Runtime.InteropServices;

namespace XPhone.Audio;

/// <summary>
/// Громкость звука конкретной программы (scrcpy) по данным Windows — чтобы понять, идёт ли звук с телефона.
/// Ничего не меняет, только читает пиковый уровень.
/// </summary>
public static class AudioMeter
{
    /// <summary>Пиковый уровень 0…1 звука процесса или null, если у процесса нет звука в Windows.</summary>
    public static float? PeakForProcess(int pid)
    {
        IMMDeviceEnumerator? en = null;
        try
        {
            en = (IMMDeviceEnumerator)new MMDeviceEnumeratorCo();
            if (en.EnumAudioEndpoints(0 /*eRender*/, 1 /*ACTIVE*/, out var col) != 0 || col == null) return null;
            col.GetCount(out var count);
            float? best = null;
            for (int i = 0; i < count; i++)
            {
                if (col.Item(i, out var dev) != 0 || dev == null) continue;
                var iid = typeof(IAudioSessionManager2).GUID;
                if (dev.Activate(ref iid, 23 /*CLSCTX_ALL*/, IntPtr.Zero, out var o) != 0) continue;
                var mgr = (IAudioSessionManager2)o;
                if (mgr.GetSessionEnumerator(out var sessions) != 0 || sessions == null) continue;
                sessions.GetCount(out var n);
                for (int k = 0; k < n; k++)
                {
                    if (sessions.GetSession(k, out var ctl) != 0 || ctl == null) continue;
                    if (ctl.GetProcessId(out var spid) != 0 || spid != pid) continue;
                    if (ctl is IAudioMeterInformation meter && meter.GetPeakValue(out var peak) == 0)
                        best = Math.Max(best ?? 0, peak);
                }
            }
            return best;
        }
        catch { return null; }
    }

    // ── COM ─────────────────────────────────────────────────────────────

    [ComImport, Guid("BCDE0395-E52F-467C-8E3D-C4579291692E")]
    private class MMDeviceEnumeratorCo { }

    [ComImport, Guid("A95664D2-9614-4F35-A746-DE8DB63617E6"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    private interface IMMDeviceEnumerator
    {
        [PreserveSig] int EnumAudioEndpoints(int dataFlow, int stateMask, out IMMDeviceCollection devices);
    }

    [ComImport, Guid("0BD7A1BE-7A1A-44DB-8397-CC5392387B5E"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    private interface IMMDeviceCollection
    {
        [PreserveSig] int GetCount(out int count);
        [PreserveSig] int Item(int index, out IMMDevice device);
    }

    [ComImport, Guid("D666063F-1587-4E43-81F1-B948E807363F"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    private interface IMMDevice
    {
        [PreserveSig] int Activate(ref Guid iid, int clsCtx, IntPtr activationParams, [MarshalAs(UnmanagedType.IUnknown)] out object obj);
    }

    [ComImport, Guid("77AA99A0-1BD6-484F-8BC7-2C654C9A9B6F"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    private interface IAudioSessionManager2
    {
        [PreserveSig] int GetAudioSessionControl(IntPtr guid, int flags, out IntPtr ctl);
        [PreserveSig] int GetSimpleAudioVolume(IntPtr guid, int flags, out IntPtr vol);
        [PreserveSig] int GetSessionEnumerator(out IAudioSessionEnumerator e);
    }

    [ComImport, Guid("E2F5BB11-0570-40CA-ACDD-3AA01277DEE8"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    private interface IAudioSessionEnumerator
    {
        [PreserveSig] int GetCount(out int count);
        [PreserveSig] int GetSession(int index, out IAudioSessionControl2 session);
    }

    [ComImport, Guid("BFB7FF88-7239-4FC9-8FA2-07C950BE9C6D"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    private interface IAudioSessionControl2
    {
        // IAudioSessionControl
        [PreserveSig] int GetState(out int state);
        [PreserveSig] int GetDisplayName(out IntPtr name);
        [PreserveSig] int SetDisplayName(IntPtr name, IntPtr ctx);
        [PreserveSig] int GetIconPath(out IntPtr path);
        [PreserveSig] int SetIconPath(IntPtr path, IntPtr ctx);
        [PreserveSig] int GetGroupingParam(out Guid g);
        [PreserveSig] int SetGroupingParam(IntPtr g, IntPtr ctx);
        [PreserveSig] int RegisterAudioSessionNotification(IntPtr n);
        [PreserveSig] int UnregisterAudioSessionNotification(IntPtr n);
        // IAudioSessionControl2
        [PreserveSig] int GetSessionIdentifier(out IntPtr id);
        [PreserveSig] int GetSessionInstanceIdentifier(out IntPtr id);
        [PreserveSig] int GetProcessId(out int pid);
    }

    [ComImport, Guid("C02216F6-8C67-4B5B-9D00-D008E73E0064"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    private interface IAudioMeterInformation
    {
        [PreserveSig] int GetPeakValue(out float peak);
    }
}
