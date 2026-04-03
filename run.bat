@echo off
REM run.bat [prompt-file]
REM If a prompt file is provided, read it and set CHAT_SYSTEM_PROMPT and CHAT_AUTO_APPROVE=true, then run the app.

if "%~1"=="" (
  echo Starting in interactive mode...
  call "%~dp0gradlew.bat" :app:run
  exit /b %ERRORLEVEL%
)

set "PROMPT_FILE=%~1"
if not exist "%PROMPT_FILE%" (
  echo Prompt file not found: %PROMPT_FILE%
  exit /b 1
)

powershell -NoProfile -ExecutionPolicy Bypass -Command "$p = Get-Content -Raw -LiteralPath '%PROMPT_FILE%'; $env:CHAT_SYSTEM_PROMPT = $p; $env:CHAT_AUTO_APPROVE = 'true'; & '%~dp0gradlew.bat' ':app:run'"
