@echo off
setlocal EnableExtensions DisableDelayedExpansion
title Aurum Edge - Qt 6.8.3 Setup

rem Optional first argument: installation root (default C:\Qt).
set "SCRIPT_DIR=%~dp0"
if "%~1"=="" (set "QT_ROOT=C:\Qt") else (set "QT_ROOT=%~f1")
set "QT_KIT=%QT_ROOT%\6.8.3\msvc2022_64"
set "QT_CONFIG=%QT_KIT%\lib\cmake\Qt6\Qt6Config.cmake"

if exist "%QT_CONFIG%" (
    echo Qt 6.8.3 MSVC2022 64-bit is already installed:
    echo   %QT_KIT%
    goto :ready
)

where powershell >nul 2>&1
if errorlevel 1 (
    echo [ERROR] Windows PowerShell is required to download the official installer.
    goto :failed
)

set "QT_INSTALLER=%TEMP%\aurum-qt-online-installer.exe"
set "QT_DOWNLOAD=%TEMP%\aurum-qt-online-installer.download"
set "QT_URL=https://download.qt.io/official_releases/online_installers/qt-unified-windows-x64-online.exe"

echo.
echo === Aurum Edge Qt setup ===
echo Destination: %QT_ROOT%
echo Packages:    Qt 6.8.3 MSVC2022 64-bit, CMake, Ninja
echo.
echo Downloading the official Qt Online Installer...
powershell -NoLogo -NoProfile -ExecutionPolicy Bypass -Command "$ErrorActionPreference = 'Stop'; [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12; Invoke-WebRequest -Uri '%QT_URL%' -OutFile '%QT_DOWNLOAD%'"
if errorlevel 1 (
    echo [ERROR] Could not download Qt. Check the internet connection and retry.
    if exist "%QT_DOWNLOAD%" del /q "%QT_DOWNLOAD%"
    goto :failed
)
move /y "%QT_DOWNLOAD%" "%QT_INSTALLER%" >nul
if errorlevel 1 goto :failed

echo.
echo A Qt account is required. Sign in or create a free account in the installer.
echo Review and accept the applicable Qt licenses, then continue the installation.
echo Do not close this window; setup will check the result automatically.
echo.
start "Qt Online Installer" /wait "%QT_INSTALLER%" --root "%QT_ROOT%" install qt.qt6.683.win64_msvc2022_64 qt.tools.cmake qt.tools.ninja

if not exist "%QT_CONFIG%" (
    echo [ERROR] Qt setup did not finish or the selected kit was not installed.
    echo Run this file again, sign in, and install the packages shown above.
    goto :failed
)

:ready
echo.
echo Qt is ready. Starting the native Windows build...
call "%SCRIPT_DIR%build_windows.bat" "%QT_KIT%"
if errorlevel 1 goto :failed
exit /b 0

:failed
echo.
echo === QT SETUP OR BUILD DID NOT COMPLETE ===
echo Review the error above. No executable was produced.
pause
exit /b 1
