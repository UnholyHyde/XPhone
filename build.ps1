# Сборка XPhone в папку dist\XPhone (XPhone.exe + tools\ + licenses\ + branding\).
# Запуск: powershell -ExecutionPolicy Bypass -File build.ps1
$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot
$out = Join-Path $root 'dist\XPhone'

$running = Get-Process XPhone, scrcpy, xphone-screen, adb -ErrorAction SilentlyContinue | Where-Object { $_.Path -and $_.Path.StartsWith($out, 'OrdinalIgnoreCase') }
if ($running) { throw 'XPhone (или его scrcpy/adb) запущен из папки dist — закройте XPhone, выполните «tools\adb.exe kill-server» и повторите сборку.' }
if (Test-Path $out) { Remove-Item $out -Recurse -Force }
dotnet publish (Join-Path $root 'src\XPhone\XPhone.csproj') -c Release -r win-x64 --self-contained false `
    -p:PublishSingleFile=true -p:DebugType=none -o $out -nologo
if ($LASTEXITCODE -ne 0) { throw 'Сборка не удалась' }

# ── Окно трансляции «под XPhone» ──────────────────────────────────────
# Копия scrcpy.exe с именем, описанием и значком XPhone: так в диспетчере задач, микшере громкости
# и на панели задач видно «XPhone — экран телефона». Сам scrcpy не меняется (Apache 2.0, см. licenses\NOTICE.txt).
$rcedit = Join-Path $env:LOCALAPPDATA 'XPhone-build\rcedit-x64.exe'
$rceditUrl = 'https://github.com/electron/rcedit/releases/download/v2.0.0/rcedit-x64.exe'
$rceditSha256 = '3E7801DB1A5EDBEC91B49A24A094AAD776CB4515488EA5A4CA2289C400EADE2A'
if (-not (Test-Path $rcedit)) {
    New-Item -ItemType Directory -Force (Split-Path $rcedit) | Out-Null
    $ProgressPreference = 'SilentlyContinue'
    Invoke-WebRequest $rceditUrl -OutFile $rcedit -UseBasicParsing
}
if ((Get-FileHash $rcedit).Hash -ne $rceditSha256) { throw 'rcedit-x64.exe не совпадает по контрольной сумме — удалите его и повторите сборку.' }

$version = ([xml](Get-Content (Join-Path $root 'src\XPhone\XPhone.csproj') -Raw)).Project.PropertyGroup.Version | Where-Object { $_ } | Select-Object -First 1
$tools = Join-Path $out 'tools'
$screen = Join-Path $tools 'xphone-screen.exe'
Copy-Item (Join-Path $tools 'scrcpy.exe') $screen
& $rcedit $screen `
    --set-icon (Join-Path $root 'src\XPhone\Assets\xphone.ico') `
    --set-version-string FileDescription 'XPhone — экран телефона' `
    --set-version-string ProductName 'XPhone' `
    --set-version-string CompanyName 'XPhone' `
    --set-version-string InternalName 'xphone-screen' `
    --set-version-string OriginalFilename 'xphone-screen.exe' `
    --set-version-string LegalCopyright 'XPhone. Основано на scrcpy (Apache 2.0)' `
    --set-file-version '5.0.1.0' `
    --set-product-version "$version"
if ($LASTEXITCODE -ne 0) { throw 'Не удалось подписать окно трансляции (rcedit)' }
Remove-Item (Join-Path $tools 'scrcpy.exe')

Copy-Item (Join-Path $root 'README.md') $out
Write-Host ''
Write-Host "Готово: $out\XPhone.exe" -ForegroundColor Green
