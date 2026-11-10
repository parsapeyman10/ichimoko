@echo off
setlocal EnableExtensions
title Aurum Edge - Native Windows x64 Build

rem Optional first argument: Qt MSVC2022 64-bit directory.
rem Example: build_windows.bat "C:\Qt\6.8.3\msvc2022_64"
set "SCRIPT_DIR=%~dp0"
for %%I in ("%SCRIPT_DIR%..") do set "REPO_DIR=%%~fI"
set "BUILD_DIR=%SCRIPT_DIR%build-local"
if "%~1"=="" (
    set "QT_PATH=C:\Qt\6.8.3\msvc2022_64"
) else (
    set "QT_PATH=%~f1"
)

echo.
echo === Aurum Edge native Windows x64 ===
echo Repository: %REPO_DIR%
echo Qt:         %QT_PATH%
echo Build:      %BUILD_DIR%
echo.

if not exist "%QT_PATH%\lib\cmake\Qt6\Qt6Config.cmake" (
    echo [ERROR] Qt6 MSVC2022 64-bit was not found at:
    echo         %QT_PATH%
    echo Pass its directory as the first argument, for example:
    echo   %~nx0 "D:\Qt\6.8.3\msvc2022_64"
    goto :failed
)

where cmake >nul 2>&1
if errorlevel 1 (
    echo [ERROR] cmake is not available in PATH. Install CMake 3.28 or newer.
    goto :failed
)
where ninja >nul 2>&1
if errorlevel 1 (
    echo [ERROR] ninja is not available in PATH. Install Ninja and reopen Command Prompt.
    goto :failed
)

set "VSWHERE=%ProgramFiles(x86)%\Microsoft Visual Studio\Installer\vswhere.exe"
if not exist "%VSWHERE%" (
    echo [ERROR] Visual Studio Installer/vswhere was not found.
    goto :failed
)
set "VS_PATH="
for /f "usebackq tokens=*" %%I in (`"%VSWHERE%" -latest -products * -requires Microsoft.VisualStudio.Component.VC.Tools.x86.x64 -property installationPath`) do (
    set "VS_PATH=%%I"
)
if not defined VS_PATH (
    echo [ERROR] Visual Studio 2022 C++ x64 tools were not found.
    echo Install "Desktop development with C++" in Visual Studio Installer.
    goto :failed
)

call "%VS_PATH%\Common7\Tools\VsDevCmd.bat" -arch=x64 -host_arch=x64
if errorlevel 1 goto :failed

echo.
echo === Configure ===
cmake -S "%SCRIPT_DIR%." -B "%BUILD_DIR%" -G Ninja ^
  -DCMAKE_BUILD_TYPE=Release ^
  -DCMAKE_PREFIX_PATH="%QT_PATH%" ^
  -DAURUM_BUILD_TESTS=ON
if errorlevel 1 goto :failed

echo.
echo === Build ===
cmake --build "%BUILD_DIR%" --config Release
if errorlevel 1 goto :failed

echo.
echo === Core tests ===
ctest --test-dir "%BUILD_DIR%" -C Release --output-on-failure
if errorlevel 1 goto :failed

echo.
echo === SUCCESS ===
echo Executable: %BUILD_DIR%\aurum_edge.exe
echo.
echo This build directory is for running on this PC.
echo For a portable folder/installer, Qt DLL deployment is also required.
echo.
pause
exit /b 0

:failed
echo.
echo === BUILD FAILED ===
echo Review the error above, fix the prerequisite, and run this file again.
echo.
pause
exit /b 1
