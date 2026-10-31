using System.Diagnostics;
using System.Text;

namespace XPhone.Core;

public sealed record ProcessResult(int ExitCode, string Output, bool TimedOut)
{
    public bool Ok => !TimedOut && ExitCode == 0;
}

/// <summary>Запуск консольных программ (adb) без окна, с таймаутом и сбором вывода.</summary>
public static class ProcessRunner
{
    public static async Task<ProcessResult> RunAsync(string exe, IEnumerable<string> args, int timeoutMs,
        IDictionary<string, string>? env = null, CancellationToken ct = default)
    {
        var psi = new ProcessStartInfo(exe)
        {
            UseShellExecute = false,
            CreateNoWindow = true,
            RedirectStandardOutput = true,
            RedirectStandardError = true,
            StandardOutputEncoding = Encoding.UTF8,
            StandardErrorEncoding = Encoding.UTF8,
            WorkingDirectory = Path.GetDirectoryName(exe) ?? "",
        };
        foreach (var a in args) psi.ArgumentList.Add(a);
        if (env != null) foreach (var kv in env) psi.Environment[kv.Key] = kv.Value;

        var output = new StringBuilder();
        using var p = new Process { StartInfo = psi, EnableRaisingEvents = true };
        var exited = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        p.Exited += (_, _) => exited.TrySetResult();
        DataReceivedEventHandler onData = (_, e) => { if (e.Data != null) lock (output) output.AppendLine(e.Data); };
        p.OutputDataReceived += onData;
        p.ErrorDataReceived += onData;

        try { p.Start(); }
        catch (Exception ex) { return new ProcessResult(-1, ex.Message, false); }
        p.BeginOutputReadLine();
        p.BeginErrorReadLine();

        using var cts = CancellationTokenSource.CreateLinkedTokenSource(ct);
        cts.CancelAfter(timeoutMs);
        bool timedOut = false;
        try { await exited.Task.WaitAsync(cts.Token); }
        catch (OperationCanceledException)
        {
            timedOut = true;
            try { p.Kill(true); } catch { }
        }
        // Важно: не ждём конца вывода бесконечно — adb может оставить трубу открытой (сервер-демон).
        await Task.Delay(60, CancellationToken.None);
        string text;
        lock (output) text = output.ToString();
        int code = -1;
        try { if (p.HasExited) code = p.ExitCode; } catch { }
        return new ProcessResult(code, text.Trim(), timedOut);
    }
}
