# myaiagent

LangChain4j / OpenAI を使った Java CLI ベースの AI エージェントです。
`ChatCLI` を中心に、対話・単発実行・会話履歴（メモリ）をサポートします。
LLM の Function Calling を通じてローカルのファイル検索・読み取り・コマンド実行をエージェントが自律的に行えます。

## 目次
- [前提](#前提)
- [環境変数](#環境変数)
- [ビルド](#ビルド)
- [実行: 対話モード](#実行---対話モード推奨)
- [実行: 単発メッセージ](#実行---単発メッセージci-や非対話環境向け)
- [表示機能](#表示機能)
- [LocalCommand の承認フロー](#localcommand-の承認フロー)
- [会話履歴 (メモリ)](#会話履歴-メモリ)
- [主要クラスと役割](#主要クラスと役割)
- [ツールアーキテクチャ](#ツールアーキテクチャ)

## 前提
- JDK 21 がインストールされていること。
- リポジトリルートから Gradle ラッパーを使って操作します。
- Windows 環境では PowerShell (`pwsh`) がパスに存在すること（LocalCommandTool が使用）。

## 環境変数

| 変数名 | 必須 | 説明 | 既定値 |
|---|---|---|---|
| `OPENAI_API_KEY` | ✅ | OpenAI の API キー | − |
| `OPENAI_MODEL` | | 使用モデル | `gpt-4o-mini` |
| `OPENAI_API_URL` | | カスタムエンドポイント URL | OpenAI 既定 |
| `CHAT_MEMORY_WINDOW` | | 会話履歴ウィンドウのサイズ | `50` |
| `CHAT_SYSTEM_PROMPT` | | システムプロンプトの全文 | 組み込み既定値 |
| `CHAT_NO_COLOR` | | `true` の場合、色付け表示を無効化 | `false` 相当 |

Windows (PowerShell) での設定例:
```powershell
$env:OPENAI_API_KEY = "sk-..."
$env:OPENAI_MODEL = "gpt-4o-mini"
$env:CHAT_MEMORY_WINDOW = "50"
```

## ビルド
```powershell
.\gradlew.bat build
```

テストをスキップする場合:
```powershell
.\gradlew.bat :app:build -x test
```

## 実行 - 対話モード（推奨）
`installDist` でネイティブ起動スクリプトを使うと Unicode 入力が安定します:
```powershell
.\gradlew.bat :app:installDist
.\app\build\install\app\bin\app.bat chat
```
プロンプトで入力し、`exit` または `quit` を入力すると終了します。

Gradle 経由での起動も可能です（Unicode 入力が制限される場合あり）:
```powershell
.\gradlew.bat :app:run --args="chat"
```

## 実行 - 単発メッセージ（CI や非対話環境向け）
Gradle の `--args` を使って一度だけメッセージを送信できます:
```powershell
.\gradlew.bat :app:run --args="chat このコードを解析してください"
```

## 表示機能
- AI の返答中に Markdown コードブロック（```lang ... ```）が含まれる場合、簡易シンタックスハイライトで表示されます。
- `User:` / `AI:` ラベルは色付き + 太字で表示されます。
- 色付けを無効化する場合は、`CHAT_NO_COLOR=true` を設定してください。

Windows (PowerShell) 例:
```powershell
$env:CHAT_NO_COLOR = "true"
```

## LocalCommand の承認フロー
- `LocalCommandTool` は即時実行せず、まず確認メッセージを返します。
- ユーザが `はい` / `yes` / `y` を入力した場合にのみ実行します。
- ユーザが `いいえ` / `no` / `n` / `キャンセル` を入力した場合は中止します。

対話イメージ:
```text
User: カレントディレクトリの一覧を表示して
AI: 確認: 「dir」を実行します。よろしいですか？（はい/yes で実行、いいえ/no でキャンセル）
User: はい
AI: 実行結果の要約...
```

## 会話履歴 (メモリ)
- `MessageWindowChatMemory` を使って直近 `CHAT_MEMORY_WINDOW` 件の会話を保持します。
- Assistant が履歴を参照して文脈に沿った応答を生成します。

## 主要クラスと役割

### エントリポイント
| クラス | パス | 説明 |
|---|---|---|
| `ChatCLI` | `app/src/main/java/org/example/ChatCLI.java` | CLI チャットアプリケーション本体。対話モード・単発モードを提供し、AiServices で Assistant を生成します。 |
| `App` | `app/src/main/java/org/example/App.java` | `main` メソッドを持つ起動クラス。引数に応じて `ChatCLI` へ処理を委譲します。 |

### ツールクラス（`org.example.tools` パッケージ）

AiServices に登録済み（LLM が Function Calling で呼び出し可能）:

| クラス | 説明 |
|---|---|
| `LocalCommandTool` | LLM が生成した読み取り専用コマンドを `pwsh` で実行します。`rg`・`Select-String`・`findstr`・`dir`・`ls`・`git status/log/show/diff/branch` などを想定。禁止操作パターン (`rm`, `del`, `git reset` 等) はブロックし、実行前に承認を要求します。 |
| `ImpactAnalysisTool` | テーブル名変更時の影響範囲を推移的に推定します。SQL 参照シンボルを起点に、Java/COBOL の参照を逆方向へたどって影響候補ファイルを列挙します。 |
| `FileReaderTool` | 拡張子ホワイトリスト方式でテキストファイルを読み込みます。UTF-8 → Shift_JIS の順でデコードを試みます。 |
| `FileWriterTool` | 拡張子ホワイトリスト方式でテキストファイルを書き込みます。親ディレクトリ自動作成、UTF-8 で保存します。 |
| `Calculator` | 加算・平方根など数値計算のサンプルツール。 |

AiServices 未登録（クラスのみ存在）:

| クラス | 説明 |
|---|---|
| `GrepTool` | Java 内部でファイル内容をキーワード / 正規表現検索します。メタ文字を自動判定し、正規表現モードとリテラルモードを切り替えます。 |
| `FileSearchTool` | ファイル名 glob パターンで検索します。 |
| `InteractiveFileSearchTool` | 対話型ファイル検索を行います。 |

### サンプル・動作確認クラス
| クラス | 説明 |
|---|---|
| `TF_A` | LangChain4j の基本的な API 呼び出しサンプル。 |
| `TF_B` | 会話メモリ（MessageWindowChatMemory）のサンプル。 |
| `TF_C` | AiServices と Calculator ツールを組み合わせたサンプル。 |

## ツールアーキテクチャ

```
ユーザ入力
    │
    ▼
ChatCLI#runChat()
    │
    ├─ コードブロック表示時にシンタックスハイライト（ANSI）
    │
    ├─ isSelectionInput() → 検索結果から番号選択の場合は handleSelectionInput() へ
    │
    └─ assistant.chat(input)  ← LLM Function Calling（優先）
           │
           ├─ LocalCommandTool#runSearchCommand()
           │      1回目: 実行せず確認メッセージを返却
           │      2回目: ユーザが「はい」なら実行
           │      pwsh -NoProfile -Command <LLM が生成したコマンド>
           │      出力上限 12,000 文字 / タイムアウト 20 秒
           │
           ├─ ImpactAnalysisTool#analyzeTableImpact()
           │      テーブル名を起点に SQL 参照クラスを検出
           │      Java/COBOL の呼び出し元を逆方向にたどって推移影響を列挙
           │
           ├─ FileReaderTool#readFile()
           │      ホワイトリスト拡張子のみ / 上限 20,000 文字
           │
           ├─ FileWriterTool#writeFile()
           │      ホワイトリスト拡張子のみ / 上限 100,000 文字
           │
           └─ Calculator#add() / squareRoot()
```

システムプロンプトにより LLM はファイル検索・内容調査に `LocalCommandTool` を優先的に使用します。
`rg`（ripgrep）が利用可能な環境では最も高速に動作します。
