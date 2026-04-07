package org.example.cobol;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import org.example.tools.FileReaderTool;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

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
    private static final Path DEFAULT_REPORT_PATH = Paths.get("build", "reports", "cobol-dependency-report.md");
    private static final Pattern COPY_PATTERN = Pattern.compile("(?im)^\\s*COPY\\s+['\"]?([A-Za-z0-9_-]+)");
    private static final List<String> COPYBOOK_EXTENSIONS = List.of(".cpy", ".copy", ".cbl", ".cob");

    private String cobolDir;
    private Connection connection;
    private Map<String, String> programIdMap;
    private Map<String, Path> copybookPathMap;
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
        this.cobolDir = cobolDir != null ? cobolDir : DEFAULT_COBOL_DIR;
        this.programIdMap = new HashMap<>();
        this.copybookPathMap = new HashMap<>();
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

        var analyzer = new CobolDependencyAnalyzer(cobolPath);
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
        initializeDatabase();

        try {
            logger.info("===== COBOL 依存関係検出ツール =====");

            // 指定されたパスを使用
            var cobolDirPath = Paths.get(cobolDir);

            // 相対パスの場合、カレントディレクトリから
            if (!cobolDirPath.isAbsolute()) {
                cobolDirPath = Paths.get(System.getProperty("user.dir")).resolve(cobolDir);
            }

            logger.info("COBOL ディレクトリ: {}", cobolDirPath.toAbsolutePath());

            var cobolFiles = discoverCobolFiles(cobolDirPath);
            indexCopybookFiles(cobolDirPath);
            logger.info("検出ファイル数: {}", cobolFiles.size());
            logger.info("検出COPYブック数: {}", copybookPathMap.size());

            // フェーズ 1: すべてのプログラムを登録
            for (var cobolFile : cobolFiles) {
                registerProgramOnly(cobolFile);
            }

            // フェーズ 2: 依存関係を解析して登録
            for (var cobolFile : cobolFiles) {
                analyzeDependencies(cobolFile);
            }

            displayDependencyGraph();

            logger.info("===== 処理完了 =====");
        } finally {
            // 例外の有無にかかわらず必ずデータベース接続を閉じる
            shutdownDatabase();
        }
    }

    /**
     * 指定されたディレクトリから COBOL ファイルを再帰的に探索します。
     * 
     * @param dir 探索対象のディレクトリ
     * @return 見つかった COBOL ファイルのリスト
     * @throws IOException ディレクトリ探索中にエラーが発生した場合
     */
    private List<Path> discoverCobolFiles(Path dir) throws IOException {
        // 探索対象の拡張子
        var targetExtensions = List.of(".cbl", ".cob", ".scob");

        var cobolFiles = new ArrayList<Path>();

        if (!Files.exists(dir)) {
            logger.error("ディレクトリが見つかりません: {}", dir.toAbsolutePath());
            return cobolFiles;
        }

        try (var paths = Files.walk(dir)) {
            paths.filter(p -> targetExtensions.stream().anyMatch(ext -> p.toString().endsWith(ext)))
                    .forEach(cobolFiles::add);
        }

        return cobolFiles;
    }

    /**
     * プログラム自体の存在をデータベースに登録します (フェーズ 1)。
     * 
     * @param cobolFile 解析対象の COBOL ファイル
     * @throws IOException ファイル読み込み中にエラーが発生した場合
     */
    private void registerProgramOnly(Path cobolFile) throws IOException {
        var content = getFileContent(cobolFile);
        var programId = extractProgramId(content);
        if (programId == null) {
            return;
        }
        programIdMap.put(cobolFile.getFileName().toString(), programId);
        registerProgram(programId, cobolFile.toString());
    }

    /**
     * プログラム間の CALL 依存関係および DB テーブルアクセスを解析します (フェーズ 2)。
     * 
     * @param cobolFile 解析対象の COBOL ファイル
     * @throws IOException ファイル読み込み中にエラーが発生した場合
     */
    private void analyzeDependencies(Path cobolFile) throws IOException {
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
     * 
     * @param content COBOL ソースコードの内容
     * @return 抽出されたプログラムID (正規化後)、見つからない場合は null
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
     * ソースコード内の SQL 文を検出し、テーブルアクセスの情報をデータベースに登録します。
     * 
     * @param programId アクセス元のプログラムID
     * @param content   ソースコードの内容
     * @param cobolFile ファイルパス
     */
    private void detectTableAccess(String programId, String content, Path cobolFile) {
        // INSERT INTO 文を正規表現で検出
        var insertPattern = Pattern.compile("(?i)INSERT\\s+INTO\\s+([\\w_]+)");
        var insertMatcher = insertPattern.matcher(content);
        while (insertMatcher.find()) {
            var tableName = insertMatcher.group(1).toUpperCase();
            logger.info("  - INSERT: {}", tableName);
            detectColumnsInInsert(programId, tableName, content, cobolFile.toString());
        }

        // SELECT文を正規表現で検出
        var selectPattern = Pattern.compile("(?i)SELECT\\s+.*?\\sFROM\\s+([\\w_]+)");
        var selectMatcher = selectPattern.matcher(content);
        while (selectMatcher.find()) {
            var tableName = selectMatcher.group(1).toUpperCase();
            logger.info("  - SELECT: {}", tableName);
            // カラム名の抽出は複雑なため、ここではテーブル名のみ登録
            registerTableAccess(programId, tableName, "ALL_COLUMNS", "SELECT", cobolFile.toString());
        }

        // UPDATE 文を正規表現で検出
        var updatePattern = Pattern.compile("(?i)UPDATE\\s+([\\w_]+)");
        var updateMatcher = updatePattern.matcher(content);
        while (updateMatcher.find()) {
            var tableName = updateMatcher.group(1).toUpperCase();
            logger.info("  - UPDATE: {}", tableName);
            // カラム名の抽出は複雑なため、ここではテーブル名のみ登録
            registerTableAccess(programId, tableName, "ALL_COLUMNS", "UPDATE", cobolFile.toString());
        }

        // DELETE 文を正規表現で検出
        var deletePattern = Pattern.compile("(?i)DELETE\\s+FROM\\s+([\\w_]+)");
        var deleteMatcher = deletePattern.matcher(content);
        while (deleteMatcher.find()) {
            var tableName = deleteMatcher.group(1).toUpperCase();
            logger.info("  - DELETE: {}", tableName);
            // カラム名の抽出は複雑なため、ここではテーブル名のみ登録
            registerTableAccess(programId, tableName, "ALL_COLUMNS", "DELETE", cobolFile.toString());
        }
    }

    /**
     * INSERT 文の VALUES 句からカラム名を抽出して登録します。
     * 
     * @param programId アクセス元のプログラムID
     * @param tableName 対象のテーブル名
     * @param content   ソースコードの内容
     * @param location  ソースファイルの位置
     */
    private void detectColumnsInInsert(String programId, String tableName,
            String content, String location) {
        var valuesPattern = Pattern.compile("(?i)VALUES\\s*\\(([^)]+)\\)");
        var valuesMatcher = valuesPattern.matcher(content);

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
     * ソースコード内の CALL 文を検出し、他プログラムへの依存関係を登録します。
     * 
     * @param programId 解析元のプログラムID
     * @param content   ソースコードの内容
     * @param cobolFile ファイルパス
     */
    private void detectCallDependencies(String programId, String content, Path cobolFile) {
        var callPattern = Pattern.compile("(?i)CALL\\s+['\"]?([\\w-]+)['\"]?");
        var callMatcher = callPattern.matcher(content);

        while (callMatcher.find()) {
            var calledProgram = callMatcher.group(1).toLowerCase().replace('-', '_');
            logger.info("  - CALL: {}", calledProgram);
            registerCallDependency(programId, calledProgram, cobolFile.toString());
        }
    }

    /**
     * ソースコード内の COPY 句を検出し、コピーブック参照関係を登録します。
     *
     * @param programId 解析元のプログラムID
     * @param content ソースコードの内容
     * @param cobolFile ファイルパス
     */
    private void detectCopyDependencies(String programId, String content, Path cobolFile) {
        var dependencyInfoMap = new LinkedHashMap<String, CopyDependencyInfo>();
        var queue = new ArrayDeque<CopyTraversalNode>();

        for (var copybookName : extractCopybookNames(content)) {
            logger.info("  - COPY: {}", copybookName);
            dependencyInfoMap.putIfAbsent(copybookName, new CopyDependencyInfo(1, null, cobolFile.toString()));
            queue.addLast(new CopyTraversalNode(copybookName, 1));
        }

        while (!queue.isEmpty()) {
            var current = queue.removeFirst();
            var currentCopybookPath = findCopybookPath(current.copybookName());
            if (currentCopybookPath == null) {
                logger.debug("COPYブックが見つかりません: {}", current.copybookName());
                continue;
            }

            var copyContent = getFileContent(currentCopybookPath);
            for (var nestedCopybookName : extractCopybookNames(copyContent)) {
                var nextDepth = current.depth() + 1;
                var existing = dependencyInfoMap.get(nestedCopybookName);
                if (existing == null || nextDepth < existing.depth()) {
                    logger.info("  - COPY(depth={}): {} via {}", nextDepth, nestedCopybookName, current.copybookName());
                    dependencyInfoMap.put(nestedCopybookName,
                            new CopyDependencyInfo(nextDepth, current.copybookName(), currentCopybookPath.toString()));
                    queue.addLast(new CopyTraversalNode(nestedCopybookName, nextDepth));
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

    /**
     * COPY 句からコピーブック名を抽出します。
     *
     * @param content COBOL または COPY ファイルの内容
     * @return 正規化済みコピーブック名の集合
     */
    private Set<String> extractCopybookNames(String content) {
        var copyMatcher = COPY_PATTERN.matcher(content);
        var copybookNames = new HashSet<String>();

        while (copyMatcher.find()) {
            copybookNames.add(copyMatcher.group(1).toUpperCase(Locale.ROOT));
        }

        return copybookNames;
    }

    /**
     * COBOL ディレクトリ周辺から COPY ブック候補を索引化します。
     *
     * @param cobolDirPath COBOL ソースのルートディレクトリ
     */
    private void indexCopybookFiles(Path cobolDirPath) {
        copybookPathMap.clear();

        var searchRoots = new ArrayList<Path>();
        var normalizedCobolDir = cobolDirPath.toAbsolutePath().normalize();
        var parent = normalizedCobolDir.getParent();

        if (parent != null) {
            searchRoots.add(parent.resolve("copy"));
            searchRoots.add(parent);
        }
        searchRoots.add(normalizedCobolDir);
        searchRoots.add(Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize());

        var visitedRoots = new HashSet<Path>();
        for (var root : searchRoots) {
            if (!Files.exists(root) || !visitedRoots.add(root)) {
                continue;
            }

            try (var paths = Files.walk(root)) {
                paths.filter(Files::isRegularFile)
                        .filter(this::isCopybookFile)
                        .forEach(path -> copybookPathMap.putIfAbsent(getBaseFileName(path), path));
            } catch (IOException e) {
                logger.debug("COPYブック索引化をスキップしました: {}", root);
            }
        }
    }

    /**
     * 指定名の COPY ブックファイルを取得します。
     *
     * @param copybookName コピーブック名
     * @return ファイルパス。見つからない場合は null
     */
    private Path findCopybookPath(String copybookName) {
        return copybookPathMap.get(copybookName.toUpperCase(Locale.ROOT));
    }

    /**
     * COPY ブックとして扱う拡張子か判定します。
     *
     * @param path 判定対象パス
     * @return COPY ブック候補の場合 true
     */
    private boolean isCopybookFile(Path path) {
        var lowerName = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return COPYBOOK_EXTENSIONS.stream().anyMatch(lowerName::endsWith);
    }

    /**
     * 拡張子を除いたベースファイル名を取得します。
     *
     * @param path 対象パス
     * @return 大文字化したベースファイル名
     */
    private String getBaseFileName(Path path) {
        var fileName = path.getFileName().toString();
        for (var extension : COPYBOOK_EXTENSIONS) {
            if (fileName.toLowerCase(Locale.ROOT).endsWith(extension)) {
                return fileName.substring(0, fileName.length() - extension.length()).toUpperCase(Locale.ROOT);
            }
        }
        return fileName.toUpperCase(Locale.ROOT);
    }

    /**
     * プログラム情報をデータベースに登録します。
     * 
     * @param programId プログラムID
     * @param filePath  ファイルパス
     */
    private void registerProgram(String programId, String filePath) {
        var sql = "INSERT INTO cobol_programs (program_id, program_name, file_path, updated_at) "
                + "VALUES (?, ?, ?, CURRENT_TIMESTAMP)";

        try (var stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, programId);
            stmt.setString(2, programId.replace('_', '-'));
            stmt.setString(3, filePath);
            stmt.executeUpdate();
        } catch (Exception e) {
            logger.error("プログラム登録エラー: {}", e.getMessage());
        }
    }

    /**
     * テーブルアクセスの情報をデータベースに登録します。
     * カラム情報が未登録の場合は table_columns にも登録します。
     * 
     * @param programId  アクセス元のプログラムID
     * @param tableName  アクセス先のテーブル名
     * @param columnName アクセス先のカラム名
     * @param accessType アクセスタイプ (例: INSERT)
     * @param location   ソースファイルの位置
     */
    private void registerTableAccess(String programId, String tableName, String columnName,
            String accessType, String location) {
        var columnId = tableName + ":" + columnName;

        var tableColumnSql = """
                INSERT INTO table_columns (column_id, table_name, column_name) VALUES (?, ?, ?)
                    """;

        try (var stmt = connection.prepareStatement(tableColumnSql)) {
            stmt.setString(1, columnId);
            stmt.setString(2, tableName);
            stmt.setString(3, columnName);
            stmt.executeUpdate();
        } catch (Exception e) {
            // 既存レコードは無視
        }

        var accessId = programId + ":" + columnId + ":" + accessType.toLowerCase();
        var accessSql = """
                INSERT INTO cobol_table_access (access_id, program_id, column_id, access_type, sql_location) VALUES (?, ?, ?, ?, ?)
                    """;

        try (var stmt = connection.prepareStatement(accessSql)) {
            stmt.setString(1, accessId);
            stmt.setString(2, programId);
            stmt.setString(3, columnId);
            stmt.setString(4, accessType);
            stmt.setString(5, location);
            stmt.executeUpdate();
        } catch (Exception e) {
            // 既存レコードは無視
        }
    }

    /**
     * CALL 文によるプログラム間の依存関係をデータベースに登録します。
     * 
     * @param callerProgramId 呼び出し元プログラムID
     * @param calleeProgramId 呼び出し先プログラムID
     * @param location        呼び出し箇所の位置情報
     */
    private void registerCallDependency(String callerProgramId, String calleeProgramId, String location) {
        var sql = """
                INSERT INTO cobol_call_dependency (dep_id, caller_program_id, callee_program_id, call_location) VALUES (?, ?, ?, ?)
                    """;

        try (var stmt = connection.prepareStatement(sql)) {
            var depId = callerProgramId + "_calls_" + calleeProgramId;
            stmt.setString(1, depId);
            stmt.setString(2, callerProgramId);
            stmt.setString(3, calleeProgramId);
            stmt.setString(4, location);
            stmt.executeUpdate();
        } catch (Exception e) {
            logger.error("CALL依存関係登録エラー ({} -> {}): {}", callerProgramId, calleeProgramId, e.getMessage());
        }
    }

    /**
     * COPY 句によるプログラムからコピーブックへの参照関係をデータベースに登録します。
     *
     * @param programId 参照元プログラムID
     * @param copybookName 参照先コピーブック名
     * @param location 参照箇所の位置情報
     */
    private void registerCopyDependency(String programId, String copybookName, String location, int depth,
            String viaCopybook) {
        var sql = """
                INSERT INTO cobol_copy_dependency (dep_id, program_id, copybook_name, copy_location, copy_depth, via_copybook) VALUES (?, ?, ?, ?, ?, ?)
                    """;

        try (var stmt = connection.prepareStatement(sql)) {
            var depId = programId + "_copies_" + copybookName + "_" + depth;
            stmt.setString(1, depId);
            stmt.setString(2, programId);
            stmt.setString(3, copybookName);
            stmt.setString(4, location);
            stmt.setInt(5, depth);
            stmt.setString(6, viaCopybook);
            stmt.executeUpdate();
        } catch (Exception e) {
            logger.error("COPY参照関係登録エラー ({} -> {}): {}", programId, copybookName, e.getMessage());
        }
    }

    /**
     * データベースに登録された情報を元に依存関係グラフを表示します。
     * ZIPCODE カラムへの依存を例に、直接的および間接的な依存関係を出力します。
     */
    private void displayDependencyGraph() {
        var targetDependencyMarkdown = buildTargetDependencyMarkdown();
        logger.info("\n{}", targetDependencyMarkdown);

        var programDependencyMarkdown = buildProgramDependencyMarkdown();
        logger.info("\n{}", programDependencyMarkdown);

        saveMarkdownReport(targetDependencyMarkdown, programDependencyMarkdown);
    }

    /**
     * 対象カラムに対する依存関係の Markdown を生成します。
     *
     * @return Markdown 形式の依存関係文字列
     */
    private String buildTargetDependencyMarkdown() {
        var sectionTitle = "## 依存関係グラフ (カラム: " + targetColumn + ")";

        // デバッグ: 登録されているデータを確認
        debugPrintTableContents();

        var rows = new ArrayList<DependencyGraphRow>();

        var sql = """
                    SELECT program_name, file_path, dependency_type
                FROM (
                  SELECT DISTINCT p.program_name, p.file_path, 'DIRECT' AS dependency_type
                  FROM cobol_table_access cta
                  JOIN cobol_programs p ON cta.program_id = p.program_id
                  JOIN table_columns tc ON cta.column_id = tc.column_id
                  WHERE tc.column_name = ?
                  UNION ALL
                  SELECT DISTINCT p1.program_name, p1.file_path,
                         'INDIRECT (via ' || p2.program_name || ')' AS dependency_type
                  FROM cobol_call_dependency ccd
                  JOIN cobol_programs p1 ON ccd.caller_program_id = p1.program_id
                  JOIN cobol_programs p2 ON ccd.callee_program_id = p2.program_id
                  JOIN cobol_table_access cta ON p2.program_id = cta.program_id
                  JOIN table_columns tc ON cta.column_id = tc.column_id
                  WHERE tc.column_name = ?
                ) AS results
                ORDER BY dependency_type DESC, program_name
                """;

        try (var pstmt = connection.prepareStatement(sql)) {
            // パラメータを2箇所セット
            pstmt.setString(1, targetColumn);
            pstmt.setString(2, targetColumn);
            try (var rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    rows.add(new DependencyGraphRow(
                            rs.getString("program_name"),
                            rs.getString("file_path"),
                            rs.getString("dependency_type")));
                }
            }
        } catch (Exception e) {
            logger.error("グラフ表示エラー: {}", e.getMessage());
        }

        addCopyMediatedDependencies(rows);

        rows.sort((left, right) -> {
            var byType = left.dependencyType().compareTo(right.dependencyType());
            if (byType != 0) {
                return byType;
            }
            return left.programName().compareTo(right.programName());
        });

        if (rows.isEmpty()) {
            return sectionTitle + "\n\n" + "（" + targetColumn + " に依存するプログラムが見つかりません）";
        }

        return sectionTitle + "\n\n" + toMarkdownTable(
                List.of("プログラム名", "ファイルパス", "依存タイプ"),
                rows.stream()
                        .map(row -> List.of(row.programName(), row.filePath(), row.dependencyType()))
                        .toList());
    }

    /**
     * 対象カラム名を含む COPY ブックを参照しているプログラムを依存関係一覧へ追加します。
     *
     * @param rows 依存関係一覧
     */
    private void addCopyMediatedDependencies(List<DependencyGraphRow> rows) {
        var matchingCopybooks = findCopybooksContainingTargetColumn();
        if (matchingCopybooks.isEmpty()) {
            return;
        }

        var existingKeys = new HashSet<String>();
        for (var row : rows) {
            existingKeys.add(row.programName() + "|" + row.dependencyType());
        }

        var sql = """
                SELECT p.program_name, p.file_path, ccd.copybook_name, ccd.copy_depth, ccd.via_copybook
                  FROM cobol_copy_dependency ccd
                  JOIN cobol_programs p ON ccd.program_id = p.program_id
                ORDER BY p.program_name, ccd.copy_depth, ccd.copybook_name
                """;

        try (var stmt = connection.createStatement();
                var rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                var copybookName = rs.getString("copybook_name");
                if (!matchingCopybooks.contains(copybookName)) {
                    continue;
                }

                var depth = rs.getInt("copy_depth");
                var viaCopybook = rs.getString("via_copybook");
                var dependencyType = depth <= 1
                        ? "COPY DIRECT (" + copybookName + ")"
                        : "COPY INDIRECT (via " + viaCopybook + ", " + copybookName + ")";
                var key = rs.getString("program_name") + "|" + dependencyType;
                if (existingKeys.add(key)) {
                    rows.add(new DependencyGraphRow(
                            rs.getString("program_name"),
                            rs.getString("file_path"),
                            dependencyType));
                }
            }
        } catch (Exception e) {
            logger.error("COPY経由依存関係表示エラー: {}", e.getMessage());
        }
    }

    /**
     * 対象カラム名を含む COPY ブック名の集合を返します。
     *
     * @return 対象カラムを含む COPY ブック名
     */
    private Set<String> findCopybooksContainingTargetColumn() {
        var matchingCopybooks = new HashSet<String>();
        var normalizedTarget = targetColumn.toUpperCase(Locale.ROOT);

        for (var entry : copybookPathMap.entrySet()) {
            var content = getFileContent(entry.getValue());
            if (!Strings.isNullOrEmpty(content) && content.toUpperCase(Locale.ROOT).contains(normalizedTarget)) {
                matchingCopybooks.add(entry.getKey());
            }
        }

        return matchingCopybooks;
    }

    /**
     * CALL と COPY の依存関係を一覧表示します。
     */
    private String buildProgramDependencyMarkdown() {
        var sql = """
                SELECT p.program_name,
                       p.file_path,
                       'CALL' AS dependency_kind,
                       ccd.callee_program_id AS dependency_target,
                       CAST(NULL AS INTEGER) AS dependency_depth,
                       CAST(NULL AS VARCHAR(100)) AS via_target
                  FROM cobol_call_dependency ccd
                  JOIN cobol_programs p ON ccd.caller_program_id = p.program_id
                UNION ALL
                SELECT p.program_name,
                       p.file_path,
                       'COPY' AS dependency_kind,
                       ccd.copybook_name AS dependency_target,
                       ccd.copy_depth AS dependency_depth,
                       ccd.via_copybook AS via_target
                  FROM cobol_copy_dependency ccd
                  JOIN cobol_programs p ON ccd.program_id = p.program_id
                ORDER BY program_name, dependency_kind, dependency_target
                """;

        var rows = new ArrayList<ProgramDependencyRow>();
        try (var stmt = connection.createStatement();
                var rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                var dependencyKind = rs.getString("dependency_kind");
                var dependencyTarget = rs.getString("dependency_target");
                var depth = rs.getInt("dependency_depth");
                var viaTarget = rs.getString("via_target");
                var dependencyType = "CALL".equals(dependencyKind) || depth <= 1
                        ? "DIRECT"
                        : "INDIRECT via " + viaTarget;
                rows.add(new ProgramDependencyRow(
                        rs.getString("program_name"),
                        rs.getString("file_path"),
                        dependencyKind,
                        dependencyTarget,
                        dependencyType));
            }
        } catch (Exception e) {
            logger.error("CALL / COPY 依存関係表示エラー: {}", e.getMessage());
            return "## CALL / COPY 依存関係\n\nCALL / COPY 依存関係の生成中にエラーが発生しました。";
        }

        if (rows.isEmpty()) {
            return "## CALL / COPY 依存関係\n\n（CALL / COPY 依存関係は見つかりませんでした）";
        }

        return "## CALL / COPY 依存関係\n\n" + toMarkdownTable(
                List.of("プログラム名", "ファイルパス", "依存種別", "参照先", "依存タイプ"),
                rows.stream()
                        .map(row -> List.of(
                                row.programName(),
                                row.filePath(),
                                row.dependencyKind(),
                                row.dependencyTarget(),
                                row.dependencyType()))
                        .toList());
    }

    /**
     * Markdown レポートをファイルへ保存します。
     *
     * @param targetDependencyMarkdown 対象カラム依存の Markdown
     * @param programDependencyMarkdown CALL/COPY 依存の Markdown
     */
    private void saveMarkdownReport(String targetDependencyMarkdown, String programDependencyMarkdown) {
        var reportPath = Paths.get(System.getProperty("user.dir")).resolve(DEFAULT_REPORT_PATH);
        var reportContent = String.join("\n\n",
                "# COBOL 依存関係レポート",
                targetDependencyMarkdown,
                programDependencyMarkdown);

        try {
            Files.createDirectories(reportPath.getParent());
            Files.writeString(reportPath, reportContent);
            logger.info("Markdown レポートを出力しました: {}", reportPath.toAbsolutePath());
        } catch (IOException e) {
            logger.error("Markdown レポート出力エラー: {}", e.getMessage());
        }
    }

    /**
     * Markdown テーブルを生成します。
     *
     * @param headers ヘッダー列
     * @param rows 行データ
     * @return Markdown テーブル文字列
     */
    private String toMarkdownTable(List<String> headers, List<List<String>> rows) {
        var builder = new StringBuilder();
        builder.append("| ").append(String.join(" | ", headers)).append(" |\n");
        builder.append("| ");
        for (var i = 0; i < headers.size(); i++) {
            builder.append("---");
            if (i < headers.size() - 1) {
                builder.append(" | ");
            }
        }
        builder.append(" |\n");

        for (var row : rows) {
            builder.append("| ")
                    .append(String.join(" | ", row.stream().map(this::escapeMarkdownCell).toList()))
                    .append(" |\n");
        }

        return builder.toString();
    }

    /**
     * Markdown テーブルセル用に文字列をエスケープします。
     *
     * @param value セル値
     * @return エスケープ済み文字列
     */
    private String escapeMarkdownCell(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("|", "\\|").replace("\n", "<br>");
    }

    /**
     * データベース内の各テーブルの内容を標準出力に表示します（デバッグ用）。
     */
    private void debugPrintTableContents() {
        logger.debug("[デバッグ] テーブル内容:");

        // cobol_programs
        try (var stmt = connection.createStatement();
                var rs = stmt.executeQuery("SELECT * FROM cobol_programs")) {
            logger.debug("  cobol_programs:");
            while (rs.next()) {
                logger.debug("    - {}: {}", rs.getString("program_id"), rs.getString("program_name"));
            }
        } catch (Exception e) {
            logger.error("  エラー: {}", e.getMessage());
        }

        // cobol_call_dependency
        try (var stmt = connection.createStatement();
                var rs = stmt.executeQuery("SELECT * FROM cobol_call_dependency")) {
            logger.debug("  cobol_call_dependency:");
            while (rs.next()) {
                logger.debug("    - {} -> {}", rs.getString("caller_program_id"), rs.getString("callee_program_id"));
            }
        } catch (Exception e) {
            logger.error("  エラー: {}", e.getMessage());
        }

        // cobol_table_access
        try (var stmt = connection.createStatement();
                var rs = stmt.executeQuery("SELECT * FROM cobol_table_access")) {
            logger.debug("  cobol_table_access:");
            while (rs.next()) {
                logger.debug("    - {} -> {}", rs.getString("program_id"), rs.getString("column_id"));
            }
        } catch (Exception e) {
            logger.error("  エラー: {}", e.getMessage());
        }

        // cobol_copy_dependency
        try (var stmt = connection.createStatement();
                var rs = stmt.executeQuery("SELECT * FROM cobol_copy_dependency")) {
            logger.debug("  cobol_copy_dependency:");
            while (rs.next()) {
                logger.debug("    - {} -> {} (depth={}, via={})",
                        rs.getString("program_id"),
                        rs.getString("copybook_name"),
                        rs.getInt("copy_depth"),
                        rs.getString("via_copybook"));
            }
        } catch (Exception e) {
            logger.error("  エラー: {}", e.getMessage());
        }

        logger.debug("");
    }

    /**
     * データベースへの接続を確立し、必要に応じてテーブルを作成します。
     */
    private void initializeDatabase() {
        try {
            connection = DriverManager.getConnection(DB_URL);
            logger.info("Derby データベース接続: OK");
            createTablesIfNotExist();
        } catch (Exception e) {
            logger.error("データベース接続エラー: {}", e.getMessage());
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
        }
    }

    /**
     * 解析に必要なデータベーステーブルを作成します。
     * テーブルが既に存在する場合は何もしません。
     */
    private void createTablesIfNotExist() {
        try (var stmt = connection.createStatement()) {
            var createCobolPrograms = """
                    CREATE TABLE cobol_programs (
                        program_id VARCHAR(100) PRIMARY KEY,
                        program_name VARCHAR(100) NOT NULL,
                        file_path VARCHAR(500) NOT NULL,
                        created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                        updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                    )
                    """;
            stmt.execute(createCobolPrograms);
        } catch (Exception e) {
            // 既に存在する場合
        }

        try (var stmt = connection.createStatement()) {
            var createTableColumns = """
                    CREATE TABLE table_columns (
                        column_id VARCHAR(200) PRIMARY KEY,
                        table_name VARCHAR(100) NOT NULL,
                        column_name VARCHAR(100) NOT NULL,
                        data_type VARCHAR(50),
                        created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                        updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                    )
                    """;
            stmt.execute(createTableColumns);
            logger.info("テーブル table_columns を作成しました");
        } catch (Exception e) {
            // 既に存在する場合
        }

        try (var stmt = connection.createStatement()) {
            var createTableColumns = """
                    CREATE TABLE table_columns (
                        column_id VARCHAR(200) PRIMARY KEY,
                        table_name VARCHAR(100) NOT NULL,
                        column_name VARCHAR(100) NOT NULL,
                        data_type VARCHAR(50),
                        created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                    )
                    """;
            stmt.execute(createTableColumns);
            logger.info("テーブル table_columns を作成しました");
        } catch (Exception e) {
            // 既に存在する場合
        }

        try (var stmt = connection.createStatement()) {
            var createCobolTableAccess = """
                    CREATE TABLE cobol_table_access (
                        access_id VARCHAR(300) PRIMARY KEY,
                        program_id VARCHAR(100) NOT NULL,
                        column_id VARCHAR(200) NOT NULL,
                        access_type VARCHAR(20),
                        sql_location VARCHAR(500),
                        created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                        FOREIGN KEY (program_id) REFERENCES cobol_programs(program_id),
                        FOREIGN KEY (column_id) REFERENCES table_columns(column_id)
                    )
                    """;
            stmt.execute(createCobolTableAccess);
            logger.info("テーブル cobol_table_access を作成しました");
        } catch (Exception e) {
            // 既に存在する場合
        }

        try (var stmt = connection.createStatement()) {
            var createCobolCallDependency = """
                    CREATE TABLE cobol_call_dependency (
                        dep_id VARCHAR(200) PRIMARY KEY,
                        caller_program_id VARCHAR(100) NOT NULL,
                        callee_program_id VARCHAR(100) NOT NULL,
                        call_location VARCHAR(500),
                        created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                        FOREIGN KEY (caller_program_id) REFERENCES cobol_programs(program_id),
                        FOREIGN KEY (callee_program_id) REFERENCES cobol_programs(program_id)
                    )
                    """;

            stmt.execute(createCobolCallDependency);
            logger.info("テーブル cobol_call_dependency を作成しました");
        } catch (Exception e) {
            // 既に存在する場合
        }

        try (var stmt = connection.createStatement()) {
            var createCobolCopyDependency = """
                    CREATE TABLE cobol_copy_dependency (
                        dep_id VARCHAR(200) PRIMARY KEY,
                        program_id VARCHAR(100) NOT NULL,
                        copybook_name VARCHAR(100) NOT NULL,
                        copy_location VARCHAR(500),
                        copy_depth INTEGER DEFAULT 1,
                        via_copybook VARCHAR(100),
                        created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                        FOREIGN KEY (program_id) REFERENCES cobol_programs(program_id)
                    )
                    """;

            stmt.execute(createCobolCopyDependency);
            logger.info("テーブル cobol_copy_dependency を作成しました");
        } catch (Exception e) {
            // 既に存在する場合
        }
    }

    /**
     * データベース接続を安全に閉じ、Derby エンジンをシャットダウンします。
     */
    private void shutdownDatabase() {
        try {
            if (connection != null && !connection.isClosed()) {
                connection.close();
            }
        } catch (Exception e) {
            // 無視
        }
    }

    /**
     * 指定されたファイルパスからファイル内容を読み取ります。
     * 
     * @param p 読み取るファイルのパス
     * @return ファイルの内容を文字列として返します
     */
    private static String getFileContent(Path p) {
        // FileReaderTool は文字エンコーディングを自動判定するため利用する
        var fr = new FileReaderTool();
        var raw = fr.readFile(p.toString());

        if (Strings.isNullOrEmpty(raw)) {
            return Strings.isNullOrEmpty(raw) ? "" : raw;
        }

        // 各行先頭6文字を取り除く正規化（行番号付き/なしを透過的に処理）
        var sb = new StringBuilder(raw.length());
        var lines = raw.split("\\r?\\n", -1);
        for (var i = 0; i < lines.length; i++) {
            var line = lines[i];
            if (line.length() > 6) {
                sb.append(line.substring(6));
            } else {
                // 行が6文字以下の場合は空行として扱う
                sb.append("");
            }
            if (i < lines.length - 1) {
                sb.append('\n');
            }
        }

        return sb.toString();
    }

    /**
     * COPY 探索用のキュー要素です。
     *
     * @param copybookName 対象コピーブック名
     * @param depth 元プログラムからの深さ
     */
    private record CopyTraversalNode(String copybookName, int depth) {
    }

    /**
     * COPY 依存関係の登録情報です。
     *
     * @param depth COPY 深さ
     * @param viaCopybook 直前の経由コピーブック名
     * @param location COPY が記述されていたファイルパス
     */
    private record CopyDependencyInfo(int depth, String viaCopybook, String location) {
    }

    /**
     * 依存関係グラフ表示用の1行分データです。
     *
     * @param programName プログラム名
     * @param filePath ファイルパス
     * @param dependencyType 依存タイプ表示文字列
     */
    private record DependencyGraphRow(String programName, String filePath, String dependencyType) {
    }

    /**
     * CALL / COPY 依存関係表示用の1行分データです。
     *
     * @param programName プログラム名
     * @param filePath ファイルパス
     * @param dependencyKind 依存種別
     * @param dependencyTarget 参照先
     * @param dependencyType 依存タイプ表示文字列
     */
    private record ProgramDependencyRow(String programName, String filePath, String dependencyKind,
            String dependencyTarget, String dependencyType) {
    }
}
