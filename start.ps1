# Double-click this file → Everything starts
$ErrorActionPreference = "SilentlyContinue"
$ROOT = $PSScriptRoot
if (-not $ROOT) { $ROOT = Get-Location }
Set-Location $ROOT
Write-Host "Trading — One Click..." -ForegroundColor Yellow
git pull origin arena/01a09055-ichimoko 2>$null
python run.py
if ($LASTEXITCODE -ne 0) { py run.py }
pause
