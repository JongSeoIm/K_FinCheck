@echo off
title AI Server 8000
rem FastAPI (http://localhost:8000)
cd /d "%~dp0ai_server"
if not exist ".env" (
  echo [error] ai_server\.env is missing
  pause
  exit /b 1
)
if not exist ".venv\Scripts\python.exe" (
  echo [setup] Creating virtual env and installing packages...
  python -m venv .venv
  ".venv\Scripts\python.exe" -m pip install -r requirements.txt
)
".venv\Scripts\python.exe" -m uvicorn app.main:app --port 8000
pause
