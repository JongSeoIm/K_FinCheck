@echo off
title K-FinCheck
rem Starts Kafka, then the backend, frontend and AI server in their own windows.
cd /d "%~dp0"
start "Kafka" "%~dp0start-kafka.cmd"

echo Waiting for Kafka (port 9092)...
set /a tries=0
:wait
powershell -NoProfile -Command "if (Get-NetTCPConnection -State Listen -LocalPort 9092 -ErrorAction SilentlyContinue) { exit 0 } else { exit 1 }" >nul
if not errorlevel 1 goto ready
set /a tries+=1
if %tries% geq 30 goto failed
timeout /t 2 /nobreak >nul
goto wait

:failed
echo [error] Kafka did not start. Check the Kafka window.
pause
exit /b 1

:ready
echo Kafka is ready. Starting the other servers...
start "Backend" "%~dp0start-backend.cmd"
start "Frontend" "%~dp0start-frontend.cmd"
start "AI Server" "%~dp0start-ai.cmd"
timeout /t 3 /nobreak >nul
