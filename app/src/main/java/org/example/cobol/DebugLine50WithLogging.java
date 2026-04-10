package org.example.cobol;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 行50のデバッグ - システムプロパティでデバッグモード有効化
 */
public class DebugLine50WithLogging {
    
    public static void main(String[] args) {
        // デバッグモード有効化
        System.setProperty("debug.assignments", "true");
        
        CobolColumnAnalysisUtil util = new CobolColumnAnalysisUtil();
        
        // 全変数定義を収集
        List<CobolColumnAnalysisUtil.VariableDefinition> allVars = new ArrayList<>();
        
        // HOST_VARS.cpy
        Path hostVarsPath = Path.of("app/src/main/resources/copy/HOST_VARS.cpy");
        String hostVarsContent = util.readNormalizedFile(hostVarsPath);
        CobolColumnAnalysisUtil.ColumnAnalysis hostAnalysis = util.analyzeContent(hostVarsContent, "ZIPCODE");
        allVars.addAll(hostAnalysis.variables());
        
        // symfo_inst.cbl
        Path symfoPath = Path.of("app/src/main/resources/cobol/symfo_inst.cbl");
        String content = util.readNormalizedFile(symfoPath);
        CobolColumnAnalysisUtil.ColumnAnalysis symfoAnalysis = util.analyzeContent(content, "ZIPCODE");
        allVars.addAll(symfoAnalysis.variables());
        
        System.out.println("\n=== 変数リスト ===");
        for (CobolColumnAnalysisUtil.VariableDefinition var : allVars) {
            System.out.println("  - " + var.name());
        }
        
        System.out.println("\n=== symfo_inst.cbl の代入文を抽出（デバッグモード） ===\n");
        List<CobolColumnAnalysisUtil.AssignmentOccurrence> assignments = 
            util.extractAssignmentsOnly(content, allVars);
        
        System.out.println("\n=== 結果 ===");
        System.out.println("代入文数: " + assignments.size());
        for (CobolColumnAnalysisUtil.AssignmentOccurrence assign : assignments) {
            System.out.printf("Line %d: %s (%s)%n",
                assign.lineNumber(),
                assign.variableName(),
                assign.statementType());
        }
    }
}
