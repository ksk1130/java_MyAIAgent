package org.example.agents;

import java.util.*;

/**
 * DatabaseQueryAgent の単体テスト
 * Derby DB の依存関係クエリが正常に動作するか確認
 */
public class DatabaseQueryTest {
    
    public static void main(String[] args) {
        System.out.println("=== DatabaseQueryAgent Test ===");
        System.out.println();
        
        DatabaseQueryAgent agent = new DatabaseQueryAgent();
        
        // POST_CD.ZIPCODE への アクセスと推移的影響を検索
        Map<String, Object> result = agent.queryDependencies("POST_CD", "ZIPCODE");
        
        // 結果を表示
        System.out.println("[Direct Access Programs]");
        @SuppressWarnings("unchecked")
        List<Map<String, String>> directPrograms = (List<Map<String, String>>) result.get("directPrograms");
        if (directPrograms != null && !directPrograms.isEmpty()) {
            for (Map<String, String> prog : directPrograms) {
                System.out.println("  - " + prog.get("programId") + " (" + prog.get("filePath") + ")");
            }
        } else {
            System.out.println("  (none)");
        }
        
        System.out.println();
        System.out.println("[Indirect Access Programs (via CALL)]");
        @SuppressWarnings("unchecked")
        List<Map<String, String>> indirectPrograms = (List<Map<String, String>>) result.get("indirectPrograms");
        if (indirectPrograms != null && !indirectPrograms.isEmpty()) {
            for (Map<String, String> prog : indirectPrograms) {
                System.out.println("  - " + prog.get("programId") + " (" + prog.get("filePath") + ")");
                System.out.println("    Access Path: " + prog.get("accessPath") + " [Level " + prog.get("level") + "]");
            }
        } else {
            System.out.println("  (none)");
        }
        
        System.out.println();
        System.out.println("[CALL Dependency Graph]");
        @SuppressWarnings("unchecked")
        Map<String, List<String>> callGraph = (Map<String, List<String>>) result.get("callGraph");
        if (callGraph != null && !callGraph.isEmpty()) {
            for (Map.Entry<String, List<String>> entry : callGraph.entrySet()) {
                System.out.println("  " + entry.getKey() + " calls: " + entry.getValue());
            }
        } else {
            System.out.println("  (no CALL dependencies)");
        }
        
        System.out.println();
        if ((Boolean) result.getOrDefault("success", false)) {
            System.out.println("✓ Query succeeded");
        } else {
            System.err.println("✗ Query failed: " + result.get("error"));
        }
        
        agent.close();
    }
}
