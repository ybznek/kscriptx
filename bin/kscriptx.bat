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

REM Short-lived CLI JVM tuning (warm cache hits).
set "KSX_JAVA_OPTS=-XX:TieredStopAtLevel=1 -XX:+UseSerialGC"

REM Rebuild argv with quotes so inline scripts keep "..." literals
set "ARGS="
:collect
if "%~1"=="" goto run
set ARGS=%ARGS% "%~1"
shift
goto collect

:run
java %KSX_JAVA_OPTS% %KSCRIPTX_JAVA_OPTS% -cp "%CP%" io.kscriptx.MainKt %ARGS%
exit /b %ERRORLEVEL%
