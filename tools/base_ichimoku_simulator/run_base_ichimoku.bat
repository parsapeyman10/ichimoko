@echo off
setlocal EnableExtensions

rem Historical CSV simulator launcher only. It builds no EXE and sends no real order.
if "%~1"=="" (
  echo Usage:
  echo   %~nx0 ^<15m-GoCharting-CSV^> [simulator options]
  echo Example:
  echo   %~nx0 C:\data\XAUUSDT_15m.csv --symbol XAUUSDT --output reports\xauusdt_base
  exit /b 2
)

where py >nul 2>nul
if errorlevel 1 (
  echo ERROR: Python launcher ^(py^) was not found. Install Python 3.11+ from python.org.
  exit /b 2
)

py -3 "%~dp0base_ichimoku_simulator.py" %*
exit /b %ERRORLEVEL%
