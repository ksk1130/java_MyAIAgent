package org.example.cobol;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.google.common.base.Strings;

/**
 * COBOL プログラムの依存関係を自動検出し、
 * Apache Derby データベースに登録するツール。
 */
public class CobolDependencyAnalyzer {

    private static final String DB_URL = "jdbc:derby:cobol_dependencies;create=true";
    private static final String DEFAULT_COBOL_DIR = "app/src/main/resources/cobol";

    private String cobolDir;
    private String copyDir;
    private Connection connection;
    private Map<String, String> programIdMap;
    private Map<String, Path> copybookPathMap;
    private Set<String> loggedResolvedCopybooks;
    private CobolFileFinder fileFinder;
    private CobolDatabaseManager dbManager;
    private CobolDependencyParser dependencyParser;
    private CobolDependencyGraph dependencyGraph;
    private CobolProgramRegistrar programRegistrar;
    private final CobolColumnAnalysisUtil columnAnalysisUtil;
    private CobolColumnAnalysisUtil.ColumnAnalysisSummary targetColumnSummary;
    private static final Logger logger = LoggerFactory.getLogger(CobolDependencyAnalyzer.class);

    /**
     * 依存関係表示の対象となるカラム名（大文字）。
     * デフォルトは ZIPCODE。
     */
    private String targetColumn = "ZIPCODE";

    /**
     * 指定されたディレクトリパスで解析器を初期化します。
     * 
     * @param cobolDir COBOL ソースファイルが格納されているディレクトリのパス
     */
    public CobolDependencyAnalyzer(String cobolDir) {
        this(cobolDir, null);
    }

    /**
     * 指定された COBOL ディレクトリと COPY ディレクトリで解析器を初期化します。
     *
     * @param cobolDir COBOL ソースファイルが格納されているディレクトリのパス
     * @param copyDir  COPY ブックが格納されているディレクトリのパス
     */
    public CobolDependencyAnalyzer(String cobolDir, String copyDir) {
        this.cobolDir = cobolDir != null ? cobolDir : DEFAULT_COBOL_DIR;
        this.copyDir = Strings.isNullOrEmpty(copyDir) ? null : copyDir;
        this.programIdMap = new HashMap<>();
        this.copybookPathMap = new HashMap<>();
        this.loggedResolvedCopybooks = new HashSet<>();
        this.fileFinder = new CobolFileFinder(this.cobolDir, this.copyDir);
        this.dbManager = new CobolDatabaseManager(DB_URL);
        this.columnAnalysisUtil = new CobolColumnAnalysisUtil();
        this.targetColumnSummary = CobolColumnAnalysisUtil.ColumnAnalysisSummary.empty(this.targetColumn);
        // dependencyParser、dependencyGraph、programRegistrarはrun()内で初期化
    }

    /**
     * デフォルトのディレクトリパスで解析器を初期化します。
     */
    public CobolDependencyAnalyzer() {
        this(DEFAULT_COBOL_DIR);
    }

    /**
     * メインエントリポイント。
     * CLI 引数を解析し、解析処理を実行します。
     * 
     * @param args コマンドライン引数。--clean を指定するとデータベースを初期化します。
     */
    public static void main(String[] args) {
        String cobolPath = DEFAULT_COBOL_DIR;
        String copyDirArg = null;
        boolean cleanDb = false;
        String targetColumnArg = null;

        // args を解析して --clean フラグと COBOL パスを抽出
        var argsList = new ArrayList<String>();
        for (var arg : args) {
            // --cleanフラグによらず、常にクリーンアップするように変更
            cleanDb = true;

            if ("--clean".equalsIgnoreCase(arg)) {
                // cleanDb = true;
            } else if (arg.startsWith("--column=")) {
                targetColumnArg = arg.substring("--column=".length());
            } else if (arg.startsWith("--copyDir=")) {
                copyDirArg = arg.substring("--copyDir=".length());
            } else {
                argsList.add(arg);
            }
        }

        // COBOL パスを指定されていれば使用
        if (!argsList.isEmpty()) {
            cobolPath = argsList.get(0);
        }

        // DB をクリーンアップ（指定時）
        if (cleanDb) {
            cleanupDatabase();
        }

        var analyzer = new CobolDependencyAnalyzer(cobolPath, copyDirArg);
        if (targetColumnArg != null && !targetColumnArg.isBlank()) {
            analyzer.setTargetColumn(targetColumnArg);
        }
        try {
            analyzer.run();
        } catch (Exception e) {
            logger.error("エラー: ", e);
        }
    }

    /**
     * Derby DB ディレクトリを削除
     */
    private static void cleanupDatabase() {
        var dbPath = Paths.get(System.getProperty("user.dir"), "cobol_dependencies");
        if (Files.exists(dbPath)) {
            logger.info("[Cleanup] Removing database: {}", dbPath.toAbsolutePath());
            try {
                deleteDirectory(dbPath);
                logger.info("[Cleanup] Database removed successfully");
            } catch (IOException e) {
                logger.warn("[Cleanup] Warning: Failed to delete database: {}", e.getMessage());
            }
        }
    }

    /**
     * 依存関係データベースを明示的に初期化します。
     * AIAgent など他コンポーネントから再解析前に利用します。
     */
    public static void resetDatabase() {
        cleanupDatabase();
    }

    /**
     * ディレクトリを再帰的に削除
     */
    private static void deleteDirectory(Path path) throws IOException {
        if (Files.isDirectory(path)) {
            try (var paths = Files.list(path)) {
                paths.forEach(p -> {
                    try {
                        deleteDirectory(p);
                    } catch (IOException e) {
                        logger.warn("[Cleanup] Error deleting {}: {}", p, e.getMessage());
                    }
                });
            }
        }
        Files.delete(path);
    }

    /**
     * 解析処理をメイン実行します。
     * データベースの初期化、ファイルの探索、2フェーズの解析、グラフ表示、データベース停止までを行います。
     * 
     * @throws IOException ファイル読み込み中にエラーが発生した場合
     */
    public void run() throws IOException {
        // DB初期化
        try {
            dbManager.connect();
        } catch (Exception e) {
            logger.error("データベース接続エラー: {}", e.getMessage());
            throw new IOException("DB接続失敗", e);
        }
        dbManager.createTablesIfNotExist();
        connection = dbManager.getConnection();

        try {
            logger.info("===== COBOL 依存関係検出ツール =====");

            // ファイル探索
            var cobolFiles = fileFinder.discoverCobolFiles();
            copybookPathMap = fileFinder.indexCopybookFiles();
            logger.info("検出ファイル数: {}", cobolFiles.size());
            logger.info("検出COPYブック数: {}", copybookPathMap.size());
                targetColumnSummary = collectTargetColumnSummary(cobolFiles, copybookPathMap);
                logger.info("対象カラム解析サマリ: column={}, analyzedFiles={}, matchedFiles={}, variables={}, assignments={}",
                    targetColumnSummary.columnName(),
                    targetColumnSummary.analyzedFileCount(),
                    targetColumnSummary.matchedFileCount(),
                    targetColumnSummary.variableCount(),
                    targetColumnSummary.assignmentCount());

            // 責務クラスを初期化
            programRegistrar = new CobolProgramRegistrar(connection);
            dependencyParser = new CobolDependencyParser(connection, copybookPathMap);
            dependencyGraph = new CobolDependencyGraph(connection, targetColumn);

            // フェーズ 1: すべてのプログラムを登録
            for (var cobolFile : cobolFiles) {
                programRegistrar.registerProgramOnly(cobolFile);
            }

            // フェーズ 2: 依存関係を解析して登録
            for (var cobolFile : cobolFiles) {
                dependencyParser.analyzeDependencies(cobolFile);
            }

            // フェーズ 3: 変数定義と代入文を DB に記録
            recordVariableDefinitionsAndAssignments(cobolFiles, copybookPathMap);

            dependencyGraph.displayDependencyGraph();

            logger.info("===== 処理完了 =====");
        } finally {
            // 例外の有無にかかわらず必ずデータベース接続を閉じる
            shutdownDatabase();
        }
    }

    /**
     * 依存関係表示の対象カラムを設定します。
     * 
     * @param column カラム名（任意の大文字/小文字を受け付けます）
     */
    public void setTargetColumn(String column) {
        if (!Strings.isNullOrEmpty(column)) {
            this.targetColumn = column.toUpperCase();
            this.targetColumnSummary = CobolColumnAnalysisUtil.ColumnAnalysisSummary.empty(this.targetColumn);
        }
    }

    /**
     * 対象カラム解析の集計サマリを返します。
     *
     * @return 対象カラム解析サマリ
     */
    public CobolColumnAnalysisUtil.ColumnAnalysisSummary getTargetColumnSummary() {
        return targetColumnSummary;
    }

    /**
     * COBOL ソースと COPY ブックを対象に、指定カラム由来の変数定義と代入文を集計します。
     *
     * @param cobolFiles COBOL ソース一覧
     * @param copybookPaths COPY ブック一覧
     * @return 集計サマリ
     */
    private CobolColumnAnalysisUtil.ColumnAnalysisSummary collectTargetColumnSummary(
        List<Path> cobolFiles,
        Map<String, Path> copybookPaths) {
        Map<String, CobolColumnAnalysisUtil.ColumnAnalysis> analyses = new LinkedHashMap<>();

        for (Path cobolFile : cobolFiles) {
            addColumnAnalysis(analyses, cobolFile);
        }
        for (Path copybookPath : copybookPaths.values()) {
            addColumnAnalysis(analyses, copybookPath);
        }

        return columnAnalysisUtil.summarizeAnalyses(targetColumn, analyses, cobolFiles.size() + copybookPaths.size());
    }

    /**
     * 単一ファイルの対象カラム解析結果を集計対象へ追加します。
     *
     * @param analyses 集計対象
     * @param filePath 対象ファイル
     */
    private void addColumnAnalysis(Map<String, CobolColumnAnalysisUtil.ColumnAnalysis> analyses, Path filePath) {
        if (filePath == null) {
            return;
        }

        var analysis = columnAnalysisUtil.analyzeFile(filePath, targetColumn);
        if (!analysis.variables().isEmpty() || !analysis.assignments().isEmpty()) {
            analyses.put(filePath.toString(), analysis);
        }
    }

    /**
     * データベース接続を安全に閉じ、Derby エンジンをシャットダウンします。
     */
    private void shutdownDatabase() {
        if (dbManager != null) {
            dbManager.close();
        }
        connection = null;
    }

    /**
     * 変数定義と代入文をデータベースに記録します。
     *
     * @param cobolFiles COBOL ソースファイル一覧
     * @param copybookPathMap COPY ブックパスマップ
     */
    private void recordVariableDefinitionsAndAssignments(
            List<Path> cobolFiles,
            Map<String, Path> copybookPathMap) {
        Map<String, Path> filesToAnalyze = new LinkedHashMap<>();

        // COBOL ファイルを追加
        for (Path file : cobolFiles) {
            filesToAnalyze.put(file.toString(), file);
        }

        // COPY ブックを追加
        for (Path file : copybookPathMap.values()) {
            filesToAnalyze.put(file.toString(), file);
        }

        // ===== パス 1: すべてのファイルから変数定義を収集 =====
        List<CobolColumnAnalysisUtil.VariableDefinition> allVariables = new ArrayList<>();
        Map<Path, String> normalizedContents = new LinkedHashMap<>();
        
        for (Path filePath : filesToAnalyze.values()) {
            try {
                // ファイル内容を読み込んで正規化
                String normalizedContent = columnAnalysisUtil.readNormalizedFile(filePath);
                normalizedContents.put(filePath, normalizedContent);
                
                // 変数定義を解析
                CobolColumnAnalysisUtil.ColumnAnalysis analysis =
                    columnAnalysisUtil.analyzeContent(normalizedContent, targetColumn);

                // 変数定義を DB に記録し、全変数リストに追加
                for (CobolColumnAnalysisUtil.VariableDefinition var : analysis.variables()) {
                    recordVariableDefinition(filePath.toString(), var);
                    allVariables.add(var);
                }
            } catch (Exception e) {
                logger.warn("変数定義解析エラー ({}): {}", filePath, e.getMessage());
            }
        }

        logger.info("全変数定義数: {}", allVariables.size());

        // ===== パス 2: すべてのファイルの代入文を解析（全変数リストを使用） =====
        for (Map.Entry<Path, String> entry : normalizedContents.entrySet()) {
            Path filePath = entry.getKey();
            String normalizedContent = entry.getValue();
            
            try {
                // 全変数リストを使って代入文を抽出
                List<CobolColumnAnalysisUtil.AssignmentOccurrence> assignments =
                    columnAnalysisUtil.extractAssignmentsOnly(normalizedContent, allVariables);

                // 代入文を DB に記録
                for (CobolColumnAnalysisUtil.AssignmentOccurrence assignment : assignments) {
                    recordVariableAssignment(filePath.toString(), assignment);
                }
                
                if (!assignments.isEmpty()) {
                    logger.debug("代入文検出 ({}): {} 件", filePath.getFileName(), assignments.size());
                }
            } catch (Exception e) {
                logger.warn("代入文解析エラー ({}): {}", filePath, e.getMessage());
            }
        }
    }

    /**
     * 変数定義をデータベースに記録します。
     *
     * @param filePath ファイルパス
     * @param varDef 変数定義
     */
    private void recordVariableDefinition(String filePath, CobolColumnAnalysisUtil.VariableDefinition varDef) {
        try {
            String sql = """
                INSERT INTO variable_definitions (var_id, file_path, column_name, variable_name, 
                    level_number, pic_clause, description)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """;

            String varId = filePath + ":" + varDef.name() + ":" + targetColumn;
            try (var pstmt = connection.prepareStatement(sql)) {
                pstmt.setString(1, varId);
                pstmt.setString(2, filePath);
                pstmt.setString(3, targetColumn);
                pstmt.setString(4, varDef.name());
                pstmt.setString(5, varDef.level());
                pstmt.setString(6, varDef.picClause());
                pstmt.setString(7, varDef.description());
                pstmt.execute();
            }
        } catch (Exception e) {
            logger.debug("変数定義記録エラー: {}", e.getMessage());
        }
    }

    /**
     * 代入文をデータベースに記録します。
     *
     * @param filePath ファイルパス
     * @param assignment 代入文
     */
    private void recordVariableAssignment(
            String filePath,
            CobolColumnAnalysisUtil.AssignmentOccurrence assignment) {
        try {
            String sql = """
                INSERT INTO variable_assignments (assign_id, file_path, column_name, variable_name, 
                    line_number, statement_type, source_line)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """;

            String assignId = filePath + ":" + assignment.variableName() + ":" + assignment.lineNumber();
            try (var pstmt = connection.prepareStatement(sql)) {
                pstmt.setString(1, assignId);
                pstmt.setString(2, filePath);
                pstmt.setString(3, targetColumn);
                pstmt.setString(4, assignment.variableName());
                pstmt.setInt(5, assignment.lineNumber());
                pstmt.setString(6, assignment.statementType());
                pstmt.setString(7, assignment.sourceLine());
                pstmt.execute();
            }
        } catch (Exception e) {
            logger.debug("代入文記録エラー: {}", e.getMessage());
        }
    }
}