package org.example.agents;

import dev.langchain4j.agentic.Agent;
import dev.langchain4j.agentic.AgenticServices;
import dev.langchain4j.agentic.supervisor.SupervisorContextStrategy;
import dev.langchain4j.agentic.supervisor.SupervisorResponseStrategy;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.V;
import org.example.cobol.CobolDependencyAnalyzer;
import org.example.tools.*;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.impl.DefaultParser;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;

import java.io.*;
import java.util.*;

/**
 * COBOL カラム型変更影響分析エージェント（SupervisorAgent パターン）
 *
 * このエージェントは、指定されたテーブル&カラムの型変更が
 * COBOL ソースコード全体に与える影響をスーパーバイザーパターンで自律的に分析します。
 *
 * AgenticScope に依存せず、メソッドの戻り値と @V パラメータでデータを受け渡します。
 * 各エージェントの戻り値は自動的にコンテキストに格納され、次のエージェントに渡されます。
 */
public class CobolColumnImpactAgent {

    /**
     * COBOL 変数定義情報を保持するレコード
     * 変数名、データ型、長さ/形式などを統合管理
     */
    public record VariableDefinition(String name, String level, String picClause, String description) {
        @Override
        public String toString() {
            return String.format("%-20s (Level:%s) %s%s", 
                name, 
                level, 
                picClause != null ? picClause : "N/A",
                description != null && !description.isEmpty() ? " - " + description : ""
            );
        }
    }

    /**
     * ワークフローインタフェース：SupervisorAgent の入出力を定義
     */
    public interface CobolAnalysisWorkflow {
        
        @Agent(description = "Analyzes COBOL source code to identify column usage and generate impact report")
        String analyze(
            @V("userRequest") String userRequest
        );
    }

    /**
     * IntentExtractorAgent
     * LLM を活用してユーザーの自然言語入力から「テーブル名」「カラム名」「ディレクトリ」を抽出
     * 複数のテーブル/カラムパターン、カスタムディレクトリ指定に対応
     */
    public static class IntentExtractorAgent {
        
        /**
         * LLM が返す構造化結果
         * tableName, columnName は必須
         * cobolDir, copyDir はオプション（null の場合はデフォルト値を使用）
         */
        public record ExtractedIntent(String tableName, String columnName, String cobolDir, String copyDir) {
        }
        
        /**
         * AiServices で LLM に構造化抽出を指示するインタフェース
         */
        private interface IntentExtractor {
            @dev.langchain4j.service.SystemMessage(
                "You are an expert at extracting table names, column names, and directory paths from user requests.\n" +
                "For cobolDir and copyDir: extract only if explicitly mentioned in the request, otherwise return null."
            )
            @dev.langchain4j.service.UserMessage(
                "Extract the following from the user request:\n" +
                "- tableName: database table name\n" +
                "- columnName: column name\n" +
                "- cobolDir: COBOL source directory (if mentioned, otherwise null)\n" +
                "- copyDir: COPY file directory (if mentioned, otherwise null)\n" +
                "\n" +
                "Examples:\n" +
                "1. 'POST_CD の ZIPCODE を /path/to/cobol と /path/to/copy で探して'\n" +
                "   → tableName: POST_CD, columnName: ZIPCODE, cobolDir: /path/to/cobol, copyDir: /path/to/copy\n" +
                "\n" +
                "2. 'ADDR_TB の ZIP_CODE を調査'\n" +
                "   → tableName: ADDR_TB, columnName: ZIP_CODE, cobolDir: null, copyDir: null\n" +
                "\n" +
                "User request: {{userRequest}}"
            )
            ExtractedIntent extract(@V("userRequest") String userRequest);
        }
        
        private final IntentExtractor llmExtractor;
        
        public IntentExtractorAgent(ChatModel chatModel) {
            this.llmExtractor = AiServices.builder(IntentExtractor.class)
                .chatModel(chatModel)
                .build();
        }
        
        /**
         * LLM が返すパス文字列をクリーニング
         * LLMが余分な文字を追加することがあるため、ドライブレター前の余分な文字を削除
         * jLineのパーサ(escapeChars(null))により、バックスラッシュは正しく保持されている
         */
        private String cleanPath(String path) {
            if (path == null || path.isBlank()) {
                return null;
            }
            
            path = path.trim();
            
            // Windows ドライブレター（C:\など）を含む場合、それ以降を抽出
            // 例: "AC:\\Users\\..." → "C:\\Users\\..."
            int colonIdx = path.lastIndexOf(':');
            if (colonIdx > 0) {
                char driveChar = path.charAt(colonIdx - 1);
                if (Character.isLetter(driveChar)) {
                    // ドライブレター前の余分な文字を削除
                    path = path.substring(colonIdx - 1);
                }
            }
            
            // パスの末尾にセパレータがあれば削除（最低3文字以上の場合）
            if (path.endsWith(File.separator) && path.length() > 3) {
                path = path.substring(0, path.length() - 1);
            }
            
            return path.isEmpty() ? null : path;
        }
        
        @Agent(
            name = "IntentExtractor",
            description = "Extracts table name, column name, and directories from user request using LLM",
            outputKey = "extractedParams"
        )
        public Map<String, String> extract(
            @V("userRequest") String userRequest
        ) {
            Map<String, String> params = new HashMap<>();
            
            // userRequest が空の場合のデフォルト
            if (userRequest == null || userRequest.isBlank()) {
                userRequest = "POST_CD テーブルの ZIPCODE カラムの型変更影響を調査してください";
            }
            
            try {
                // jLineのパーサ(escapeChars(null))でバックスラッシュが正しく保持されているため、
                // ユーザーリクエストをそのままLLMに渡す
                
                // LLM に構造化抽出を依頼
                ExtractedIntent intent = llmExtractor.extract(userRequest);
                
                if (intent != null && intent.tableName() != null && intent.columnName() != null) {
                    params.put("tableName", intent.tableName().toUpperCase());
                    params.put("columnName", intent.columnName().toUpperCase());
                    
                    // ディレクトリはユーザーが指定していない場合はデフォルト値を使用
                    String cobolDir = cleanPath(intent.cobolDir());
                    String copyDir = cleanPath(intent.copyDir());
                    
                    if (cobolDir == null || cobolDir.isBlank()) {
                        cobolDir = System.getProperty("user.dir") + File.separator + "app" + File.separator + 
                                  "src" + File.separator + "main" + File.separator + "resources" + File.separator + "cobol";
                    }
                    if (copyDir == null || copyDir.isBlank()) {
                        copyDir = System.getProperty("user.dir") + File.separator + "app" + File.separator + 
                                 "src" + File.separator + "main" + File.separator + "resources" + File.separator + "copy";
                    }
                    
                    params.put("cobolDir", cobolDir);
                    params.put("copyDir", copyDir);
                } else {
                    // LLM が null を返した場合のデフォルト
                    params.put("tableName", "POST_CD");
                    params.put("columnName", "ZIPCODE");
                    params.put("cobolDir", System.getProperty("user.dir") + File.separator + "app" + File.separator + 
                              "src" + File.separator + "main" + File.separator + "resources" + File.separator + "cobol");
                    params.put("copyDir", System.getProperty("user.dir") + File.separator + "app" + File.separator + 
                              "src" + File.separator + "main" + File.separator + "resources" + File.separator + "copy");
                }
            } catch (Exception e) {
                // LLM 呼び出し失敗時のデフォルト
                System.out.println("IntentExtractor: LLM extraction failed, using default values");
                params.put("tableName", "POST_CD");
                params.put("columnName", "ZIPCODE");
                params.put("cobolDir", System.getProperty("user.dir") + File.separator + "app" + File.separator + 
                          "src" + File.separator + "main" + File.separator + "resources" + File.separator + "cobol");
                params.put("copyDir", System.getProperty("user.dir") + File.separator + "app" + File.separator + 
                          "src" + File.separator + "main" + File.separator + "resources" + File.separator + "copy");
            }
            
            return params;
        }
    }

    /**
     * FileScannerAgent
     * 非AIエージェント：GrepTool を使用して高速にテーブル参照ファイルを探索
     * 前のエージェント（IntentExtractor）の戻り値（Map<String, String>）から
     * tableName と rootDir を @V パラメータで取得
     */
    public static class FileScannerAgent {
        
        private final GrepTool grepTool;
        
        public FileScannerAgent() {
            this.grepTool = new GrepTool();
        }
        
        @Agent(
            name = "FileScanner",
            description = "Scans COBOL files to find references to the target table",
            outputKey = "foundFiles"
        )
        public List<String> scan(
            @V("tableName") String tableName,
            @V("cobolDir") String cobolDir
        ) {
            if (tableName == null || cobolDir == null) {
                return Collections.emptyList();
            }
            
            // GrepTool で COBOL ファイルを検索（cobolDir を使用）
            String grepResult = grepTool.grepCobolFiles(cobolDir, tableName);
            
            // 結果をパース：ファイルパスを抽出
            return parseGrepResult(grepResult);
        }
        
        /**
         * GrepTool の出力をパースしてファイルパスリストを抽出
         * 
         * 形式："C:\path\to\file.cbl:12: content" → "C:\path\to\file.cbl"
         * Windows パス対応：最初の2文字後の最初の : をファイルパス終端と判定しない
         */
        private List<String> parseGrepResult(String grepResult) {
            List<String> files = new ArrayList<>();
            if (grepResult == null || grepResult.isEmpty()) {
                return files;
            }
            
            String[] lines = grepResult.split("\n");
            Set<String> uniqueFiles = new HashSet<>();
            
            for (String line : lines) {
                if (line == null || line.trim().isEmpty()) {
                    continue;
                }
                
                // Windows パス対応：C:\ のあとの : で分割する（最初の3文字はスキップ）
                String filePath = null;
                
                // Windows パスの場合: C:\path:linenum:content
                if (line.length() > 2 && line.charAt(1) == ':') {
                    // Windows パス：C:\ のあとの最初の : を探す
                    int colonIndex = line.indexOf(':', 2);
                    if (colonIndex > 0) {
                        filePath = line.substring(0, colonIndex);
                    }
                } else {
                    // Unix パスまたはその他：最初の : で分割
                    int colonIndex = line.indexOf(':');
                    if (colonIndex > 0) {
                        filePath = line.substring(0, colonIndex);
                    }
                }
                
                if (filePath != null && !filePath.isEmpty()) {
                    uniqueFiles.add(filePath);
                }
            }
            
            files.addAll(uniqueFiles);
            return files;
        }
    }

    /**
     * DependencyAnalyzerAgent
     * 非AIエージェント：CobolDependencyAnalyzer を実行して Derby 依存関係DBを再構築します。
     * cobolDir と copyDir をそのまま利用し、DatabaseQueryAgent が参照する最新DBを準備します。
     */
    public static class DependencyAnalyzerAgent {

        @Agent(
            name = "DependencyAnalyzer",
            description = "Runs CobolDependencyAnalyzer to rebuild dependency DB using the provided COBOL and COPY directories",
            outputKey = "dependencyBuildInfo"
        )
        public Map<String, Object> analyze(
            @V("cobolDir") String cobolDir,
            @V("copyDir") String copyDir,
            @V("columnName") String columnName
        ) {
            Map<String, Object> result = new HashMap<>();

            if (cobolDir == null || cobolDir.isBlank()) {
                result.put("success", false);
                result.put("message", "COBOL directory is not specified.");
                return result;
            }

            try {
                CobolDependencyAnalyzer.resetDatabase();

                CobolDependencyAnalyzer analyzer = new CobolDependencyAnalyzer(cobolDir, copyDir);
                if (columnName != null && !columnName.isBlank()) {
                    analyzer.setTargetColumn(columnName);
                }
                analyzer.run();

                result.put("success", true);
                result.put("message", "Dependency database rebuilt successfully.");
                result.put("cobolDir", cobolDir);
                result.put("copyDir", copyDir);
            } catch (Exception e) {
                result.put("success", false);
                result.put("message", "Dependency analyzer failed: " + e.getMessage());
            }

            return result;
        }
    }

    /**
     * CobolAnalyzerAgent
     * AI エージェント：FileScannerの結果（List<String>）を読み込み、
     * 実際に COBOL/COPY ファイルから columnName 関連の変数を特定
     */
    public static class CobolAnalyzerAgent {
        
        private final FileReaderTool fileReaderTool;
        private final GrepTool grepTool;
        
        public CobolAnalyzerAgent() {
            this.fileReaderTool = new FileReaderTool();
            this.grepTool = new GrepTool();
        }
        
        @Agent(
            name = "CobolAnalyzer",
            description = "Analyzes COBOL code to identify variables that store the target column",
            outputKey = "analysisResult"
        )
        public Map<String, Object> analyze(
            @V("foundFiles") List<String> foundFiles,
            @V("columnName") String columnName,
            @V("tableName") String tableName,
            @V("cobolDir") String cobolDir,
            @V("copyDir") String copyDir
        ) {
            Map<String, Object> analysisResult = new HashMap<>();
            List<VariableDefinition> identifiedVars = new ArrayList<>();
            Map<String, List<VariableDefinition>> fileVariables = new HashMap<>();
            Map<String, List<String>> fileCopyDependencies = new HashMap<>();
            
            // Step 1: 見つかったファイルから columnName を含む行を読み込む
            if (foundFiles != null && !foundFiles.isEmpty()) {
                for (String filePath : foundFiles) {
                    try {
                        String fileContent = fileReaderTool.readFile(filePath);
                        List<VariableDefinition> varsInFile = extractVariables(fileContent, columnName);
                        
                        // COPY 句を抽出
                        List<String> copyStatements = extractCopyStatements(fileContent);
                        if (!copyStatements.isEmpty()) {
                            fileCopyDependencies.put(filePath, copyStatements);
                        }
                        
                        if (!varsInFile.isEmpty()) {
                            fileVariables.put(filePath, varsInFile);
                            identifiedVars.addAll(varsInFile);
                        }
                    } catch (Exception e) {
                        // ファイル読み込み失敗時はスキップ
                    }
                }
            }
            
            // Step 2: COPY ファイルからも columnName 関連の変数定義を探す
            // copyDir パラメータを直接使用（ユーザーが指定）
            try {
                String copySearchResult = grepTool.grepCobolFiles(copyDir, columnName);
                
                if (copySearchResult != null && !copySearchResult.isEmpty()) {
                    List<String> copyFiles = parseGrepResults(copySearchResult);
                    
                    for (String copyFilePath : copyFiles) {
                        try {
                            String copyFileContent = fileReaderTool.readFile(copyFilePath);
                            List<VariableDefinition> copyVars = extractVariables(copyFileContent, columnName);
                            
                            if (!copyVars.isEmpty()) {
                                if (!fileVariables.containsKey(copyFilePath)) {
                                    fileVariables.put(copyFilePath, copyVars);
                                }
                                identifiedVars.addAll(copyVars);
                            }
                        } catch (Exception e) {
                            // COPY ファイル読み込み失敗時もスキップ
                        }
                    }
                }
            } catch (Exception e) {
                // grep 実行失敗時もスキップ
            }
            
            // 重複排除（VariableDefinitionの名前で）
            Map<String, VariableDefinition> uniqueMap = new LinkedHashMap<>();
            for (VariableDefinition var : identifiedVars) {
                uniqueMap.putIfAbsent(var.name(), var);
            }
            
            analysisResult.put("variables", new ArrayList<>(uniqueMap.values()));
            analysisResult.put("fileVariables", fileVariables);
            analysisResult.put("fileCopyDependencies", fileCopyDependencies);
            
            return analysisResult;
        }
        
        /**
         * Grep 結果をファイルパスのリストに変換
         * 形式："C:\path\file.cbl:line:content" → ["C:\path\file.cbl", ...]
         * Windows パス対応
         */
        private List<String> parseGrepResults(String grepOutput) {
            List<String> files = new ArrayList<>();
            Set<String> uniqueFiles = new HashSet<>();
            
            if (grepOutput == null || grepOutput.isBlank()) {
                return files;
            }
            
            String[] lines = grepOutput.split("\n");
            for (String line : lines) {
                if (line == null || line.trim().isEmpty()) {
                    continue;
                }
                
                String filePath = null;
                
                // Windows パス対応：C:\ のあとの最初の : を探す
                if (line.length() > 2 && line.charAt(1) == ':') {
                    int colonIndex = line.indexOf(':', 2);
                    if (colonIndex > 0) {
                        filePath = line.substring(0, colonIndex);
                    }
                } else {
                    // Unix パス：最初の : で分割
                    int colonIndex = line.indexOf(':');
                    if (colonIndex > 0) {
                        filePath = line.substring(0, colonIndex);
                    }
                }
                
                if (filePath != null && !filePath.isEmpty()) {
                    uniqueFiles.add(filePath);
                }
            }
            
            files.addAll(uniqueFiles);
            return files;
        }
        
        /**
         * COBOLコードから変数定義を抽出（型・長さ情報を含む）
         * パターン例：
         *   01 ZIPCODE-VAR PIC X(7)
         *   05 ZIP-CODE PIC X(7)
         *   10 ZIPCODE PIC 9(5)
         *   * このようなコメント行は抽出対象外
         */
        private List<VariableDefinition> extractVariables(String cobolCode, String columnName) {
            List<VariableDefinition> vars = new ArrayList<>();
            
            if (cobolCode == null) {
                return vars;
            }
            
            String[] lines = cobolCode.split("\n");
            
            for (String line : lines) {
                // コメント行（アスタリスクで始まる）をスキップ
                String trimmed = line.trim();
                if (trimmed.startsWith("*") || trimmed.startsWith("EXEC SQL")) {
                    continue;
                }
                
                // 大文字小文字を区別しない検索
                if (line.toUpperCase().contains(columnName.toUpperCase())) {
                    // 01/05/10 で始まる COBOL 定義行から詳細情報を抽出
                    String[] parts = line.trim().split("\\s+");
                    if (parts.length >= 2 && parts[0].matches("^\\d{2}$")) {
                        String level = parts[0];
                        String varName = parts[1];
                        
                        // PIC句の抽出（複数スペース対応）
                        String picClause = extractPicClause(line);
                        
                        // コメントの抽出（アスタリスク以降）
                        String description = extractComment(line);
                        
                        vars.add(new VariableDefinition(varName, level, picClause, description));
                    }
                }
            }
            
            return vars;
        }
        
        /**
         * 行から PIC句を抽出
         * 例：PIC X(7), PIC 9(5), PIC X(20)
         */
        private String extractPicClause(String line) {
            // PIC または PICTURE キーワードを探す
            String upperLine = line.toUpperCase();
            int picIndex = upperLine.indexOf("PIC ");
            if (picIndex < 0) {
                picIndex = upperLine.indexOf("PICTURE ");
            }
            
            if (picIndex >= 0) {
                // PIC キーワード以降を抽出
                String afterPic = line.substring(picIndex);
                // 次のスペースまで、または行末まで抽出
                String[] tokens = afterPic.split("\\s+");
                if (tokens.length >= 2) {
                    // PIC と次の要素（例：X(7)）を結合
                    return tokens[0] + " " + tokens[1];
                } else if (tokens.length == 1 && !tokens[0].equalsIgnoreCase("PIC")) {
                    return tokens[0];
                }
            }
            return null;
        }
        
        /**
         * 行からコメントを抽出（アスタリスク以降）
         */
        private String extractComment(String line) {
            int commentIndex = line.indexOf('*');
            if (commentIndex > 0) {
                return line.substring(commentIndex).trim();
            }
            return "";
        }
        
        /**
         * COBOL ファイルから COPY 句を抽出
         * 例：COPY "HOST_VARS.cpy", COPY HOST_VARS, etc.
         */
        private List<String> extractCopyStatements(String cobolCode) {
            List<String> copyStatements = new ArrayList<>();
            
            if (cobolCode == null) {
                return copyStatements;
            }
            
            String[] lines = cobolCode.split("\n");
            
            for (String line : lines) {
                String trimmed = line.trim().toUpperCase();
                
                // COPY キーワードを含む行を抽出
                if (trimmed.startsWith("COPY ")) {
                    // COPY 以降を抽出（クォートがあれば除去）
                    String copyPart = line.substring(line.toUpperCase().indexOf("COPY") + 5).trim();
                    // クォートを除去
                    copyPart = copyPart.replaceAll("[\"']", "");
                    // ピリオドがあれば除去
                    copyPart = copyPart.replaceAll("\\.$", "").trim();
                    
                    if (!copyPart.isEmpty()) {
                        copyStatements.add(copyPart);
                    }
                }
            }
            
            return copyStatements;
        }
    }

    /**
     * ResultSaverAgent
     * 非AIエージェント：FileWriterTool を使用して結果をMarkdownで保存
     * CobolAnalyzerの戻り値（Map<String, Object>）と
     * DatabaseQueryAgentの戻り値（dependencyInfo）と
     * IntentExtractorの戻り値から tableName と columnName を @V で取得
     */
    public static class ResultSaverAgent {
        
        private final FileWriterTool fileWriterTool;
        
        public ResultSaverAgent() {
            this.fileWriterTool = new FileWriterTool();
        }
        
        @Agent(
            name = "ResultSaver",
            description = "Saves the integrated analysis result (DB findings + variable definitions) to Markdown",
            outputKey = "saveResult"
        )
        public String save(
            @V("tableName") String tableName,
            @V("columnName") String columnName,
            @V("analysisResult") Map<String, Object> analysisResult,
            @V("dependencyInfo") Map<String, Object> dependencyInfo
        ) {
            // analysisResult から変数定義を取得
            @SuppressWarnings("unchecked")
            List<VariableDefinition> variables = (List<VariableDefinition>) (analysisResult != null ? analysisResult.get("variables") : null);
            @SuppressWarnings("unchecked")
            Map<String, List<VariableDefinition>> fileVariables = (Map<String, List<VariableDefinition>>) (analysisResult != null ? analysisResult.get("fileVariables") : null);
            @SuppressWarnings("unchecked")
            Map<String, List<String>> fileCopyDependencies = (Map<String, List<String>>) (analysisResult != null ? analysisResult.get("fileCopyDependencies") : null);
            
            // dependencyInfo から DIRECT/INDIRECT プログラムを取得
            @SuppressWarnings("unchecked")
            List<Map<String, String>> directPrograms = 
                (List<Map<String, String>>) (dependencyInfo != null ? dependencyInfo.get("directPrograms") : null);
            @SuppressWarnings("unchecked")
            List<Map<String, String>> indirectPrograms = 
                (List<Map<String, String>>) (dependencyInfo != null ? dependencyInfo.get("indirectPrograms") : null);
            @SuppressWarnings("unchecked")
            Map<String, List<String>> callGraph = 
                (Map<String, List<String>>) (dependencyInfo != null ? dependencyInfo.get("callGraph") : null);
            
            // 結果を Markdown 形式で構築
            StringBuilder markdown = new StringBuilder();
            markdown.append("# COBOL Column Impact Analysis Report\n\n");
            markdown.append("## Analysis Parameters\n\n");
            markdown.append("- **Table Name**: `").append(tableName).append("`\n");
            markdown.append("- **Column Name**: `").append(columnName).append("`\n");
            markdown.append("- **Timestamp**: ").append(new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new java.util.Date())).append("\n\n");
            
            // ========== セクション 1: 依存関係分析（DB から） ==========
            if (dependencyInfo != null && (Boolean) dependencyInfo.getOrDefault("success", false)) {
                markdown.append("## Dependency Analysis (DB Query Results)\n\n");
                
                // DIRECT アクセス
                markdown.append("### Direct Access Programs\n\n");
                if (directPrograms != null && !directPrograms.isEmpty()) {
                    markdown.append("| Program ID | File Path | Access Type |\n");
                    markdown.append("|---|---|---|\n");
                    for (Map<String, String> prog : directPrograms) {
                        markdown.append("| `").append(prog.get("programId")).append("` | ");
                        markdown.append("`").append(prog.get("filePath")).append("` | ");
                        markdown.append(prog.get("accessType")).append(" |\n");
                    }
                    markdown.append("\n");
                } else {
                    markdown.append("No direct access programs found.\n\n");
                }
                
                // INDIRECT アクセス
                markdown.append("### Indirect Access Programs (via CALL)\n\n");
                if (indirectPrograms != null && !indirectPrograms.isEmpty()) {
                    markdown.append("| Program ID | File Path | Access Path | Level |\n");
                    markdown.append("|---|---|---|---|\n");
                    for (Map<String, String> prog : indirectPrograms) {
                        markdown.append("| `").append(prog.get("programId")).append("` | ");
                        markdown.append("`").append(prog.get("filePath")).append("` | ");
                        markdown.append(prog.get("accessPath")).append(" | ");
                        markdown.append(prog.get("level")).append(" |\n");
                    }
                    markdown.append("\n");
                } else {
                    markdown.append("No indirect access programs found.\n\n");
                }
                
                // CALL 依存関係グラフ
                markdown.append("### CALL Dependency Graph\n\n");
                if (callGraph != null && !callGraph.isEmpty()) {
                    markdown.append("```\n");
                    for (Map.Entry<String, List<String>> entry : callGraph.entrySet()) {
                        markdown.append(entry.getKey()).append(" calls: ").append(entry.getValue()).append("\n");
                    }
                    markdown.append("```\n\n");
                } else {
                    markdown.append("No CALL dependencies found.\n\n");
                }
                
                // 影響プログラム数とリスク評価
                int totalDirectPrograms = directPrograms != null ? directPrograms.size() : 0;
                int totalIndirectPrograms = indirectPrograms != null ? indirectPrograms.size() : 0;
                int totalPrograms = totalDirectPrograms + totalIndirectPrograms;
                
                markdown.append("### Impact Summary\n\n");
                markdown.append("- **Direct Impact**: ").append(totalDirectPrograms).append(" program(s)\n");
                markdown.append("- **Indirect Impact**: ").append(totalIndirectPrograms).append(" program(s)\n");
                markdown.append("- **Total Impact**: ").append(totalPrograms).append(" program(s)\n\n");
                
                markdown.append("#### Risk Assessment\n\n");
                String riskLevel = "Low";
                String riskDescription = "No programs affected by schema change.";
                if (totalPrograms > 5) {
                    riskLevel = "High";
                    riskDescription = totalPrograms + " programs affected. Detailed review recommended.";
                } else if (totalPrograms > 0) {
                    riskLevel = "Medium";
                    riskDescription = totalPrograms + " program(s) affected.";
                }
                markdown.append("- **Risk Level**: **").append(riskLevel).append("**\n");
                markdown.append("- **Description**: ").append(riskDescription).append("\n\n");
            }
            
            // ========== セクション 2: COPY 依存関係 ==========
            markdown.append("## File Dependencies & Relationships\n\n");
            if (fileCopyDependencies != null && !fileCopyDependencies.isEmpty()) {
                for (Map.Entry<String, List<String>> entry : fileCopyDependencies.entrySet()) {
                    String cobolFile = new File(entry.getKey()).getName();
                    markdown.append("### ").append(cobolFile).append("\n\n");
                    markdown.append("**COPY References**:\n");
                    for (String copyFile : entry.getValue()) {
                        markdown.append("- `COPY \"").append(copyFile).append("\"`\n");
                    }
                    markdown.append("\n");
                }
            } else {
                markdown.append("No COPY dependencies found.\n\n");
            }
            
            // ========== セクション 3: 識別された変数 ==========
            markdown.append("## Identified Variables\n\n");
            if (variables != null && !variables.isEmpty()) {
                markdown.append("| Variable Name | Level | Data Type | Description |\n");
                markdown.append("|---|---|---|---|\n");
                for (VariableDefinition var : variables) {
                    markdown.append("| `").append(var.name()).append("` | ");
                    markdown.append(var.level()).append(" | ");
                    markdown.append(var.picClause() != null ? var.picClause() : "N/A").append(" | ");
                    markdown.append(var.description() != null && !var.description().isEmpty() ? var.description() : "").append(" |\n");
                }
            } else {
                markdown.append("No variables identified.\n");
            }
            
            // ========== セクション 4: ファイル別変数参照 ==========
            markdown.append("\n## File-wise Variable References\n\n");
            if (fileVariables != null && !fileVariables.isEmpty()) {
                for (Map.Entry<String, List<VariableDefinition>> entry : fileVariables.entrySet()) {
                    markdown.append("### ").append(new File(entry.getKey()).getName()).append("\n\n");
                    markdown.append("**Path**: `").append(entry.getKey()).append("`\n\n");
                    if (entry.getValue() != null && !entry.getValue().isEmpty()) {
                        markdown.append("| Variable Name | Level | Data Type | Description |\n");
                        markdown.append("|---|---|---|---|\n");
                        for (VariableDefinition var : entry.getValue()) {
                            markdown.append("| `").append(var.name()).append("` | ");
                            markdown.append(var.level()).append(" | ");
                            markdown.append(var.picClause() != null ? var.picClause() : "N/A").append(" | ");
                            markdown.append(var.description() != null && !var.description().isEmpty() ? var.description() : "").append(" |\n");
                        }
                    } else {
                        markdown.append("No variables found.\n");
                    }
                    markdown.append("\n");
                }
            } else {
                markdown.append("No file-wise references found.\n");
            }
            
            markdown.append("---\n");
            markdown.append("*Generated by COBOL Column Impact Analysis Tool*\n");
            
            // ファイルに保存
            String outputPath = System.getProperty("user.dir") + File.separator + "cobol_impact_analysis.md";
            try {
                fileWriterTool.writeFile(outputPath, markdown.toString());
                return "✅ Analysis saved to: " + outputPath;
            } catch (Exception e) {
                return "❌ Failed to save analysis: " + e.getMessage();
            }
        }
    }

    /**
     * SupervisorAgent の構築・実行
     */
    public static CobolAnalysisWorkflow build(ChatModel chatModel) {
        IntentExtractorAgent intentExtractor = new IntentExtractorAgent(chatModel);
        DependencyAnalyzerAgent dependencyAnalyzer = new DependencyAnalyzerAgent();
        FileScannerAgent fileScanner = new FileScannerAgent();
        CobolAnalyzerAgent cobolAnalyzer = new CobolAnalyzerAgent();
        DatabaseQueryAgent dbQueryAgent = new DatabaseQueryAgent();
        ResultSaverAgent resultSaver = new ResultSaverAgent();
        
        return AgenticServices.supervisorBuilder(CobolAnalysisWorkflow.class)
            .chatModel(chatModel)
            .subAgents(intentExtractor, dependencyAnalyzer, fileScanner, cobolAnalyzer, dbQueryAgent, resultSaver)
            .responseStrategy(SupervisorResponseStrategy.SUMMARY)
            .contextGenerationStrategy(SupervisorContextStrategy.CHAT_MEMORY_AND_SUMMARIZATION)
            .supervisorContext(
                "You are analyzing COBOL source code to understand the impact of a column type change. " +
                "Follow these steps in order:\n" +
                "1. Extract table name, column name, and directory from the request\n" +
                "2. Run DependencyAnalyzer to rebuild the Derby dependency database using cobolDir and copyDir\n" +
                "3. Use DatabaseQuery to search the refreshed dependency database\n" +
                "4. Scan COBOL files for table references\n" +
                "5. Analyze COBOL code to identify variables that store the column\n" +
                "6. Save the analysis result as Markdown"
            )
            .maxAgentsInvocations(10)
            .build();
    }

    /**
     * Windows パスをそのまま入力できるようにした JLine パーサを返します。
     * バックスラッシュをエスケープ文字として扱わないため、C:\hoge のような入力が崩れません。
     *
     * @return 設定済みのパーサ
     */
    private static DefaultParser buildInputParser() {
        return new DefaultParser().escapeChars(null);
    }

    /**
     * ワークフロー実行
     * ユーザーリクエストを必須入力とし、LLM で自動抽出
     */
    public static void main(String[] args) throws Exception {
        String apiKey = System.getenv("OPENAI_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("OPENAI_API_KEY is not set");
        }
        
        // ユーザーリクエストの入力
        String userRequest = null;
        
        if (args != null && args.length > 0) {
            // コマンドラインから入力
            userRequest = String.join(" ", args);
        } else {
            // 対話的に入力を促す（jLine 3 で日本語対応）
            System.out.println("=== COBOL Column Impact Analysis System ===");
            System.out.println();
            System.out.println("テーブル名とカラム名を自然言語で指定してください。");
            System.out.println("ディレクトリも指定可能です。");
            System.out.println();
            System.out.println("例1: POST_CD テーブルの ZIPCODE カラムの型変更影響を調査");
            System.out.println("例2: ADDR_TB の ZIP_CODE 関連変数を探して");
            System.out.println("例3: POST_CD の ZIPCODE を /path/to/cobol から /path/to/copy で探して");
            
            try (Terminal terminal = TerminalBuilder.builder()
                    .system(true)
                    .build()) {
                LineReader reader = LineReaderBuilder.builder()
                        .terminal(terminal)
                        .parser(buildInputParser())
                        .build();
                userRequest = reader.readLine("> ");
            } catch (IOException e) {
                // フォールバック：基本的なBufferedReaderを使用
                System.out.print("> ");
                BufferedReader fallbackReader = new BufferedReader(new InputStreamReader(System.in, "UTF-8"));
                userRequest = fallbackReader.readLine();
            }
            
            if (userRequest == null || userRequest.isBlank()) {
                System.out.println("エラー: ユーザーリクエストが空です。");
                System.exit(1);
            }
        }
        
        System.out.println();
        System.out.println("=== COBOL Column Impact Analysis ===");
        System.out.println("Request: " + userRequest);
        System.out.println("Processing...");
        System.out.println();
        
        // OpenAiChatModel を初期化
        ChatModel chatModel = OpenAiChatModel.builder()
            .apiKey(apiKey)
            .modelName("gpt-4o-mini")
            .build();
        
        // IntentExtractor を Supervisor 外で実行（userRequest を確実に渡すため）
        IntentExtractorAgent intentExtractor = new IntentExtractorAgent(chatModel);
        Map<String, String> extractedParams = intentExtractor.extract(userRequest);
        
        System.out.println("Extracted: tableName=" + extractedParams.get("tableName") + 
                          ", columnName=" + extractedParams.get("columnName") +
                          ", cobolDir=" + extractedParams.get("cobolDir") +
                          ", copyDir=" + extractedParams.get("copyDir"));
        System.out.println();
        
        // 修正版 Supervisor を構築・実行
        CobolAnalysisWorkflow workflow = buildModifiedWorkflow(chatModel, extractedParams);
        
        String result = workflow.analyze(userRequest);
        
        System.out.println("=== Analysis Result ===");
        System.out.println(result);
    }
    
    /**
     * 修正版 build メソッド：IntentExtractor をスキップして直接ファイルスキャンを開始
     */
    private static CobolAnalysisWorkflow buildModifiedWorkflow(ChatModel chatModel, 
                                                                Map<String, String> extractedParams) {
        DependencyAnalyzerAgent dependencyAnalyzer = new DependencyAnalyzerAgent();
        FileScannerAgent fileScanner = new FileScannerAgent();
        CobolAnalyzerAgent cobolAnalyzer = new CobolAnalyzerAgent();
        ResultSaverAgent resultSaver = new ResultSaverAgent();
        DatabaseQueryAgent dbQueryAgent = new DatabaseQueryAgent();
        
        // ユーザーから取得した抽出パラメータを使用
        String cobolDir = extractedParams.get("cobolDir");
        String copyDir = extractedParams.get("copyDir");
        String tableName = extractedParams.get("tableName");
        String columnName = extractedParams.get("columnName");
        
        return AgenticServices.supervisorBuilder(CobolAnalysisWorkflow.class)
            .chatModel(chatModel)
            .subAgents(dependencyAnalyzer, fileScanner, cobolAnalyzer, dbQueryAgent, resultSaver)
            .responseStrategy(SupervisorResponseStrategy.SUMMARY)
            .contextGenerationStrategy(SupervisorContextStrategy.CHAT_MEMORY_AND_SUMMARIZATION)
            .supervisorContext(
                "You are analyzing COBOL source code and dependencies to understand the impact of a column type change.\n" +
                "=== TARGET INFORMATION ===\n" +
                "Table Name: " + tableName + "\n" +
                "Column Name: " + columnName + "\n" +
                "COBOL Directory: " + cobolDir + "\n" +
                "COPY Directory: " + copyDir + "\n" +
                "=== TASK STEPS ===\n" +
                "1. Use DependencyAnalyzer to rebuild the Derby dependency database with COBOL directory '" + cobolDir +
                "' and COPY directory '" + copyDir + "'\n" +
                "2. Use DatabaseQuery to search the refreshed Derby dependency database for programs accessing '" + tableName + "." + columnName + "'\n" +
                "   - Find DIRECT access (programs directly accessing the column)\n" +
                "   - Find INDIRECT access (programs calling other programs that access the column)\n" +
                "3. Use FileScanner to scan COBOL files in directory '" + cobolDir + 
                "' for references to table '" + tableName + "'\n" +
                "4. Use CobolAnalyzer to identify variables that store column '" + columnName + 
                "' in the found files and COPY files (directory: '" + copyDir + "')\n" +
                "5. Use ResultSaver to save the integrated analysis result (DB findings + variable definitions) as Markdown"
            )
            .maxAgentsInvocations(15)
            .build();
    }
}
