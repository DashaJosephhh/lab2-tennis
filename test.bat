@echo off
chcp 65001 >nul
cd /d "%~dp0"
powershell -NoProfile -ExecutionPolicy Bypass -File ".\build.ps1"
if errorlevel 1 exit /b 1
java -Dfile.encoding=UTF-8 -cp "build\classes;build\test-classes" tennisdb.DatabaseTest
