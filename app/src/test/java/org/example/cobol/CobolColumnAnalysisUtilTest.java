package org.example.cobol;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.nio.file.Files;

import org.junit.Test;

/**
 * CobolColumnAnalysisUtil の解析ロジックを検証するテストです。
 */
public class CobolColumnAnalysisUtilTest {

    /**
     * 固定形式 COBOL から変数定義と代入文を抽出できることを確認します。
     */
    @Test
    public void analyzeContentExtractsVariablesAndAssignments() {
        var util = new CobolColumnAnalysisUtil();
        var content = String.join("\n",
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
                fixedFormatLine("END-EXEC."));

        var result = util.analyzeContent(content, "ZIPCODE");

        assertEquals(1, result.variables().size());
        assertEquals("ZIPCODE", result.variables().get(0).name());
        assertEquals(2, result.assignments().size());
        assertEquals("MOVE TO", result.assignments().get(0).statementType());
        assertEquals("EXEC SQL INTO", result.assignments().get(1).statementType());
    }

    /**
     * 固定形式 COBOL の COPY 句を抽出できることを確認します。
     */
    @Test
    public void extractCopyStatementsSupportsFixedFormat() {
        var util = new CobolColumnAnalysisUtil();
        var content = String.join("\n",
                fixedFormatLine("DATA DIVISION."),
                fixedFormatLine("WORKING-STORAGE SECTION."),
                fixedFormatLine("COPY BOOK1."),
                fixedFormatLine("COPY 'BOOK2'."));

        var copyStatements = util.extractCopyStatements(content);

        assertEquals(2, copyStatements.size());
        assertTrue(copyStatements.contains("BOOK1"));
        assertTrue(copyStatements.contains("BOOK2"));
    }

    /**
     * 正規化済みファイル読み込みが固定形式の行番号領域を除去することを確認します。
     *
     * @throws Exception テスト準備または実行に失敗した場合
     */
    @Test
    public void readNormalizedFileRemovesFixedFormatSequenceArea() throws Exception {
        var util = new CobolColumnAnalysisUtil();
        var tempFile = Files.createTempFile("cobol-normalized", ".cbl");
        Files.writeString(tempFile, String.join("\n",
                fixedFormatLine("IDENTIFICATION DIVISION."),
                fixedFormatLine("PROGRAM-ID. TESTPGM.")));

        var normalized = util.readNormalizedFile(tempFile);

        assertTrue(normalized.contains("PROGRAM-ID. TESTPGM."));
        assertTrue(!normalized.contains("000100 PROGRAM-ID. TESTPGM."));
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