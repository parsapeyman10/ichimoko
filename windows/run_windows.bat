@echo off
setlocal EnableExtensions

title Aurum Edge - PyQt6 Desktop
set "SCRIPT_DIR=%~dp0"
set "APP_FILE=%SCRIPT_DIR%aurum_edge.py"
set "PYTHON_CMD="

if not exist "%APP_FILE%" (
    echo [ERROR] Application source was not found: %APP_FILE%
    pause
    exit /b 1
)

where py >nul 2>&1
if not errorlevel 1 (
    for %%V in (3.13 3.12 3.11 3.14 3) do (
        if not defined PYTHON_CMD (
            py -%%V -c "import sys; raise SystemExit(0 if sys.version_info >= (3, 11) else 1)" >nul 2>&1
            if not errorlevel 1 set "PYTHON_CMD=py -%%V"
        )
    )
)
if not defined PYTHON_CMD (
    where python >nul 2>&1
    if not errorlevel 1 (
        python -c "import sys; raise SystemExit(0 if sys.version_info >= (3, 11) else 1)" >nul 2>&1
        if not errorlevel 1 set "PYTHON_CMD=python"
    )
)
if not defined PYTHON_CMD (
    echo [ERROR] Python 3.11 or newer was not found.
    echo Install the official 64-bit Python and select Add Python to PATH.
    pause
    exit /b 1
)

%PYTHON_CMD% -c "from PyQt6.QtWidgets import QApplication" >nul 2>&1
if errorlevel 1 (
    echo [ERROR] PyQt6 is not installed for the selected Python.
    echo Run: %PYTHON_CMD% -m pip install -r "%SCRIPT_DIR%requirements.txt"
    pause
    exit /b 1
)

%PYTHON_CMD% "%APP_FILE%"
set "APP_EXIT_CODE=%ERRORLEVEL%"
if not "%APP_EXIT_CODE%"=="0" pause
exit /b %APP_EXIT_CODE%
