@echo off
rem AI server (FastAPI, http://127.0.0.1:8087)
rem Keys are read from ai-server\.env, then backend\.env (OPENAI_API_KEY).
rem This file uses ASCII only: Korean text + chcp in a .cmd breaks parsing in Korean folder paths.
set PYTHONUTF8=1
set PYTHONIOENCODING=utf-8
cd /d "%~dp0ai-server"
if not exist ".venv\Scripts\python.exe" (
  echo [setup] Creating Python virtual env and installing packages...
  python -m venv .venv
  ".venv\Scripts\python.exe" -m pip install -r requirements.txt
)
".venv\Scripts\python.exe" -m uvicorn main:app --host 127.0.0.1 --port 8087
