package org.example.cobol;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import org.example.tools.FileReaderTool;

import com.google.common.base.Strings;

/**
 * 対象カラムに関連する COBOL 変数定義と代入文を解析するユーティリティです。
 */
public class CobolColumnAnalysisUtil {

    private final FileReaderTool fileReaderTool;

    /**
     * 変数定義情報を保持するレコードです。
     *
     * @param name 変数名
     * @param level レベル番号
     * @param picClause PIC 句
     * @param description コメントや補足説明
     */
    public record VariableDefinition(String name, String level, String picClause, String description) {
    }

    /**
     * 変数への代入文を保持するレコードです。
     *
     * @param variableName 対象変数名
     * @param lineNumber 行番号
     * @param statementType 代入種別
     * @param sourceLine 元のソース行
     */
    public record AssignmentOccurrence(String variableName, int lineNumber, String statementType, String sourceLine) {
    }

    /**
     * 単一ファイルの解析結果を保持するレコードです。
     *
     * @param variables 変数定義一覧
     * @param assignments 代入文一覧
     */
    public record ColumnAnalysis(List<VariableDefinition> variables, List<AssignmentOccurrence> assignments) {
    }

    /**
     * 対象カラム解析の集計結果を保持するレコードです。
     *
     * @param columnName 対象カラム名
     * @param analyzedFileCount 解析対象ファイル数
     * @param matchedFileCount 変数または代入文が見つかったファイル数
     * @param variableCount 変数定義総数
     * @param assignmentCount 代入文総数
     */
    public record ColumnAnalysisSummary(
        String columnName,
        int analyzedFileCount,
        int matchedFileCount,
        int variableCount,
        int assignmentCount) {

        /**
         * 空の集計結果を返します。
         *
         * @param columnName 対象カラム名
         * @return 空の集計結果
         */
        public static ColumnAnalysisSummary empty(String columnName) {
            return new ColumnAnalysisSummary(columnName, 0, 0, 0, 0);
        }
    }

    /**
     * ユーティリティを初期化します。
     */
    public CobolColumnAnalysisUtil() {
        this.fileReaderTool = new FileReaderTool();
    }

    /**
     * 指定ファイルを読み込み、対象カラムに関連する変数定義と代入文を解析します。
     *
     * @param filePath 解析対象ファイル
     * @param columnName 対象カラム名
     * @return 解析結果
     */
    public ColumnAnalysis analyzeFile(Path filePath, String columnName) {
        return analyzeContent(readNormalizedFile(filePath), columnName);
    }

    /**
     * 指定ファイルを読み込み、固定形式を考慮して正規化した内容を返します。
     *
     * @param filePath 対象ファイル
     * @return 正規化済みファイル内容
     */
    public String readNormalizedFile(Path filePath) {
        if (filePath == null) {
            return "";
        }
        String rawContent = fileReaderTool.readFile(filePath.toString());
        return normalizeCobolContent(rawContent);
    }

    /**
     * COBOL ソース文字列から対象カラムに関連する変数定義と代入文を解析します。
     *
     * @param cobolCode COBOL ソース全体
     * @param columnName 対象カラム名
     * @return 解析結果
     */
    public ColumnAnalysis analyzeContent(String cobolCode, String columnName) {
        List<VariableDefinition> variables = extractVariables(cobolCode, columnName);
        List<AssignmentOccurrence> assignments = extractAssignments(cobolCode, variables);
        return new ColumnAnalysis(variables, assignments);
    }

    /**
     * COBOL ソース文字列から代入文のみを解析します（変数定義は既知のリストを使用）。
     * COPY ブックで定義された変数への代入を検出するために使用します。
     *
     * @param cobolCode COBOL ソース全体
     * @param allVariables すべての関連変数定義（COBOL ファイル + COPY ブック）
     * @return 代入文のリスト
     */
    public List<AssignmentOccurrence> extractAssignmentsOnly(String cobolCode, List<VariableDefinition> allVariables) {
        return extractAssignments(cobolCode, allVariables);
    }

    /**
     * 複数ファイルの解析結果から集計サマリを作成します。
     *
     * @param columnName 対象カラム名
     * @param analyses ファイル別解析結果
     * @param analyzedFileCount 解析対象ファイル数
     * @return 集計サマリ
     */
    public ColumnAnalysisSummary summarizeAnalyses(
        String columnName,
        Map<String, ColumnAnalysis> analyses,
        int analyzedFileCount) {
        int variableCount = 0;
        int assignmentCount = 0;
        for (ColumnAnalysis analysis : analyses.values()) {
            variableCount += analysis.variables().size();
            assignmentCount += analysis.assignments().size();
        }
        return new ColumnAnalysisSummary(
            columnName,
            analyzedFileCount,
            analyses.size(),
            variableCount,
            assignmentCount);
    }

    /**
     * COBOL ソースから COPY 句を抽出します。
     *
     * @param cobolCode COBOL ソース全体
     * @return COPY 句の対象一覧
     */
    public List<String> extractCopyStatements(String cobolCode) {
        List<String> copyStatements = new ArrayList<>();

        if (cobolCode == null) {
            return copyStatements;
        }

        String[] lines = cobolCode.split("\n");

        for (String line : lines) {
            String trimmed = normalizeCobolLine(line).trim().toUpperCase(Locale.ROOT);
            if (trimmed.startsWith("COPY ")) {
                String copyPart = trimmed.substring(5).trim();
                copyPart = copyPart.replaceAll("[\"']", "");
                copyPart = copyPart.replaceAll("\\.$", "").trim();
                if (!copyPart.isEmpty()) {
                    copyStatements.add(copyPart);
                }
            }
        }

        return copyStatements;
    }

    /**
     * 固定形式 COBOL の行を解析向けに正規化します。
     *
     * @param line 元の行文字列
     * @return 正規化後の行文字列
     */
    public String normalizeCobolLine(String line) {
        if (line == null) {
            return "";
        }

        if (line.matches("^\\d{6}.+")) {
            return line.substring(Math.min(7, line.length()));
        }

        return line;
    }

    /**
     * 固定形式 COBOL のソース全文を正規化します。
     *
     * @param rawContent 元のソース全文
     * @return 正規化後のソース全文
     */
    public String normalizeCobolContent(String rawContent) {
        if (Strings.isNullOrEmpty(rawContent)) {
            return "";
        }

        String[] lines = rawContent.split("\\r?\\n", -1);
        StringBuilder builder = new StringBuilder(rawContent.length());
        for (int i = 0; i < lines.length; i++) {
            builder.append(normalizeCobolLine(lines[i]));
            if (i < lines.length - 1) {
                builder.append('\n');
            }
        }
        return builder.toString();
    }

    /**
     * COBOL ソースから対象カラムに関連する変数定義を抽出します。固定形式の行番号領域を考慮して解析します。
     * @param cobolCode COBOL ソース全体
     * @param columnName 対象カラム名
     * @return 変数定義のリスト
     */
    private List<VariableDefinition> extractVariables(String cobolCode, String columnName) {
        List<VariableDefinition> variables = new ArrayList<>();
        if (cobolCode == null || Strings.isNullOrEmpty(columnName)) {
            return variables;
        }

        String[] lines = cobolCode.split("\n");
        for (String line : lines) {
            String trimmed = normalizeCobolLine(line).trim();
            if (trimmed.startsWith("*") || trimmed.startsWith("EXEC SQL")) {
                continue;
            }

            if (trimmed.toUpperCase(Locale.ROOT).contains(columnName.toUpperCase(Locale.ROOT))) {
                String[] parts = trimmed.split("\\s+");
                if (parts.length >= 2 && parts[0].matches("^\\d{2}$")) {
                    variables.add(new VariableDefinition(
                        parts[1],
                        parts[0],
                        extractPicClause(trimmed),
                        extractComment(trimmed)));
                }
            }
        }

        return variables;
    }

    /**
     * COBOL ソースから対象変数への代入文を抽出します。固定形式の行番号領域を考慮して解析します。
     * @param cobolCode COBOL ソース全体
     * @param variables 解析対象変数の定義リスト
     * @return 代入文のリスト
     */
    private List<AssignmentOccurrence> extractAssignments(String cobolCode, List<VariableDefinition> variables) {
        List<AssignmentOccurrence> assignments = new ArrayList<>();
        if (cobolCode == null || variables == null || variables.isEmpty()) {
            return assignments;
        }

        String[] lines = cobolCode.split("\n");
        boolean insideExecSql = false;
        StringBuilder sqlBuffer = new StringBuilder();
        int sqlStartLine = -1;
        
        // デバッグ: 行50付近をログ出力
        boolean debugMode = System.getProperty("debug.assignments", "false").equals("true");

        for (int i = 0; i < lines.length; i++) {
            String trimmed = normalizeCobolLine(lines[i]).trim();
            
            // デバッグログ: 行50
            if (debugMode && i == 49) {
                System.err.println("=== DEBUG: Line 50 ===");
                System.err.println("Raw: [" + lines[i] + "]");
                System.err.println("Trimmed: [" + trimmed + "]");
                System.err.println("Starts with *: " + trimmed.startsWith("*"));
                System.err.println("Empty: " + trimmed.isEmpty());
            }
            
            if (trimmed.isEmpty() || trimmed.startsWith("*")) {
                if (debugMode && i == 49) {
                    System.err.println("SKIPPED (empty or comment)");
                }
                continue;
            }

            String upperLine = trimmed.toUpperCase(Locale.ROOT);
            
            // デバッグログ: 行50
            if (debugMode && i == 49) {
                System.err.println("Upper: [" + upperLine + "]");
                System.err.println("insideExecSql: " + insideExecSql);
                System.err.println("Contains EXEC SQL: " + upperLine.contains("EXEC SQL"));
            }
            
            // EXEC SQL ブロックの開始
            if (upperLine.contains("EXEC SQL")) {
                if (debugMode && i == 49) {
                    System.err.println("→ Branch: EXEC SQL start");
                }
                insideExecSql = true;
                sqlBuffer = new StringBuilder();
                sqlBuffer.append(upperLine);
                sqlStartLine = i;
                
                // 同じ行に END-EXEC がある場合（1行完結型の EXEC SQL）
                if (upperLine.contains("END-EXEC")) {
                    insideExecSql = false;
                    String fullSql = sqlBuffer.toString();
                    
                    // DECLARE や INCLUDE は代入ではないのでスキップ
                    boolean isDeclareOrInclude = fullSql.contains("DECLARE") || fullSql.contains("INCLUDE");
                    
                    if (!isDeclareOrInclude) {
                        // 1行 SQL に対して変数をチェック
                        for (VariableDefinition variable : variables) {
                            String statementType = detectAssignmentType(fullSql, variable.name(), true);
                            if (statementType != null) {
                                assignments.add(new AssignmentOccurrence(variable.name(), sqlStartLine + 1, statementType, fullSql));
                            }
                        }
                    }
                }
            } else if (insideExecSql) {
                if (debugMode && i == 49) {
                    System.err.println("→ Branch: inside EXEC SQL");
                }
                // SQL ブロック内の行を連結
                sqlBuffer.append(" ").append(upperLine);
                
                // EXEC SQL ブロックの終了
                if (upperLine.contains("END-EXEC")) {
                    insideExecSql = false;
                    String fullSql = sqlBuffer.toString();
                    
                    // DECLARE や INCLUDE は代入ではないのでスキップ
                    boolean isDeclareOrInclude = fullSql.contains("DECLARE") || fullSql.contains("INCLUDE");
                    
                    if (!isDeclareOrInclude) {
                        // 複数行 SQL に対して変数をチェック
                        for (VariableDefinition variable : variables) {
                            String statementType = detectAssignmentType(fullSql, variable.name(), true);
                            if (statementType != null) {
                                assignments.add(new AssignmentOccurrence(variable.name(), sqlStartLine + 1, statementType, fullSql));
                            }
                        }
                    }
                }
            } else {
                if (debugMode && i == 49) {
                    System.err.println("→ Branch: normal COBOL statement");
                }
                // EXEC SQL 外の通常の COBOL ステートメント
                if (debugMode && i == 49) {
                    System.err.println("Processing as normal COBOL statement");
                    System.err.println("Variables to check: " + variables.size());
                }
                
                for (VariableDefinition variable : variables) {
                    String statementType = detectAssignmentType(upperLine, variable.name(), false);
                    
                    if (debugMode && i == 49) {
                        System.err.println("  Checking variable: " + variable.name() + " -> " + statementType);
                    }
                    
                    if (statementType != null) {
                        assignments.add(new AssignmentOccurrence(variable.name(), i + 1, statementType, trimmed));
                        
                        if (debugMode && i == 49) {
                            System.err.println("  ✅ MATCH FOUND!");
                        }
                    }
                }
            }
        }

        return deduplicateAssignments(assignments);
    }

    /**
     * 行文字列から対象変数への代入文かどうかを判定し、代入種別を返します。
     * @param upperLine 大文字化された行文字列
     * @param variableName 対象変数名
     * @return 代入種別。該当しない場合は null
     */
    private String detectAssignmentType(String upperLine, String variableName, boolean insideExecSql) {
        String escapedVariable = Pattern.quote(variableName.toUpperCase(Locale.ROOT));

        if (matchesAssignment(upperLine, "\\bMOVE\\b.*\\bTO\\s+:?" + escapedVariable + "\\b")) {
            return "MOVE TO";
        }
        if (matchesAssignment(upperLine, "\\bSET\\s+:?" + escapedVariable + "\\b.*\\bTO\\b")) {
            return "SET TO";
        }
        if (matchesAssignment(upperLine, "\\bCOMPUTE\\s+:?" + escapedVariable + "\\b\\s*=")) {
            return "COMPUTE";
        }
        if (matchesAssignment(upperLine, "\\bADD\\b.*\\bTO\\s+:?" + escapedVariable + "\\b")) {
            return "ADD TO";
        }
        if (matchesAssignment(upperLine, "\\bSUBTRACT\\b.*\\bFROM\\s+:?" + escapedVariable + "\\b")) {
            return "SUBTRACT FROM";
        }
        if (matchesAssignment(upperLine, "\\bMULTIPLY\\b.*\\bGIVING\\s+:?" + escapedVariable + "\\b")) {
            return "MULTIPLY GIVING";
        }
        if (matchesAssignment(upperLine, "\\bDIVIDE\\b.*\\b(?:INTO|GIVING)\\s+:?" + escapedVariable + "\\b")) {
            return "DIVIDE INTO/GIVING";
        }
        if (matchesAssignment(upperLine, "\\bINITIALIZE\\s+:?" + escapedVariable + "\\b")) {
            return "INITIALIZE";
        }
        if (matchesAssignment(upperLine, "\\bACCEPT\\s+:?" + escapedVariable + "\\b")) {
            return "ACCEPT";
        }
        if (matchesAssignment(upperLine, "\\bSTRING\\b.*\\bINTO\\s+:?" + escapedVariable + "\\b")) {
            return "STRING INTO";
        }
        if (matchesAssignment(upperLine, "\\bUNSTRING\\b.*\\bINTO\\s+:?" + escapedVariable + "\\b")) {
            return "UNSTRING INTO";
        }
        if (insideExecSql && matchesAssignment(upperLine, "\\bINTO\\s+:" + escapedVariable + "\\b")) {
            return "EXEC SQL INTO";
        }
        // EXEC SQL 内の VALUES 句：INSERT INTO ... VALUES (:ZIPCODE, ...)
        if (insideExecSql && matchesAssignment(upperLine, "\\bVALUES\\b.*:" + escapedVariable + "\\b")) {
            return "EXEC SQL VALUES";
        }
        // EXEC SQL 内の SET 句：UPDATE ... SET col = :ZIPCODE WHERE ...
        if (insideExecSql && matchesAssignment(upperLine, "\\bSET\\b.*:" + escapedVariable + "\\b")) {
            return "EXEC SQL SET";
        }
        // EXEC SQL 内の WHERE 句：WHERE id = :ZIPCODE
        if (insideExecSql && matchesAssignment(upperLine, "\\bWHERE\\b.*:" + escapedVariable + "\\b")) {
            return "EXEC SQL WHERE";
        }

        return null;
    }

    /**
     * 行文字列が対象変数への代入文にマッチするかを判定します。
     * @param upperLine 大文字化された行文字列
     * @param pattern 代入文の正規表現パターン
     * @return マッチする場合は true、そうでない場合は false
     */
    private boolean matchesAssignment(String upperLine, String pattern) {
        return Pattern.compile(pattern).matcher(upperLine).find();
    }

    /**
     * 代入文のリストから重複を除去します。行番号と代入種別が同一のものを同一とみなします。
     * @param assignments 代入文のリスト
     * @return 重複を除去した代入文のリスト
     */
    private List<AssignmentOccurrence> deduplicateAssignments(List<AssignmentOccurrence> assignments) {
        Map<String, AssignmentOccurrence> uniqueAssignments = new LinkedHashMap<>();
        for (AssignmentOccurrence assignment : assignments) {
            String key = assignment.variableName() + "@" + assignment.lineNumber() + "@"
                + assignment.statementType() + "@" + assignment.sourceLine();
            uniqueAssignments.putIfAbsent(key, assignment);
        }
        return new ArrayList<>(uniqueAssignments.values());
    }

    /**
     * PIC 句を行文字列から抽出します。
     * @param line 行文字列
     * @return PIC 句。見つからない場合は null
     */
    private String extractPicClause(String line) {
        String upperLine = line.toUpperCase(Locale.ROOT);
        int picIndex = upperLine.indexOf("PIC ");
        if (picIndex < 0) {
            picIndex = upperLine.indexOf("PICTURE ");
        }

        if (picIndex >= 0) {
            String afterPic = line.substring(picIndex);
            String[] tokens = afterPic.split("\\s+");
            if (tokens.length >= 2) {
                return tokens[0] + " " + tokens[1];
            }
            if (tokens.length == 1 && !tokens[0].equalsIgnoreCase("PIC")) {
                return tokens[0];
            }
        }
        return null;
    }

    /**
     * 行文字列からコメントを抽出します。固定形式の行番号領域を考慮して解析します。
     * @param line 行文字列
     * @return コメント。見つからない場合は空文字列
     */
    private String extractComment(String line) {
        int commentIndex = line.indexOf('*');
        if (commentIndex > 0) {
            return line.substring(commentIndex).trim();
        }
        return "";
    }
}