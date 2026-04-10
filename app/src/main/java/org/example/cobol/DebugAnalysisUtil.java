package org.example.cobol;

import java.nio.file.Path;
import java.util.List;

/**
 * CobolColumnAnalysisUtil のデバッグテスト
 */
public class DebugAnalysisUtil {
    
    public static void main(String[] args) {
        CobolColumnAnalysisUtil util = new CobolColumnAnalysisUtil();
        
        // symfo_inst.cbl を解析
        Path symfoInstPath = Path.of("app/src/main/resources/cobol/symfo_inst.cbl");
        System.out.println("=== symfo_inst.cbl を解析中 ===");
        System.out.println("対象カラム: ZIPCODE\n");
        
        CobolColumnAnalysisUtil.ColumnAnalysis analysis = util.analyzeFile(symfoInstPath, "ZIPCODE");
        
        System.out.println("【変数定義】");
        for (CobolColumnAnalysisUtil.VariableDefinition var : analysis.variables()) {
            System.out.printf("  - %s (Level %s) %s%n", var.name(), var.level(), var.picClause());
        }
        
        System.out.println("\n【代入文】");
        for (CobolColumnAnalysisUtil.AssignmentOccurrence assign : analysis.assignments()) {
            System.out.printf("  - Line %d: %s -> %s%n    Type: %s%n    Source: %s%n",
                assign.lineNumber(),
                assign.variableName(),
                "ASSIGNMENT",
                assign.statementType(),
                assign.sourceLine().length() > 80 ? assign.sourceLine().substring(0, 77) + "..." : assign.sourceLine());
        }
        
        System.out.println("\n=== call_symfo_inst.cbl を解析中 ===");
        Path callSymfoPath = Path.of("app/src/main/resources/cobol/call_symfo_inst.cbl");
        CobolColumnAnalysisUtil.ColumnAnalysis analysis2 = util.analyzeFile(callSymfoPath, "ZIPCODE");
        
        System.out.println("【変数定義】");
        for (CobolColumnAnalysisUtil.VariableDefinition var : analysis2.variables()) {
            System.out.printf("  - %s (Level %s) %s%n", var.name(), var.level(), var.picClause());
        }
        
        System.out.println("\n【代入文】");
        for (CobolColumnAnalysisUtil.AssignmentOccurrence assign : analysis2.assignments()) {
            System.out.printf("  - Line %d: %s%n    Type: %s%n    Source: %s%n",
                assign.lineNumber(),
                assign.variableName(),
                assign.statementType(),
                assign.sourceLine().length() > 80 ? assign.sourceLine().substring(0, 77) + "..." : assign.sourceLine());
        }
    }
}
