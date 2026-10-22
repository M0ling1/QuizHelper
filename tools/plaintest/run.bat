@echo off
rem PlainText sample test. Runs on the PC, no phone needed.
rem The class deliberately imports nothing from android.*, so plain javac/java works.
rem NOTE: keep this file ASCII-only, CRLF line endings - cmd.exe parses .bat with the GBK codepage.

setlocal
chcp 65001 >nul
set HERE=%~dp0
set ROOT=%HERE%..\..
set JAVA_HOME=C:\Program Files\Java\jdk-17
set OUT=%TEMP%\qh_plaintest

if not exist "%OUT%" mkdir "%OUT%"
"%JAVA_HOME%\bin\javac.exe" -encoding UTF-8 -d "%OUT%" -sourcepath "%ROOT%\app\src\main\java" "%HERE%PtTest.java"
if errorlevel 1 goto fail
"%JAVA_HOME%\bin\java.exe" -Dfile.encoding=UTF-8 -cp "%OUT%" com.moling.quizhelper.PtTest

echo.
pause
exit /b 0

:fail
echo compile failed
pause
exit /b 1
