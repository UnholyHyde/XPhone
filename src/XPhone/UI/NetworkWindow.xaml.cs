using System.Windows;
using System.Windows.Controls;
using System.Windows.Interop;
using XPhone.Adb;
using XPhone.Core;

namespace XPhone.UI;

public partial class NetworkWindow : Window
{
    private readonly MainViewModel _vm;
    private bool _busy;
    public NetworkWindow(MainViewModel vm)
    {
        InitializeComponent(); _vm=vm;
        SourceInitialized+=(_,_)=>Win32.DarkTitleBar(new WindowInteropHelper(this).Handle);
        Loaded+=async (_,_)=>
        {
            RefreshSaved();
            var usb=UsbDevice();
            if (usb!=null) try { await _vm.Adb.FillInfoAsync(usb); } catch { StatusText.Text="Не удалось обновить сетевые адреса телефона. Проверьте USB-кабель."; }
            UsbText.Text=usb==null?"Телефон по USB не найден. Разрешите отладку на телефоне.":$"По USB: {usb.DisplayName}.";
            var lan=usb?.Addresses.FirstOrDefault(a=>a.IsLan);
            if(lan!=null) AddressBox.Text=lan.Ip+":5555";
            else if(_vm.Settings.NetworkAddresses.FirstOrDefault() is { } saved)AddressBox.Text=saved;
            PrepareButton.IsEnabled=usb!=null&&_vm.Session==null;
            DisableButton.IsEnabled=_vm.Watcher.Current.Any(d=>d.IsReady)&&_vm.Session==null;
        };
    }
    private PhoneDevice? UsbDevice()=>_vm.Watcher.Current.FirstOrDefault(d=>d.IsReady&&d.Transport==Transport.Usb);
    private void RefreshSaved()=>SavedAddresses.ItemsSource=_vm.Settings.NetworkAddresses.ToArray();
    private void Saved_Changed(object sender,SelectionChangedEventArgs e){if(SavedAddresses.SelectedItem is string value)AddressBox.Text=value;}
    private void Forget_Click(object sender,RoutedEventArgs e)
    {
        if(SavedAddresses.SelectedItem is string value){_vm.ForgetNetworkAddress(value);RefreshSaved();StatusText.Text="Адрес забыт. Сетевая отладка на самом телефоне при этом не меняется.";}
    }
    private void Busy(bool value)
    {
        _busy=value;ConnectButton.IsEnabled=!value;PrepareButton.IsEnabled=!value&&UsbDevice()!=null&&_vm.Session==null;
        DisableButton.IsEnabled=!value&&_vm.Session==null&&_vm.Watcher.Current.Any(d=>d.IsReady);
    }
    private async void Connect_Click(object sender,RoutedEventArgs e)
    {
        if(_busy)return;
        if(!NetworkEndpoint.TryParse(AddressBox.Text,out var endpoint)){StatusText.Text="Введите IPv4 и порт: например 192.168.0.6:5555. Порт — от 1 до 65535.";return;}
        if(NetCheck.VpnOnRouteTo(endpoint.Host) is { } vpn){StatusText.Text=NetCheck.VpnHint(vpn);return;}
        Busy(true);StatusText.Text="Подключаем телефон по сети…";
        try
        {
            if(!await _vm.Adb.EnsureServerAsync()){StatusText.Text="Не удалось запустить службу связи adb.";return;}
            var result=await _vm.Adb.ConnectAsync(endpoint.Address);
            if(!result.ok){StatusText.Text="Телефон не отвечает. Проверьте IP, порт, локальную сеть и режим TCP/IP. Для защищённой отладки сначала выполните сопряжение. Если на телефоне появилось разрешение — подтвердите его и повторите.";return;}
            _vm.RememberNetworkAddress(endpoint.Address);RefreshSaved();
            await _vm.RefreshAsync();
            var actual=_vm.Watcher.Current.FirstOrDefault(d=>d.Serial==endpoint.Address);
            StatusText.Text=actual?.IsReady==true
                ? "Подключено. Выберите телефон в главном окне и нажмите «Подключить» или начните трансляцию с телефона. Адрес сохранён для повторного подключения."
                : "Сетевой канал открыт, но телефон ещё не готов. Проверьте окно разрешения отладки на телефоне и нажмите «Обновить» в главном окне. Адрес сохранён.";
        }
        catch{StatusText.Text="Не получилось подключиться по сети. Проверьте адрес, кабель, роутер и VPN.";}
        finally{Busy(false);}
    }
    private async void Prepare_Click(object sender,RoutedEventArgs e)
    {
        if(_busy)return;
        if(_vm.Session!=null){StatusText.Text="Сначала остановите трансляцию: подготовка перезапускает службу связи.";return;}
        var usb=UsbDevice();if(usb==null){StatusText.Text="Подключите телефон USB-кабелем и разрешите отладку.";return;}
        Busy(true);StatusText.Text="Подготавливаем LAN. USB-связь может на несколько секунд пропасть…";
        try
        {
            var result=await _vm.Adb.PrepareLanAsync(usb);
            StatusText.Text=!result.TimedOut&&result.ExitCode==0&&result.Output.Contains("5555")
                ? "Готово: TCP/IP включён на порту 5555. Подключите Ethernet-переходник и посмотрите LAN-адрес в XPhone на телефоне."
                : "Телефон не подтвердил включение TCP/IP. Проверьте отладку по USB и повторите.";
        }
        catch{StatusText.Text="Не удалось включить TCP/IP. Переподключите USB-кабель и повторите.";}
        finally{Busy(false);}
    }
    private async void Disable_Click(object sender,RoutedEventArgs e)
    {
        if(_busy||_vm.Session!=null)return;
        var device=UsbDevice()??_vm.Watcher.Current.FirstOrDefault(d=>d.IsReady);
        if(device==null)return;
        Busy(true);
        try{var result=await _vm.Adb.DisableTcpAsync(device);StatusText.Text=result.ExitCode==0&&!result.TimedOut?"Режим TCP/IP выключен. Для прямого подключения используйте USB-кабель.":"Телефон не подтвердил выключение TCP/IP.";}
        catch{StatusText.Text="Связь с телефоном пропала. Подключите USB-кабель и повторите.";}
        finally{Busy(false);}
    }
    private void Close_Click(object sender,RoutedEventArgs e)=>Close();
}
