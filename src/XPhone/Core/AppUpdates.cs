using System.Diagnostics;
using System.Net.Http;
using System.Security.Cryptography;
using System.Text.Json;
using System.Text.RegularExpressions;
using Microsoft.Win32;

namespace XPhone.Core;

internal static class AppUpdates
{
    private const string Repository = "https://github.com/UnholyHyde/XPhone/";
    private const string UninstallKey = @"Software\Microsoft\Windows\CurrentVersion\Uninstall\{8E878E75-59C3-45C0-8FF1-A6A8A422559B}_is1";
    private static readonly HttpClient Http = new()
    {
        Timeout = TimeSpan.FromSeconds(20),
        DefaultRequestHeaders = { { "User-Agent", "XPhone-Windows" } }
    };
    private static readonly string StateDirectory = Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "XPhone", "Updates");
    private static string NotesPath => Path.Combine(StateDirectory, "pending.json");
    internal static Version Installed { get; } = typeof(AppUpdates).Assembly.GetName().Version!;

    internal sealed record Update(Version Version, Uri Url, string Digest, long Size, string Notes);
    private sealed record Pending(Version Version, string Notes);

    internal static async Task<Update?> CheckAsync()
    {
        using var response = await Http.GetAsync("https://api.github.com/repos/UnholyHyde/XPhone/releases?per_page=30");
        response.EnsureSuccessStatusCode();
        using var releases = JsonDocument.Parse(await response.Content.ReadAsStringAsync());
        Update? newest = null;
        foreach (var release in releases.RootElement.EnumerateArray())
        {
            if (release.GetProperty("draft").GetBoolean() || release.GetProperty("prerelease").GetBoolean())
                continue;
            foreach (var asset in release.GetProperty("assets").EnumerateArray())
            {
                var match = Regex.Match(asset.GetProperty("name").GetString() ?? "",
                    @"^XPhone-Setup-([0-9]+\.[0-9]+\.[0-9]+)-x64\.exe$");
                if (!match.Success || !Version.TryParse(match.Groups[1].Value + ".0", out var version)
                    || version <= (newest?.Version ?? Installed))
                    continue;
                string? digest = asset.TryGetProperty("digest", out var field) ? field.GetString() : null;
                string? url = asset.GetProperty("browser_download_url").GetString();
                long size = asset.GetProperty("size").GetInt64();
                if (digest is null || !Regex.IsMatch(digest, @"^sha256:[0-9a-fA-F]{64}$")
                    || url is null || !url.StartsWith(Repository + "releases/download/", StringComparison.Ordinal)
                    || !url.EndsWith("/" + match.Value, StringComparison.Ordinal)
                    || size <= 0 || size > 512L * 1024 * 1024)
                    continue;
                newest = new Update(version, new Uri(url), digest[7..], size,
                    release.GetProperty("body").GetString()?.Trim() ?? "");
            }
        }
        return newest;
    }

    internal static string? InstallScope()
    {
        using var machine = RegistryKey.OpenBaseKey(RegistryHive.LocalMachine, RegistryView.Registry64);
        foreach (var (root, scope) in new[] { (Registry.CurrentUser, "/CURRENTUSER"), (machine, "/ALLUSERS") })
        {
            using var key = root.OpenSubKey(UninstallKey);
            if (key?.GetValue("InstallLocation") is string location &&
                string.Equals(Path.TrimEndingDirectorySeparator(location),
                    Path.TrimEndingDirectorySeparator(AppContext.BaseDirectory), StringComparison.OrdinalIgnoreCase))
                return scope;
        }
        return null;
    }

    internal static async Task InstallAsync(Update update, string scope)
    {
        Directory.CreateDirectory(StateDirectory);
        string path = Path.Combine(StateDirectory, $"setup-{Guid.NewGuid():N}.exe");
        try
        {
            using var timeout = new CancellationTokenSource(TimeSpan.FromMinutes(10));
            using var response = await Http.GetAsync(update.Url, HttpCompletionOption.ResponseHeadersRead, timeout.Token);
            response.EnsureSuccessStatusCode();
            using var hash = IncrementalHash.CreateHash(HashAlgorithmName.SHA256);
            await using (var source = await response.Content.ReadAsStreamAsync(timeout.Token))
            await using (var file = new FileStream(path, FileMode.CreateNew, FileAccess.Write, FileShare.None, 65536, true))
            {
                var buffer = new byte[65536];
                long received = 0;
                int count;
                while ((count = await source.ReadAsync(buffer, timeout.Token)) != 0)
                {
                    received += count;
                    if (received > update.Size) throw new InvalidDataException();
                    hash.AppendData(buffer, 0, count);
                    await file.WriteAsync(buffer.AsMemory(0, count), timeout.Token);
                }
                if (received != update.Size || !Convert.ToHexString(hash.GetHashAndReset())
                        .Equals(update.Digest, StringComparison.OrdinalIgnoreCase))
                    throw new InvalidDataException();
            }
            File.WriteAllText(NotesPath + ".tmp", JsonSerializer.Serialize(new Pending(update.Version, update.Notes)));
            File.Move(NotesPath + ".tmp", NotesPath, overwrite: true);

            var start = new ProcessStartInfo(path) { UseShellExecute = true };
            foreach (var arg in new[] { scope, "/SILENT", "/NORESTART", "/XPHONEUPDATE=1",
                         "/DIR=" + Path.TrimEndingDirectorySeparator(AppContext.BaseDirectory) })
                start.ArgumentList.Add(arg);
            using var installer = Process.Start(start) ?? throw new InvalidOperationException();
        }
        catch
        {
            TryDelete(path);
            TryDelete(NotesPath);
            throw;
        }
    }

    internal static string? TakeInstalledNotes()
    {
        try
        {
            if (!File.Exists(NotesPath)) return null;
            var pending = JsonSerializer.Deserialize<Pending>(File.ReadAllText(NotesPath));
            if (pending is null || pending.Version > Installed) return null;
            File.Delete(NotesPath);
            return pending.Version == Installed ? pending.Notes : null;
        }
        catch (IOException) { return null; }
        catch (UnauthorizedAccessException) { return null; }
        catch (JsonException) { return null; }
    }

    internal static void CleanInstallers()
    {
        try
        {
            if (!Directory.Exists(StateDirectory)) return;
            foreach (var path in Directory.EnumerateFiles(StateDirectory, "setup-*.exe"))
                if (File.GetLastWriteTimeUtc(path) < DateTime.UtcNow.AddDays(-1)) TryDelete(path);
        }
        catch (IOException) { }
        catch (UnauthorizedAccessException) { }
    }

    private static void TryDelete(string path)
    {
        try { File.Delete(path); }
        catch (IOException) { }
        catch (UnauthorizedAccessException) { }
    }
}
