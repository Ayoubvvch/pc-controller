@echo off
cd /d "%~dp0"
title PC Controller - Install Autostart

echo ========================================================
echo       PC Controller - Install 24/7 Autostart
echo ========================================================
echo.
python setup_autostart.py
echo.
echo ========================================================
pause
