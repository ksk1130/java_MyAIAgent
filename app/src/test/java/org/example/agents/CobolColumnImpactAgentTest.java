package org.example.agents;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.Test;
import org.example.cobol.CobolColumnAnalysisUtil;

/**
 * CobolColumnImpactAgent のワークフロー補助コンポーネントを検証するテストです。
 */
public class CobolColumnImpactAgentTest {

    /**
     * DependencyAnalyzerAgent が cobolDir と copyDir を使って依存関係DBを再構築できることを確認します。
     *
     * @throws Exception テスト準備または実行に失敗した場合
     */
    @Test
    public void dependencyAnalyzerAgentRebuildsDependencyDatabase() throws Exception {
        var tempRoot = Files.createTempDirectory("cobol-impact-agent-test");
        var originalUserDir = System.getProperty("user.dir");

        try {
            System.setProperty("user.dir", tempRoot.toString());

            var cobolDir = Files.createDirectories(tempRoot.resolve("cobol"));
            var copyDir = Files.createDirectories(tempRoot.resolve("copy"));

            Files.writeString(cobolDir.resolve("TESTPGM.cbl"), String.join("\n",
                    fixedFormatLine("IDENTIFICATION DIVISION."),
                    fixedFormatLine("PROGRAM-ID. TESTPGM."),
                    fixedFormatLine("DATA DIVISION."),
                    fixedFormatLine("WORKING-STORAGE SECTION."),
                    fixedFormatLine("COPY BOOK1."),
                    fixedFormatLine("PROCEDURE DIVISION."),
                    fixedFormatLine("DISPLAY 'OK'.")));

            Files.writeString(copyDir.resolve("BOOK1.cpy"), String.join("\n",
                    fixedFormatLine("01 TEST-REC."),
                    fixedFormatLine("05 ZIPCODE PIC X(10).")));

            var agent = new CobolColumnImpactAgent.DependencyAnalyzerAgent();
            Map<String, Object> result = agent.analyze(cobolDir.toString(), copyDir.toString(), "ZIPCODE");
            var reportPath = tempRoot.resolve("build").resolve("reports").resolve("cobol-dependency-report.md");

            var targetColumnSummary = (CobolColumnAnalysisUtil.ColumnAnalysisSummary) result.get("targetColumnSummary");

            assertEquals(Boolean.TRUE, result.get("success"));
            assertTrue(Files.exists(reportPath));
            assertTrue(Files.readString(reportPath).contains("BOOK1"));
            assertEquals(1, targetColumnSummary.variableCount());
        } finally {
            System.setProperty("user.dir", originalUserDir);
        }
    }

            /**
             * CobolAnalyzerAgent が対象変数への代入文を摘出できることを確認します。
             *
             * @throws Exception テスト準備または実行に失敗した場合
             */
            @Test
            public void cobolAnalyzerAgentExtractsAssignmentStatements() throws Exception {
            var tempRoot = Files.createTempDirectory("cobol-assignment-test");
            var cobolDir = Files.createDirectories(tempRoot.resolve("cobol"));
            var copyDir = Files.createDirectories(tempRoot.resolve("copy"));
            var cobolFile = cobolDir.resolve("TESTPGM.cbl");

            Files.writeString(cobolFile, String.join("\n",
                fixedFormatLine("IDENTIFICATION DIVISION."),
                fixedFormatLine("PROGRAM-ID. TESTPGM."),
                fixedFormatLine("DATA DIVISION."),
                fixedFormatLine("WORKING-STORAGE SECTION."),
                fixedFormatLine("01 POST-REC."),
                fixedFormatLine("05 ZIPCODE PIC X(10)."),
                fixedFormatLine("PROCEDURE DIVISION."),
                fixedFormatLine("MOVE WS-IN-ZIP TO ZIPCODE."),
                fixedFormatLine("EXEC SQL"),
                fixedFormatLine("SELECT ZIPCODE"),
                fixedFormatLine("INTO :ZIPCODE"),
                fixedFormatLine("END-EXEC.")));

            var agent = new CobolColumnImpactAgent.CobolAnalyzerAgent();
            Map<String, Object> result = agent.analyze(
                List.of(cobolFile.toString()),
                "ZIPCODE",
                cobolDir.toString(),
                copyDir.toString());

            @SuppressWarnings("unchecked")
            Map<String, List<CobolColumnAnalysisUtil.AssignmentOccurrence>> fileAssignments =
                (Map<String, List<CobolColumnAnalysisUtil.AssignmentOccurrence>>) result.get("fileAssignments");

            assertTrue(fileAssignments.containsKey(cobolFile.toString()));
            assertEquals(2, fileAssignments.get(cobolFile.toString()).size());
            assertEquals("MOVE TO", fileAssignments.get(cobolFile.toString()).get(0).statementType());
            assertEquals("EXEC SQL INTO", fileAssignments.get(cobolFile.toString()).get(1).statementType());
            }

    /**
     * 固定形式 COBOL の通常行を生成します。
     *
     * @param body 本文
     * @return 固定形式1行
     */
    private static String fixedFormatLine(String body) {
        return "000100 " + body;
    }
}