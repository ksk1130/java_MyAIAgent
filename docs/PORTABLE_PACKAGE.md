# ポータブルパッケージ実行ガイド

`app/build/distribution/app/` に生成されるポータブルパッケージは、Java 環境がインストールされていないマシンでもアプリケーションを実行可能にします。

## 特徴

- ✅ Java 21 をポータブル化（jlink で最小限化）
- ✅ 全ての依存ライブラリを同梱
- ✅ `run.bat` で簡単起動
- ✅ インストール不要（他のマシンへも配布可能）

## ディレクトリ構成

```
app/build/distribution/app/
├── run.bat          (起動スクリプト - **メインクラス: org.example.App**)
├── lib/             (JAR ファイルと依存ライブラリ)
├── jre/             (Java 21 ポータブル実行環境)
└── README.txt       (簡易ガイド)
```

## 実行方法

### 前提条件

```powershell
# OPENAI_API_KEY を環境変数に設定
$env:OPENAI_API_KEY = "sk-..."
```

### 基本的な起動

```powershell
# ディレクトリに移動
cd app\build\distribution\app\

# デフォルト: ChatCLI 対話モード
.\run.bat

# または明示的に指定
.\run.bat chat
```

### Agent対応版の起動

```powershell
# AgentChatCLI 対話モード
.\run.bat agent chat

# AgentChatCLI で /filesearch コマンドが利用可能
# 例:
# User: /filesearch . report
# → ファイル検索・選択・要約ワークフロー実行
```

### 単発メッセージモード

```powershell
# ChatCLI 単発モード
.\run.bat "このコードを解析してください"

# AgentChatCLI 単発モード
.\run.bat agent "このコードを解析してください"
```

## run.bat の動作

```batch
@echo off
REM メインクラス: org.example.App
REM App が ChatCLI/AgentChatCLI をルーティング

set JAVA_EXE=.\jre\bin\java.exe
set CLASSPATH=[lib内の全JAR]

REM 引数がない場合: ChatCLI 対話モード
if "%1"=="" (
    %JAVA_EXE% -Dfile.encoding=UTF-8 -cp "%CLASSPATH%" org.example.App chat
) else (
    REM 引数がある場合: App に直接渡す
    %JAVA_EXE% -Dfile.encoding=UTF-8 -cp "%CLASSPATH%" org.example.App %*
)
```

## App.java のルーティング

```java
public static void main(String[] args) throws Exception {
    if (args.length > 0 && "agent".equalsIgnoreCase(args[0])) {
        // Agent 対応版を使用
        AgentChatCLI.main(Arrays.copyOfRange(args, 1, args.length));
    } else {
        // 従来版を使用（デフォルト）
        ChatCLI.main(args);
    }
}
```

## トラブルシューティング

### エラー: ClassNotFoundException: org.example.App

**原因**: クラスパスが正しく構築されていない

**解決方法**:
```powershell
# ディレクトリを確認
ls lib\*.jar | Select-Object -First 5

# app.jar が含まれているか確認
ls lib\app.jar
```

### エラー: JRE が見つかりません

**原因**: jre ディレクトリが欠落している

**解決方法**:
```powershell
# ポータブルパッケージを再生成
cd C:\...\java_MyAIAgent
./gradlew.bat createPortablePackage
```

### エラー: OPENAI_API_KEY が未設定

**原因**: OpenAI API キーが環境変数に設定されていない

**解決方法**:
```powershell
# API キーを設定（Windows PowerShell）
$env:OPENAI_API_KEY = "sk-your-api-key-here"

# または環境変数として永続的に設定
[Environment]::SetEnvironmentVariable("OPENAI_API_KEY", "sk-...", "User")
```

## 動作検証

```powershell
cd app\build\distribution\app\

# JRE バージョン確認
.\jre\bin\java -version

# メインクラスが正しく実行されるか確認
.\run.bat --help 2>&1 | Select-String "Usage\|Error"
```

## 配布方法

1. `app/build/distribution/app/` ディレクトリ全体をコピー
2. OPENAI_API_KEY を設定したマシンで `run.bat` を実行
3. インストール不要で動作

## パッケージサイズ

- JRE (jlink 最小化): 約 200-300 MB
- ライブラリ: 約 50-100 MB
- 合計: 約 250-400 MB

## 関連ドキュメント

- [README.md](../README.md) - 全体的な使用方法
- [AgentChatCLI.md](AgentChatCLI.md) - Agent 機能の詳細
