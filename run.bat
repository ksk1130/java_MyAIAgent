@echo off
REM run.bat - Launch script for MyAIAgent
REM 使い方:
REM   run.bat chat           - ChatCLI 対話モード（従来版）
REM   run.bat agent chat     - AgentChatCLI 対話モード（Agent対応版）
REM   run.bat <message>      - ChatCLI 単発モード
REM   run.bat agent <message> - AgentChatCLI 単発モード

if "%~1"=="" (
  REM デフォルト: ChatCLI 対話モード
  echo Starting ChatCLI in interactive mode...
  call "%~dp0gradlew.bat" :app:run --args="chat"
  exit /b %ERRORLEVEL%
)

REM 引数をそのまま App.java に渡す
echo Starting application with arguments: %*
call "%~dp0gradlew.bat" :app:run --args="%*"
exit /b %ERRORLEVEL%

