package org.example.agents;

import dev.langchain4j.model.openai.OpenAiChatModel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * FileSearchWorkflow のテスト。
 */
public class FileSearchWorkflowTest {

    public static void main(String[] args) throws Exception {
        // OpenAiChatModel を初期化
        String apiKey = System.getenv("OPENAI_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            System.err.println("エラー: OPENAI_API_KEY 環境変数が設定されていません");
            System.exit(1);
        }

        OpenAiChatModel model = OpenAiChatModel.builder()
                .apiKey(apiKey)
                .modelName("gpt-4o-mini")
                .build();

        FileSearchWorkflow workflow = new FileSearchWorkflow(model);

        // テスト用のサンプルファイルを作成
        Path testDir = Paths.get("test_documents");
        Files.createDirectories(testDir);

        Files.writeString(testDir.resolve("report_2024.txt"),
                "2024年度の財務報告書です。\n売上高は前年比5%増加しました。\nコスト削減により利益率が改善されています。");
        Files.writeString(testDir.resolve("document_sample.txt"),
                "これはサンプルドキュメントです。\n重要な情報が含まれています。\n詳細は別途ご確認ください。");

        System.out.println("========================================");
        System.out.println("FileSearchWorkflow テスト");
        System.out.println("========================================\n");

        // ワークフロー実行
        String userInput = "test_documents ディレクトリから report というキーワードを含むファイルを探して";
        String result = workflow.executeWorkflow(userInput);

        System.out.println("\n最終結果: " + result);

        // クリーンアップ
        Files.walk(testDir)
                .sorted(java.util.Comparator.reverseOrder())
                .forEach(path -> {
                    try {
                        Files.delete(path);
                    } catch (Exception e) {
                        System.err.println("削除エラー: " + e.getMessage());
                    }
                });
    }
}
