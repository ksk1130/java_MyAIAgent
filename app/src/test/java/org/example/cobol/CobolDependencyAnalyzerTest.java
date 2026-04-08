package org.example.cobol;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.Test;

/**
 * CobolDependencyAnalyzer の COPY 解析を検証するテストです。
 */
public class CobolDependencyAnalyzerTest {

    /**
     * 継続行をまたぐ COPY ... REPLACING でも対象カラム依存を検出できることを確認します。
     *
     * @throws Exception テスト準備またはリフレクション呼び出しに失敗した場合
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

        var analyzer = new CobolDependencyAnalyzer(cobolDir.toString());
        analyzer.setTargetColumn("ZIPCODE");
        invokePrivate(analyzer, "indexCopybookFiles", new Class<?>[] { Path.class }, cobolDir);

        var dependencies = invokeCollectCopyTargetDependencies(analyzer, programFile);
        assertEquals(1, dependencies.size());
        assertEquals("BOOK1", readRecordComponent(dependencies.get(0), "copybookName"));
        assertEquals(1, readRecordComponent(dependencies.get(0), "depth"));
        assertNull(readRecordComponent(dependencies.get(0), "viaCopybook"));
    }

    /**
     * ネストした COPY を仮想展開して親 COPY でも対象カラム依存を検出できることを確認します。
     *
     * @throws Exception テスト準備またはリフレクション呼び出しに失敗した場合
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

        Files.writeString(copyDir.resolve("BOOK1.cpy"), String.join("\n",
                fixedFormatLine("01 OUTER-REC."),
                fixedFormatLine("COPY BOOK2 REPLACING ==:FIELD:== BY ==ZIPCODE==.")));

        Files.writeString(copyDir.resolve("BOOK2.cpy"), String.join("\n",
                fixedFormatLine("05 ==:FIELD:== PIC X(10).")));

        var analyzer = new CobolDependencyAnalyzer(cobolDir.toString());
        analyzer.setTargetColumn("ZIPCODE");
        invokePrivate(analyzer, "indexCopybookFiles", new Class<?>[] { Path.class }, cobolDir);

        var dependencies = invokeCollectCopyTargetDependencies(analyzer, programFile);
        assertEquals(2, dependencies.size());
        assertEquals("BOOK1", readRecordComponent(dependencies.get(0), "copybookName"));
        assertEquals(1, readRecordComponent(dependencies.get(0), "depth"));
        assertEquals("BOOK2", readRecordComponent(dependencies.get(1), "copybookName"));
        assertEquals(2, readRecordComponent(dependencies.get(1), "depth"));
        assertEquals("BOOK1", readRecordComponent(dependencies.get(1), "viaCopybook"));
    }

    /**
     * 明示指定した COPY ディレクトリが自動探索より優先されることを確認します。
     *
     * @throws Exception テスト準備またはリフレクション呼び出しに失敗した場合
     */
    @Test
    public void indexCopybookFilesPrioritizesExplicitCopyDir() throws Exception {
        var rootDir = Files.createTempDirectory("cobol-copy-priority-test");
        var cobolDir = Files.createDirectories(rootDir.resolve("cobol"));
        var autoCopyDir = Files.createDirectories(rootDir.resolve("copy"));
        var explicitCopyDir = Files.createDirectories(rootDir.resolve("copy-explicit"));

        Files.writeString(autoCopyDir.resolve("BOOK1.cpy"), String.join("\n",
                fixedFormatLine("01 AUTO-REC."),
                fixedFormatLine("05 AUTO-FIELD PIC X(10).")));

        var explicitCopybook = explicitCopyDir.resolve("BOOK1.cpy");
        Files.writeString(explicitCopybook, String.join("\n",
                fixedFormatLine("01 EXPLICIT-REC."),
                fixedFormatLine("05 ZIPCODE PIC X(10).")));

        var analyzer = new CobolDependencyAnalyzer(cobolDir.toString(), explicitCopyDir.toString());
        invokePrivate(analyzer, "indexCopybookFiles", new Class<?>[] { Path.class }, cobolDir);

        var copybookPath = (Path) invokePrivate(analyzer, "findCopybookPath", new Class<?>[] { String.class }, "BOOK1");
        assertNotNull(copybookPath);
        assertEquals(explicitCopybook.toAbsolutePath().normalize(), copybookPath.toAbsolutePath().normalize());
    }

    /**
     * private メソッドを呼び出します。
     *
     * @param target 対象インスタンス
     * @param methodName メソッド名
     * @param parameterTypes 引数型一覧
     * @param args 実引数
     * @return 実行結果
     * @throws Exception リフレクション呼び出しに失敗した場合
     */
    private static Object invokePrivate(Object target, String methodName, Class<?>[] parameterTypes, Object... args)
            throws Exception {
        var method = target.getClass().getDeclaredMethod(methodName, parameterTypes);
        method.setAccessible(true);
        return method.invoke(target, args);
    }

    /**
     * collectCopyTargetDependencies の結果を取得します。
     *
     * @param analyzer テスト対象
     * @param programFile プログラムファイル
     * @return 依存関係一覧
     * @throws Exception リフレクション呼び出しに失敗した場合
     */
    @SuppressWarnings("unchecked")
    private static List<Object> invokeCollectCopyTargetDependencies(CobolDependencyAnalyzer analyzer, Path programFile)
            throws Exception {
        return (List<Object>) invokePrivate(analyzer, "collectCopyTargetDependencies", new Class<?>[] { Path.class },
                programFile);
    }

    /**
     * private record のコンポーネント値を取得します。
     *
     * @param record 対象 record
     * @param componentName コンポーネント名
     * @return 値
     * @throws Exception リフレクション呼び出しに失敗した場合
     */
    private static Object readRecordComponent(Object record, String componentName) throws Exception {
        Method accessor = record.getClass().getDeclaredMethod(componentName);
        accessor.setAccessible(true);
        return accessor.invoke(record);
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