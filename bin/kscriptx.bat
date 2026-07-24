@echo off
setlocal EnableExtensions
set "DIR=%~dp0"
set "JAR=%DIR%kscriptx.jar"
if not exist "%JAR%" (
  echo kscriptx.jar not found. Build with: gradlew.bat :cli:build
  exit /b 1
)

set "CP=%JAR%"
if exist "%DIR%lib\" set "CP=%JAR%;%DIR%lib\*"

REM Rebuild argv with quotes so inline scripts keep "..." literals
set "ARGS="
:collect
if "%~1"=="" goto run
set ARGS=%ARGS% "%~1"
shift
goto collect

:run
java -cp "%CP%" io.kscriptx.MainKt %ARGS%
exit /b %ERRORLEVEL%
