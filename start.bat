@echo off
cd /d "%~dp0"
echo Trading — One Click...
git pull origin arena/01a09055-ichimoko 2>nul
python run.py
if errorlevel 1 py run.py
pause
