@echo off
setlocal EnableExtensions

title Aurum Edge - Python Desktop
set "SCRIPT_DIR=%~dp0"
set "APP_FILE=%SCRIPT_DIR%aurum_edge.py"

if not exist "%APP_FILE%" (
    echo [ERROR] Application source was not found: %APP_FILE%
    pause
    exit /b 1
)

where py >nul 2>&1
if not errorlevel 1 (
    py -3 "%APP_FILE%"
) else (
    where python >nul 2>&1
    if errorlevel 1 (
        echo [ERROR] Python 3.11 or newer was not found.
        echo Install Python from python.org and select "Add Python to PATH".
        pause
        exit /b 1
    )
    python "%APP_FILE%"
)

set "APP_EXIT_CODE=%ERRORLEVEL%"
if not "%APP_EXIT_CODE%"=="0" pause
exit /b %APP_EXIT_CODE%
