# AgentChatCLI - Agent対応版 CLI チャットアプリケーション

`AgentChatCLI` は、従来の `ChatCLI` をベースに、**エージェントワークフロー**（FileSearchWorkflow など）を統合した拡張版です。
通常のLLM Function Calling と同時に、複雑なマルチステップワークフローを実行可能になります。

## 起動方法

### 対話モード
```powershell
# 従来版 ChatCLI
.\gradlew.bat :app:run --args="chat"

# Agent対応版 AgentChatCLI
.\gradlew.bat :app:run --args="agent chat"
```

### 単発モード
```powershell
# 従来版
.\gradlew.bat :app:run --args="このコードを解析してください"

# Agent対応版
.\gradlew.bat :app:run --args="agent このコードを解析してください"
```

## コマンド

### `/filesearch` - ファイル検索・選択・要約ワークフロー

ユーザーの自然言語指示からファイルを検索し、選択・要約するエージェントワークフローです。

**構文:**
```
/filesearch <ディレクトリ> <キーワード>
```

**例:**
```
User: /filesearch . report
User: /filesearch app config
User: /filesearch test_sample important
```

**処理フロー:**

1. **Intent 抽出**: AI が入力からディレクトリとキーワードを解析
2. **ファイル検索**: Java の `Files.walk()` で該当ファイルを探索
3. **ユーザー対話**: 見つかったファイルをリスト表示し、番号で選択させる
4. **ファイル読み込み**: 選択されたファイルをテキストとして読み込む
5. **AI 要約**: LLM で内容を簡潔に要約

**対話例:**
```
User: /filesearch test_sample report
[エージェント処理中...]
見つかったファイル:
========================================
1. C:\Users\kskan\Desktop\java_MyAIAgent\test_sample\report_2024.txt
2. C:\Users\kskan\Desktop\java_MyAIAgent\test_sample\project_progress.txt
========================================
要約したいファイルの番号を入力してください (1-2): 1
  選択されたファイル: C:\Users\kskan\Desktop\java_MyAIAgent\test_sample\report_2024.txt
  ファイルサイズ: 550 文字

[AI要約中...]
AI: 2024年度の財務報告書です。
売上高は前年比5%増加し100億円を達成しました。
営業利益は25億円で、利益率は20%から25%へ向上しています。
2025年度のマーケティング投資と製品開発に注力する計画です。
```

### `/clear` - 履歴クリア

会話履歴と入力コマンド履歴をリセットします（ChatCLI と同じ）。

```
User: /clear
AI: ✓ 会話履歴と入力履歴をクリアしました
```

## 通常のチャット機能

AgentChatCLI は従来の ChatCLI と同じ Function Calling をサポートしているため、
通常のチャットやツール呼び出しも可能です。

例：
```
User: 以下のコマンドの実行結果をまとめてください：dir
AI: [実行確認メッセージを表示]
...
```

## 環境変数

ChatCLI と同じ環境変数をサポートしています：

| 変数名 | 説明 | 既定値 |
|---|---|---|
| `OPENAI_API_KEY` | OpenAI API キー（必須） | − |
| `OPENAI_MODEL` | 使用モデル | `gpt-4o-mini` |
| `CHAT_MEMORY_WINDOW` | 会話履歴ウィンドウサイズ | `50` |
| `CHAT_NO_COLOR` | カラー表示を無効化（`true`で無効） | `false` |

## 設計パターン

AgentChatCLI の設計パターンは以下の通りです：

### 1. コマンドベースのディスパッチ

ユーザー入力を検査し、特定のコマンド（`/filesearch` など）の場合はワークフローを起動：

```java
if (normalizedMessage.startsWith(FILE_SEARCH_COMMAND)) {
    // FileSearchWorkflow を実行
    String result = runFileSearchWorkflow(model, searchQuery);
    // ...
}
```

### 2. 拡張性

新しいエージェントワークフロー（例：`/analyze`, `/translate`）を追加する場合：

1. 新しいワークフロー クラスを `org.example.agents` パッケージに作成
2. `runXxxWorkflow()` メソッドを追加
3. AgentChatCLI に新しいコマンド処理を追加

例：
```java
if (normalizedMessage.startsWith("/analyze")) {
    String result = runAnalysisWorkflow(model, query);
    // ...
}
```

### 3. スピナー表示

長時間かかるワークフロー実行中は、スピナーアニメーションを表示：

```java
String result = withSpinner(writer, AGENT_WORKING_LABEL,
    () -> runFileSearchWorkflow(model, searchQuery));
```

## トラブルシューティング

### `/filesearch` コマンドが反応しない

- ディレクトリパスが正確か確認
- 相対パス（`.` など）または絶対パスを使用してください
- キーワードが正しいか確認（大文字小文字は区別されません）

### ファイルが見つからない

- 検索対象ディレクトリが正しいか確認
- サブディレクトリ内のファイルもすべて走査されます
- ファイル数が多い場合、検索に時間がかかる可能性があります

### Intent 抽出が失敗する

ユーザー入力がより明確になるよう提示してください：
- ❌ 悪い例：`/filesearch doc file`
- ✅ 良い例：`/filesearch . config`

## API リファレンス

### FileSearchWorkflow

詳細は [docs/FileSearchWorkflow.md](../docs/FileSearchWorkflow.md) を参照してください。

```java
public Map<String, String> extractIntents(String userInput)
public List<String> searchFiles(String directory, String keyword)
public String selectFileInteractive(List<String> foundFiles)
public String readFileContent(String filePath)
public String summarizeContent(String fileContent)
public String executeWorkflow(String userInput)
```

## 関連ドキュメント

- [README.md](../README.md) - 全体的な使用方法
- [docs/FileSearchWorkflow.md](../docs/FileSearchWorkflow.md) - FileSearchWorkflow 仕様
- [docs/IMPLEMENTATION_GUIDE.md](../docs/IMPLEMENTATION_GUIDE.md) - 実装ガイド
