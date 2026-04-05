package org.example.agents;

import dev.langchain4j.agentic.Agent;
import dev.langchain4j.agentic.AgenticServices;
import dev.langchain4j.agentic.supervisor.SupervisorContextStrategy;
import dev.langchain4j.agentic.supervisor.SupervisorResponseStrategy;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;
import org.example.tools.*;

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
                "Always respond in JSON format ONLY, following the response type structure.\n" +
                "Do not include any explanation or markdown formatting.\n" +
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
                "  Input: 'POST_CD の ZIPCODE を /path/to/cobol と /path/to/copy で探して'\n" +
                "  Output: {\"tableName\": \"POST_CD\", \"columnName\": \"ZIPCODE\", \"cobolDir\": \"/path/to/cobol\", \"copyDir\": \"/path/to/copy\"}\n" +
                "\n" +
                "  Input: 'ADDR_TB の ZIP_CODE を調査'\n" +
                "  Output: {\"tableName\": \"ADDR_TB\", \"columnName\": \"ZIP_CODE\", \"cobolDir\": null, \"copyDir\": null}\n" +
                "\n" +
                "User request: {{userRequest}}"
            )
            ExtractedIntent extract(@V("userRequest") String userRequest);
        }
        
        private final IntentExtractor llmExtractor;
        
        public IntentExtractorAgent(OpenAiChatModel chatModel) {
            this.llmExtractor = AiServices.builder(IntentExtractor.class)
                .chatModel(chatModel)
                .build();
        }
        
        /**
         * LLM が返すパス文字列をクリーニング
         * LLMが不要な文字を追加することがあるため、有効なディレクトリパスを抽出
         */
        private String cleanPath(String path) {
            if (path == null || path.isBlank()) {
                return null;
            }
            
            path = path.trim();
            
            // Windows ドライブレター（C:\など）を含む場合、それ以降を抽出
            // 例: "AC:\\Users\\..." → "C:\\Users\\..."
            if (path.matches("^[A-Z].*[A-Z]:\\\\.*")) {
                int colonIdx = path.indexOf(':');
                // 最初に見つかった : の前の文字がドライブレターかチェック
                if (colonIdx > 0 && colonIdx < path.length() - 1) {
                    char driveChar = path.charAt(colonIdx - 1);
                    if (Character.isLetter(driveChar) && colonIdx > 1) {
                        // ドライブレター前の余分な文字を削除
                        path = path.substring(colonIdx - 1);
                    }
                }
            }
            
            // パスの有効性をチェック（最後にセパレータがないかなど）
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
            List<String> identifiedVars = new ArrayList<>();
            Map<String, List<String>> fileVariables = new HashMap<>();
            
            // Step 1: 見つかったファイルから columnName を含む行を読み込む
            if (foundFiles != null && !foundFiles.isEmpty()) {
                for (String filePath : foundFiles) {
                    try {
                        String fileContent = fileReaderTool.readFile(filePath);
                        List<String> varsInFile = extractVariables(fileContent, columnName);
                        
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
                            List<String> copyVars = extractVariables(copyFileContent, columnName);
                            
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
            
            // 重複排除
            Set<String> uniqueVars = new HashSet<>(identifiedVars);
            identifiedVars.clear();
            identifiedVars.addAll(uniqueVars);
            
            analysisResult.put("variables", identifiedVars);
            analysisResult.put("fileVariables", fileVariables);
            
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
         * COBOLコードから変数定義を抽出（簡易実装）
         * パターン例：
         *   01 ZIPCODE-VAR PIC X(7)
         *   05 ZIP-CODE PIC X(7)
         *   10 ZIPCODE VALUE '12345'
         */
        private List<String> extractVariables(String cobolCode, String columnName) {
            List<String> vars = new ArrayList<>();
            
            if (cobolCode == null) {
                return vars;
            }
            
            // 簡易パターン：列名を含むCOBOL変数定義を抽出
            // 例：01 ZIPCODE-VAR, 05 ZIP-CODE PIC X(7)
            String[] lines = cobolCode.split("\n");
            
            for (String line : lines) {
                // 大文字小文字を区別しない検索
                if (line.toUpperCase().contains(columnName.toUpperCase())) {
                    // 01/05 で始まる COBOL 定義行から変数名を抽出
                    String[] parts = line.trim().split("\\s+");
                    if (parts.length >= 2 && parts[0].matches("^\\d{2}$")) {
                        vars.add(parts[1]);
                    }
                }
            }
            
            return vars;
        }
    }

    /**
     * ResultSaverAgent
     * 非AIエージェント：FileWriterTool を使用して結果をJSONで保存
     * CobolAnalyzerの戻り値（Map<String, Object>）と
     * IntentExtractorの戻り値から tableName と columnName を @V で取得
     */
    public static class ResultSaverAgent {
        
        private final FileWriterTool fileWriterTool;
        
        public ResultSaverAgent() {
            this.fileWriterTool = new FileWriterTool();
        }
        
        @Agent(
            name = "ResultSaver",
            description = "Saves the analysis result to a JSON file",
            outputKey = "saveResult"
        )
        public String save(
            @V("tableName") String tableName,
            @V("columnName") String columnName,
            @V("analysisResult") Map<String, Object> analysisResult
        ) {
            Object variables = analysisResult != null ? analysisResult.get("variables") : null;
            Object fileVariables = analysisResult != null ? analysisResult.get("fileVariables") : null;
            // 結果をJSON形式で構築
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("tableName", tableName);
            result.put("columnName", columnName);
            result.put("identifiedVariables", variables != null ? variables : Collections.emptyList());
            result.put("fileVariables", fileVariables != null ? fileVariables : new HashMap<>());
            result.put("timestamp", System.currentTimeMillis());
            
            // JSON を文字列に変換
            String jsonContent = formatAsJson(result);
            
            // ファイルに保存
            String outputPath = System.getProperty("user.dir") + File.separator + "cobol_impact_analysis.json";
            try {
                fileWriterTool.writeFile(outputPath, jsonContent);
                return "Analysis saved to: " + outputPath;
            } catch (Exception e) {
                return "Failed to save analysis: " + e.getMessage();
            }
        }
        
        /**
         * Map を JSON 文字列に変換
         */
        private String formatAsJson(Map<String, Object> data) {
            StringBuilder json = new StringBuilder("{\n");
            
            for (Map.Entry<String, Object> entry : data.entrySet()) {
                json.append("  \"").append(entry.getKey()).append("\": ");
                
                Object value = entry.getValue();
                if (value instanceof String) {
                    json.append("\"").append(value).append("\"");
                } else if (value instanceof Number) {
                    json.append(value);
                } else if (value instanceof List<?>) {
                    json.append(formatList((List<?>) value));
                } else if (value instanceof Map<?, ?>) {
                    json.append(formatMap((Map<?, ?>) value));
                } else {
                    json.append("null");
                }
                
                json.append(",\n");
            }
            
            // 最後のカンマを削除
            if (json.length() > 2) {
                json.setLength(json.length() - 2);
                json.append("\n");
            }
            
            json.append("}");
            return json.toString();
        }
        
        private String formatList(List<?> list) {
            StringBuilder sb = new StringBuilder("[\n");
            for (Object item : list) {
                sb.append("    \"").append(item).append("\",\n");
            }
            if (sb.length() > 2) {
                sb.setLength(sb.length() - 2);
                sb.append("\n");
            }
            sb.append("  ]");
            return sb.toString();
        }
        
        private String formatMap(Map<?, ?> map) {
            StringBuilder sb = new StringBuilder("{\n");
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                sb.append("    \"").append(entry.getKey()).append("\": [");
                if (entry.getValue() instanceof List<?>) {
                    List<?> list = (List<?>) entry.getValue();
                    for (int i = 0; i < list.size(); i++) {
                        sb.append("\"").append(list.get(i)).append("\"");
                        if (i < list.size() - 1) sb.append(", ");
                    }
                }
                sb.append("],\n");
            }
            if (sb.length() > 2) {
                sb.setLength(sb.length() - 2);
                sb.append("\n");
            }
            sb.append("  }");
            return sb.toString();
        }
    }

    /**
     * SupervisorAgent の構築・実行
     */
    public static CobolAnalysisWorkflow build(OpenAiChatModel chatModel) {
        IntentExtractorAgent intentExtractor = new IntentExtractorAgent(chatModel);
        FileScannerAgent fileScanner = new FileScannerAgent();
        CobolAnalyzerAgent cobolAnalyzer = new CobolAnalyzerAgent();
        ResultSaverAgent resultSaver = new ResultSaverAgent();
        
        return AgenticServices.supervisorBuilder(CobolAnalysisWorkflow.class)
            .chatModel(chatModel)
            .subAgents(intentExtractor, fileScanner, cobolAnalyzer, resultSaver)
            .responseStrategy(SupervisorResponseStrategy.SUMMARY)
            .contextGenerationStrategy(SupervisorContextStrategy.CHAT_MEMORY_AND_SUMMARIZATION)
            .supervisorContext(
                "You are analyzing COBOL source code to understand the impact of a column type change. " +
                "Follow these steps in order:\n" +
                "1. Extract table name, column name, and directory from the request\n" +
                "2. Scan COBOL files for table references\n" +
                "3. Analyze COBOL code to identify variables that store the column\n" +
                "4. Save the analysis result as JSON"
            )
            .maxAgentsInvocations(10)
            .build();
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
            // 対話的に入力を促す
            System.out.println("=== COBOL Column Impact Analysis System ===");
            System.out.println();
            System.out.println("テーブル名とカラム名を自然言語で指定してください。");
            System.out.println("ディレクトリも指定可能です。");
            System.out.println();
            System.out.println("例1: POST_CD テーブルの ZIPCODE カラムの型変更影響を調査");
            System.out.println("例2: ADDR_TB の ZIP_CODE 関連変数を探して");
            System.out.println("例3: POST_CD の ZIPCODE を /path/to/cobol から /path/to/copy で探して");
            System.out.print("> ");
            
            java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(System.in));
            userRequest = reader.readLine();
            
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
        OpenAiChatModel chatModel = OpenAiChatModel.builder()
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
    private static CobolAnalysisWorkflow buildModifiedWorkflow(OpenAiChatModel chatModel, 
                                                                Map<String, String> extractedParams) {
        FileScannerAgent fileScanner = new FileScannerAgent();
        CobolAnalyzerAgent cobolAnalyzer = new CobolAnalyzerAgent();
        ResultSaverAgent resultSaver = new ResultSaverAgent();
        
        // ユーザーから取得した抽出パラメータを使用
        String cobolDir = extractedParams.get("cobolDir");
        String copyDir = extractedParams.get("copyDir");
        String tableName = extractedParams.get("tableName");
        String columnName = extractedParams.get("columnName");
        
        return AgenticServices.supervisorBuilder(CobolAnalysisWorkflow.class)
            .chatModel(chatModel)
            .subAgents(fileScanner, cobolAnalyzer, resultSaver)
            .responseStrategy(SupervisorResponseStrategy.SUMMARY)
            .contextGenerationStrategy(SupervisorContextStrategy.CHAT_MEMORY_AND_SUMMARIZATION)
            .supervisorContext(
                "You are analyzing COBOL source code to understand the impact of a column type change.\n" +
                "=== TARGET INFORMATION ===\n" +
                "Table Name: " + tableName + "\n" +
                "Column Name: " + columnName + "\n" +
                "COBOL Directory: " + cobolDir + "\n" +
                "COPY Directory: " + copyDir + "\n" +
                "=== TASK STEPS ===\n" +
                "1. Use FileScanner to scan COBOL files in directory '" + cobolDir + 
                "' for references to table '" + tableName + "'\n" +
                "2. Use CobolAnalyzer to identify variables that store column '" + columnName + 
                "' in the found files and COPY files (directory: '" + copyDir + "')\n" +
                "3. Use ResultSaver to save the analysis result as JSON"
            )
            .maxAgentsInvocations(10)
            .build();
    }
}
