@echo off
chcp 65001 >nul
rem 프론트엔드 실행 (http://localhost:8086)
cd /d "%~dp0frontend"
if not exist "node_modules" (
  echo 처음 실행이라 패키지를 설치합니다...
  call npm ci
)
call npm run dev
