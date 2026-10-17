@echo off
cd /d "%~dp0"
title PC Controller - Server Status

echo ========================================================
echo          PC Controller Windows Server - Status
echo ========================================================
echo.

powershell -NoProfile -ExecutionPolicy Bypass -Command "$procs = Get-CimInstance Win32_Process | Where-Object { $_.CommandLine -like '*server.py*' -or $_.CommandLine -like '*watchdog.py*' }; if ($procs) { Write-Host '[STATUS] PC Controller is RUNNING!' -ForegroundColor Green; $procs | Select-Object ProcessId, Name, CommandLine | Format-Table -AutoSize; try { $res = Invoke-RestMethod -Uri 'http://127.0.0.1:5050/ping' -TimeoutSec 3; Write-Host ('[PING] Health Check: ' + $res.status) -ForegroundColor Green } catch { Write-Host ('[PING] Server busy or not responding on port 5050') -ForegroundColor Red } } else { Write-Host '[STATUS] Server is NOT running. Run start.bat to start.' -ForegroundColor Red }"

echo.
echo Local IP Addresses for Android Connection:
powershell -NoProfile -ExecutionPolicy Bypass -Command "Get-NetIPAddress -AddressFamily IPv4 | Where-Object { $_.InterfaceAlias -notlike '*Loopback*' -and $_.IPAddress -notlike '169.254*' } | Select-Object IPAddress, InterfaceAlias | Format-Table -AutoSize"

echo ========================================================
pause
