package org.example.cobol;

import dev.langchain4j.model.openai.OpenAiChatModel;
import org.example.agents.CobolColumnImpactAgent;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * 完全統合デモ：CobolColumnImpactAgent のハイブリッド統合ワークフロー実行
 * 
 * 以下のフロー を実行します：
 * 1. IntentExtractorAgent: TABLE/COLUMN を自然言語から抽出
 * 2. DatabaseQueryAgent: Derby DB をクエリして DIRECT/INDIRECT 依存関係を検索
 * 3. FileScannerAgent: 見つかったプログラムのファイルをスキャン
 * 4. CobolAnalyzerAgent: 変数定義を抽出（DB 結果と統合）
 * 5. ResultSaverAgent: 統合分析結果をマークダウンで保存
 */
public class IntegratedAnalysisDemo {
    
    public static void main(String[] args) throws Exception {
        String userRequest = "POST_CDテーブルのZIPCODEカラムの型変更が何に影響するか調査してください";
        
        if (args.length > 0) {
            userRequest = String.join(" ", args);
        }
        
        System.out.println("╔" + "═".repeat(78) + "╗");
        System.out.println("║ " + " ".repeat(76) + " ║");
        System.out.println("║ " + String.format("%-76s", "COBOL Column Impact Analysis - Complete Integration Workflow") + " ║");
        System.out.println("║ " + " ".repeat(76) + " ║");
        System.out.println("╚" + "═".repeat(78) + "╝");
        System.out.println();
        
        System.out.println("📋 User Request:");
        System.out.println("   " + userRequest);
        System.out.println();
        
        System.out.println("🔄 Workflow Steps:");
        System.out.println("   [1] IntentExtractorAgent   → Extract TABLE/COLUMN from natural language");
        System.out.println("   [2] DatabaseQueryAgent     → Query Derby DB for DIRECT/INDIRECT programs");
        System.out.println("   [3] FileScannerAgent       → Scan COBOL files for table references");
        System.out.println("   [4] CobolAnalyzerAgent     → Extract variable definitions");
        System.out.println("   [5] ResultSaverAgent       → Generate integrated Markdown report");
        System.out.println();
        
        // OpenAI API キーを環境変数から取得
        String apiKey = System.getenv("OPENAI_API_KEY");
        if (apiKey == null || apiKey.isEmpty()) {
            System.err.println("❌ Error: OPENAI_API_KEY environment variable not set");
            System.exit(1);
        }
        
        System.out.println("⚙️  Initializing LLM...");
        System.out.println("   Model: gpt-4o-mini");
        OpenAiChatModel chatModel = OpenAiChatModel.builder()
            .apiKey(apiKey)
            .modelName("gpt-4o-mini")
            .build();
        System.out.println("   ✓ LLM initialized");
        System.out.println();
        
        System.out.println("🚀 Executing Supervisor Agent Workflow...");
        System.out.println();
        System.out.println("-".repeat(80));
        
        try {
            // CobolColumnImpactAgent のワークフローを実行
            // buildModifiedWorkflow を使用するため、args[] スタイルで呼び出し
            String[] workflowArgs = { userRequest };
            CobolColumnImpactAgent.main(workflowArgs);
            
            System.out.println("-".repeat(80));
            System.out.println();
            
            // 生成されたレポートを確認
            String reportPath = System.getProperty("user.dir") + File.separator + "cobol_impact_analysis.md";
            File reportFile = new File(reportPath);
            
            if (reportFile.exists()) {
                System.out.println("📄 Generated Report:");
                System.out.println("   Location: " + reportPath);
                System.out.println("   Size: " + reportFile.length() + " bytes");
                System.out.println();
                
                // 最初の20行を表示
                System.out.println("📋 Report Preview (First 20 lines):");
                System.out.println("─".repeat(80));
                String content = new String(Files.readAllBytes(Paths.get(reportPath)));
                String[] lines = content.split("\n");
                int displayLines = Math.min(20, lines.length);
                for (int i = 0; i < displayLines; i++) {
                    System.out.println(lines[i]);
                }
                if (lines.length > 20) {
                    System.out.println("... (" + (lines.length - 20) + " more lines)");
                }
                System.out.println("─".repeat(80));
                System.out.println();
            } else {
                System.err.println("⚠️  Warning: Report file not found at " + reportPath);
            }
            
        } catch (Exception e) {
            System.err.println("❌ Workflow execution failed:");
            e.printStackTrace();
            System.exit(1);
        }
        
        System.out.println("✅ Workflow completed successfully!");
        System.out.println();
        System.out.println("📌 Next Steps:");
        System.out.println("   1. Review the generated Markdown report: cobol_impact_analysis.md");
        System.out.println("   2. Check for DIRECT and INDIRECT impact programs");
        System.out.println("   3. Assess risk level and plan necessary changes");
        System.out.println("   4. Update variable definitions in affected COBOL programs");
        System.out.println();
    }
}
