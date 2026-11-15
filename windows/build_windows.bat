@echo off
setlocal EnableExtensions

title Aurum Edge - Cross-platform Python Desktop Build
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
    echo Install Python from python.org and select "Add Python to PATH".
    goto :failed
)

pushd "%SCRIPT_DIR%"
if errorlevel 1 goto :failed
%PYTHON_CMD% build_desktop.py %*
set "BUILD_EXIT_CODE=%ERRORLEVEL%"
popd

if "%BUILD_EXIT_CODE%"=="0" (
    echo.
    echo === SUCCESS ===
    echo Windows EXE: dist-local\windows\Aurum_Edge_Windows.exe
    pause
    exit /b 0
)

goto :failed

:failed
echo.
echo === BUILD FAILED ===
echo Read the error above, correct the prerequisite, and run this file again.
pause
exit /b 1
