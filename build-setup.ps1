# Сборка автономного установщика Windows x64.
param([string]$IsccPath)
$ErrorActionPreference = 'Stop'
if (-not $IsccPath) {
    $candidates = @(
        (Join-Path $env:LOCALAPPDATA 'XPhone-build\inno\ISCC.exe'),
        "${env:ProgramFiles(x86)}\Inno Setup 6\ISCC.exe",
        "$env:ProgramFiles\Inno Setup 7\ISCC.exe"
    )
    $IsccPath = $candidates | Where-Object { Test-Path -LiteralPath $_ } | Select-Object -First 1
}
if (-not $IsccPath -or -not (Test-Path -LiteralPath $IsccPath)) {
    throw 'Для сборки установите Inno Setup: https://jrsoftware.org/isdl.php (или укажите -IsccPath).'
}
& (Join-Path $PSScriptRoot 'build.ps1') -SelfContained
if (-not (Test-Path -LiteralPath (Join-Path $PSScriptRoot 'dist\XPhone\XPhone.exe'))) { throw 'Нет готового XPhone.exe' }
$project = [xml](Get-Content -LiteralPath (Join-Path $PSScriptRoot 'src\XPhone\XPhone.csproj') -Raw)
$version = $project.Project.PropertyGroup.Version | Where-Object { $_ } | Select-Object -First 1
& $IsccPath "/DAppVersion=$version" (Join-Path $PSScriptRoot 'installer\XPhone.iss')
if ($LASTEXITCODE -ne 0) { throw 'Не удалось собрать установщик' }
Write-Host "Готово: dist\Setup\XPhone-Setup-$version-x64.exe" -ForegroundColor Green
