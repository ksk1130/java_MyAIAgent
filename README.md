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
- [FileSearchWorkflow](#filesearchworkflow-新機能) / [AgentChatCLI](#agentchatcli)
- [主要クラスと役割](#主要クラスと役割)
- [ツールアーキテクチャ](#ツールアーキテクチャ)
- **📖 ドキュメントリンク:**
  - [🔗 AgentChatCLI ガイド](docs/AgentChatCLI.md)
  - [🔗 FileSearchWorkflow ガイド](docs/FileSearchWorkflow.md)
  - [🔗 引数処理ガイド](docs/ARGUMENT_HANDLING.md)
  - [🔗 実装ガイド](docs/IMPLEMENTATION_GUIDE.md)
  - [🔗 ポータブルパッケージ](docs/PORTABLE_PACKAGE.md)

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
| `CHAT_AGENT_MAX_STEPS` | | 1ターン内のエージェント最大ステップ数（1〜10） | `5` |
| `CHAT_SYSTEM_PROMPT` | | システムプロンプトの全文 | 組み込み既定値 |
| `CHAT_NO_COLOR` | | `true` の場合、色付け表示を無効化 | `false` 相当 |

Windows (PowerShell) での設定例:
```powershell
$env:OPENAI_API_KEY = "sk-..."
$env:OPENAI_MODEL = "gpt-4o-mini"
$env:CHAT_MEMORY_WINDOW = "50"
$env:CHAT_AGENT_MAX_STEPS = "5"
```

## ビルド
```powershell
.\gradlew.bat build
```

テストをスキップする場合:
```powershell
.\gradlew.bat :app:build -x test
```

## ポータブルパッケージ作成（配布向け）
JRE を同梱したポータブル版パッケージを作成できます：
```powershell
.\gradlew.bat createPortablePackage
```

出力先: `app/build/distribution/app/`

このパッケージは以下を含みます：
- `run.bat` - アプリケーション起動スクリプト（自動的に App.java をルーティング）
- `lib/` - JAR ファイルと依存ライブラリ
- `jre/` - Java 21 ポータブル実行環境（jlink 生成）
- `README.txt` - 使用方法

**使用方法：**
```powershell
# ポータブルパッケージディレクトリに移動
cd app\build\distribution\app\

# 従来版 ChatCLI - 対話モード（デフォルト）
.\run.bat

# または明示的に指定
.\run.bat chat

# Agent対応版 - 対話モード
.\run.bat agent chat

# Agent対応版 - ファイル検索コマンド
.\run.bat agent
# （起動後に /filesearch コマンドが利用可能）
```

**注意:**
- JRE は同梱済み（別途インストール不要）
- OPENAI_API_KEY 環境変数を設定してから実行してください
- ポータブルパッケージは他のマシンに配布可能です

詳細は [docs/PORTABLE_PACKAGE.md](docs/PORTABLE_PACKAGE.md) を参照してください。

## 実行 - 対話モード（推奨）

### 従来版 ChatCLI
```powershell
.\gradlew.bat :app:run --args="chat"
```

### Agent対応版 AgentCLI（新機能）
```powershell
.\gradlew.bat :app:run --args="agent chat"
```

`installDist` でネイティブ起動スクリプトを使うと Unicode 入力が安定します:
```powershell
.\gradlew.bat :app:installDist
# 従来版
.\app\build\install\app\bin\app.bat chat
# Agent対応版
.\app\build\install\app\bin\app.bat agent chat
```

プロンプトで入力し、`exit` または `quit` を入力すると終了します。

## 実行 - 単発メッセージ（CI や非対話環境向け）
Gradle の `--args` を使って一度だけメッセージを送信できます:

### 従来版 ChatCLI
```powershell
.\gradlew.bat :app:run --args="このコードを解析してください"
```

### Agent対応版 AgentCLI
```powershell
.\gradlew.bat :app:run --args="agent このコードを解析してください"
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

### 履歴クリア
対話モード中に `/clear` コマンドを入力することで、会話履歴と入力コマンド履歴をリセットできます：

```text
User: /clear
AI: ✓ 会話履歴と入力履歴をクリアしました
```

このコマンドは以下を実行します：
- LLM の会話メモリ（`chatMemory`）をクリア
- 入力コマンド履歴ファイル（`~/.ai_history`）を削除

新規の会話を開始したい場合や、過去の文脈から独立した質問をしたい場合に便利です。

## FileSearchWorkflow (新機能)

ユーザーの自然言語指示に基づいて、ファイルを自動検索・選択・要約するワークフロー**エージェント**です。
AIが自然言語からディレクトリとキーワードを抽出し、ユーザーが対話的にファイルを選択したら、内容をAIが要約します。

### 主要機能

1. **Intent 抽出**: ユーザーの自然言語から、検索対象のディレクトリとキーワードをAIが自動抽出
2. **ファイル検索**: 指定ディレクトリを再帰的に走査し、キーワードを含むファイルを検出
3. **対話的ファイル選択**: 見つかったファイルを番号付きリストで表示し、ユーザーが選択
4. **ファイル読み込み**: 選択されたファイルの内容をテキストとして読み込み
5. **AI 要約**: LLMを使用して、ファイル内容を自動要約

### AgentChatCLI での使用（推奨）

Agent対応版 (`./gradlew.bat :app:run --args="agent chat"`) を使用することで、CLI内から FileSearchWorkflow を直接利用できます：

**ファイル検索コマンド:**
```
User: /filesearch test_sample report
```

このコマンドで以下の処理が自動実行されます：
1. `test_sample` ディレクトリから `report` を含むファイルを検索
2. 見つかったファイル一覧をユーザーに表示
3. ユーザーが番号で選択
4. 選択されたファイルの内容をAIで要約

**対話例:**
```
User: /filesearch . config
[エージェント処理中...]
見つかったファイル:
========================================
1. C:\...\app\config.yml
2. C:\...\database.conf
3. C:\...\settings.json
========================================
要約したいファイルの番号を入力してください (1-3): 1
[ファイル読み込み中...]
[AI要約中...]
AI: このファイルはアプリケーション設定を定義しています...
```

### 直接利用（Java コード）

FileSearchWorkflow を Java プログラムから直接利用することもできます：

```java
import dev.langchain4j.model.openai.OpenAiChatModel;
import org.example.agents.FileSearchWorkflow;

public class Example {
    public static void main(String[] args) throws Exception {
        // OpenAiChatModel の初期化
        OpenAiChatModel model = OpenAiChatModel.builder()
            .apiKey(System.getenv("OPENAI_API_KEY"))
            .modelName("gpt-4o-mini")
            .build();

        // ワークフローの作成
        FileSearchWorkflow workflow = new FileSearchWorkflow(model);

        // ユーザーの指示に基づいてワークフロー実行
        String userInput = "Documents ディレクトリから report というキーワードを含むファイルを探して";
        String summary = workflow.executeWorkflow(userInput);

        System.out.println("要約:\n" + summary);
    }
}
```

        System.out.println("要約:\n" + summary);
    }
}
```

### 設計パターン

- **Intent 抽出**: `@UserMessage` アノテーション付き `AiServices` インタフェース
- **ファイル検索**: Java 標準 `Files.walk()` による非AI処理
- **ユーザー対話**: `BufferedReader` による stdin
- **AI 要約**: `@UserMessage` アノテーション付き `AiServices` インタフェース

詳細は以下のドキュメントを参照してください：
- [AgentChatCLI ユーザーガイド](docs/AgentChatCLI.md) - コマンドリファレンス
- [FileSearchWorkflow API 仕様](docs/FileSearchWorkflow.md)
- [実装ガイド - LangChain4j設計パターン](docs/IMPLEMENTATION_GUIDE.md)

## 複数行入力
- `Enter` は送信ではなく改行として扱います。
- `Ctrl+D` で入力全体を送信します。
- 入力が空の状態で `Ctrl+D` を押すと対話モードを終了します。
- `Ctrl+C` を押すと、その時点までの入力を破棄してやり直せます。

例:
```text
User: 1行目
... 2行目
... （Ctrl+D で送信）
AI: （2行を連結した入力として処理）
```

## 主要クラスと役割

### エントリポイント
| クラス | パス | 説明 |
|---|---|---|
| `App` | `app/src/main/java/org/example/App.java` | アプリケーションの起動クラス。引数 `agent` の有無で ChatCLI または AgentChatCLI を選択します。 |
| `ChatCLI` | `app/src/main/java/org/example/ChatCLI.java` | 従来版 CLI チャットアプリケーション。対話モード・単発モードを提供し、AiServices で Assistant を生成します。 |
| `AgentChatCLI` | `app/src/main/java/org/example/AgentChatCLI.java` | **Agent対応版** - FileSearchWorkflow などのエージェント機能をサポート。`/filesearch` コマンドで対話的ファイル検索が可能です。 |

### ツールクラス（`org.example.tools` パッケージ）

AiServices に登録済み（LLM が Function Calling で呼び出し可能）:

| クラス | 説明 |
|---|---|
| `LocalCommandTool` | LLM が生成した読み取り専用コマンドを `pwsh` で実行します。`rg`・`Select-String`・`findstr`・`dir`・`ls`・`git status/log/show/diff/branch` などを想定。禁止操作パターン (`rm`, `del`, `git reset` 等) はブロックし、実行前に承認を要求します。 |
| `ImpactAnalysisTool` | テーブル名変更時の影響範囲を推移的に推定します。SQL 参照シンボルを起点に、Java/COBOL の参照を逆方向へたどって影響候補ファイルを列挙します。`analyzeTableImpact(table)` に加え、`analyzeTableImpactInRoot(table, rootDir)` で探索ルート指定も可能です。 |
| `FileReaderTool` | 拡張子ホワイトリスト方式でテキストファイルを読み込みます。UTF-8 → Shift_JIS の順でデコードを試みます。 |
| `FileWriterTool` | 拡張子ホワイトリスト方式でテキストファイルを書き込みます。親ディレクトリ自動作成、UTF-8 で保存します。 |
| `Calculator` | 加算・平方根など数値計算のサンプルツール。 |

### エージェント・ワークフロー

| クラス | パス | 説明 |
|---|---|---|
| `FileSearchWorkflow` | `app/src/main/java/org/example/agents/FileSearchWorkflow.java` | ファイル検索・選択・要約ワークフロー。内部で `IntentExtractor` (AI) と `FileSummarizer` (AI) を使用。5段階のステップを自動実行します。 |

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

### ChatCLI（従来版）- Function Calling ベース

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

### AgentChatCLI（Agent対応版）- ワークフローベース

```
ユーザ入力
    │
    ├─ 通常のチャット
    │      ↓
    │  assistant.chat(input)  ← Function Calling（ChatCLI と同じ）
    │
    └─ /filesearch コマンド
           ↓
       FileSearchWorkflow#executeWorkflow()
           │
           ├─ [1] IntentExtractor (AI)
           │       ↓ (ディレクトリ・キーワードを抽出)
           │
           ├─ [2] FileSearchAgent (Files.walk())
           │       ↓ (ファイル検索)
           │
           ├─ [3] FileSelector (ユーザー対話)
           │       ↓ (ファイル選択)
           │
           ├─ [4] FileReadAgent (Files.readString())
           │       ↓ (ファイル読み込み)
           │
           └─ [5] FileSummarizer (AI)
                   ↓ (要約生成)
                 ユーザーへ結果返却
```

### 特徴

- **ChatCLI**: 従来の Function Calling 中心。LLM が自動的に適切なツールを選択
- **AgentChatCLI**: Function Calling + Agent ワークフロー。両方の利点を兼ね備える

システムプロンプトにより LLM はファイル検索・内容調査に `LocalCommandTool` を優先的に使用します。
`rg`（ripgrep）が利用可能な環境では最も高速に動作します。

