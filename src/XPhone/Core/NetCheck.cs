using System.Net;
using System.Net.NetworkInformation;
using System.Net.Sockets;
using System.Text.RegularExpressions;

namespace XPhone.Core;

/// <summary>
/// Проверки сети для Wi-Fi: чаще всего телефон «не находится» из-за VPN на компьютере —
/// он забирает себе даже трафик к домашней сети.
/// </summary>
public static class NetCheck
{
    private static readonly Regex VpnName = new(
        "vpn|amnezia|wireguard|wintun|openvpn|tap-|tap |tun|nord|proton|outline|v2ray|hiddify|xray|nekoray|clash|zerotier|tailscale|hamachi|radmin|forti|cisco|globalprotect|pulse",
        RegexOptions.IgnoreCase);

    private static bool LooksLikeVpn(NetworkInterface n) =>
        n.NetworkInterfaceType is NetworkInterfaceType.Ppp or NetworkInterfaceType.Tunnel
        || VpnName.IsMatch(n.Name) || VpnName.IsMatch(n.Description);

    /// <summary>Название включённого VPN, если он есть.</summary>
    public static string? ActiveVpnName()
    {
        try
        {
            return NetworkInterface.GetAllNetworkInterfaces()
                .Where(n => n.OperationalStatus == OperationalStatus.Up && n.NetworkInterfaceType != NetworkInterfaceType.Loopback)
                .FirstOrDefault(LooksLikeVpn)?.Name;
        }
        catch { return null; }
    }

    /// <summary>
    /// Через какое подключение компьютер пойдёт к телефону. Если через VPN — вернёт его название
    /// (значит, телефон в домашней сети для компьютера недоступен).
    /// </summary>
    public static string? VpnOnRouteTo(string? phoneIp)
    {
        if (phoneIp == null || !IPAddress.TryParse(phoneIp, out var target)) return null;
        try
        {
            using var s = new Socket(AddressFamily.InterNetwork, SocketType.Dgram, ProtocolType.Udp);
            s.Connect(target, 9); // UDP: ничего не отправляет, только выбирает маршрут
            if (s.LocalEndPoint is not IPEndPoint local) return null;
            var nic = NetworkInterface.GetAllNetworkInterfaces().FirstOrDefault(n =>
                n.GetIPProperties().UnicastAddresses.Any(a => a.Address.Equals(local.Address)));
            return nic != null && LooksLikeVpn(nic) ? nic.Name : null;
        }
        catch { return null; }
    }

    /// <summary>Через какое подключение компьютер идёт к телефону: название, скорость (Мбит/с), Wi-Fi ли это.</summary>
    public static (string name, long mbps, bool wifi) LinkTo(string phoneIp)
    {
        try
        {
            using var s = new Socket(AddressFamily.InterNetwork, SocketType.Dgram, ProtocolType.Udp);
            s.Connect(IPAddress.Parse(phoneIp), 9);
            if (s.LocalEndPoint is IPEndPoint local)
            {
                var nic = NetworkInterface.GetAllNetworkInterfaces().FirstOrDefault(n =>
                    n.GetIPProperties().UnicastAddresses.Any(a => a.Address.Equals(local.Address)));
                if (nic != null)
                    return (nic.Name, nic.Speed / 1_000_000, nic.NetworkInterfaceType == NetworkInterfaceType.Wireless80211);
            }
        }
        catch { }
        return ("", 0, false);
    }

    public static string VpnHint(string vpn) =>
        $"Компьютер отправляет запросы к телефону через VPN «{vpn}», поэтому не видит телефон в домашней сети. " +
        $"Выключите VPN на время сопряжения и трансляции или в настройках «{vpn}» добавьте домашнюю сеть " +
        "(например, 192.168.0.0/24) в исключения — «не через VPN» (раздельное туннелирование).";
}
