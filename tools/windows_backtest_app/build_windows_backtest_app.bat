@echo off
setlocal EnableExtensions

title BASE Ichimoku Native 15m - Python Windows Build
set "SCRIPT_DIR=%~dp0"
set "VENV_DIR=%SCRIPT_DIR%.build-venv"
set "WORK_DIR=%SCRIPT_DIR%build-local"
set "DIST_DIR=%SCRIPT_DIR%dist-local"
set "SPEC_DIR=%SCRIPT_DIR%spec-local"
set "APP_FILE=%SCRIPT_DIR%base_ichimoku_backtest_app.py"

if not exist "%APP_FILE%" (
    echo [ERROR] Application source was not found:
    echo         %APP_FILE%
    goto :failed
)

set "PYTHON_CMD="
where py >nul 2>&1
if not errorlevel 1 (
    set "PYTHON_CMD=py -3"
) else (
    where python >nul 2>&1
    if not errorlevel 1 set "PYTHON_CMD=python"
)
if not defined PYTHON_CMD (
    echo [ERROR] Python 3.11 or newer was not found.
    echo Install Python from python.org and enable "Add Python to PATH".
    goto :failed
)

%PYTHON_CMD% -c "import sys; raise SystemExit(0 if sys.version_info >= (3, 11) else 1)"
if errorlevel 1 (
    echo [ERROR] Python 3.11 or newer is required.
    %PYTHON_CMD% --version
    goto :failed
)

echo.
echo === BASE Ichimoku Native 15m Python App ===
echo Source: %SCRIPT_DIR%
echo.

if not exist "%VENV_DIR%\Scripts\python.exe" (
    echo === Creating local build virtual environment ===
    %PYTHON_CMD% -m venv "%VENV_DIR%"
    if errorlevel 1 goto :failed
)

call "%VENV_DIR%\Scripts\activate.bat"
if errorlevel 1 goto :failed

python -c "import tkinter; print('Tkinter is available for the desktop build.')"
if errorlevel 1 (
    echo [ERROR] This Python installation does not include Tkinter.
    echo Install the standard Windows Python distribution from python.org.
    goto :failed
)

echo === Installing local packaging dependency ===
python -m pip install --upgrade pip
if errorlevel 1 goto :failed
python -m pip install --upgrade "pyinstaller>=6.0,<7.0"
if errorlevel 1 goto :failed

echo === Building Windows executable ===
python -m PyInstaller --noconfirm --clean --onefile --windowed ^
  --name "BASE_ICHIMOKU_15m_Backtest" ^
  --paths "%SCRIPT_DIR%" ^
  --distpath "%DIST_DIR%" ^
  --workpath "%WORK_DIR%" ^
  --specpath "%SPEC_DIR%" ^
  "%APP_FILE%"
if errorlevel 1 goto :failed

echo.
echo === SUCCESS ===
echo Executable created locally at:
echo %DIST_DIR%\BASE_ICHIMOKU_15m_Backtest.exe
echo.
echo This tool is historical backtesting only. It does not send live orders.
pause
exit /b 0

:failed
echo.
echo === BUILD FAILED ===
echo Read the error above, correct the prerequisite, and run this BAT again.
pause
exit /b 1
