@echo off
setlocal EnableExtensions

title Aurum Edge - Automatic Python Desktop Build
set "SCRIPT_DIR=%~dp0"
set "PYTHON_CMD="

echo === Looking for a healthy Python 3.11+ with Tkinter ===
where py >nul 2>&1
if not errorlevel 1 (
    rem Try the current launcher default first, then common installed versions.
    rem A crashing/broken Tcl-Tk runtime is rejected automatically.
    for %%V in (3 3.14 3.13 3.12 3.11) do (
        if not defined PYTHON_CMD (
            py -%%V -c "import sys, tkinter; raise SystemExit(0 if sys.version_info >= (3, 11) else 1)" >nul 2>&1
            if not errorlevel 1 set "PYTHON_CMD=py -%%V"
        )
    )
)

if not defined PYTHON_CMD (
    where python >nul 2>&1
    if not errorlevel 1 (
        python -c "import sys, tkinter; raise SystemExit(0 if sys.version_info >= (3, 11) else 1)" >nul 2>&1
        if not errorlevel 1 set "PYTHON_CMD=python"
    )
)

if not defined PYTHON_CMD (
    echo [ERROR] No healthy Python 3.11+ runtime with Tkinter was found.
    echo The BAT automatically tested the Python launcher and common Python versions.
    echo Repair or install the official 64-bit Python with Tcl/Tk enabled, then run this BAT again.
    echo A generated .build-venv cannot repair a broken host Python installation.
    goto :failed
)

echo Using: %PYTHON_CMD%
pushd "%SCRIPT_DIR%"
if errorlevel 1 goto :failed

rem Always rebuild the generated venv. This prevents stale Python/Tcl DLL references
rem after Python updates, and requires no manual deletion of .build-venv.
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
echo Read the diagnostic above, correct the host prerequisite if needed, and run this file again.
pause
exit /b 1
