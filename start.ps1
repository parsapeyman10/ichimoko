# Aurum Edge — One-Click Start for Windows PowerShell
# Usage: Right-click -> Run with PowerShell, or:
#   powershell -ExecutionPolicy Bypass -File start.ps1
$ErrorActionPreference = "Stop"
$ROOT = $PSScriptRoot
if (-not $ROOT) { $ROOT = Get-Location }

Write-Host "`n=== Aurum Edge — One Click Start ===" -ForegroundColor Yellow
Write-Host "ROOT: $ROOT" -ForegroundColor Gray

# 1) Check Python
try { $py = & python --version 2>&1 } catch { $py = & py --version 2>&1 }
Write-Host "Python: $py" -ForegroundColor Green

# 2) Check Node
try { $node = & node --version 2>&1; $npm = & npm --version 2>&1; Write-Host "Node: $node  npm: $npm" -ForegroundColor Green } catch { Write-Host "Node.js not found! Install from https://nodejs.org" -ForegroundColor Red; pause; exit 1 }

# 3) Install backend deps
Write-Host "`n[1/4] Installing backend deps..." -ForegroundColor Cyan
Push-Location $ROOT
& python -m pip install -r backend/requirements.txt
if ($LASTEXITCODE -ne 0) { & py -m pip install -r backend/requirements.txt }

# 4) Install frontend deps
Write-Host "`n[2/4] Installing frontend deps..." -ForegroundColor Cyan
& npm install
if ($LASTEXITCODE -ne 0) { Write-Host "npm install failed" -ForegroundColor Red; pause; exit 1 }

# 5) Build check
Write-Host "`n[3/4] Building frontend (quick check)..." -ForegroundColor Cyan
& npm run build
if ($LASTEXITCODE -ne 0) { Write-Host "Build failed, but dev may still work" -ForegroundColor Yellow }

# 6) Start both
Write-Host "`n[4/4] Starting backend (8000) + frontend (5173)..." -ForegroundColor Cyan
Write-Host "  Backend: http://127.0.0.1:8000/docs" -ForegroundColor Green
Write-Host "  Frontend: http://127.0.0.1:5173" -ForegroundColor Green
Write-Host "  Health: http://127.0.0.1:8000/api/v1/health" -ForegroundColor Gray
Write-Host "`nIf browser doesn't open, open http://127.0.0.1:5173 manually" -ForegroundColor Yellow
Write-Host "Press Ctrl+C in each window to stop.`n" -ForegroundColor Gray

# Start backend in new window
Start-Process powershell -ArgumentList "-NoExit", "-Command", "cd `"$ROOT`"; python run.py --reload"
Start-Sleep -Seconds 2
# Start frontend in new window
Start-Process powershell -ArgumentList "-NoExit", "-Command", "cd `"$ROOT`"; npm run dev"

Write-Host "Both windows opened! Wait 5s then opening browser..." -ForegroundColor Green
Start-Sleep -Seconds 5
Start-Process "http://127.0.0.1:5173"
Start-Process "http://127.0.0.1:8000/docs"

Write-Host "`nDone! If you see ModuleNotFoundError, run: git pull origin arena/01a09055-ichimoko" -ForegroundColor Yellow
