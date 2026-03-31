# myaiagent

このリポジトリは LangChain4j/OpenAI を使った小さな Java CLI サンプルです。ChatCLI を中心に、対話・単発実行・会話履歴（メモリ）をサポートします。

## 目次
- 前提
- 環境変数
- ビルド
- 実行: 対話モード
- 実行: 単発メッセージ
- 会話履歴 (メモリ)
- 実装の概要

## 前提
- JDK 21 がインストールされていること。
- リポジトリルートから Gradle ラッパーを使って操作します。

## 環境変数
- OPENAI_API_KEY（必須）: OpenAI の API キー
- OPENAI_API_URL（任意）: カスタムエンドポイント（省略すると OpenAI の既定エンドポイントを使用）
- OPENAI_MODEL（任意）: 使用モデル（既定: gpt-4o-mini）
- CHAT_MEMORY_WINDOW（任意）: 会話履歴ウィンドウのサイズ（既定: 10）

Windows (PowerShell) 例:
```
$env:OPENAI_API_KEY = "..."
$env:OPENAI_MODEL = "gpt-4o-mini"
$env:CHAT_MEMORY_WINDOW = "10"
```

## ビルド
```
.\gradlew.bat build
```

## 実行 - 対話モード（推奨）
対話モードは端末の標準入力を使って連続的に会話します。installDist を使うと対話が安定します:
```
.\gradlew.bat :app:installDist
.\app\build\install\app\bin\app.bat chat
```
プロンプトで入力し、`exit` または `quit` を入力すると終了します。

## 実行 - 単発メッセージ（CI や非対話環境向け）
Gradle の --args を使って一度だけメッセージを送信できます:
```
.\gradlew.bat :app:run --args="chat Hello, please reply"
```

## 会話履歴 (メモリ)
- 本プロジェクトは langchain4j の MessageWindowChatMemory を使って会話履歴を管理します。
- CHAT_MEMORY_WINDOW で直近 n 件を保持し、Assistant が内部で履歴を参照して応答します。

## 実装の概要
- app/src/main/java/org/example/ChatCLI.java: メインの CLI 実装。langchain4j の OpenAiChatModel と MessageWindowChatMemory を使い、AiServices を通じて Assistant を生成します。
