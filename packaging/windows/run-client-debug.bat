@echo off
rem MQTT H.264 video receiver - debug launcher (console stays open to show logs).
setlocal
cd /d "%~dp0"

set "JAVA="
if defined JAVA_HOME if exist "%JAVA_HOME%\bin\java.exe" set "JAVA=%JAVA_HOME%\bin\java.exe"
if not defined JAVA where java.exe >nul 2>nul && set "JAVA=java.exe"
if not defined JAVA (
  echo [ERROR] Java 17+ not found. Set JAVA_HOME or add java to PATH, then retry.
  pause
  exit /b 1
)

"%JAVA%" -Dfile.encoding=UTF-8 -jar "%~dp0mqtt-h264-client.jar"
pause
