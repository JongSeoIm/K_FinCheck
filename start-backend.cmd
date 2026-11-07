@echo off
chcp 65001 >nul
rem 백엔드 실행 (포트 8085). 비밀 값은 backend\.env 에서 읽는다.
rem 이 PC에 JDK가 여러 개 있으면 JDK 21을 우선 사용한다.
if exist "C:\Program Files\Java\jdk-21\bin\java.exe" set "JAVA_HOME=C:\Program Files\Java\jdk-21"
if not defined JAVA_HOME (
  echo [안내] JAVA_HOME 이 없습니다. JDK 21을 설치하고 JAVA_HOME 을 지정하세요.
  pause
  exit /b 1
)
set "PATH=%JAVA_HOME%\bin;%PATH%"
echo 사용 JDK: %JAVA_HOME%

cd /d "%~dp0backend"
if not exist ".env" (
  copy ".env.example" ".env" >nul
  echo [안내] backend\.env 를 만들었습니다. API 키와 DB 비밀번호를 넣은 뒤 다시 실행하세요.
  pause
  exit /b 1
)
call mvnw.cmd spring-boot:run
