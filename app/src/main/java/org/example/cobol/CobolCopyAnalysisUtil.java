package org.example.cobol;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.example.tools.FileReaderTool;

/**
 * COPY 文の論理行解析、REPLACING 展開、依存解決を扱うユーティリティです。
 */
public class CobolCopyAnalysisUtil {

    private static final Pattern COPY_STATEMENT_PATTERN = Pattern.compile(
            "(?i)^COPY\\s+['\"]?([A-Za-z0-9_-]+)['\"]?(?:\\s+REPLACING\\s+(.+))?$");
    private static final Pattern REPLACEMENT_PATTERN = Pattern.compile(
            "(?i)(==.*?==|[A-Za-z0-9:_-]+)\\s+BY\\s+(==.*?==|[A-Za-z0-9:_-]+)");

    private final CobolColumnAnalysisUtil columnAnalysisUtil;
    private final FileReaderTool fileReaderTool;

    /**
     * ユーティリティを初期化します。
     */
    public CobolCopyAnalysisUtil() {
        this.columnAnalysisUtil = new CobolColumnAnalysisUtil();
        this.fileReaderTool = new FileReaderTool();
    }

    /**
     * 生の COBOL ソースから COPY 文一覧を抽出します。
     *
     * @param content COBOL ソース全体
     * @return COPY 文一覧
     */
    public List<CopyStatement> extractCopyStatements(String content) {
        return extractCopyStatementsFromLogicalLines(normalizeCopyAnalysisLines(content));
    }

    /**
     * 論理行一覧から COPY 文一覧を抽出します。
     *
     * @param logicalLines 論理行一覧
     * @return COPY 文一覧
     */
    public List<CopyStatement> extractCopyStatementsFromLogicalLines(List<LogicalLine> logicalLines) {
        List<CopyStatement> copyStatements = new ArrayList<>();
        for (LogicalLine logicalLine : logicalLines) {
            String trimmed = logicalLine.text().trim();
            if (trimmed.isEmpty() || trimmed.startsWith("*")) {
                continue;
            }

            String statementText = trimmed.replaceAll("\\.$", "").trim();
            var matcher = COPY_STATEMENT_PATTERN.matcher(statementText);
            if (!matcher.find()) {
                continue;
            }

            copyStatements.add(new CopyStatement(
                    matcher.group(1),
                    parseCopyReplacements(matcher.group(2)),
                    logicalLine.lineNumber()));
        }
        return copyStatements;
    }

    /**
     * 固定形式・継続行を考慮して COPY 解析用の論理行へ変換します。
     *
     * @param content COBOL ソース全体
     * @return 論理行一覧
     */
    public List<LogicalLine> normalizeCopyAnalysisLines(String content) {
        List<LogicalLine> logicalLines = new ArrayList<>();
        if (content == null || content.isBlank()) {
            return logicalLines;
        }

        String[] lines = content.split("\\r?\\n", -1);
        StringBuilder currentText = null;
        int currentLineNumber = 1;

        for (int i = 0; i < lines.length; i++) {
            String rawLine = lines[i];
            boolean continuation = isContinuationLine(rawLine);
            String statementArea = extractStatementArea(rawLine).stripTrailing();

            if (currentText == null) {
                currentText = new StringBuilder(statementArea);
                currentLineNumber = i + 1;
                continue;
            }

            if (continuation) {
                currentText.append(' ').append(statementArea.trim());
            } else {
                logicalLines.add(new LogicalLine(currentLineNumber, currentText.toString().trim()));
                currentText = new StringBuilder(statementArea);
                currentLineNumber = i + 1;
            }
        }

        if (currentText != null) {
            logicalLines.add(new LogicalLine(currentLineNumber, currentText.toString().trim()));
        }

        return logicalLines;
    }

    /**
     * COPY ブックを読み込み、REPLACING を適用した論理行一覧を返します。
     *
     * @param copybookPath COPY ブックパス
     * @param replacements 置換定義
     * @return 展開後の論理行一覧
     * @throws IOException 読み込み失敗時
     */
    public List<LogicalLine> loadExpandedCopyLines(Path copybookPath, List<CopyReplacement> replacements) throws IOException {
        String rawContent = readRawContent(copybookPath);
        List<LogicalLine> logicalLines = normalizeCopyAnalysisLines(rawContent);
        if (replacements == null || replacements.isEmpty()) {
            return logicalLines;
        }

        List<LogicalLine> expanded = new ArrayList<>();
        for (LogicalLine logicalLine : logicalLines) {
            String replaced = logicalLine.text();
            for (CopyReplacement replacement : replacements) {
                replaced = replacement.apply(replaced);
            }
            expanded.add(new LogicalLine(logicalLine.lineNumber(), replaced));
        }
        return expanded;
    }

    /**
     * COPY ブック索引から対象名に一致するパスを解決します。
     *
     * @param copybookPathMap COPY ブック索引
     * @param copybookName COPY 名
     * @return 見つかったパス。見つからない場合は null
     */
    public Path findCopybookPath(Map<String, Path> copybookPathMap, String copybookName) {
        if (copybookPathMap == null || copybookName == null || copybookName.isBlank()) {
            return null;
        }

        Path path = copybookPathMap.get(copybookName);
        if (path != null) {
            return path;
        }
        path = copybookPathMap.get(copybookName.toUpperCase(Locale.ROOT));
        if (path != null) {
            return path;
        }
        return copybookPathMap.get(copybookName.toLowerCase(Locale.ROOT));
    }

    /**
     * プログラムファイルから、対象カラムへ到達する COPY 依存を抽出します。
     *
     * @param programFile プログラムファイル
     * @param copybookPathMap COPY ブック索引
     * @param targetColumn 対象カラム名
     * @return COPY 依存一覧
     * @throws IOException 読み込み失敗時
     */
    public List<CopyColumnDependency> collectCopyTargetDependencies(
            Path programFile,
            Map<String, Path> copybookPathMap,
            String targetColumn) throws IOException {
        Map<String, CopyColumnDependency> dependencies = new LinkedHashMap<>();
        Set<String> visitedStates = new HashSet<>();
        String programContent = readRawContent(programFile);

        for (CopyStatement copyStatement : extractCopyStatements(programContent)) {
            collectCopyTargetDependencyRecursive(copyStatement, copybookPathMap, targetColumn, 1, null,
                    dependencies, visitedStates);
        }

        List<CopyColumnDependency> sortedDependencies = new ArrayList<>(dependencies.values());
        sortedDependencies.sort(Comparator.comparingInt(CopyColumnDependency::depth)
            .thenComparing(CopyColumnDependency::copybookName));
        return sortedDependencies;
    }

    private boolean collectCopyTargetDependencyRecursive(
            CopyStatement statement,
            Map<String, Path> copybookPathMap,
            String targetColumn,
            int depth,
            String viaCopybook,
            Map<String, CopyColumnDependency> dependencies,
            Set<String> visitedStates) throws IOException {
        String stateKey = buildStateKey(statement, depth, viaCopybook);
        if (!visitedStates.add(stateKey)) {
            return false;
        }

        Path copybookPath = findCopybookPath(copybookPathMap, statement.copybookName());
        if (copybookPath == null) {
            return false;
        }

        List<LogicalLine> expandedLines = loadExpandedCopyLines(copybookPath, statement.replacements());
        boolean directMatch = containsTargetColumn(expandedLines, targetColumn);
        boolean descendantMatch = false;

        for (CopyStatement nestedStatement : extractCopyStatementsFromLogicalLines(expandedLines)) {
            descendantMatch |= collectCopyTargetDependencyRecursive(nestedStatement, copybookPathMap, targetColumn,
                    depth + 1, statement.copybookName(), dependencies, visitedStates);
        }

        if (directMatch || descendantMatch) {
            CopyColumnDependency dependency = new CopyColumnDependency(
                    statement.copybookName(),
                    depth,
                    viaCopybook,
                    copybookPath + ":" + statement.lineNumber());
            dependencies.putIfAbsent(dependency.uniqueKey(), dependency);
            return true;
        }

        return false;
    }

    private List<CopyReplacement> parseCopyReplacements(String replacementText) {
        List<CopyReplacement> replacements = new ArrayList<>();
        if (replacementText == null || replacementText.isBlank()) {
            return replacements;
        }

        var matcher = REPLACEMENT_PATTERN.matcher(replacementText.replaceAll("\\.$", ""));
        while (matcher.find()) {
            String sourceText = matcher.group(1);
            String targetText = matcher.group(2);
            boolean wholeWord = !sourceText.startsWith("==") && !targetText.startsWith("==");
            replacements.add(new CopyReplacement(sourceText, targetText, wholeWord));
        }
        return replacements;
    }

    private boolean containsTargetColumn(List<LogicalLine> logicalLines, String targetColumn) {
        String normalizedTarget = targetColumn == null ? "" : targetColumn.toUpperCase(Locale.ROOT);
        for (LogicalLine logicalLine : logicalLines) {
            String upperLine = logicalLine.text().toUpperCase(Locale.ROOT);
            if (upperLine.startsWith("COPY ")) {
                continue;
            }
            if (upperLine.contains(normalizedTarget)) {
                return true;
            }
        }
        return false;
    }

    private String buildStateKey(CopyStatement statement, int depth, String viaCopybook) {
        StringBuilder builder = new StringBuilder(statement.copybookName())
                .append('|').append(depth)
                .append('|').append(viaCopybook == null ? "" : viaCopybook);
        for (CopyReplacement replacement : statement.replacements()) {
            builder.append('|').append(replacement.signature());
        }
        return builder.toString();
    }

    private boolean isContinuationLine(String rawLine) {
        return rawLine != null
                && rawLine.length() > 6
                && rawLine.substring(0, 6).chars().allMatch(Character::isDigit)
                && rawLine.charAt(6) == '-';
    }

    private String extractStatementArea(String rawLine) {
        if (rawLine == null) {
            return "";
        }
        if (rawLine.length() > 6 && rawLine.substring(0, Math.min(6, rawLine.length())).chars().allMatch(Character::isDigit)) {
            return rawLine.length() > 7 ? rawLine.substring(7) : "";
        }
        return rawLine;
    }

    /**
     * COPY 解析用にファイルの生テキストを読み込みます。
     *
     * @param path 対象パス
     * @return 生テキスト
     */
    private String readRawContent(Path path) {
        if (path == null) {
            return "";
        }
        String rawContent = fileReaderTool.readFile(path.toString());
        return rawContent == null ? "" : rawContent;
    }
}