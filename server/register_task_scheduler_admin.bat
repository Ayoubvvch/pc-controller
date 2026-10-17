@echo off
:: Batch script to register PC Remote Server into Windows Task Scheduler with Elevation
net session >nul 2>&1
if %errorlevel% neq 0 (
    echo Requesting Administrator privileges...
    powershell -Command "Start-Process cmd -ArgumentList '/c \"\"%~dp0register_task_scheduler_admin.bat\"\"' -Verb RunAs"
    exit /b
)

echo ========================================================
echo Registering PC Remote Server in Task Scheduler...
echo ========================================================

cd /d "%~dp0"
python setup_autostart.py

echo.
timeout /t 5
