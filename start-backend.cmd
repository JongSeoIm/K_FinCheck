@echo off
title Backend 8080
rem Spring Boot (http://localhost:8080). Start Kafka first.
cd /d "%~dp0backend"
if not exist "src\main\resources\application-local.properties" (
  echo [error] backend\src\main\resources\application-local.properties is missing
  pause
  exit /b 1
)
if exist "C:\Program Files\Java\jdk-21\bin\java.exe" set "JAVA_HOME=C:\Program Files\Java\jdk-21"
call mvnw.cmd spring-boot:run
pause
