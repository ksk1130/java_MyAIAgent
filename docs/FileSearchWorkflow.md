# FileSearchWorkflow 使用ガイド

`FileSearchWorkflow` は、ユーザーの自然言語指示からファイルを検索・選択・要約するエージェントベースのワークフロー実装です。

## 主要機能

- **Intent 抽出**: ユーザーの自然言語入力から、検索対象ディレクトリとキーワードを自動抽出
- **ファイル検索**: 指定されたディレクトリから、キーワードを含むファイルを検出
- **対話的ファイル選択**: 見つかったファイルをリスト表示し、ユーザーが選択（番号入力）
- **ファイル読み込み**: 選択されたファイルの内容をテキストとして読み込み
- **AI による要約**: LLM（OpenAI の gpt-4o-mini など）を使用して、ファイル内容を自動要約

## 設計

```
ユーザー入力
    ↓
[1] IntentExtractor (AI)
    ↓ (directory, keyword を抽出)
[2] FileSearchAgent (非AI)
    ↓ (ファイル検索)
[3] FileSelector (ユーザー対話)
    ↓ (ファイル選択)
[4] FileReadAgent (非AI)
    ↓ (ファイル読み込み)
[5] FileSummarizer (AI)
    ↓ (要約生成)
ユーザーへの結果返却
```

### 各ステップの詳細

| ステップ | 処理内容 | 実装方式 |
|---------|--------|--------|
| 1 | 自然言語から directory と keyword を抽出 | `@UserMessage` 付き AIService インタフェース |
| 2 | Files.walk() で該当ファイルを探索 | 標準 Java FileIO |
| 3 | 見つかったファイルを列挙、ユーザーに番号選択を促す | BufferedReader による stdin |
| 4 | Files.readString() でファイル内容を読み込み | 標準 Java FileIO |
| 5 | LLM に内容を送信して要約を生成 | `@UserMessage` 付き AIService インタフェース |

## 実装例

### 基本的な使用方法

```java
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

System.out.println("結果: " + summary);
```

### 各ステップの個別利用

```java
// ステップ1: Intent 抽出
Map<String, String> intents = workflow.extractIntents("appsディレクトリからbugを含むファイルを探して");
String directory = intents.get("directory");   // → "apps"
String keyword = intents.get("keyword");       // → "bug"

// ステップ2: ファイル検索
List<String> files = workflow.searchFiles(directory, keyword);

// ステップ3: 対話的選択
String selectedFile = workflow.selectFileInteractive(files);

// ステップ4: ファイル読み込み
String content = workflow.readFileContent(selectedFile);

// ステップ5: 要約生成
String summary = workflow.summarizeContent(content);
```

## 前提条件

- Java 21 以上
- OpenAI API キーが環境変数 `OPENAI_API_KEY` に設定されていること
- LangChain4j 1.12.2 以上
- ネットワーク接続可能な環境

## JSON 解析の仕組み

Intent 抽出の結果は JSON 形式で返されます：

```json
{
  "directory": "/path/to/docs",
  "keyword": "important"
}
```

このコードは正規表現で簡易的にパースされます。`parseIntentResult()` メソッドで処理されます。

## エラーハンドリング

- ディレクトリが存在しない場合 → エラーメッセージ出力、空リスト返却
- ファイルが見つからない場合 → ユーザーへ通知、フローを中断
- ファイル読み込みエラー → エラーログ出力、null 返却
- Intent 抽出失敗 → エラーログ出力、空マップ返却

## 拡張可能性

このワークフローは以下のように拡張できます：

- **複数ファイル対応**: `selectFileInteractive()` を複数選択対応に変更
- **カスタム要約スタイル**: `FileSummarizer` のプロンプトを変更
- **Agentic Services 統合**: LangChain4j の新しいエージェント API に移行
- **キャッシング**: 検索結果をメモリーキャッシュして、再利用時の高速化

## トラブルシューティング

### "OPENAI_API_KEY が設定されていません"

```bash
# Windows PowerShell
$env:OPENAI_API_KEY="sk-..."

# Linux/Mac
export OPENAI_API_KEY="sk-..."
```

### Intent 抽出が失敗する

ユーザー入力がより明確になるよう促してください：
- 悪い例: "ファイルを探して"
- 良い例: "documents ディレクトリから report というキーワードを含むファイルを探して"

### ファイルが見つからない

- ディレクトリパスが正確であるか確認
- キーワードのスペルを確認
- ファイル名大文字小文字は区別されません（内部で `.toLowerCase()` されます）
