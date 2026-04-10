package org.example.cobol;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;

/**
 * CobolCopyAnalysisUtil の COPY 解析を検証するテストです。
 */
public class CobolCopyAnalysisUtilTest {

    /**
     * 継続行をまたぐ COPY ... REPLACING でも対象カラム依存を検出できることを確認します。
     *
     * @throws Exception テスト準備または実行に失敗した場合
     */
    @Test
    public void collectCopyTargetDependenciesSupportsReplacingAcrossContinuationLines() throws Exception {
        var rootDir = Files.createTempDirectory("cobol-copy-test");
        var cobolDir = Files.createDirectories(rootDir.resolve("cobol"));
        var copyDir = Files.createDirectories(rootDir.resolve("copy"));

        var programFile = cobolDir.resolve("TESTPROG.cbl");
        Files.writeString(programFile, String.join("\n",
                fixedFormatLine("IDENTIFICATION DIVISION."),
                fixedFormatLine("PROGRAM-ID. TESTPROG."),
                fixedFormatLine("DATA DIVISION."),
                fixedFormatLine("WORKING-STORAGE SECTION."),
                fixedFormatLine("COPY BOOK1 REPLACING ==:TARGET-NAME:=="),
                continuationLine("BY ==ZIPCODE==.")));

        var copybookFile = copyDir.resolve("BOOK1.cpy");
        Files.writeString(copybookFile, String.join("\n",
                fixedFormatLine("01 TEST-REC."),
                fixedFormatLine("05 ==:TARGET-NAME:== PIC X(10).")));

        var util = new CobolCopyAnalysisUtil();
        var dependencies = util.collectCopyTargetDependencies(programFile, buildCopybookPathMap(copybookFile), "ZIPCODE");

        assertEquals(1, dependencies.size());
        assertEquals("BOOK1", dependencies.get(0).copybookName());
        assertEquals(1, dependencies.get(0).depth());
        assertNull(dependencies.get(0).viaCopybook());
    }

    /**
     * ネストした COPY を仮想展開して親 COPY でも対象カラム依存を検出できることを確認します。
     *
     * @throws Exception テスト準備または実行に失敗した場合
     */
    @Test
    public void collectCopyTargetDependenciesExpandsNestedCopyReplacing() throws Exception {
        var rootDir = Files.createTempDirectory("cobol-copy-nested-test");
        var cobolDir = Files.createDirectories(rootDir.resolve("cobol"));
        var copyDir = Files.createDirectories(rootDir.resolve("copy"));

        var programFile = cobolDir.resolve("TESTNEST.cbl");
        Files.writeString(programFile, String.join("\n",
                fixedFormatLine("IDENTIFICATION DIVISION."),
                fixedFormatLine("PROGRAM-ID. TESTNEST."),
                fixedFormatLine("DATA DIVISION."),
                fixedFormatLine("WORKING-STORAGE SECTION."),
                fixedFormatLine("COPY BOOK1.")));

        var book1 = copyDir.resolve("BOOK1.cpy");
        Files.writeString(book1, String.join("\n",
                fixedFormatLine("01 OUTER-REC."),
                fixedFormatLine("COPY BOOK2 REPLACING ==:FIELD:== BY ==ZIPCODE==.")));

        var book2 = copyDir.resolve("BOOK2.cpy");
        Files.writeString(book2, String.join("\n",
                fixedFormatLine("05 ==:FIELD:== PIC X(10).")));

        var util = new CobolCopyAnalysisUtil();
        var dependencies = util.collectCopyTargetDependencies(programFile, buildCopybookPathMap(book1, book2), "ZIPCODE");

        assertEquals(2, dependencies.size());
        assertEquals("BOOK1", dependencies.get(0).copybookName());
        assertEquals(1, dependencies.get(0).depth());
        assertEquals("BOOK2", dependencies.get(1).copybookName());
        assertEquals(2, dependencies.get(1).depth());
        assertEquals("BOOK1", dependencies.get(1).viaCopybook());
    }

    /**
     * COPY 名から拡張子付き/なし、大小文字差異を吸収して解決できることを確認します。
     *
     * @throws Exception テスト準備または実行に失敗した場合
     */
    @Test
    public void findCopybookPathResolvesExplicitCopybookName() throws Exception {
        var rootDir = Files.createTempDirectory("cobol-copy-priority-test");
        var explicitCopybook = rootDir.resolve("BOOK1.cpy");
        Files.writeString(explicitCopybook, String.join("\n",
                fixedFormatLine("01 EXPLICIT-REC."),
                fixedFormatLine("05 ZIPCODE PIC X(10).")));

        var util = new CobolCopyAnalysisUtil();
        var copybookPath = util.findCopybookPath(buildCopybookPathMap(explicitCopybook), "BOOK1");

        assertNotNull(copybookPath);
        assertEquals(explicitCopybook.toAbsolutePath().normalize(), copybookPath.toAbsolutePath().normalize());
    }

    /**
     * COPY ブック索引を構築します。
     *
     * @param copybooks COPY ブック一覧
     * @return 索引
     */
    private static Map<String, Path> buildCopybookPathMap(Path... copybooks) {
        Map<String, Path> copybookPathMap = new LinkedHashMap<>();
        for (Path copybook : copybooks) {
            String fileName = copybook.getFileName().toString();
            copybookPathMap.put(fileName, copybook);
            copybookPathMap.put(fileName.substring(0, fileName.lastIndexOf('.')), copybook);
        }
        return copybookPathMap;
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

    /**
     * 固定形式 COBOL の継続行を生成します。
     *
     * @param body 本文
     * @return 継続行
     */
    private static String continuationLine(String body) {
        return "000100-" + body;
    }
}