package org.example.cobol;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * symfo_inst.cbl の行50を詳しくデバッグ
 */
public class DebugLine50 {
    
    public static void main(String[] args) {
        CobolColumnAnalysisUtil util = new CobolColumnAnalysisUtil();
        
        Path symfoPath = Path.of("app/src/main/resources/cobol/symfo_inst.cbl");
        String content = util.readNormalizedFile(symfoPath);
        
        // 行50付近を表示
        String[] lines = content.split("\n");
        System.out.println("=== symfo_inst.cbl の行45-55を表示 ===\n");
        for (int i = 44; i < 55 && i < lines.length; i++) {
            String line = lines[i];
            System.out.printf("Line %d: [%s]%n", i + 1, line);
        }
        
        // 全変数定義を収集（HOST_VARS.cpy と symfo_inst.cbl から）
        System.out.println("\n=== 全変数定義を収集 ===\n");
        List<CobolColumnAnalysisUtil.VariableDefinition> allVars = new ArrayList<>();
        
        // HOST_VARS.cpy
        Path hostVarsPath = Path.of("app/src/main/resources/copy/HOST_VARS.cpy");
        String hostVarsContent = util.readNormalizedFile(hostVarsPath);
        CobolColumnAnalysisUtil.ColumnAnalysis hostAnalysis = util.analyzeContent(hostVarsContent, "ZIPCODE");
        allVars.addAll(hostAnalysis.variables());
        
        // symfo_inst.cbl
        CobolColumnAnalysisUtil.ColumnAnalysis symfoAnalysis = util.analyzeContent(content, "ZIPCODE");
        allVars.addAll(symfoAnalysis.variables());
        
        System.out.println("変数定義数: " + allVars.size());
        for (CobolColumnAnalysisUtil.VariableDefinition var : allVars) {
            System.out.printf("  - %s%n", var.name());
        }
        
        // 行50を手動でテスト
        System.out.println("\n=== 行50を手動でテスト ===\n");
        String line50 = lines[49]; // 0-based
        System.out.println("Line 50 content: [" + line50 + "]");
        System.out.println("Trimmed: [" + line50.trim() + "]");
        System.out.println("Upper: [" + line50.trim().toUpperCase() + "]");
        System.out.println();
        
        // ZIPCODE 変数が存在するか確認
        boolean hasZipcode = false;
        for (CobolColumnAnalysisUtil.VariableDefinition var : allVars) {
            if (var.name().equalsIgnoreCase("ZIPCODE")) {
                hasZipcode = true;
                System.out.println("✅ ZIPCODE 変数が見つかりました");
                break;
            }
        }
        
        if (!hasZipcode) {
            System.out.println("❌ ZIPCODE 変数が見つかりません！");
        }
        
        // 代入文を抽出
        System.out.println("\n=== symfo_inst.cbl の代入文を抽出 ===\n");
        List<CobolColumnAnalysisUtil.AssignmentOccurrence> assignments = 
            util.extractAssignmentsOnly(content, allVars);
        
        System.out.println("代入文数: " + assignments.size());
        for (CobolColumnAnalysisUtil.AssignmentOccurrence assign : assignments) {
            System.out.printf("Line %d: %s (%s)%n  Source: %s%n",
                assign.lineNumber(),
                assign.variableName(),
                assign.statementType(),
                assign.sourceLine().length() > 100 ? assign.sourceLine().substring(0, 97) + "..." : assign.sourceLine());
        }
        
        // 行50が含まれているか確認
        boolean found50 = false;
        for (CobolColumnAnalysisUtil.AssignmentOccurrence assign : assignments) {
            if (assign.lineNumber() == 50) {
                found50 = true;
                break;
            }
        }
        
        if (!found50) {
            System.out.println("\n❌ 行50の代入文が検出されませんでした！");
        } else {
            System.out.println("\n✅ 行50の代入文が検出されました");
        }
    }
}
