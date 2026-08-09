@echo off
cd /d "%~dp0\.."
python tools\ws_server.py
if errorlevel 1 pause
