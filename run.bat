@echo off
chcp 65001 >nul
cd /d "%~dp0"
if not exist "build\tennis-minidb.jar" (
  powershell -NoProfile -ExecutionPolicy Bypass -File ".\build.ps1"
  if errorlevel 1 exit /b 1
)
java -Dfile.encoding=UTF-8 -jar "build\tennis-minidb.jar" %*
