@echo off
title Kafka
rem Kafka (C:\kafka). Run this first.
if not exist "C:\kafka\bin\windows\kafka-server-start.bat" (
  echo [error] Kafka not found in C:\kafka
  pause
  exit /b 1
)
cd /d C:\kafka
set KAFKA_HEAP_OPTS=-Xmx1G -Xms1G
call bin\windows\kafka-server-start.bat config\kraft\server.properties
pause
