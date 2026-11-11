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
    echo [ERROR] Windows PowerShell is required for download and signature checks.
    goto :failed
)

set "QT_INSTALLER=%TEMP%\aurum-qt-online-installer.exe"
set "QT_DOWNLOAD=%TEMP%\aurum-qt-online-installer.download"
set "QT_LOCAL=%SCRIPT_DIR%qt-online-installer-windows-x64-online.exe"
set "QT_URL=https://download.qt.io/official_releases/online_installers/qt-online-installer-windows-x64-online.exe"
set "QT_MIRROR=https://mirrors.ocf.berkeley.edu/qt/official_releases/online_installers/qt-online-installer-windows-x64-online.exe"

echo.
echo === Aurum Edge Qt setup ===
echo Destination: %QT_ROOT%
echo Packages:    Qt 6.8.3 MSVC2022 64-bit, CMake, Ninja
echo.

rem A manually downloaded installer can bypass DNS/download restrictions.
if exist "%QT_LOCAL%" (
    echo Using the local Qt Online Installer...
    set "QT_INSTALLER=%QT_LOCAL%"
    goto :verify
)
if exist "%QT_INSTALLER%" (
    echo Using the previously downloaded Qt Online Installer...
    goto :verify
)

set "QT_ACTIVE_URL=%QT_URL%"
echo Downloading from the official Qt server...
call :download
if not errorlevel 1 goto :downloaded

echo.
echo The official server was unreachable. This is usually a DNS/network issue.
echo Trying the UC Berkeley Qt mirror...
set "QT_ACTIVE_URL=%QT_MIRROR%"
call :download
if errorlevel 1 (
    echo [ERROR] Both download servers were unreachable.
    echo Check DNS/proxy/VPN settings, or manually download:
    echo   %QT_URL%
    echo Save it beside this batch file as:
    echo   qt-online-installer-windows-x64-online.exe
    echo Then run this file again.
    goto :failed
)

:downloaded
move /y "%QT_DOWNLOAD%" "%QT_INSTALLER%" >nul
if errorlevel 1 goto :failed

:verify
echo Verifying the installer's Authenticode signature...
powershell -NoLogo -NoProfile -ExecutionPolicy Bypass -Command "$ErrorActionPreference = 'Stop'; $s = Get-AuthenticodeSignature -FilePath $env:QT_INSTALLER; if ($s.Status -ne 'Valid' -or $null -eq $s.SignerCertificate -or $s.SignerCertificate.Subject -notmatch 'Qt') { Write-Error ('Untrusted installer signature: ' + $s.Status); exit 1 }; Write-Host ('Verified publisher: ' + $s.SignerCertificate.Subject)"
if errorlevel 1 (
    echo [ERROR] The installer was not executed because its signature is invalid.
    if not "%QT_INSTALLER%"=="%QT_LOCAL%" del /q "%QT_INSTALLER%"
    goto :failed
)

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

:download
if exist "%QT_DOWNLOAD%" del /q "%QT_DOWNLOAD%"
powershell -NoLogo -NoProfile -ExecutionPolicy Bypass -Command "$ErrorActionPreference = 'Stop'; [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12; Invoke-WebRequest -Uri $env:QT_ACTIVE_URL -OutFile $env:QT_DOWNLOAD"
if errorlevel 1 (
    if exist "%QT_DOWNLOAD%" del /q "%QT_DOWNLOAD%"
    exit /b 1
)
exit /b 0

:failed
echo.
echo === QT SETUP OR BUILD DID NOT COMPLETE ===
echo Review the error above. No executable was produced.
pause
exit /b 1
