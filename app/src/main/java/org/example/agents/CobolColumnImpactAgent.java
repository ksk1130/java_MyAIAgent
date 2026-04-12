package org.example.agents;

import dev.langchain4j.agentic.Agent;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.V;
import org.example.cobol.CobolDependencyAnalyzer;
import org.example.cobol.CobolColumnAnalysisUtil;
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
     * DependencyAnalyzerAgent
     * 非AIエージェント：CobolDependencyAnalyzer を実行して Derby 依存関係DBを再構築します。
     * cobolDir と copyDir をそのまま利用し、DatabaseQueryAgent が参照する最新DBを準備します。
     */
    public static class DependencyAnalyzerAgent {

        public Map<String, Object> analyze(
            String cobolDir,
            String copyDir,
            String columnName
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
                result.put("targetColumnSummary", analyzer.getTargetColumnSummary());
            } catch (Exception e) {
                result.put("success", false);
                result.put("message", "Dependency analyzer failed: " + e.getMessage());
            }

            return result;
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
        private final DatabaseQueryAgent dbQueryAgent;
        
        public ResultSaverAgent() {
            this.fileWriterTool = new FileWriterTool();
            this.dbQueryAgent = new DatabaseQueryAgent();
        }
        
        public String save(
            String tableName,
            String columnName,
            Map<String, Object> dependencyInfo,
            Map<String, Object> dependencyBuildInfo
        ) {
            // DB から変数定義と代入文を取得（ローカル解析は不要）
            List<Map<String, String>> variableDefinitions = dbQueryAgent.queryVariableDefinitions(columnName);
            List<Map<String, String>> variableAssignments = dbQueryAgent.queryVariableAssignments(columnName);
            
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
            
            // ========== セクション 0: 詳細グラフ情報（DependencyAnalyzer生成） ==========
            String detailedGraphContent = readDetailedGraphReport();
            if (detailedGraphContent != null && !detailedGraphContent.isEmpty()) {
                markdown.append("## Detailed Dependency Graph\n\n");
                markdown.append(detailedGraphContent).append("\n\n");
                
                // 詳細グラフレポートから出現箇所（代入文）を抽出
                extractAndAddAssignmentsFromGraph(markdown, detailedGraphContent);
            }
            
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
            markdown.append("(COPY dependencies are tracked via the dependency graph above)\n\n");
            
            // ========== セクション 3: 識別された変数 ==========
            markdown.append("## Identified Variables\n\n");
            if (variableDefinitions != null && !variableDefinitions.isEmpty()) {
                markdown.append("| Variable Name | File | Level | Data Type | Description |\n");
                markdown.append("|---|---|---|---|---|\n");
                for (Map<String, String> varDef : variableDefinitions) {
                    markdown.append("| `").append(varDef.get("variableName")).append("` | ");
                    markdown.append("`").append(new File(varDef.get("filePath")).getName()).append("` | ");
                    markdown.append(varDef.get("level") != null ? varDef.get("level") : "N/A").append(" | ");
                    markdown.append(varDef.get("picClause") != null ? varDef.get("picClause") : "N/A").append(" | ");
                    markdown.append(varDef.get("description") != null && !varDef.get("description").isEmpty() ? varDef.get("description") : "").append(" |\n");
                }
            } else {
                markdown.append("No variables identified.\n");
            }

            // ========== セクション 4: 代入文 ===========
            markdown.append("\n## Assignment Statements\n\n");
            if (variableAssignments != null && !variableAssignments.isEmpty()) {
                // ファイルごとにグループ化
                Map<String, List<Map<String, String>>> assignmentsByFile = new LinkedHashMap<>();
                for (Map<String, String> assignment : variableAssignments) {
                    String filePath = assignment.get("filePath");
                    assignmentsByFile.computeIfAbsent(filePath, k -> new ArrayList<>()).add(assignment);
                }
                
                for (Map.Entry<String, List<Map<String, String>>> entry : assignmentsByFile.entrySet()) {
                    markdown.append("### ").append(new File(entry.getKey()).getName()).append("\n\n");
                    markdown.append("**Path**: `").append(entry.getKey()).append("`\n\n");
                    markdown.append("| Variable Name | Line | Statement Type | Source |\n");
                    markdown.append("|---|---|---|---|\n");
                    for (Map<String, String> assignment : entry.getValue()) {
                        markdown.append("| `").append(assignment.get("variableName")).append("` | ");
                        markdown.append(assignment.get("lineNumber")).append(" | ");
                        markdown.append(assignment.get("statementType")).append(" | ");
                        markdown.append(escapeMarkdown(assignment.get("sourceLine"))).append(" |\n");
                    }
                    markdown.append("\n");
                }
            } else {
                markdown.append("No assignment statements identified.\n");
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

        /**
         * Markdown テーブル用にセル文字列をエスケープします。
         *
         * @param value 元の文字列
         * @return エスケープ済み文字列
         */
        private String escapeMarkdown(String value) {
            if (value == null) {
                return "";
            }
            return value.replace("|", "\\|").replace("\n", "<br>");
        }
        
        /**
         * DependencyAnalyzer が生成した詳細グラフレポートを読み込みます。
         *
         * @return レポート内容（存在しない場合は null）
         */
        private String readDetailedGraphReport() {
            try {
                java.nio.file.Path reportPath = java.nio.file.Paths.get(
                    System.getProperty("user.dir"), 
                    "build", "reports", "cobol-dependency-report.md"
                );
                if (java.nio.file.Files.exists(reportPath)) {
                    return new String(java.nio.file.Files.readAllBytes(reportPath), 
                        java.nio.charset.StandardCharsets.UTF_8);
                }
            } catch (Exception e) {
                // ファイル読み込み失敗時は無視
            }
            return null;
        }
        
        /**
         * 詳細グラフレポートから出現箇所（代入文）を抽出して、markdownに追加します。
         *
         * @param markdown 追記対象の StringBuilder
         * @param graphContent グラフレポートのコンテンツ
         */
        private void extractAndAddAssignmentsFromGraph(StringBuilder markdown, String graphContent) {
            try {
                // "依存関係グラフ (カラム: ...)" セクションを探す
                int graphSectionStart = graphContent.indexOf("## 依存関係グラフ");
                if (graphSectionStart < 0) {
                    return;
                }
                
                // テーブル部分を抽出（最初の | から次のセクションまで）
                int tableStart = graphContent.indexOf("|", graphSectionStart);
                if (tableStart < 0) {
                    return;
                }
                
                String[] lines = graphContent.substring(tableStart).split("\n");
                Map<String, String> fileLocationMap = new LinkedHashMap<>();
                
                // テーブルの行をパース（スキップ行を除く）
                boolean isHeader = true;
                for (String line : lines) {
                    if (line.trim().isEmpty() || line.startsWith("##")) {
                        break;
                    }
                    if (line.startsWith("|")) {
                        if (isHeader && line.contains("---")) {
                            isHeader = false;
                            continue;
                        }
                        if (!isHeader) {
                            // テーブル行をパース
                            String[] cells = line.split("\\|");
                            if (cells.length >= 4) {
                                String filePath = cells[2].trim().replaceAll("`", "");
                                String location = cells[4].trim().replaceAll("`", "");
                                if (!filePath.isEmpty() && !location.isEmpty()) {
                                    fileLocationMap.put(filePath, location);
                                }
                            }
                        }
                    }
                }
                
                // 出現箇所をレポートに追加
                if (!fileLocationMap.isEmpty()) {
                    markdown.append("## Source Code References (Line Numbers)\n\n");
                    markdown.append("| File | Location |\n");
                    markdown.append("|---|---|\n");
                    for (Map.Entry<String, String> entry : fileLocationMap.entrySet()) {
                        markdown.append("| `").append(new java.io.File(entry.getKey()).getName())
                            .append("` | `").append(entry.getValue()).append("` |\n");
                    }
                    markdown.append("\n");
                }
            } catch (Exception e) {
                // パース失敗時は無視
            }
        }
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
        
        System.out.println("Step 1: Rebuilding Dependency DB...");
        DependencyAnalyzerAgent dependencyAnalyzer = new DependencyAnalyzerAgent();
        Map<String, Object> buildInfo = dependencyAnalyzer.analyze(
            extractedParams.get("cobolDir"),
            extractedParams.get("copyDir"),
            extractedParams.get("columnName")
        );
        
        System.out.println("Step 2: Querying Dependencies...");
        DatabaseQueryAgent dbQueryAgent = new DatabaseQueryAgent();
        Map<String, Object> dependencyInfo = dbQueryAgent.queryDependencies(
            extractedParams.get("tableName"),
            extractedParams.get("columnName")
        );
        
        System.out.println("Step 3: Saving Report...");
        ResultSaverAgent resultSaver = new ResultSaverAgent();
        String result = resultSaver.save(
            extractedParams.get("tableName"),
            extractedParams.get("columnName"),
            dependencyInfo,
            buildInfo
        );
        
        System.out.println("=== Analysis Result ===");
        System.out.println(result);
    }
}
    

