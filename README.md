# myaiagent

LangChain4j / OpenAI を使った Java CLI ベースの AI エージェントです。
`ChatCLI` を中心に、対話・単発実行・会話履歴（メモリ）をサポートします。
## 目次
- [前提](#前提)
- [環境変数](#環境変数)
- [主要クラスと役割](#主要クラスと役割)
- [ツールアーキテクチャ](#ツールアーキテクチャ)
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
| `OPENAI_API_URL` | | カスタムエンドポイント URL（未使用） | − |
| `CHAT_MEMORY_WINDOW` | | 会話履歴ウィンドウのサイズ | `100` |
| `CHAT_AGENT_MAX_STEPS` | | 1ターン内のエージェント最大ステップ数（1〜10） | `5` |
| `CHAT_SYSTEM_PROMPT` | | システムプロンプトの全文 | 組み込み既定値 |
| `CHAT_AUTO_APPROVE` | | One-shot 実行時にローカルコマンドの自動承認を有効にする | `true` |
| `CHAT_NO_COLOR` | | `true` の場合、色付け表示を無効化 | `false` 相当 |

Windows (PowerShell) での設定例:
```powershell
$env:OPENAI_API_KEY = "sk-..."
$env:OPENAI_MODEL = "gpt-4o-mini"
$env:CHAT_MEMORY_WINDOW = "100"
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
.\gradlew.bat createPortablePackage
```

出力先: `app/build/distribution/app/`

このパッケージは以下を含みます：
- `run.bat` - アプリケーション起動スクリプト
- `lib/` - JAR ファイルと依存ライブラリ
- `jre/` - Java 21 ポータブル実行環境（jlink 生成）
- `README.txt` - 使用方法

**使用方法：**
```powershell
# ポータブルパッケージディレクトリに移動
cd app\build\distribution\app\

# ChatCLI - 対話モード
.\run.bat

# ChatCLI - ファイル検索機能の利用（起動後に利用可能）
# > /filesearch src keyword
```

**注意:**
- JRE は同梱済み（別途インストール不要）
- OPENAI_API_KEY 環境変数を設定してから実行してください
- ポータブルパッケージは他のマシンに配布可能です

詳細は [docs/PORTABLE_PACKAGE.md](docs/PORTABLE_PACKAGE.md) を参照してください。

## 実行 - 対話モード（推奨）

```powershell
.\gradlew.bat :app:run
```
`installDist` でネイティブ起動スクリプトを使うと Unicode 入力が安定します:
```powershell
.\gradlew.bat :app:installDist
.\app\build\install\app\bin\app.bat
```

プロンプトで入力し、`exit` または `quit` を入力すると終了します。

## 実行 - 単発メッセージ（CI や非対話環境向け）
Gradle の `--args` を使って一度だけメッセージを送信できます:

```powershell
.\gradlew.bat :app:run --args="このコードを解析してください"
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
AI: 実行結果の要約...
### 履歴クリア
対話モード中に `/clear` コマンドを入力することで、会話履歴と入力コマンド履歴をリセットできます：

AI: ✓ 会話履歴と入力履歴をクリアしました
```

- LLM の会話メモリ（`chatMemory`）をクリア



AIが自然言語からディレクトリとキーワードを抽出し、ユーザーが対話的にファイルを選択したら、内容をAIが要約します。
### ファイル検索ワークフロー（/filesearch）の使用
LLM（Function Calling）による自律実行に加えて、専用のファイル検索ワークフローを利用できます。

**ファイル検索コマンド:**
```text
User: /filesearch test_sample report
```

**対話例:**
```
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

### ヘルプコマンド (/help)

対話モードで `/help` を入力すると、利用可能なスラッシュコマンドと簡単な説明が表示されます。例:

```text
User: /help
```

このコマンドは対話中に使用してください。表示されるコマンド例には `exit`（終了）、`/clear`（履歴クリア）、`/filesearch`（ファイル検索ワークフロー）、`/plan`（タスクの自律実行）などが含まれます。より詳しいコマンドリファレンスは `docs/ChatCLI.md` を参照してください。

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
- [ChatCLI ユーザーガイド](docs/ChatCLI.md) - コマンドリファレンス
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
| `ChatCLI` | `app/src/main/java/org/example/ChatCLI.java` | アプリケーションのエントリポイント。対話モード・単発モード・`/filesearch` の全てを提供します。 |

### ツールクラス（`org.example.tools` パッケージ）

AiServices に登録済み（LLM が Function Calling で呼び出し可能）:

| クラス | 説明 |
|---|---|
| `LocalCommandTool` | LLM が生成した読み取り専用コマンドを `pwsh` で実行します。`rg`・`Select-String`・`findstr`・`dir`・`ls`・`git status/log/show/diff/branch` などを想定。禁止操作パターン (`rm`, `del`, `git reset` 等) はブロックし、実行前に承認を要求します。 |
| `ImpactAnalysisTool` | テーブル名変更時の影響範囲を推移的に推定します。SQL 参照シンボルを起点に、Java/COBOL の参照を逆方向へたどって影響候補ファイルを列挙します。`analyzeTableImpact(table)` に加え、`analyzeTableImpactInRoot(table, rootDir)` で探索ルート指定も可能です。 |
| `FileReaderTool` | 拡張子ホワイトリスト方式でテキストファイルを読み込みます。UTF-8 → Shift_JIS の順でデコードを試みます。 |
| `FileWriterTool` | 拡張子ホワイトリスト方式でテキストファイルを書き込みます。親ディレクトリ自動作成、UTF-8 で保存します。（ファイル全体の上書き用） |
| `FileEditorTool` | 拡張子ホワイトリスト方式でテキストファイルの一部のみを置換します。指定文字列がファイル内で一つだけ完全に一致する場合のみ安全に置換します。（部分的な編集用） |
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

## COBOL 依存関係分析ツール

### 概要
COBOL プログラムの依存関係を自動検出し、Apache Derby データベースに登録するツール。
スキーマ変更時の影響範囲分析や、プログラム間の呼び出し関係を可視化します。

### 実行方法

**デフォルトパスで実行：**
```powershell
.\gradlew.bat app:runCobolAnalyzer
```

**カスタムパスで実行（推奨）：**
```powershell
.\gradlew.bat app:runCobolAnalyzer --args="app/src/main/resources/cobol"
```

**DB をリセットして実行（--clean オプション）：**
```powershell
# 重複キーエラーが出た場合は、DB を削除してから再実行
.\gradlew.bat app:runCobolAnalyzer --args="--clean app/src/main/resources/cobol"
```

**COPY ディレクトリを明示指定して実行（自動探索より優先）：**
```powershell
.\gradlew.bat app:runCobolAnalyzer --args="--copyDir=app/src/main/resources/copy app/src/main/resources/cobol"
```

`--copyDir=...` を指定すると、COPY ブック探索時にそのディレクトリを最優先で参照します。
同名の COPY ブックが自動探索先にも存在する場合は、`--copyDir` 側が優先されます。

`--clean` フラグを指定すると、`cobol_dependencies` ディレクトリを削除してから新規作成します。
複数回実行する場合や、既存の DB にエラーがある場合に使用してください。

または相対パスで指定：
```powershell
.\gradlew.bat app:runCobolAnalyzer --args="src/main/resources/cobol"
```

### 検出内容

| 項目 | 説明 |
|-----|------|
| **PROGRAM-ID** | 各COBOL プログラムのID を自動抽出 |
| **テーブルアクセス** | INSERT/SELECT/UPDATE/DELETE句を解析してテーブル・カラムアクセスを検出 |
| **CALL 依存関係** | プログラム間の呼び出し関係（`CALL` ステートメント）を検出 |
| **推移的影響** | A → B → C という推移的な依存関係も追跡可能 |

### アーキテクチャ

CobolDependencyAnalyzer は `単一責任の原則（SRP）` に従い、以下の専門的なクラスに分割されています：

| クラス | 責務 |
|--------|------|
| **CobolFileFinder** | COBOL ファイルの探索・インデックス化。`.cbl/.cob/.scob` ファイルと `.cpy/.copy` ファイルの自動検出 |
| **CobolDatabaseManager** | Derby データベースの接続・テーブル作成・ライフサイクル管理 |
| **CobolProgramRegistrar** | `PROGRAM-ID` の抽出と DB への登録。プログラム情報の一元管理 |
| **CobolDependencyParser** | 依存関係の解析。`CALL` / `COPY` / `SQL` (INSERT/SELECT/UPDATE/DELETE) 検出 |
| **CobolDependencyGraph** | 依存関係グラフの生成・表示。Markdown レポート出力 |

**CobolDependencyAnalyzer（オーケストレーター）**
- 各サービスクラスを統合し、2 フェーズの処理を調整
  1. **Phase 1**: すべてのプログラムを登録（CobolProgramRegistrar）
  2. **Phase 2**: 依存関係を解析・登録（CobolDependencyParser）
- 最終的に依存関係グラフを表示（CobolDependencyGraph）

### 出力

実行後、`cobol_dependencies` Derby データベースに以下テーブルが作成・更新されます：

- `cobol_programs` - プログラム情報
- `table_columns` - テーブル・カラム定義
- `cobol_table_access` - アクセス情報（INSERT/SELECT/UPDATE/DELETE）
- `cobol_call_dependency` - CALL 依存関係
- `cobol_copy_dependency` - COPY 依存関係

### 使用例

**例1：デフォルトパスから検出**
```powershell
.\gradlew.bat app:runCobolAnalyzer
```

**例2：カスタムパスから検出**
```powershell
.\gradlew.bat app:runCobolAnalyzer --args="app/src/main/resources/cobol"
```

出力例：
```
===== COBOL 依存関係検出ツール =====
COBOL ディレクトリ: C:\...\app\src\main\resources\cobol
検出ファイル数: 2

[解析] call_symfo_inst.cbl
  - CALL: symfo_inst

[解析] symfo_inst.cbl
  - INSERT: POST_CD

===== 依存関係グラフ =====
プログラム名                    ファイルパス                              依存タイプ
────────────────────────────────────────────────────────────────────────────────────
call-symfo-inst                 C:\...\call_symfo_inst.cbl         INDIRECT (via symfo-inst)
symfo-inst                      C:\...\symfo_inst.cbl              DIRECT
```

#### 推移的依存関係の検出について

上記例では、スキーマ変更の影響範囲を自動検出しています：

- **DIRECT**: `symfo_inst` が直接 `POST_CD` テーブルの `ZIPCODE` 列にアクセス（INSERT）
- **INDIRECT (via symfo_inst)**: `call_symfo_inst` は `symfo_inst` をCALL しており、`symfo_inst` を通じて間接的に `POST_CD` にアクセス

つまり、`POST_CD.ZIPCODE` のスキーマを変更すると、推移的に `call_symfo_inst.cbl` にも影響が出ることを自動検出します。

### 実装
- **言語**: Java 21
- **データベース**: Apache Derby (Pure Java、外部バイナリ不要)
- **パッケージ**: `org.example.cobol.CobolDependencyAnalyzer`

## ハイブリッド統合：CobolColumnImpactAgent × CobolDependencyAnalyzer

CobolDependencyAnalyzer の依存関係データベースと CobolColumnImpactAgent の自然言語処理を統合し、以下を実現します：

### 概要

```
1. CobolColumnImpactAgent が自然言語入力を処理
   ↓
2. DependencyAnalyzerAgent が CobolDependencyAnalyzer を実行して Derby DB を再構築
   ↓
3. DatabaseQueryAgent が最新 DB から該当プログラム＋推移的影響を検索
   ↓
4. 統合分析結果を出力
```

### 使用フロー

**統合分析を実行（自然言語入力）**
```powershell
.\gradlew.bat :app:runCobolColumnImpact --args="POST_CDテーブルのZIPCODEカラムの変更影響を調査"
```

または対話モード：
```powershell
.\gradlew.bat :app:runCobolColumnImpact
# > POST_CDテーブルのZIPCODEカラムの変更影響を調査
```

COBOL / COPY ディレクトリを明示したい場合は、自然言語でそのまま指定できます：

```powershell
.\gradlew.bat :app:runCobolColumnImpact --args="POST_CD の ZIPCODE を C:\work\cobol と C:\work\copy で調査"
```

この場合、IntentExtractorAgent が `cobolDir` と `copyDir` を抽出し、DependencyAnalyzerAgent が
その値をそのまま CobolDependencyAnalyzer に渡して Derby DB を再構築します。

### 統合分析のメリット

| 項目 | CobolColumnImpactAgent のみ | ハイブリッド統合 |
|------|-------|---------|
| **自然言語入力** | ✅ | ✅ |
| **変数定義キャプチャ** | ✅ | ✅ |
| **DIRECT 依存関係** | × | ✅ |
| **推移的影響（INDIRECT）** | × | ✅ |
| **CALL グラフ表示** | × | ✅ |
| **DB 永続化** | × | ✅ |

### DatabaseQueryAgent について

`DatabaseQueryAgent` は Derby DB をクエリして以下を検索します：

- **DIRECT アクセス**: テーブル・カラムに直接アクセスするプログラム
- **INDIRECT アクセス**: CALL を通じて間接的にアクセスするプログラム（推移的依存関係）
- **CALL グラフ**: プログラム間の呼び出し関係を構築

#### テスト実行

```powershell
# DependencyAnalyzerAgent により DB は自動再構築されるが、DB クエリ単体確認にも使用可能
.\gradlew.bat app:runDatabaseQueryTest
```

出力例：
```
[Direct Access Programs]
  - symfo_inst (C:\...\symfo_inst.cbl)

[Indirect Access Programs (via CALL)]
  - call_symfo_inst (C:\...\call_symfo_inst.cbl)
    Access Path: via symfo_inst [Level 1]

[CALL Dependency Graph]
  call_symfo_inst calls: [symfo_inst]
```

## Step 3: 完全なワークフロー実行（CobolColumnImpactAgent 統合）

CobolColumnImpactAgent の完全な統合実装により、以下の純粋なJavaベースのシーケンシャルパイプラインによる高速な統合ワークフローが実現されました：

### 完全なワークフロー フロー

LLMの利用は最初の「意図抽出」のみに限定され、以降はAST（構文木）解析ベースのDerbyDBを活用したローカル処理で完結します。

```
ユーザ入力（自然言語）
  ↓
[1] IntentExtractorAgent (AI/LLM)
  → TABLE/COLUMN を自然言語から抽出
  ↓
[2] DependencyAnalyzerAgent (純粋Java)
  → CobolDependencyAnalyzer を cobolDir / copyDir 付きで実行
  → 全COBOL資源をAST解析し Derby DB を最新化
  ↓
[3] DatabaseQueryAgent (純粋Java)
  → 最新の Derby DB をクエリ
  → DIRECT/INDIRECT プログラムや変数代入箇所をDBから一括検索
  ↓
[4] ResultSaverAgent (純粋Java)
  → 統合分析結果をマークダウンで保存
```

### 使用方法

**完全統合ワークフローを実行**

デフォルト（POST_CD/ZIPCODE を自動分析）：
```powershell
.\gradlew.bat app:runIntegratedAnalysisDemo
```

カスタムリクエスト（自然言語で指定）：
```powershell
.\gradlew.bat app:runIntegratedAnalysisDemo --args="USER_MASTER テーブルの ID カラムの影響分析"
```

ディレクトリを明示する場合：
```powershell
.\gradlew.bat app:runIntegratedAnalysisDemo --args="POST_CD の ZIPCODE を C:\work\cobol と C:\work\copy で調査"
```

事前に `app:runCobolAnalyzer` を手動実行しなくても、ワークフロー内で Derby DB が更新されます。

### 出力例

生成されたレポート（`cobol_impact_analysis.md`）：

```markdown
# COBOL Column Impact Analysis Report

## Analysis Parameters
- **Table Name**: `POST_CD`
- **Column Name**: `ZIPCODE`
- **Timestamp**: 2026-04-06 16:30:00

## Dependency Analysis (DB Query Results)

### Direct Access Programs
| Program ID | File Path | Access Type |
|---|---|---|
| `symfo_inst` | `C:\...\symfo_inst.cbl` | DIRECT |

### Indirect Access Programs (via CALL)
| Program ID | File Path | Access Path | Level |
|---|---|---|---|
| `call_symfo_inst` | `C:\...\call_symfo_inst.cbl` | via symfo_inst | 1 |

### CALL Dependency Graph
```
call_symfo_inst calls: [symfo_inst]
```

### Impact Summary
- **Direct Impact**: 1 program(s)
- **Indirect Impact**: 1 program(s)
- **Total Impact**: 2 program(s)

#### Risk Assessment
- **Risk Level**: **Medium**
- **Description**: 2 program(s) affected.

## Identified Variables
| Variable Name | Level | Data Type | Description |
|---|---|---|---|
| `ZIPCODE-VAR` | 01 | PIC X(7) | Postal Code Variable |

## File-wise Variable References
...
```

### 統合ワークフローのメリット

| 項目 | 従来版 | 統合版 ✨ |
|------|--------|---------|
| **自然言語入力** | ✅ | ✅ |
| **変数定義キャプチャ** | ✅ | ✅ |
| **DIRECT 依存関係** | ❌ | ✅ |
| **INDIRECT 依存関係** | ❌ | ✅ |
| **CALL グラフ** | ❌ | ✅ |
| **リスク評価** | ❌ | ✅ |
| **推移的影響（段階的）** | ❌ | ✅ |

## ツールアーキテクチャ

### ChatCLI - ハイブリッド・アーキテクチャ

以前は `ChatCLI`（通常版）と `AgentChatCLI`（エージェント版）に分かれていましたが、現在は **`ChatCLI`** ひとつに統一され、両方の利点を備えています。

```text
ユーザ入力
    │
    ├─ 通常のチャット (Assistant への Function Calling)
    │      ↓
    │  assistant.chat(input)
    │      ├─ LocalCommandTool
    │      ├─ ImpactAnalysisTool
    │      ├─ FileReaderTool
    │      └─ ...
    │
    └─ /filesearch コマンド
           ↓
       FileSearchWorkflow#executeWorkflow()
           ├─ [1] IntentExtractor (AI)
           ├─ [2] FileSearchAgent (Files.walk)
           ├─ [3] FileSelector (対話的選択)
           ├─ [4] FileReadAgent
           └─ [5] FileSummarizer (AI)
```

システムプロンプトにより LLM はファイル検索・内容調査に `LocalCommandTool` を優先的に使用します。
`rg`（ripgrep）が利用可能な環境では最も高速に動作します。

