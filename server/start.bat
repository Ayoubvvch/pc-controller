@echo off
cd /d "%~dp0"
title PC Controller - Start Server

echo ========================================================
echo          PC Controller Windows Server (v2.0)
echo ========================================================
echo.

python -c "import sys, subprocess, os; p = os.path.join(os.path.dirname(sys.executable), 'pythonw.exe'); p = p if os.path.exists(p) else sys.executable; subprocess.Popen([p, 'watchdog.py'], creationflags=0x08000000 | 0x00000008)" 2>nul

if errorlevel 1 (
    echo [ERROR] Python was not found in your PATH.
    echo Please install Python 3.8+ and check 'Add Python to PATH' during setup.
    echo.
    pause
    exit /b 1
)

echo [OK] PC Controller Server & Watchdog started in the background.
echo.
echo   • HTTP API Port: 5050
echo   • UDP Auto-Discovery: Port 5051
echo.
echo Open the Android app to connect automatically!
echo You can close this window at any time.
echo ========================================================
timeout /t 3 >nul
