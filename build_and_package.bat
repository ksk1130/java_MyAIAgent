@echo off
setlocal enabledelayedexpansion

echo ========================================
echo MyAIAgent Build and Package Script
echo ========================================
echo.

REM Check if JAVA_HOME is set
if not defined JAVA_HOME (
    echo ERROR: JAVA_HOME environment variable is not set
    echo Please set JAVA_HOME to your JDK 21 installation directory
    exit /b 1
)

echo JAVA_HOME: %JAVA_HOME%
echo.

REM Step 1: Build the application
echo [Step 1/2] Building application...
echo.
call .\gradlew.bat build -x test
if errorlevel 1 (
    echo ERROR: Build failed
    exit /b 1
)
echo [Step 1/2] Build completed successfully
echo.

REM Step 2: Create portable package with JRE
echo [Step 2/2] Creating portable package with JRE...
echo This may take a few minutes...
echo.
call .\gradlew.bat createPortablePackage --no-daemon
if errorlevel 1 (
    echo ERROR: Portable package creation failed
    exit /b 1
)
echo [Step 2/2] Portable package creation completed successfully
echo.

echo ========================================
echo Build and Package Completed Successfully
echo ========================================
echo.
echo Distribution package location:
echo   %CD%\app\build\distribution\app\
echo.
echo Package contents:
echo   - run.bat           (Launcher script)
echo   - lib/              (JAR files and dependencies)
echo   - jre/              (Java 21 portable runtime)
echo   - README.txt        (Usage instructions)
echo.
echo To run the application:
echo   1. Navigate to: app\build\distribution\app\
echo   2. Double-click run.bat or execute: run.bat
echo.
echo Note: OPENAI_API_KEY environment variable must be set before running
echo.

pause
