@echo off
echo === Aurum Edge — One Click Start ===
echo.
python --version
node --version
npm --version

echo [1/4] Installing backend deps...
python -m pip install -r backend/requirements.txt
if errorlevel 1 py -m pip install -r backend/requirements.txt

echo [2/4] Installing frontend deps...
call npm install

echo [3/4] Building frontend...
call npm run build

echo [4/4] Starting backend + frontend...
echo Backend: http://127.0.0.1:8000/docs
echo Frontend: http://127.0.0.1:5173
start cmd /k "python run.py --reload"
timeout /t 3
start cmd /k "npm run dev"
timeout /t 5
start http://127.0.0.1:5173
start http://127.0.0.1:8000/docs
echo Done! Press Ctrl+C to stop each window.
pause
