using System.Net;
using System.Net.Sockets;
using System.Text.RegularExpressions;

namespace XPhone.Adb;

/// <summary>Only a literal unicast IPv4 and a port can reach adb connect.</summary>
public sealed record NetworkEndpoint(string Host, int Port)
{
    public string Address => $"{Host}:{Port}";
    public static bool TryParse(string? input, out NetworkEndpoint endpoint)
    {
        endpoint = null!;
        var parts = (input ?? "").Trim().Split(':');
        if (parts.Length is < 1 or > 2) return false;
        var octets = parts[0].Split('.');
        if (octets.Length != 4 || octets.Any(x => x.Length == 0 || !x.All(char.IsAsciiDigit) || !byte.TryParse(x, out _))) return false;
        var host = string.Join(".", octets.Select(x => byte.Parse(x).ToString(System.Globalization.CultureInfo.InvariantCulture)));
        var first = byte.Parse(octets[0]);
        if (first == 0 || first == 127 || first >= 224 || host == "255.255.255.255") return false;
        int port = 5555;
        if (parts.Length == 2 && (!int.TryParse(parts[1], out port) || !parts[1].All(char.IsAsciiDigit))) return false;
        if (port is < 1 or > 65535) return false;
        endpoint = new(host, port); return true;
    }
}

public sealed record PhoneAddress(string Interface, string Ip)
{
    public bool IsWifi => Interface.StartsWith("wlan", StringComparison.OrdinalIgnoreCase) || Interface.StartsWith("wifi", StringComparison.OrdinalIgnoreCase);
    public bool IsLan => Interface.StartsWith("eth", StringComparison.OrdinalIgnoreCase) || Interface.StartsWith("en", StringComparison.OrdinalIgnoreCase)
                         || Interface.StartsWith("usb", StringComparison.OrdinalIgnoreCase) || Interface.StartsWith("rndis", StringComparison.OrdinalIgnoreCase);
    public static IReadOnlyList<PhoneAddress> Parse(string output)
    {
        var result = new List<PhoneAddress>();
        foreach (Match m in Regex.Matches(output, @"^\s*\d+:\s+(?<iface>[^\s:@]+)(?:@[^\s:]+)?\s+.*?\binet\s+(?<ip>\d+\.\d+\.\d+\.\d+)/", RegexOptions.Multiline))
        {
            var address = new PhoneAddress(m.Groups["iface"].Value, m.Groups["ip"].Value);
            if ((address.IsWifi || address.IsLan) && NetworkEndpoint.TryParse(address.Ip, out _)) result.Add(address);
        }
        return result.Distinct().ToArray();
    }
}
