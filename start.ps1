# Double-click this file to start the local API and web terminal.
# Deliberately does NOT run git pull: pulling a hard-coded branch can overwrite local work.
$ErrorActionPreference = "Continue"
$ROOT = $PSScriptRoot
if (-not $ROOT) { $ROOT = Get-Location }
Set-Location $ROOT
$branch = git branch --show-current 2>$null
Write-Host "Trading — One Click ($branch)" -ForegroundColor Yellow
Write-Host "Auto-update is disabled; update the current branch intentionally before starting." -ForegroundColor DarkGray
python run.py
if ($LASTEXITCODE -ne 0) { py run.py }
pause
