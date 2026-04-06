package org.example.agents;

import dev.langchain4j.agentic.Agent;

import java.sql.*;
import java.util.*;

/**
 * Derby DB をクエリして、テーブル・カラムアクセスと推移的依存関係を検索するエージェント
 *
 * CobolDependencyAnalyzer で生成された Derby DB から、指定されたテーブル・カラムに関する
 * 直接・間接のプログラムアクセス依存関係を検索します。
 */
public class DatabaseQueryAgent {
    
    private static final String DB_URL = "jdbc:derby:cobol_dependencies";
    private Connection connection;
    
    public DatabaseQueryAgent() {
        initializeDatabase();
    }
    
    /**
     * Derby DB に接続
     */
    private void initializeDatabase() {
        try {
            connection = DriverManager.getConnection(DB_URL);
            System.out.println("[DatabaseQueryAgent] Derby DB に接続しました");
        } catch (SQLException e) {
            System.err.println("[DatabaseQueryAgent] DB 接続エラー: " + e.getMessage());
        }
    }
    
    /**
     * 指定されたテーブル・カラムにアクセスするプログラムと、その推移的依存関係を検索
     *
     * @param tableName テーブル名（例：POST_CD）
     * @param columnName カラム名（例：ZIPCODE）
     * @return 依存関係情報を含む構造化データ
     */
    @Agent(
        name = "DatabaseQuery",
        description = "Query Derby DB to find column access and transitive dependencies",
        outputKey = "dependencyInfo"
    )
    public Map<String, Object> queryDependencies(
        String tableName,
        String columnName
    ) {
        Map<String, Object> result = new HashMap<>();
        
        if (connection == null || tableName == null || columnName == null) {
            result.put("error", "Invalid parameters or DB connection");
            return result;
        }
        
        try {
            // 1. 直接アクセス：該当カラムにアクセスするプログラムを検索
            List<Map<String, String>> directPrograms = findDirectAccess(tableName, columnName);
            result.put("directPrograms", directPrograms);
            
            // 2. 推移的依存関係：CALL を通じて間接的にアクセスするプログラムを検索
            List<Map<String, String>> indirectPrograms = findIndirectAccess(tableName, columnName);
            result.put("indirectPrograms", indirectPrograms);
            
            // 3. CALL 依存関係グラフ
            Map<String, List<String>> callGraph = buildCallGraph();
            result.put("callGraph", callGraph);
            
            result.put("success", true);
        } catch (SQLException e) {
            result.put("error", "Database query failed: " + e.getMessage());
            result.put("success", false);
        }
        
        return result;
    }
    
    /**
     * 直接アクセス：tableName.columnName にアクセスするプログラムを検索
     *
     * @return プログラムリスト（program_id, file_path を含む）
     */
    private List<Map<String, String>> findDirectAccess(String tableName, String columnName) throws SQLException {
        List<Map<String, String>> programs = new ArrayList<>();
        
        String sql = "SELECT DISTINCT cp.program_id, cp.file_path " +
                     "FROM cobol_table_access cta " +
                     "JOIN cobol_programs cp ON cta.program_id = cp.program_id " +
                     "JOIN table_columns tc ON cta.column_id = tc.column_id " +
                     "WHERE UPPER(tc.table_name) = ? " +
                     "AND UPPER(tc.column_name) = ?";
        
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, tableName.toUpperCase());
            stmt.setString(2, columnName.toUpperCase());
            
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    Map<String, String> prog = new LinkedHashMap<>();
                    prog.put("programId", rs.getString("program_id"));
                    prog.put("filePath", rs.getString("file_path"));
                    prog.put("accessType", "DIRECT");
                    programs.add(prog);
                }
            }
        }
        
        return programs;
    }
    
    /**
     * 推移的依存関係：CALL を通じて間接的にアクセスするプログラムを検索
     *
     * 計算ロジック：
     * - 直接アクセスするプログラム A を特定
     * - A を CALL するプログラム B を特定 → B は推移的に A を通じてアクセス
     * - B を CALL するプログラム C を特定 → C も推移的にアクセス
     * （段数制限あり：3段階まで）
     *
     * @return プログラムリスト（program_id, file_path, accessPath を含む）
     */
    private List<Map<String, String>> findIndirectAccess(String tableName, String columnName) throws SQLException {
        List<Map<String, String>> indirectPrograms = new ArrayList<>();
        
        // Step 1: 直接アクセスするプログラムを取得
        List<String> directProgramIds = new ArrayList<>();
        String directSql = "SELECT DISTINCT cta.program_id " +
                          "FROM cobol_table_access cta " +
                          "JOIN table_columns tc ON cta.column_id = tc.column_id " +
                          "WHERE UPPER(tc.table_name) = ? " +
                          "AND UPPER(tc.column_name) = ?";
        
        try (PreparedStatement stmt = connection.prepareStatement(directSql)) {
            stmt.setString(1, tableName.toUpperCase());
            stmt.setString(2, columnName.toUpperCase());
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    directProgramIds.add(rs.getString("program_id"));
                }
            }
        }
        
        if (directProgramIds.isEmpty()) {
            return indirectPrograms;
        }
        
        // Step 2: 直接アクセスプログラムを CALL するプログラムを検索（Level 1）
        Set<String> processedPrograms = new HashSet<>(directProgramIds);
        List<String> currentLevel = new ArrayList<>(directProgramIds);
        
        for (int level = 1; level <= 3 && !currentLevel.isEmpty(); level++) {
            List<String> nextLevel = new ArrayList<>();
            
            for (String directProg : currentLevel) {
                String indirectSql = "SELECT DISTINCT ccd.caller_program_id, cp.file_path " +
                                   "FROM cobol_call_dependency ccd " +
                                   "JOIN cobol_programs cp ON ccd.caller_program_id = cp.program_id " +
                                   "WHERE ccd.callee_program_id = ?";
                
                try (PreparedStatement stmt = connection.prepareStatement(indirectSql)) {
                    stmt.setString(1, directProg);
                    try (ResultSet rs = stmt.executeQuery()) {
                        while (rs.next()) {
                            String callerId = rs.getString("caller_program_id");
                            if (!processedPrograms.contains(callerId)) {
                                Map<String, String> prog = new LinkedHashMap<>();
                                prog.put("programId", callerId);
                                prog.put("filePath", rs.getString("file_path"));
                                prog.put("accessType", "INDIRECT");
                                prog.put("accessPath", "via " + directProg);
                                prog.put("level", String.valueOf(level));
                                indirectPrograms.add(prog);
                                
                                processedPrograms.add(callerId);
                                nextLevel.add(callerId);
                            }
                        }
                    }
                }
            }
            
            currentLevel = nextLevel;
        }
        
        return indirectPrograms;
    }
    
    /**
     * CALL 依存関係グラフを構築
     * 形式：{ "programA": ["programB", "programC"], ... }
     *
     * @return プログラム → 呼び出し先プログラムのマッピング
     */
    private Map<String, List<String>> buildCallGraph() throws SQLException {
        Map<String, List<String>> callGraph = new HashMap<>();
        
        String sql = "SELECT caller_program_id, callee_program_id FROM cobol_call_dependency";
        
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            
            while (rs.next()) {
                String caller = rs.getString("caller_program_id");
                String callee = rs.getString("callee_program_id");
                callGraph.computeIfAbsent(caller, k -> new ArrayList<>()).add(callee);
            }
        }
        
        return callGraph;
    }
    
    /**
     * DB 接続をクローズ
     */
    public void close() {
        if (connection != null) {
            try {
                connection.close();
            } catch (SQLException e) {
                System.err.println("[DatabaseQueryAgent] DB クローズエラー: " + e.getMessage());
            }
        }
    }
}
