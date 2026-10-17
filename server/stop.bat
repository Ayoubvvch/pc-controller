@echo off
cd /d "%~dp0"
title PC Controller - Stop Server

echo ========================================================
echo          PC Controller Windows Server - Stopping
echo ========================================================
echo.

powershell -NoProfile -ExecutionPolicy Bypass -Command "$procs = Get-CimInstance Win32_Process | Where-Object { $_.CommandLine -like '*server.py*' -or $_.CommandLine -like '*watchdog.py*' }; if ($procs) { $procs | ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }; Write-Host '[OK] PC Controller Server and Watchdog stopped successfully.' -ForegroundColor Green } else { Write-Host '[INFO] No running PC Controller Server or Watchdog was found.' -ForegroundColor Yellow }"

echo.
echo ========================================================
timeout /t 3 >nul
