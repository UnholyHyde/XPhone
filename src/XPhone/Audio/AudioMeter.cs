using System.Runtime.InteropServices;

namespace XPhone.Audio;

/// <summary>
/// Пиковый уровень и громкость звуковых сеансов конкретного процесса.
/// </summary>
public static class AudioMeter
{
    /// <summary>Пиковый уровень 0…1 звука процесса или null, если у процесса нет звука в Windows.</summary>
    public static float? PeakForProcess(int pid, float volume = 1, HashSet<string>? adjustedSessions = null)
    {
        IMMDeviceEnumerator? en = null;
        try
        {
            en = (IMMDeviceEnumerator)new MMDeviceEnumeratorCo();
            if (en.EnumAudioEndpoints(0 /*eRender*/, 1 /*ACTIVE*/, out var col) != 0 || col == null) return null;
            try
            {
                col.GetCount(out var count);
                float? best = null;
                for (int i = 0; i < count; i++)
                {
                    if (col.Item(i, out var dev) != 0 || dev == null) continue;
                    try
                    {
                        var peak = InspectEndpoint(dev, pid, volume, adjustedSessions);
                        if (peak.HasValue) best = Math.Max(best ?? 0, peak.Value);
                    }
                    finally { Marshal.ReleaseComObject(dev); }
                }
                return best;
            }
            finally { Marshal.ReleaseComObject(col); }
        }
        catch { return null; }
        finally { if (en != null) Marshal.ReleaseComObject(en); }
    }

    private static float? InspectEndpoint(IMMDevice device, int pid, float volume, HashSet<string>? adjustedSessions)
    {
        var iid = typeof(IAudioSessionManager2).GUID;
        if (device.Activate(ref iid, 23 /*CLSCTX_ALL*/, IntPtr.Zero, out var obj) != 0) return null;
        try
        {
            var manager = (IAudioSessionManager2)obj;
            if (manager.GetSessionEnumerator(out var sessions) != 0 || sessions == null) return null;
            try
            {
                float? best = null;
                sessions.GetCount(out var count);
                for (int i = 0; i < count; i++)
                {
                    if (sessions.GetSession(i, out var session) != 0 || session == null) continue;
                    try
                    {
                        if (session is not IAudioSessionControl2 control ||
                            control.GetProcessId(out var sessionPid) != 0 || sessionPid != pid) continue;
                        if (adjustedSessions != null && session is ISimpleAudioVolume audio &&
                            control.GetSessionInstanceIdentifier(out var identifier) == 0)
                        {
                            string? id;
                            try { id = Marshal.PtrToStringUni(identifier); }
                            finally { Marshal.FreeCoTaskMem(identifier); }
                            if (id != null && !adjustedSessions.Contains(id) &&
                                audio.SetMasterVolume(volume, IntPtr.Zero) == 0)
                                adjustedSessions.Add(id);
                        }
                        if (session is IAudioMeterInformation meter && meter.GetPeakValue(out var peak) == 0)
                            best = Math.Max(best ?? 0, peak);
                    }
                    finally { Marshal.ReleaseComObject(session); }
                }
                return best;
            }
            finally { Marshal.ReleaseComObject(sessions); }
        }
        finally { Marshal.ReleaseComObject(obj); }
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
        [PreserveSig] int GetSession(int index, [MarshalAs(UnmanagedType.IUnknown)] out object session);
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

    [ComImport, Guid("87CE5498-68D6-44E5-9215-6DA47EF883D8"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    private interface ISimpleAudioVolume
    {
        [PreserveSig] int SetMasterVolume(float level, IntPtr eventContext);
    }
}
