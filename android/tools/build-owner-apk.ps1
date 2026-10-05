<#
.SYNOPSIS
  Builds an owner-signed release APK with the same private key on Windows.
.PARAMETER KeystorePath
  Absolute path to the keystore file (outside the repository).
.PARAMETER Alias
  Key alias name (e.g. aurum-edge).
#>
param(
    [Parameter(Mandatory=$true)]
    [string]$KeystorePath,
    [Parameter(Mandatory=$true)]
    [string]$Alias
)

$ErrorActionPreference = "Stop"
$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$RepoRoot = (Resolve-Path "$ScriptDir\..\..").Path

$FullKeystorePath = [System.IO.Path]::GetFullPath($KeystorePath)
if (!(Test-Path $FullKeystorePath)) {
    Write-Error "Error: Keystore file not found at $FullKeystorePath"
    exit 2
}

if ($FullKeystorePath.StartsWith($RepoRoot, [System.StringComparison]::OrdinalIgnoreCase)) {
    Write-Warning "هشدار: فایل کلید (Keystore) درون پوشهٔ پروژه قرار دارد. برای امنیت بیشتر از کامیت کردن آن خودداری کنید."
}

$env:AURUM_RELEASE_STORE_FILE = $FullKeystorePath
$env:AURUM_RELEASE_KEY_ALIAS = $Alias

if ([string]::IsNullOrWhiteSpace($env:AURUM_RELEASE_STORE_PASSWORD)) {
    $storePass = Read-Host "Enter Keystore password" -AsSecureString
    $env:AURUM_RELEASE_STORE_PASSWORD = [System.Net.NetworkCredential]::new("", $storePass).Password
}

if ([string]::IsNullOrWhiteSpace($env:AURUM_RELEASE_KEY_PASSWORD)) {
    $keyPass = Read-Host "Enter Key password" -AsSecureString
    $env:AURUM_RELEASE_KEY_PASSWORD = [System.Net.NetworkCredential]::new("", $keyPass).Password
}

Set-Location "$RepoRoot\android"
Write-Host "Running Gradle build for Release APK..." -ForegroundColor Cyan

try {
    & .\gradlew.bat --no-daemon -PaurumRequireReleaseSigning=true testDebugUnitTest assembleRelease
}
finally {
    $env:AURUM_RELEASE_STORE_PASSWORD = $null
    $env:AURUM_RELEASE_KEY_PASSWORD = $null
}

$apkPath = "$RepoRoot\android\app\build\outputs\apk\release\app-release.apk"
if (!(Test-Path $apkPath)) {
    Write-Error "Error: Owner-signed APK was not produced."
    exit 1
}

Write-Host ""
Write-Host "==========================================================" -ForegroundColor Green
Write-Host " APK built successfully with owner signing!" -ForegroundColor Green
Write-Host " Location: $apkPath" -ForegroundColor Green
Write-Host "==========================================================" -ForegroundColor Green

$hash = Get-FileHash -Path $apkPath -Algorithm SHA256
Write-Host "SHA-256 Checksum: $($hash.Hash)" -ForegroundColor Yellow
