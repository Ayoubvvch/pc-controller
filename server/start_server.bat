@echo off
cd /d "%~dp0"
title PC Controller - Debug Console Mode
echo ========================================================
echo Starting PC Controller in visible debug console mode...
echo (Close this window or press Ctrl+C to stop)
echo ========================================================
python server.py
pause
