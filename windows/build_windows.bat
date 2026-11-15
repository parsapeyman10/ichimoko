@echo off
setlocal EnableExtensions

title Aurum Edge - PyQt6 Desktop Build
set "SCRIPT_DIR=%~dp0"
set "PYTHON_CMD="

echo === Looking for Python 3.11+ ===
where py >nul 2>&1
if not errorlevel 1 (
    rem Prefer stable CPython versions with widely available PyQt6 wheels.
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
    echo Install the official 64-bit Python, then run this file again.
    goto :failed
)

echo Using: %PYTHON_CMD%
pushd "%SCRIPT_DIR%"
if errorlevel 1 goto :failed

rem Always recreate generated build files. PyQt6 is installed inside this venv,
rem so the host Python does not need a preinstalled GUI toolkit.
%PYTHON_CMD% build_desktop.py --recreate-venv %*
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
echo Read the diagnostic above, correct the prerequisite if needed, and run this file again.
pause
exit /b 1
