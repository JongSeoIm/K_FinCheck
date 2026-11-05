@echo off
title Frontend 3000
rem Next.js (http://localhost:3000)
cd /d "%~dp0front_end"
if not exist "node_modules" (
  echo [setup] Installing packages...
  call npm ci
)
call npm run dev
pause
