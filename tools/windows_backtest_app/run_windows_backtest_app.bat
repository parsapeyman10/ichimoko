@echo off
setlocal EnableExtensions
set "SCRIPT_DIR=%~dp0"

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

%PYTHON_CMD% -c "import sys, tkinter; raise SystemExit(0 if sys.version_info >= (3, 11) else 1)"
if errorlevel 1 (
    echo [ERROR] Python 3.11+ with Tkinter is required.
    goto :failed
)

%PYTHON_CMD% "%SCRIPT_DIR%base_ichimoku_backtest_app.py"
exit /b %ERRORLEVEL%

:failed
pause
exit /b 1
