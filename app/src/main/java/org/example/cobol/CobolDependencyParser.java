package org.example.cobol;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.example.tools.FileReaderTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.google.common.base.Strings;

/**
 * CALL/COPY/SQL依存の抽出・解析を担当するクラス。
 */
public class CobolDependencyParser {
    private final Connection connection;
    private final Map<String, Path> copybookPathMap;
    private static final Logger logger = LoggerFactory.getLogger(CobolDependencyParser.class);

    public CobolDependencyParser(Connection connection, Map<String, Path> copybookPathMap) {
        this.connection = connection;
        this.copybookPathMap = copybookPathMap;
    }

    /**
     * プログラム間のCALL依存関係およびDBテーブルアクセスを解析します（フェーズ2）。
     */
    public void analyzeDependencies(Path cobolFile) throws IOException {
        logger.info("[解析] {}", cobolFile.getFileName());
        var content = getFileContent(cobolFile);
        var programId = extractProgramId(content);
        if (programId == null) {
            System.out.println("  警告: プログラムID が見つかりません");
            return;
        }
        detectTableAccess(programId, content, cobolFile);
        detectCallDependencies(programId, content, cobolFile);
        detectCopyDependencies(programId, content, cobolFile);
    }

    /**
     * COBOL ソースから PROGRAM-ID を抽出します。
     */
    private String extractProgramId(String content) {
        var pattern = Pattern.compile("(?i)PROGRAM-ID\\.\\s+([\\w-]+)");
        var matcher = pattern.matcher(content);
        if (matcher.find()) {
            return matcher.group(1).toLowerCase().replace('-', '_');
        }
        return null;
    }

    /**
     * ソースコード内のSQL文を検出し、テーブルアクセスの情報をデータベースに登録します。
     */
    private void detectTableAccess(String programId, String content, Path cobolFile) {
        var insertPattern = Pattern.compile("(?i)INSERT\\s+INTO\\s+([\\w_]+)");
        var insertMatcher = insertPattern.matcher(content);
        while (insertMatcher.find()) {
            var tableName = insertMatcher.group(1).toUpperCase();
            logger.info("  - INSERT: {}", tableName);
            detectColumnsInInsert(programId, tableName, content, cobolFile,
                    findLineNumber(content, insertMatcher.start()));
        }

        var selectPattern = Pattern.compile("(?i)SELECT\\s+.*?\\sFROM\\s+([\\w_]+)");
        var selectMatcher = selectPattern.matcher(content);
        while (selectMatcher.find()) {
            var tableName = selectMatcher.group(1).toUpperCase();
            logger.info("  - SELECT: {}", tableName);
            registerTableAccess(programId, tableName, "ALL_COLUMNS", "SELECT",
                    buildSourceLocation(cobolFile, findLineNumber(content, selectMatcher.start())));
        }

        var updatePattern = Pattern.compile("(?i)UPDATE\\s+([\\w_]+)");
        var updateMatcher = updatePattern.matcher(content);
        while (updateMatcher.find()) {
            var tableName = updateMatcher.group(1).toUpperCase();
            logger.info("  - UPDATE: {}", tableName);
            registerTableAccess(programId, tableName, "ALL_COLUMNS", "UPDATE",
                    buildSourceLocation(cobolFile, findLineNumber(content, updateMatcher.start())));
        }

        var deletePattern = Pattern.compile("(?i)DELETE\\s+FROM\\s+([\\w_]+)");
        var deleteMatcher = deletePattern.matcher(content);
        while (deleteMatcher.find()) {
            var tableName = deleteMatcher.group(1).toUpperCase();
            logger.info("  - DELETE: {}", tableName);
            registerTableAccess(programId, tableName, "ALL_COLUMNS", "DELETE",
                    buildSourceLocation(cobolFile, findLineNumber(content, deleteMatcher.start())));
        }
    }

    /**
     * INSERT 文の VALUES 句からカラム名を抽出して登録します。
     */
    private void detectColumnsInInsert(String programId, String tableName,
            String content, Path cobolFile, int lineNumber) {
        var valuesPattern = Pattern.compile("(?i)VALUES\\s*\\(([^)]+)\\)");
        var valuesMatcher = valuesPattern.matcher(content);
        var location = buildSourceLocation(cobolFile, lineNumber);

        if (valuesMatcher.find()) {
            var values = valuesMatcher.group(1);
            var parts = values.split(",");
            for (var part : parts) {
                var column = part.trim().replace(":", "").toUpperCase();
                if (!column.isEmpty()) {
                    registerTableAccess(programId, tableName, column, "INSERT", location);
                }
            }
        }
    }

    /**
     * ソースコード内のCALL文を検出し、他プログラムへの依存関係を登録します。
     */
    private void detectCallDependencies(String programId, String content, Path cobolFile) {
        var callPattern = Pattern.compile("(?i)CALL\\s+['\"]?([\\w-]+)['\"]?");
        var callMatcher = callPattern.matcher(content);
        while (callMatcher.find()) {
            var calledProgram = callMatcher.group(1).toLowerCase().replace('-', '_');
            logger.info("  - CALL: {}", calledProgram);
            registerCallDependency(programId, calledProgram,
                    buildSourceLocation(cobolFile, findLineNumber(content, callMatcher.start())));
        }
    }

    /**
     * ソースコード内のCOPY句を検出し、コピーブック参照関係を登録します。
     */
    private void detectCopyDependencies(String programId, String content, Path cobolFile) {
        var dependencyInfoMap = new LinkedHashMap<String, CopyDependencyInfo>();
        var queue = new ArrayDeque<CopyTraversalNode>();
        var visitedStates = new HashSet<String>();

        for (var copyStatement : extractCopyStatements(content)) {
            logger.info("  - COPY: {}", copyStatement.copybookName());
            dependencyInfoMap.putIfAbsent(copyStatement.copybookName(),
                new CopyDependencyInfo(1, null, buildSourceLocation(cobolFile, copyStatement.lineNumber())));
            queue.addLast(new CopyTraversalNode(copyStatement, 1, null,
                buildSourceLocation(cobolFile, copyStatement.lineNumber())));
        }

        while (!queue.isEmpty()) {
            var current = queue.removeFirst();
            if (!visitedStates.add(current.stateKey())) {
                continue;
            }

            var currentCopybookPath = findCopybookPath(current.statement().copybookName());
            if (currentCopybookPath == null) {
                logger.debug("COPYブックが見つかりません: {}", current.statement().copybookName());
                continue;
            }

            var expandedCopyLines = loadExpandedCopyLines(currentCopybookPath, current.statement().replacements());
            for (var nestedCopyStatement : extractCopyStatementsFromLogicalLines(expandedCopyLines)) {
                var nextDepth = current.depth() + 1;
                var nestedCopybookName = nestedCopyStatement.copybookName();
                var existing = dependencyInfoMap.get(nestedCopybookName);
                if (existing == null || nextDepth < existing.depth()) {
                    logger.info("  - COPY(depth={}): {} via {}", nextDepth, nestedCopybookName,
                            current.statement().copybookName());
                    dependencyInfoMap.put(nestedCopybookName,
                            new CopyDependencyInfo(nextDepth, current.statement().copybookName(),
                            buildSourceLocation(currentCopybookPath, nestedCopyStatement.lineNumber())));
                    queue.addLast(new CopyTraversalNode(nestedCopyStatement, nextDepth,
                        current.statement().copybookName(),
                        buildSourceLocation(currentCopybookPath, nestedCopyStatement.lineNumber())));
                }
            }
        }

        dependencyInfoMap.forEach((copybookName, info) -> registerCopyDependency(
                programId,
                copybookName,
                info.location(),
                info.depth(),
                info.viaCopybook()));
    }

    // ヘルパーメソッドは省略（Analyzer本体から逐次移動）

    private List<CopyStatement> extractCopyStatements(String content) {
        return extractCopyStatementsFromLogicalLines(normalizeCopyAnalysisLines(content));
    }

    private List<CopyStatement> extractCopyStatementsFromLogicalLines(List<LogicalLine> logicalLines) {
        // 実装は後述
        return new ArrayList<>();
    }

    private List<LogicalLine> normalizeCopyAnalysisLines(String content) {
        // 実装は後述
        return new ArrayList<>();
    }

    private Path findCopybookPath(String copybookName) {
        return copybookPathMap.get(copybookName);
    }

    private List<LogicalLine> loadExpandedCopyLines(Path copybookPath, List<CopyReplacement> replacements) {
        // 実装は後述
        return new ArrayList<>();
    }

    private void registerTableAccess(String programId, String tableName, String column, String accessType, String location) {
        var columnId = tableName + ":" + column;
        var accessId = programId + ":" + columnId + ":" + accessType;
        var sql = """
            INSERT INTO cobol_table_access (access_id, program_id, column_id, access_type, sql_location)
            VALUES (?, ?, ?, ?, ?)
        """;
        try (var pstmt = connection.prepareStatement(sql)) {
            pstmt.setString(1, accessId);
            pstmt.setString(2, programId);
            pstmt.setString(3, columnId);
            pstmt.setString(4, accessType);
            pstmt.setString(5, location);
            pstmt.execute();
        } catch (Exception e) {
            logger.debug("テーブルアクセス登録: {}", e.getMessage());
        }
    }

    private void registerCallDependency(String programId, String calledProgram, String location) {
        var depId = programId + "->" + calledProgram;
        var sql = """
            INSERT INTO cobol_call_dependency (dep_id, caller_program_id, callee_program_id, call_location)
            VALUES (?, ?, ?, ?)
        """;
        try (var pstmt = connection.prepareStatement(sql)) {
            pstmt.setString(1, depId);
            pstmt.setString(2, programId);
            pstmt.setString(3, calledProgram);
            pstmt.setString(4, location);
            pstmt.execute();
        } catch (Exception e) {
            logger.debug("CALL依存関係登録: {}", e.getMessage());
        }
    }

    private void registerCopyDependency(String programId, String copybookName, String location, int depth, String viaCopybook) {
        var depId = programId + "->" + copybookName;
        var sql = """
            INSERT INTO cobol_copy_dependency (dep_id, program_id, copybook_name, copy_location, copy_depth, via_copybook)
            VALUES (?, ?, ?, ?, ?, ?)
        """;
        try (var pstmt = connection.prepareStatement(sql)) {
            pstmt.setString(1, depId);
            pstmt.setString(2, programId);
            pstmt.setString(3, copybookName);
            pstmt.setString(4, location);
            pstmt.setInt(5, depth);
            pstmt.setString(6, viaCopybook);
            pstmt.execute();
        } catch (Exception e) {
            logger.debug("COPY依存関係登録: {}", e.getMessage());
        }
    }

    private int findLineNumber(String content, int position) {
        var lineCount = 1;
        for (var i = 0; i < position && i < content.length(); i++) {
            if (content.charAt(i) == '\n') {
                lineCount++;
            }
        }
        return lineCount;
    }

    private String buildSourceLocation(Path file, int lineNumber) {
        return file.toString() + ":" + lineNumber;
    }

    private static String getFileContent(Path p) {
        var fr = new FileReaderTool();
        var raw = fr.readFile(p.toString());
        if (Strings.isNullOrEmpty(raw)) {
            return "";
        }
        var sb = new StringBuilder(raw.length());
        var lines = raw.split("\\r?\\n", -1);
        for (var i = 0; i < lines.length; i++) {
            var line = lines[i];
            if (line.length() > 6) {
                sb.append(line.substring(6));
            } else {
                sb.append("");
            }
            if (i < lines.length - 1) {
                sb.append('\n');
            }
        }
        return sb.toString();
    }
}
