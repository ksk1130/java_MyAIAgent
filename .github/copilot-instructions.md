# プロジェクト概要
・JavaでCLIベースのAIエージェントを作成する
・主な機能
　・LLMはGemini or OpenAIのインタフェース(エンドポイントURLは指定可能とする、API_KEYは環境変数からロードする)
　・エージェントとして実現したいこと
　　・コードを解析して、コード間のつながりを自律的に解析する
　　・コードは端末内にあることを前提として、端末内を自律的に走査する
　　・コードの構成(ディレクトリ構成や命名規則など)を記載したファイルを読み込み、構成を把握する

## 目的
- 開発者がローカルでビルド・テスト・実行を素早く行えるように、必要なコマンドと重要ファイルを示す。

## 主要ワークフロー（要約）
- ビルド: `./gradlew.bat build`（Windows）
- テスト: `./gradlew.bat test`
- 実行: サブプロジェク1.ト `app` を使う場合 `./gradlew.bat :app:run`（`app` の `build.gradle` による）

## 重要ファイル（必読）
- プロジェクト定義: [settings.gradle](settings.gradle)
- アプリビルド設定: [app/build.gradle](app/build.gradle)
- エントリポイント: [app/src/main/java/org/example/App.java](app/src/main/java/org/example/App.java)
- テスト: [app/src/test/java/org/example/AppTest.java](app/src/test/java/org/example/AppTest.java)

## エージェントに伝えるべき事柄（テンプレ）
- ローカルでの標準コマンドは `./gradlew.bat build` と `./gradlew.bat test` であること。
- Java toolchain と依存バージョンは `gradle/libs.versions.toml` にあること。
- 日本語でやり取りすること
- JavaのクラスやメソッドにはJavaDocコメントを日本語で記載すること
- 重要なファイルやコマンドはREADME.mdにまとめること
- Javaは21を使用すること

## 参考／リンク方針
- 長文の説明はリポジトリ内の専用 `docs/` にまとめ、ここではリンクで誘導する（Link, don't embed）。
