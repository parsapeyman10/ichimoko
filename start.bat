@echo off
setlocal
cd /d "%~dp0"
for /f %%b in ('git branch --show-current 2^>nul') do set "BRANCH=%%b"
echo Trading — One Click (%BRANCH%)
echo Auto-update is disabled; update the current branch intentionally before starting.
python run.py
if errorlevel 1 py run.py
pause
