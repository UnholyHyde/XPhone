# Сборка XPhone в папку dist\XPhone (XPhone.exe + tools\ + licenses\).
# Запуск: powershell -ExecutionPolicy Bypass -File build.ps1
$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot
$out = Join-Path $root 'dist\XPhone'

$running = Get-Process XPhone, scrcpy, adb -ErrorAction SilentlyContinue | Where-Object { $_.Path -and $_.Path.StartsWith($out, 'OrdinalIgnoreCase') }
if ($running) { throw 'XPhone (или его scrcpy/adb) запущен из папки dist — закройте XPhone, выполните «tools\adb.exe kill-server» и повторите сборку.' }
if (Test-Path $out) { Remove-Item $out -Recurse -Force }
dotnet publish (Join-Path $root 'src\XPhone\XPhone.csproj') -c Release -r win-x64 --self-contained false `
    -p:PublishSingleFile=true -p:DebugType=none -o $out -nologo
if ($LASTEXITCODE -ne 0) { throw 'Сборка не удалась' }

Copy-Item (Join-Path $root 'README.md') $out
Write-Host ''
Write-Host "Готово: $out\XPhone.exe" -ForegroundColor Green
