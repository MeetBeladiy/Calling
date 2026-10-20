@echo off
setlocal
title Calling App Backend
cd /d "%~dp0backend"

where node >nul 2>nul
if errorlevel 1 (
  echo Node.js is not installed or is not available in PATH.
  echo Install Node.js 20 or newer, then run this file again.
  pause
  exit /b 1
)

if not exist "node_modules" (
  echo Installing backend packages...
  call npm install
  if errorlevel 1 goto :failed
)

echo Building the Calling App backend...
call npm run build
if errorlevel 1 goto :failed

echo.
echo Calling App server is starting on port 8080.
echo Keep this window open while testing the app.
echo Press Ctrl+C to stop the server.
echo.
call npm start
exit /b %errorlevel%

:failed
echo.
echo The Calling App backend could not be started.
pause
exit /b 1
