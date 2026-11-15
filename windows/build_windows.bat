@echo off
setlocal EnableExtensions

title Aurum Edge - Python Windows Build
rem Builds the pure-Python desktop application. No Qt, CMake, Ninja, or Visual Studio is used.
set "SCRIPT_DIR=%~dp0"
set "VENV_DIR=%SCRIPT_DIR%.build-venv"
set "WORK_DIR=%SCRIPT_DIR%build-local"
set "DIST_DIR=%SCRIPT_DIR%dist-local"
set "SPEC_DIR=%SCRIPT_DIR%spec-local"
set "APP_FILE=%SCRIPT_DIR%aurum_edge.py"
set "CORE_FILE=%SCRIPT_DIR%aurum_core.py"
set "TEST_FILE=%SCRIPT_DIR%test_aurum_core.py"

if not exist "%APP_FILE%" (
    echo [ERROR] Application source was not found: %APP_FILE%
    goto :failed
)
if not exist "%CORE_FILE%" (
    echo [ERROR] Python core was not found: %CORE_FILE%
    goto :failed
)
if not exist "%TEST_FILE%" (
    echo [ERROR] Core tests were not found: %TEST_FILE%
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
    echo Install Python from python.org and select "Add Python to PATH".
    goto :failed
)

%PYTHON_CMD% -c "import sys; raise SystemExit(0 if sys.version_info >= (3, 11) else 1)"
if errorlevel 1 (
    echo [ERROR] Python 3.11 or newer is required.
    %PYTHON_CMD% --version
    goto :failed
)

echo.
echo === Aurum Edge Python desktop build ===
echo Source: %SCRIPT_DIR%
echo Runtime: Python standard library + Tkinter
echo.

if not exist "%VENV_DIR%\Scripts\python.exe" (
    echo === Creating local build virtual environment ===
    %PYTHON_CMD% -m venv "%VENV_DIR%"
    if errorlevel 1 goto :failed
)
call "%VENV_DIR%\Scripts\activate.bat"
if errorlevel 1 goto :failed

python -c "import tkinter; print('Tkinter is available.')"
if errorlevel 1 (
    echo [ERROR] Tkinter is unavailable in this Python installation.
    echo Install the standard Windows Python distribution from python.org.
    goto :failed
)

echo === Running deterministic Python core tests ===
python "%TEST_FILE%"
if errorlevel 1 goto :failed

echo === Installing only the local packaging dependency ===
python -m pip install --upgrade pip
if errorlevel 1 goto :failed
python -m pip install --upgrade "pyinstaller>=6.0,<7.0"
if errorlevel 1 goto :failed

echo === Packaging one-file Windows application ===
rem %%~dp0 ends with a backslash. Passing that value inside quotes to a Python
rem executable can escape the closing quote, swallowing the positional script name.
rem Build from the application directory and use dot/source literals instead.
pushd "%SCRIPT_DIR%"
if errorlevel 1 goto :failed
python -m PyInstaller --noconfirm --clean --onefile --windowed --name "Aurum_Edge_Windows" --paths "." --distpath "%DIST_DIR%" --workpath "%WORK_DIR%" --specpath "%SPEC_DIR%" "aurum_edge.py"
set "PYINSTALLER_EXIT_CODE=%ERRORLEVEL%"
popd
if not "%PYINSTALLER_EXIT_CODE%"=="0" goto :failed

echo.
echo === SUCCESS ===
echo Executable created locally at:
echo %DIST_DIR%\Aurum_Edge_Windows.exe
echo.
echo The app is a historical-analysis and paper-journal tool. It never sends live orders.
pause
exit /b 0

:failed
echo.
echo === BUILD FAILED ===
echo Read the error above, correct the prerequisite, and run this BAT again.
pause
exit /b 1
