package org.example.cobol;

import org.example.agents.DatabaseQueryAgent;
import java.util.*;

/**
 * ハイブリッド統合デモ：CobolDependencyAnalyzer + DatabaseQueryAgent
 *
 * 以下のワークフローを実行します：
 * 1. Derby DB（CobolDependencyAnalyzer で生成）からテーブルアクセス情報を検索
 * 2. DIRECT および INDIRECT 依存関係を表示
 * 3. マークダウンレポート（簡易版）を生成
 */
public class HybridAnalysisDemo {
    
    public static void main(String[] args) {
        String tableName = "POST_CD";
        String columnName = "ZIPCODE";
        
        // args がある場合はそこから取得
        if (args.length >= 2) {
            tableName = args[0];
            columnName = args[1];
        }
        
        System.out.println("=" .repeat(70));
        System.out.println("COBOL スキーマ影響分析 - ハイブリッド統合デモ");
        System.out.println("=" .repeat(70));
        System.out.println();
        System.out.println("対象テーブル: " + tableName);
        System.out.println("対象カラム:   " + columnName);
        System.out.println();
        
        DatabaseQueryAgent agent = new DatabaseQueryAgent();
        Map<String, Object> result = agent.queryDependencies(tableName, columnName);
        
        if (!(Boolean) result.getOrDefault("success", false)) {
            System.err.println("エラー: " + result.get("error"));
            System.exit(1);
        }
        
        // 1. DIRECT アクセス
        System.out.println("📌 直接アクセス (DIRECT)");
        System.out.println("-" .repeat(70));
        @SuppressWarnings("unchecked")
        List<Map<String, String>> directPrograms = 
            (List<Map<String, String>>) result.get("directPrograms");
        
        if (directPrograms != null && !directPrograms.isEmpty()) {
            for (Map<String, String> prog : directPrograms) {
                System.out.println(
                    String.format("  %-25s %s", 
                        prog.get("programId"),
                        prog.get("filePath"))
                );
            }
        } else {
            System.out.println("  (アクセスプログラムなし)");
        }
        System.out.println();
        
        // 2. INDIRECT アクセス
        System.out.println("🔗 間接アクセス (INDIRECT - CALL 経由)");
        System.out.println("-" .repeat(70));
        @SuppressWarnings("unchecked")
        List<Map<String, String>> indirectPrograms = 
            (List<Map<String, String>>) result.get("indirectPrograms");
        
        if (indirectPrograms != null && !indirectPrograms.isEmpty()) {
            for (Map<String, String> prog : indirectPrograms) {
                System.out.println(
                    String.format("  %-25s %s", 
                        prog.get("programId"),
                        prog.get("filePath"))
                );
                System.out.println(
                    String.format("    → %s", prog.get("accessPath"))
                );
            }
        } else {
            System.out.println("  (推移的アクセスなし)");
        }
        System.out.println();
        
        // 3. 影響プログラム一覧
        int totalPrograms = (directPrograms != null ? directPrograms.size() : 0) +
                           (indirectPrograms != null ? indirectPrograms.size() : 0);
        System.out.println("📊 影響プログラム数");
        System.out.println("-" .repeat(70));
        System.out.println(
            String.format("  DIRECT:   %d プログラム", 
                directPrograms != null ? directPrograms.size() : 0)
        );
        System.out.println(
            String.format("  INDIRECT: %d プログラム", 
                indirectPrograms != null ? indirectPrograms.size() : 0)
        );
        System.out.println(
            String.format("  合計:      %d プログラム", totalPrograms)
        );
        System.out.println();
        
        // 4. リスク評価
        System.out.println("⚠️  リスク評価");
        System.out.println("-" .repeat(70));
        if (totalPrograms == 0) {
            System.out.println("  リスク: 低 - スキーマ変更の影響範囲なし");
        } else if (totalPrograms <= 2) {
            System.out.println("  リスク: 中 - " + totalPrograms + " プログラムに影響あり");
        } else {
            System.out.println("  リスク: 高 - " + totalPrograms + " プログラムに影響あり（詳細確認推奨）");
        }
        System.out.println();
        
        System.out.println("=" .repeat(70));
        System.out.println("分析完了");
        System.out.println("=" .repeat(70));
        
        agent.close();
    }
}
