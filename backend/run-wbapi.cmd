@echo off
rem Launches the wbapi module from the script's directory.
cd /d "%~dp0"
call .\mvnw.cmd -q -pl workbench -am install -DskipTests
if errorlevel 1 exit /b 1
call .\mvnw.cmd -pl wbapi spring-boot:run
