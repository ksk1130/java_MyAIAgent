package org.example.cobol;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import org.example.tools.FileReaderTool;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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

    private String cobolDir;
    private Connection connection;
    private Map<String, String> programIdMap;
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
            logger.info("検出ファイル数: {}", cobolFiles.size());

            // フェーズ 1: すべてのプログラムを登録
            for (var cobolFile : cobolFiles) {
                registerProgramOnly(cobolFile);
            }

            // フェーズ 2: 依存関係を解析して登録
            for (var cobolFile : cobolFiles) {
                analyzeDependencies(cobolFile);
            }

            displayDependencyGraph();

            System.out.println("===== 処理完了 =====");
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
    }

    /**
     * COBOL プログラムを解析（登録・テーブルアクセス・依存関係すべて）します。
     * 
     * @param cobolFile 解析対象の COBOL ファイル
     * @throws IOException ファイル読み込み中にエラーが発生した場合
     */
    private void analyzeCobolFile(Path cobolFile) throws IOException {
        logger.info("[解析] {}", cobolFile.getFileName());

        var content = getFileContent(cobolFile);

        var programId = extractProgramId(content);
        if (programId == null) {
            System.out.println("  警告: プログラムID が見つかりません");
            return;
        }

        programIdMap.put(cobolFile.getFileName().toString(), programId);
        registerProgram(programId, cobolFile.toString());
        detectTableAccess(programId, content, cobolFile);
        detectCallDependencies(programId, content, cobolFile);
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
     * データベースに登録された情報を元に依存関係グラフを表示します。
     * ZIPCODE カラムへの依存を例に、直接的および間接的な依存関係を出力します。
     */
    private void displayDependencyGraph() {
        System.out.println("\n===== 依存関係グラフ (カラム: " + targetColumn + ") =====\n");

        // デバッグ: 登録されているデータを確認
        debugPrintTableContents();

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
                logger.info("プログラム名\t\t\tファイルパス\t\t\t\t\t\t依存タイプ");
                logger.info("{}", "─".repeat(100));

                var hasResults = false;
                while (rs.next()) {
                    hasResults = true;
                    var progName = rs.getString("program_name");
                    var filePath = rs.getString("file_path");
                    var depType = rs.getString("dependency_type");

                    logger.info(String.format("%-20s\t%-40s\t%s", progName, filePath, depType));
                }

                if (!hasResults) {
                    logger.info("（{} に依存するプログラムが見つかりません）", targetColumn);
                }
            }
        } catch (Exception e) {
            logger.error("グラフ表示エラー: {}", e.getMessage());
        }
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
}
