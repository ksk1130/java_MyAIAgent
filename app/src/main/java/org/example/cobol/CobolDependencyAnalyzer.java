package org.example.cobol;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

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

    public CobolDependencyAnalyzer(String cobolDir) {
        this.cobolDir = cobolDir != null ? cobolDir : DEFAULT_COBOL_DIR;
        this.programIdMap = new HashMap<>();
    }

    public CobolDependencyAnalyzer() {
        this(DEFAULT_COBOL_DIR);
    }

    public static void main(String[] args) {
        String cobolPath = DEFAULT_COBOL_DIR;
        boolean cleanDb = false;
        
        // args を解析して --clean フラグと COBOL パスを抽出
        List<String> argsList = new ArrayList<>();
        for (String arg : args) {
            if ("--clean".equalsIgnoreCase(arg)) {
                cleanDb = true;
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
        
        CobolDependencyAnalyzer analyzer = new CobolDependencyAnalyzer(cobolPath);
        try {
            analyzer.run();
        } catch (Exception e) {
            System.err.println("エラー: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    /**
     * Derby DB ディレクトリを削除
     */
    private static void cleanupDatabase() {
        Path dbPath = Paths.get(System.getProperty("user.dir"), "cobol_dependencies");
        if (Files.exists(dbPath)) {
            System.out.println("[Cleanup] Removing database: " + dbPath.toAbsolutePath());
            try {
                deleteDirectory(dbPath);
                System.out.println("[Cleanup] Database removed successfully");
            } catch (IOException e) {
                System.err.println("[Cleanup] Warning: Failed to delete database: " + e.getMessage());
            }
        }
    }
    
    /**
     * ディレクトリを再帰的に削除
     */
    private static void deleteDirectory(Path path) throws IOException {
        if (Files.isDirectory(path)) {
            try (Stream<Path> paths = Files.list(path)) {
                paths.forEach(p -> {
                    try {
                        deleteDirectory(p);
                    } catch (IOException e) {
                        System.err.println("[Cleanup] Error deleting " + p + ": " + e.getMessage());
                    }
                });
            }
        }
        Files.delete(path);
    }

    public void run() throws IOException {
        initializeDatabase();
        
        System.out.println("===== COBOL 依存関係検出ツール =====");
        
        // 指定されたパスを使用
        Path cobolDirPath = Paths.get(cobolDir);
        
        // 相対パスの場合、カレントディレクトリから
        if (!cobolDirPath.isAbsolute()) {
            cobolDirPath = Paths.get(System.getProperty("user.dir")).resolve(cobolDir);
        }
        
        System.out.println("COBOL ディレクトリ: " + cobolDirPath.toAbsolutePath());
        
        List<Path> cobolFiles = discoverCobolFiles(cobolDirPath);
        System.out.println("検出ファイル数: " + cobolFiles.size());
        
        // フェーズ 1: すべてのプログラムを登録
        for (Path cobolFile : cobolFiles) {
            registerProgramOnly(cobolFile);
        }
        
        // フェーズ 2: 依存関係を解析して登録
        for (Path cobolFile : cobolFiles) {
            analyzeDependencies(cobolFile);
        }
        
        displayDependencyGraph();
        
        System.out.println("===== 処理完了 =====");
        shutdownDatabase();
    }

    private List<Path> discoverCobolFiles(Path dir) throws IOException {
        List<Path> cobolFiles = new ArrayList<>();
        
        if (!Files.exists(dir)) {
            System.err.println("ディレクトリが見つかりません: " + dir.toAbsolutePath());
            return cobolFiles;
        }
        
        try (Stream<Path> paths = Files.walk(dir)) {
            paths.filter(p -> p.toString().endsWith(".cbl"))
                 .forEach(cobolFiles::add);
        }
        
        return cobolFiles;
    }

    private void registerProgramOnly(Path cobolFile) throws IOException {
        String content = new String(Files.readAllBytes(cobolFile));
        String programId = extractProgramId(content);
        if (programId == null) {
            return;
        }
        programIdMap.put(cobolFile.getFileName().toString(), programId);
        registerProgram(programId, cobolFile.toString());
    }

    private void analyzeDependencies(Path cobolFile) throws IOException {
        System.out.println("\n[解析] " + cobolFile.getFileName());
        
        String content = new String(Files.readAllBytes(cobolFile));
        String programId = extractProgramId(content);
        if (programId == null) {
            System.out.println("  警告: プログラムID が見つかりません");
            return;
        }
        
        detectTableAccess(programId, content, cobolFile);
        detectCallDependencies(programId, content, cobolFile);
    }

    private void analyzeCobolFile(Path cobolFile) throws IOException {
        System.out.println("\n[解析] " + cobolFile.getFileName());
        
        String content = new String(Files.readAllBytes(cobolFile));
        
        String programId = extractProgramId(content);
        if (programId == null) {
            System.out.println("  警告: プログラムID が見つかりません");
            return;
        }
        
        programIdMap.put(cobolFile.getFileName().toString(), programId);
        registerProgram(programId, cobolFile.toString());
        detectTableAccess(programId, content, cobolFile);
        detectCallDependencies(programId, content, cobolFile);
    }

    private String extractProgramId(String content) {
        Pattern pattern = Pattern.compile("(?i)PROGRAM-ID\\.\\s+([\\w-]+)");
        Matcher matcher = pattern.matcher(content);
        if (matcher.find()) {
            return matcher.group(1).toLowerCase().replace('-', '_');
        }
        return null;
    }

    private void detectTableAccess(String programId, String content, Path cobolFile) {
        Pattern insertPattern = Pattern.compile("(?i)INSERT\\s+INTO\\s+([\\w_]+)");
        Matcher insertMatcher = insertPattern.matcher(content);
        while (insertMatcher.find()) {
            String tableName = insertMatcher.group(1).toUpperCase();
            System.out.println("  - INSERT: " + tableName);
            detectColumnsInInsert(programId, tableName, content, cobolFile.toString());
        }
    }

    private void detectColumnsInInsert(String programId, String tableName, 
                                      String content, String location) {
        Pattern valuesPattern = Pattern.compile("(?i)VALUES\\s*\\(([^)]+)\\)");
        Matcher valuesMatcher = valuesPattern.matcher(content);
        
        if (valuesMatcher.find()) {
            String values = valuesMatcher.group(1);
            String[] parts = values.split(",");
            
            for (String part : parts) {
                String column = part.trim().replace(":", "").toUpperCase();
                if (!column.isEmpty()) {
                    registerTableAccess(programId, tableName, column, "INSERT", location);
                }
            }
        }
    }

    private void detectCallDependencies(String programId, String content, Path cobolFile) {
        Pattern callPattern = Pattern.compile("(?i)CALL\\s+['\"]?([\\w-]+)['\"]?");
        Matcher callMatcher = callPattern.matcher(content);
        
        while (callMatcher.find()) {
            String calledProgram = callMatcher.group(1).toLowerCase().replace('-', '_');
            System.out.println("  - CALL: " + calledProgram);
            registerCallDependency(programId, calledProgram, cobolFile.toString());
        }
    }

    private void registerProgram(String programId, String filePath) {
        String sql = "INSERT INTO cobol_programs (program_id, program_name, file_path, updated_at) "
                   + "VALUES (?, ?, ?, CURRENT_TIMESTAMP)";
        
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, programId);
            stmt.setString(2, programId.replace('_', '-'));
            stmt.setString(3, filePath);
            stmt.executeUpdate();
        } catch (Exception e) {
            System.err.println("プログラム登録エラー: " + e.getMessage());
        }
    }

    private void registerTableAccess(String programId, String tableName, String columnName, 
                                     String accessType, String location) {
        String columnId = tableName + "_" + columnName.replace('-', '_');
        
        String tableColumnSql = "INSERT INTO table_columns (column_id, table_name, column_name) "
                              + "VALUES (?, ?, ?)";
        try (PreparedStatement stmt = connection.prepareStatement(tableColumnSql)) {
            stmt.setString(1, columnId);
            stmt.setString(2, tableName);
            stmt.setString(3, columnName);
            stmt.executeUpdate();
        } catch (Exception e) {
            // 既存レコードは無視
        }
        
        String accessId = programId + "_" + columnId + "_" + accessType.toLowerCase();
        String accessSql = "INSERT INTO cobol_table_access (access_id, program_id, column_id, access_type, sql_location) "
                         + "VALUES (?, ?, ?, ?, ?)";
        try (PreparedStatement stmt = connection.prepareStatement(accessSql)) {
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

    private void registerCallDependency(String callerProgramId, String calleeProgramId, String location) {
        String sql = "INSERT INTO cobol_call_dependency (dep_id, caller_program_id, callee_program_id, call_location) "
                   + "VALUES (?, ?, ?, ?)";
        
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            String depId = callerProgramId + "_calls_" + calleeProgramId;
            stmt.setString(1, depId);
            stmt.setString(2, callerProgramId);
            stmt.setString(3, calleeProgramId);
            stmt.setString(4, location);
            stmt.executeUpdate();
        } catch (Exception e) {
            System.err.println("CALL依存関係登録エラー (" + callerProgramId + " -> " + calleeProgramId + "): " + e.getMessage());
        }
    }

    private void displayDependencyGraph() {
        System.out.println("\n===== 依存関係グラフ =====\n");
        
        // デバッグ: 登録されているデータを確認
        debugPrintTableContents();
        
        String sql = "SELECT program_name, file_path, dependency_type "
                   + "FROM ( "
                   + "  SELECT DISTINCT p.program_name, p.file_path, 'DIRECT' AS dependency_type "
                   + "  FROM cobol_table_access cta "
                   + "  JOIN cobol_programs p ON cta.program_id = p.program_id "
                   + "  JOIN table_columns tc ON cta.column_id = tc.column_id "
                   + "  WHERE tc.column_name = 'ZIPCODE' "
                   + "  UNION ALL "
                   + "  SELECT DISTINCT p1.program_name, p1.file_path, "
                   + "         'INDIRECT (via ' || p2.program_name || ')' AS dependency_type "
                   + "  FROM cobol_call_dependency ccd "
                   + "  JOIN cobol_programs p1 ON ccd.caller_program_id = p1.program_id "
                   + "  JOIN cobol_programs p2 ON ccd.callee_program_id = p2.program_id "
                   + "  JOIN cobol_table_access cta ON p2.program_id = cta.program_id "
                   + "  JOIN table_columns tc ON cta.column_id = tc.column_id "
                   + "  WHERE tc.column_name = 'ZIPCODE' "
                   + ") AS results "
                   + "ORDER BY dependency_type DESC, program_name";
        
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            
            System.out.println("プログラム名\t\t\tファイルパス\t\t\t\t\t\t依存タイプ");
            System.out.println("─".repeat(100));
            
            boolean hasResults = false;
            while (rs.next()) {
                hasResults = true;
                String progName = rs.getString("program_name");
                String filePath = rs.getString("file_path");
                String depType = rs.getString("dependency_type");
                
                System.out.printf("%-20s\t%-40s\t%s\n", progName, filePath, depType);
            }
            
            if (!hasResults) {
                System.out.println("（ZIPCODE に依存するプログラムが見つかりません）");
            }
        } catch (Exception e) {
            System.err.println("グラフ表示エラー: " + e.getMessage());
        }
    }
    
    private void debugPrintTableContents() {
        System.out.println("[デバッグ] テーブル内容:");
        
        // cobol_programs
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT * FROM cobol_programs")) {
            System.out.println("  cobol_programs:");
            while (rs.next()) {
                System.out.println("    - " + rs.getString("program_id") + ": " + rs.getString("program_name"));
            }
        } catch (Exception e) {
            System.err.println("  エラー: " + e.getMessage());
        }
        
        // cobol_call_dependency
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT * FROM cobol_call_dependency")) {
            System.out.println("  cobol_call_dependency:");
            while (rs.next()) {
                System.out.println("    - " + rs.getString("caller_program_id") + " -> " + rs.getString("callee_program_id"));
            }
        } catch (Exception e) {
            System.err.println("  エラー: " + e.getMessage());
        }
        
        // cobol_table_access
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT * FROM cobol_table_access")) {
            System.out.println("  cobol_table_access:");
            while (rs.next()) {
                System.out.println("    - " + rs.getString("program_id") + " -> " + rs.getString("column_id"));
            }
        } catch (Exception e) {
            System.err.println("  エラー: " + e.getMessage());
        }
        
        System.out.println();
    }

    private void initializeDatabase() {
        try {
            connection = DriverManager.getConnection(DB_URL);
            System.out.println("Derby データベース接続: OK");
            createTablesIfNotExist();
        } catch (Exception e) {
            System.err.println("データベース接続エラー: " + e.getMessage());
        }
    }

    private void createTablesIfNotExist() {
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("CREATE TABLE cobol_programs ("
                    + "program_id VARCHAR(100) PRIMARY KEY, "
                    + "program_name VARCHAR(100) NOT NULL, "
                    + "file_path VARCHAR(500) NOT NULL, "
                    + "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, "
                    + "updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP"
                    + ")");
            System.out.println("テーブル cobol_programs を作成しました");
        } catch (Exception e) {
            // 既に存在する場合
        }
        
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("CREATE TABLE table_columns ("
                    + "column_id VARCHAR(200) PRIMARY KEY, "
                    + "table_name VARCHAR(100) NOT NULL, "
                    + "column_name VARCHAR(100) NOT NULL, "
                    + "data_type VARCHAR(50), "
                    + "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP"
                    + ")");
            System.out.println("テーブル table_columns を作成しました");
        } catch (Exception e) {
            // 既に存在する場合
        }
        
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("CREATE TABLE cobol_table_access ("
                    + "access_id VARCHAR(300) PRIMARY KEY, "
                    + "program_id VARCHAR(100) NOT NULL, "
                    + "column_id VARCHAR(200) NOT NULL, "
                    + "access_type VARCHAR(20), "
                    + "sql_location VARCHAR(500), "
                    + "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, "
                    + "FOREIGN KEY (program_id) REFERENCES cobol_programs(program_id), "
                    + "FOREIGN KEY (column_id) REFERENCES table_columns(column_id)"
                    + ")");
            System.out.println("テーブル cobol_table_access を作成しました");
        } catch (Exception e) {
            // 既に存在する場合
        }
        
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("CREATE TABLE cobol_call_dependency ("
                    + "dep_id VARCHAR(200) PRIMARY KEY, "
                    + "caller_program_id VARCHAR(100) NOT NULL, "
                    + "callee_program_id VARCHAR(100) NOT NULL, "
                    + "call_location VARCHAR(500), "
                    + "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, "
                    + "FOREIGN KEY (caller_program_id) REFERENCES cobol_programs(program_id), "
                    + "FOREIGN KEY (callee_program_id) REFERENCES cobol_programs(program_id)"
                    + ")");
            System.out.println("テーブル cobol_call_dependency を作成しました");
        } catch (Exception e) {
            // 既に存在する場合
        }
    }

    private void shutdownDatabase() {
        try {
            if (connection != null && !connection.isClosed()) {
                connection.close();
            }
        } catch (Exception e) {
            // 無視
        }
    }
}
