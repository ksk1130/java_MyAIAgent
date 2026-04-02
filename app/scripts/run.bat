@echo off
REM Launch script for ChatCLI application
REM このスクリプトはJava 21のポータブル版JREを使用します

setlocal enabledelayedexpansion

REM Get the directory where this script is located
for /f "delims=" %%i in ('cd') do set SCRIPT_DIR=%%i

REM Set paths
set JRE_DIR=%SCRIPT_DIR%\jre
set JAVA_EXE=%JRE_DIR%\bin\java.exe
set LIB_DIR=%SCRIPT_DIR%\lib
set MAIN_JAR=%LIB_DIR%\app.jar

REM Check if JRE exists
if not exist "%JAVA_EXE%" (
    echo エラー: JREが見つかりません: %JAVA_EXE%
    pause
    exit /b 1
)

REM Check if main JAR exists
if not exist "%MAIN_JAR%" (
    echo エラー: JAR ファイルが見つかりません: %MAIN_JAR%
    pause
    exit /b 1
)

REM Build classpath
setlocal
for %%f in ("%LIB_DIR%\*.jar") do (
    if "!CLASSPATH!"=="" (
        set "CLASSPATH=%%f"
    ) else (
        set "CLASSPATH=!CLASSPATH!;%%f"
    )
)

REM Launch application with UTF-8 encoding
echo ChatCLI を起動しています...
"%JAVA_EXE%" -Dfile.encoding=UTF-8 -cp "%CLASSPATH%" org.example.ChatCLI %*

endlocal
