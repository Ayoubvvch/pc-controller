@echo off
cd /d "%~dp0"
title PC Controller - Remove Autostart

echo ========================================================
echo       PC Controller - Remove Autostart
echo ========================================================
echo.
python uninstall_autostart.py
echo.
echo ========================================================
pause
