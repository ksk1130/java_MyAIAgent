# COBOL 統合分析ツール - 完全ガイド

## 概要

このプロジェクトは、COBOL プログラムのスキーマ変更影響分析を以下の 3つのツールの統合で実現します：

1. **CobolDependencyAnalyzer** - COBOL 依存関係の自動検出＆DB 登録
2. **DatabaseQueryAgent** - Derby DB からの推移的影響検索
3. **CobolColumnImpactAgent** - 自然言語入力による統合分析

---

## 完全なワークフロー（Step-by-Step）

### Step 1️⃣: COBOL 依存関係 DB を生成

COBOL ファイルを解析して、依存関係を Derby データベースに登録します。

```powershell
.\gradlew.bat app:runCobolAnalyzer --args="app/src/main/resources/cobol"
```

**重複キーエラーが出た場合（複数回実行時）：**
```powershell
# DB をリセットしてから実行
.\gradlew.bat app:runCobolAnalyzer --args="--clean app/src/main/resources/cobol"
```

`--clean` フラグを指定すると、既存の `cobol_dependencies/` ディレクトリを削除してから新規作成します。

**出力:**
- `cobol_dependencies/` Derby DB ディレクトリが生成
- 4つのテーブルが作成・更新：
  - `cobol_programs` - プログラムメタデータ
  - `table_columns` - テーブル・カラム定義
  - `cobol_table_access` - テーブルアクセス情報
  - `cobol_call_dependency` - CALL 依存関係

**サンプル出力:**
```
===== COBOL 依存関係検出ツール =====
COBOL ディレクトリ: C:\...\app\src\main\resources\cobol
検出ファイル数: 2

[解析] call_symfo_inst.cbl
  - CALL: symfo_inst

[解析] symfo_inst.cbl
  - INSERT: POST_CD

===== 依存関係グラフ =====
プログラム名          ファイルパス                    依存タイプ
────────────────────────────────────────────────────────────────
call-symfo-inst       C:\...\call_symfo_inst.cbl     INDIRECT (via symfo-inst)
symfo-inst            C:\...\symfo_inst.cbl          DIRECT
```

---

### Step 2️⃣: DB クエリをテスト

DatabaseQueryAgent が Derby DB を正しくクエリできることを確認します。

```powershell
.\gradlew.bat app:runDatabaseQueryTest
```

**出力:**
```
[Direct Access Programs]
  - symfo_inst (C:\...\symfo_inst.cbl)

[Indirect Access Programs (via CALL)]
  - call_symfo_inst (C:\...\call_symfo_inst.cbl)
    Access Path: via symfo_inst [Level 1]

[CALL Dependency Graph]
  call_symfo_inst calls: [symfo_inst]

✓ Query succeeded
```

---

### Step 3️⃣: ハイブリッド分析デモを実行

DB クエリと簡易リスク評価を含むデモを実行します。

```powershell
# デフォルト（POST_CD/ZIPCODE）
.\gradlew.bat app:runHybridAnalysisDemo

# カスタム TABLE/COLUMN
.\gradlew.bat app:runHybridAnalysisDemo --args="POST_CD ZIPCODE"
```

**出力:**
```
====================================================================
COBOL スキーマ影響分析 - ハイブリッド統合デモ
====================================================================

対象テーブル: POST_CD
対象カラム:   ZIPCODE

📌 直接アクセス (DIRECT)
──────────────────────────────────────────────────────────────────
  symfo_inst                C:\...\symfo_inst.cbl

🔗 間接アクセス (INDIRECT - CALL 経由)
──────────────────────────────────────────────────────────────────
  call_symfo_inst           C:\...\call_symfo_inst.cbl
    → via symfo_inst

📊 影響プログラム数
──────────────────────────────────────────────────────────────────
  DIRECT:   1 プログラム
  INDIRECT: 1 プログラム
  合計:      2 プログラム

⚠️  リスク評価
──────────────────────────────────────────────────────────────────
  リスク: 中 - 2 プログラムに影響あり
====================================================================
分析完了
====================================================================
```

---

### Step 4️⃣: 完全統合ワークフローを実行 ⭐

CobolColumnImpactAgent の完全な統合ワークフローを実行します。
自然言語入力から統合マークダウンレポート生成まで、すべてを自動で実行します。

```powershell
# デフォルト（POST_CDテーブルの ZIPCODE カラムを自動分析）
.\gradlew.bat app:runIntegratedAnalysisDemo

# カスタムリクエスト（自然言語で指定）
.\gradlew.bat app:runIntegratedAnalysisDemo --args="USER_MASTER テーブルの ID カラムの影響分析"
```

**ワークフロー:**
```
1. IntentExtractorAgent
   → 自然言語から TABLE/COLUMN を抽出（LLM）

2. DatabaseQueryAgent
   → Derby DB をクエリして DIRECT/INDIRECT プログラムを検索

3. FileScannerAgent
   → 検出されたプログラムのファイルをスキャン

4. CobolAnalyzerAgent
   → 変数定義を抽出（DB 結果と統合）

5. ResultSaverAgent
   → 統合分析結果をマークダウンで保存
```

**出力ファイル:** `cobol_impact_analysis.md`

**レポート内容:**
- ✅ Analysis Parameters（分析対象）
- ✅ Dependency Analysis（DB クエリ結果）
  - Direct Access Programs
  - Indirect Access Programs
  - CALL Dependency Graph
  - Impact Summary
  - Risk Assessment
- ✅ File Dependencies & Relationships（COPY 依存関係）
- ✅ Identified Variables（変数定義）
- ✅ File-wise Variable References（ファイル別参照）

---

## 各ツール詳細

### 1. CobolDependencyAnalyzer

**役割**: COBOL ファイルを自動解析し、依存関係を Derby DB に登録

**検出内容:**
- PROGRAM-ID 抽出
- テーブルアクセス（INSERT/SELECT/UPDATE/DELETE）
- カラム参照（PIC 句などの変数定義）
- CALL 依存関係（プログラム間の呼び出し）

**実装**: `org.example.cobol.CobolDependencyAnalyzer`

**DB スキーマ:**
```
cobol_programs
├── program_id (PK)
├── program_name
├── file_path
└── created_at / updated_at

table_columns
├── column_id (PK)
├── table_name
├── column_name
└── data_type

cobol_table_access
├── access_id (PK)
├── program_id (FK → cobol_programs)
├── column_id (FK → table_columns)
├── access_type (INSERT/SELECT/UPDATE/DELETE)
└── sql_location

cobol_call_dependency
├── dep_id (PK)
├── caller_program_id (FK)
├── callee_program_id (FK)
└── call_location
```

### 2. DatabaseQueryAgent

**役割**: Derby DB をクエリして、指定テーブル・カラムへのアクセスプログラムを検索

**検索機能:**
- `findDirectAccess()` - 直接アクセスするプログラムを検索
- `findIndirectAccess()` - CALL を通じて間接的にアクセスするプログラムを検索（推移的）
- `buildCallGraph()` - CALL 依存関係グラフを構築

**実装**: `org.example.agents.DatabaseQueryAgent`

**特徴:**
- 3段階までの推移的影響を検出
- 各プログラムのアクセスパスを記録
- CALL グラフで依存関係を可視化

### 3. CobolColumnImpactAgent

**役割**: 複数エージェントのスーパーバイザーワークフローで、統合分析を実行

**Supervisor Agent パターン:**
```
IntentExtractorAgent
  └─→ DatabaseQueryAgent (新規）
      └─→ FileScannerAgent
          └─→ CobolAnalyzerAgent
              └─→ ResultSaverAgent
```

**実装**: `org.example.agents.CobolColumnImpactAgent`

**改修内容:**
- DatabaseQueryAgent を Supervisor に追加
- ResultSaverAgent を拡張して DB 検索結果を統合
- マークダウンレポートに DIRECT/INDIRECT 分析を含める

---

## 利用シナリオ

### シナリオ 1: 単発の影響分析

```powershell
# 1. DB を生成
.\gradlew.bat app:runCobolAnalyzer --args="app/src/main/resources/cobol"

# 2. 完全分析を実行
.\gradlew.bat app:runIntegratedAnalysisDemo --args="POST_CD の ZIPCODE が何に影響するか"

# 3. 生成されたレポート（cobol_impact_analysis.md）を確認
```

### シナリオ 2: 複数のテーブル/カラムを順番に分析

```powershell
# DB は一度生成すれば再利用可能

.\gradlew.bat app:runIntegratedAnalysisDemo --args="POST_CD ZIPCODE"
.\gradlew.bat app:runIntegratedAnalysisDemo --args="USER_MASTER USER_ID"
.\gradlew.bat app:runIntegratedAnalysisDemo --args="ADDR_TB PREF_CODE"
```

各実行で新しいレポート（タイムスタンプ付き）が生成されます。

### シナリオ 3: DB を再生成して最新状態に更新

```powershell
# 古い DB を削除
Remove-Item -Path "cobol_dependencies" -Recurse -Force

# 新しい COBOL ファイルセットで DB を再生成
.\gradlew.bat app:runCobolAnalyzer --args="new_cobol_directory"

# 分析を実行
.\gradlew.bat app:runIntegratedAnalysisDemo
```

---

## トラブルシューティング

### DB が見つからないエラー

```
データベース接続エラー: No suitable driver found for jdbc:derby:cobol_dependencies;create=true
```

**原因**: Analyzer をまだ実行していない

**解決**: Step 1 を実行して DB を生成

```powershell
.\gradlew.bat app:runCobolAnalyzer --args="app/src/main/resources/cobol"
```

### 重複キーエラー（duplicate key constraint violation）

```
エラーでした: The statement was aborted because it would have caused 
a duplicate key value in a unique or primary key constraint...
```

**原因**: DB が既に存在し、同じデータを再度登録しようとしている

**解決**: `--clean` フラグで DB をリセット

```powershell
# DB を削除してから新規作成
.\gradlew.bat app:runCobolAnalyzer --args="--clean app/src/main/resources/cobol"
```

または手動でリセット：

```powershell
# ディレクトリを手動削除
Remove-Item -Path "cobol_dependencies" -Recurse -Force

# 再実行
.\gradlew.bat app:runCobolAnalyzer --args="app/src/main/resources/cobol"
```

### OpenAI API キーエラー

```
Error: OPENAI_API_KEY environment variable not set
```

**原因**: 環境変数が設定されていない

**解決**: PowerShell で環境変数を設定

```powershell
$env:OPENAI_API_KEY = "sk-..."
```

### COBOL ディレクトリが見つからない

```
ディレクトリが見つかりません: C:\...\app\src\main\resources\cobol
```

**解決**: パスを正しく指定

```powershell
.\gradlew.bat app:runCobolAnalyzer --args="C:\full\path\to\cobol"
```

---

## パフォーマンス

### 処理時間の目安

| 処理 | 時間 |
|------|------|
| CobolDependencyAnalyzer (100 ファイル) | 2-5 秒 |
| DatabaseQueryTest | < 1 秒 |
| HybridAnalysisDemo | < 1 秒 |
| IntegratedAnalysisDemo (LLM 呼び出し含む) | 10-30 秒 |

### メモリ使用量

- Derby DB: 10-50 MB（COBOL ファイル数に依存）
- JVM: 512 MB （デフォルト）

---

## 拡張性

### カスタム TABLE/COLUMN の分析

IntegratedAnalysisDemo に任意のテーブル・カラムを指定：

```powershell
.\gradlew.bat app:runIntegratedAnalysisDemo --args="MY_TABLE MY_COLUMN"
```

### 複数ファイルセットの DB 管理

異なるコードベース用に複数の DB を作成：

```powershell
# コードベース A
.\gradlew.bat app:runCobolAnalyzer --args="codebases/A/cobol"
.\gradlew.bat app:runIntegratedAnalysisDemo

# コードベース B へ切り替え
Remove-Item -Path "cobol_dependencies" -Recurse -Force
.\gradlew.bat app:runCobolAnalyzer --args="codebases/B/cobol"
.\gradlew.bat app:runIntegratedAnalysisDemo
```

---

## 参考資料

- README.md - プロジェクト概要
- docs/ - 詳細ドキュメント
- CobolDependencyAnalyzer.java - COBOL 解析実装
- DatabaseQueryAgent.java - DB クエリ実装
- CobolColumnImpactAgent.java - Supervisor ワークフロー実装

---

**作成日**: 2026-04-06
**バージョン**: 1.0.0
