@echo off
REM Build the release APK and drop it into ..\dist.
REM dl.google.com is unreachable here, so dependencies come from the Aliyun mirror;
REM the Android SDK and Gradle both live under %~dp0..\tools.
REM Keep this file ASCII-only: cmd.exe parses .bat with the OEM codepage (GBK here),
REM and a UTF-8 Chinese character whose last byte looks like a GBK lead byte will
REM swallow the following newline, gluing two commands together.
REM Also note %~dp0 ends in a backslash: inside a quoted argument that backslash
REM escapes the closing quote and eats the rest of the command line, so append a dot.
setlocal
set JAVA_HOME=C:\Program Files\Java\jdk-17
set GRADLE=%~dp0..\tools\gradle-8.11.1\bin\gradle.bat
call "%GRADLE%" -p "%~dp0." assembleRelease --no-daemon %*
if errorlevel 1 (
  echo.
  echo [FAILED] build did not pass.
  exit /b 1
)
echo.
echo [OK] APK: %~dp0app\build\outputs\apk\release\app-release.apk
copy /Y "%~dp0app\build\outputs\apk\release\app-release.apk" "%~dp0..\dist\QuizHelper-1.0-arm64.apk" >nul
echo [EXPORTED] %~dp0..\dist\QuizHelper-1.0-arm64.apk
