<#
.SYNOPSIS
  Generates a stable release keystore for com.aurum.edge on Windows.
.PARAMETER KeystorePath
  Absolute path to save the keystore (must be outside the repository).
.PARAMETER Alias
  Key alias name (defaults to aurum-edge).
#>
param(
    [string]$KeystorePath = "$HOME\aurum-private\aurum-edge.jks",
    [string]$Alias = "aurum-edge"
)

$ErrorActionPreference = "Stop"
$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$RepoRoot = Resolve-Path "$ScriptDir\..\.."

# Ensure destination is outside the repo
$FullKeystorePath = [System.IO.Path]::GetFullPath($KeystorePath)
if ($FullKeystorePath.StartsWith($RepoRoot.Path, [System.StringComparison]::OrdinalIgnoreCase)) {
    Write-Error "Error: Keystore file must be located OUTSIDE the repository."
    exit 2
}

$ParentDir = [System.IO.Path]::GetDirectoryName($FullKeystorePath)
if (!(Test-Path $ParentDir)) {
    New-Item -ItemType Directory -Path $ParentDir -Force | Out-Null
}

if (Test-Path $FullKeystorePath) {
    Write-Host "File $FullKeystorePath already exists." -ForegroundColor Yellow
    $ans = Read-Host "Do you want to view the certificate fingerprint? (y/N)"
    if ($ans -eq "y" -or $ans -eq "Y") {
        & keytool -list -v -keystore "$FullKeystorePath" -alias "$Alias"
    }
    exit 0
}

Write-Host "==========================================================" -ForegroundColor Cyan
Write-Host " Generating Release Keystore for com.aurum.edge" -ForegroundColor Cyan
Write-Host "==========================================================" -ForegroundColor Cyan
Write-Host "Keystore Path: $FullKeystorePath"
Write-Host "Key Alias:     $Alias"
Write-Host "Algorithm:     RSA 3072-bit (Validity: 10000 days)"
Write-Host "----------------------------------------------------------"

& keytool -genkeypair -v `
  -keystore "$FullKeystorePath" `
  -alias "$Alias" `
  -keyalg RSA `
  -keysize 3072 `
  -validity 10000 `
  -storetype JKS

Write-Host ""
Write-Host "==========================================================" -ForegroundColor Green
Write-Host " Keystore created successfully!" -ForegroundColor Green
Write-Host "==========================================================" -ForegroundColor Green

$certInfo = & keytool -list -v -keystore "$FullKeystorePath" -alias "$Alias" 2>$null | Select-String "SHA256:"
if ($certInfo) {
    $sha256 = ($certInfo -split ":", 2)[1].Trim()
    Write-Host "SHA-256 Fingerprint: $sha256" -ForegroundColor Yellow
}

$base64Content = [Convert]::ToBase64String([System.IO.File]::ReadAllBytes($FullKeystorePath))
Write-Host ""
Write-Host "For GitHub Actions Secrets:" -ForegroundColor Cyan
Write-Host "1. AURUM_RELEASE_KEYSTORE_BASE64: $base64Content"
Write-Host "2. AURUM_RELEASE_KEY_ALIAS:       $Alias"
Write-Host "3. AURUM_RELEASE_STORE_PASSWORD:  [Your Keystore Password]"
Write-Host "4. AURUM_RELEASE_KEY_PASSWORD:    [Your Key Password]"
if ($sha256) {
    Write-Host "5. AURUM_RELEASE_CERT_SHA256:     $sha256"
}
Write-Host "==========================================================" -ForegroundColor Green
