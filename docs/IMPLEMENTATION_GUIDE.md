# FileSearchWorkflow 実装ガイド

本ドキュメントは、提示いただいたユーザー対話型のファイル検索・選択・要約の要件に基づいて実装した、
LangChain4j @Agent ベースのワークフローエージェント実装ガイドです。

## 実装概要

### 目的
ユーザーが自然言語で「XXX ディレクトリから、YYYY というキーワードを含むファイルを探して」と指示したら、
AIが自動的に以下の処理を順序立てて実行する仕組みを実装します。

### 実装のポイント

LangChain4j 1.12.2 では、`@Agent` アノテーションは **Agentic Services**（実験的API）に含まれています。
本実装では、これを代わりに `@UserMessage` アノテーション付き `AiServices` インタフェースで実現しています。
これにより、AI 処理と非AI処理（ファイルI/O）を混在させた柔軟なワークフロー構築が可能になります。

## 実装コンポーネント

### 1. IntentExtractor（AI エージェント）

```java
interface IntentExtractor {
    @UserMessage("""
            次のユーザー入力からディレクトリパスとキーワードを抽出してください。
            必ず JSON 形式で、"directory" と "keyword" のキーを含む形式で返してください。

            ユーザー入力: {{userInput}}

            回答例:
            {"directory": "/home/user/documents", "keyword": "report"}
            """)
    String extract(String userInput);
}
```

**役割**: ユーザーの自然言語入力をAIに解析させ、ディレクトリパスとキーワードをJSON形式で抽出します。

**実装方式**:
- `AiServices.builder(IntentExtractor.class).chatModel(model).build()` で AI サービスを生成
- `@UserMessage` でプロンプトテンプレートを定義
- LLM (OpenAI) が自然言語を解析

**出力例**:
```json
{
  "directory": "C:\\Users\\kskan\\Desktop\\java_MyAIAgent\\test_sample",
  "keyword": "report"
}
```

### 2. FileSearchAgent（非AI エージェント）

```java
public List<String> searchFiles(String directory, String keyword) {
    Path startPath = Paths.get(directory);
    return Files.walk(startPath)
        .filter(Files::isRegularFile)
        .filter(path -> path.getFileName().toString().toLowerCase()
                .contains(keyword.toLowerCase()))
        .map(Path::toString)
        .collect(Collectors.toList());
}
```

**役割**: Java の標準 FileIO 機能を使用して、指定ディレクトリ配下からキーワードを含むファイルを再帰的に検索します。

**実装方式**:
- `Files.walk()` で再帰的にファイルを走査
- `Filter(Files::isRegularFile)` で通常ファイルのみを対象
- ファイル名に大文字小文字を区別せずキーワード検索

**出力例**:
```
C:\Users\kskan\Desktop\java_MyAIAgent\test_sample\report_2024.txt
C:\Users\kskan\Desktop\java_MyAIAgent\test_sample\project_progress.txt
```

### 3. FileSelector（ユーザー対話）

```java
public String selectFileInteractive(List<String> foundFiles) {
    System.out.println("見つかったファイル:");
    for (int i = 0; i < foundFiles.size(); i++) {
        System.out.printf("%d. %s%n", i + 1, foundFiles.get(i));
    }
    
    System.out.print("要約したいファイルの番号を入力してください: ");
    String input = reader.readLine();
    int index = Integer.parseInt(input);
    return foundFiles.get(index - 1);
}
```

**役割**: 見つかったファイルをユーザーに提示し、対話的にファイルを選択させます。

**実装方式**:
- `System.out.println()` でファイル一覧を表示
- `BufferedReader` で stdin からユーザー入力を受け取り
- 番号入力で選択

**ユーザー操作例**:
```
見つかったファイル:
========================================
1. C:\Users\kskan\Desktop\java_MyAIAgent\test_sample\report_2024.txt
2. C:\Users\kskan\Desktop\java_MyAIAgent\test_sample\project_progress.txt
========================================
要約したいファイルの番号を入力してください (1-2): 1
```

### 4. FileReadAgent（非AI エージェント）

```java
public String readFileContent(String filePath) {
    return Files.readString(Paths.get(filePath), StandardCharsets.UTF_8);
}
```

**役割**: 選択されたファイルの内容をテキストとして読み込みます。

**実装方式**:
- `Files.readString()` で UTF-8 エンコーディングで読み込み
- 最大数百KB程度のテキストファイルを想定

### 5. FileSummarizer（AI エージェント）

```java
interface FileSummarizer {
    @UserMessage("""
            次のファイルの内容を、日本語で簡潔に（3～5段落程度）要約してください：

            {{fileContent}}
            """)
    String summarize(String fileContent);
}
```

**役割**: ファイルの内容を LLM で要約します。

**実装方式**:
- `AiServices.builder(FileSummarizer.class).chatModel(model).build()` で AI サービスを生成
- `@UserMessage` でプロンプトを定義
- LLM が自然言語による要約を生成

**出力例**:
```
2024年度の財務報告書では、総売上が100億円となり前年比5%増加したことが報告されています。
営業利益は25億円に達し、利益率も20%から25%へ向上しました。
2025年度の展望としては、マーケティング投資と製品開発に注力する計画です。
```

## 全体ワークフロー（executeWorkflow メソッド）

```java
public String executeWorkflow(String userInput) {
    // [ステップ1] Intent 抽出
    Map<String, String> intents = extractIntents(userInput);
    String directory = intents.get("directory");
    String keyword = intents.get("keyword");
    
    // [ステップ2] ファイル検索
    List<String> foundFiles = searchFiles(directory, keyword);
    
    // [ステップ3] ユーザーが選択
    String selectedFile = selectFileInteractive(foundFiles);
    
    // [ステップ4] ファイル読み込み
    String fileContent = readFileContent(selectedFile);
    
    // [ステップ5] AI 要約
    String summary = summarizeContent(fileContent);
    
    return summary;
}
```

## @UserMessage と @Agent の関係

LangChain4j のバージョン 1.12.2 では以下のような仕様となっています：

| 機能 | 方式 | 用途 |
|-----|------|-----|
| `@UserMessage` | `AiServices.builder().chatModel().build()` | LLM と1回のやり取りを行う簡易インタフェース |
| `@Agent` | `AgenticServices.*` | 複数ステップの自律的な推論（実験的） |

本実装では `@UserMessage` を使用することで、**安定性と互換性を重視**しました。
LangChain4j の Agentic Services が安定化された際は、`@Agent` への移行が可能です。

## JSON パース戦略

Intent 抽出後、LLM が返した JSON を簡易的にパースします：

```java
private String extractJsonValue(String json, String key) {
    String pattern = "\"" + key + "\"\\s*:\\s*\"([^\"]*)\"";
    Pattern p = Pattern.compile(pattern);
    Matcher m = p.matcher(json);
    if (m.find()) {
        return m.group(1);
    }
    return null;
}
```

より堅牢にするため、Jackson や Gson などの JSON ライブラリの使用も検討できます。

## ユースケース

### ケース1: ドキュメント検索

```
ユーザー: "docs ディレクトリから、実装ガイドというキーワードを含むファイルを探して"

処理:
  [1] directory: "docs", keyword: "実装ガイド"
  [2] 検索結果: docs/impl_guide.md, docs/tech_guide.md, ...
  [3] ユーザー: "1 番を選択"
  [4] ファイル読み込み
  [5] AI 要約返却
```

### ケース2: 設定ファイル検索

```
ユーザー: "config フォルダから、database と書いてあるファイルを探してください"

処理:
  [1] directory: "config", keyword: "database"
  [2] 検索結果: config/database.yml, config/db_settings.conf, ...
  [3] ユーザーが選択
  [4] 設定内容を AI が要約
```

## エラーハンドリング

実装では以下のエラーケースに対応しています：

| エラー | 対処 |
|-------|------|
| ディレクトリが存在しない | エラーメッセージ出力、空リスト返却 |
| ファイルが見つからない | ユーザーへ通知、フロー中断 |
| ファイル読み込みエラー | エラーログ、null 返却 |
| Intent 抽出失敗 | エラーログ、空マップ返却 |
| ユーザー入力が無効 | 番号入力の再プロンプト |

## 拡張例

### 複数ファイル選択対応

```java
public List<String> selectFilesInteractive(List<String> foundFiles) {
    // 複数番号入力対応
    // "1,3,5" → files[0], files[2], files[4]
    // ...
}
```

### 検索結果キャッシング

```java
private Map<String, List<String>> searchCache = new HashMap<>();

public List<String> searchFiles(String directory, String keyword) {
    String key = directory + ":" + keyword;
    if (searchCache.containsKey(key)) {
        return searchCache.get(key);
    }
    // ... 検索実行
    searchCache.put(key, results);
    return results;
}
```

### 要約スタイルのカスタマイズ

FileSummarizer の `@UserMessage` プロンプトを変更することで、
要約のスタイル（箇条書き、1文、詳細など）を柔軟に制御できます。

## テスト

テストコード例 (`FileSearchWorkflowTest.java`) が含まれています：

```bash
# テスト実行
.\gradlew.bat :app:test --tests "*FileSearchWorkflowTest"
```

テスト内容：
1. テスト用ディレクトリとサンプルファイルを作成
2. ワークフローを実行
3. ファイルが正しく検出されたか確認
4. 要約が生成されたか確認
5. テストファイルをクリーンアップ

## 参考資料

- [FileSearchWorkflow.md](FileSearchWorkflow.md) - API 仕様書
- [LangChain4j 公式ドキュメント](https://docs.langchain4j.dev/)
- [OpenAI API リファレンス](https://platform.openai.com/docs/api-reference)
