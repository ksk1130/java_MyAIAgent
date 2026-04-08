package org.example.agents;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.Test;

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

            assertEquals(Boolean.TRUE, result.get("success"));
            assertTrue(Files.exists(reportPath));
            assertTrue(Files.readString(reportPath).contains("BOOK1"));
        } finally {
            System.setProperty("user.dir", originalUserDir);
        }
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