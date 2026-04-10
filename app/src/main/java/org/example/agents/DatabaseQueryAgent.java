package org.example.agents;

import dev.langchain4j.agentic.Agent;
import dev.langchain4j.service.V;

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
        @V("tableName") String tableName,
        @V("columnName") String columnName
    ) {
        Map<String, Object> result = new HashMap<>();

        if (tableName == null || columnName == null) {
            result.put("error", "Invalid parameters");
            result.put("success", false);
            return result;
        }

        ensureConnected();
        if (connection == null) {
            result.put("error", "DB connection is not available");
            result.put("success", false);
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
     * 必要に応じて Derby DB へ再接続します。
     */
    private void ensureConnected() {
        try {
            if (connection == null || connection.isClosed()) {
                initializeDatabase();
            }
        } catch (SQLException e) {
            initializeDatabase();
        }
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
     * 指定したカラムに関連する変数定義をデータベースから取得します。
     *
     * @param columnName カラム名
     * @return 変数定義リスト
     */
    public List<Map<String, String>> queryVariableDefinitions(String columnName) {
        List<Map<String, String>> definitions = new ArrayList<>();
        
        ensureConnected();
        if (connection == null) {
            return definitions;
        }
        
        try {
            String sql = "SELECT file_path, variable_name, level_number, pic_clause, description " +
                         "FROM variable_definitions " +
                         "WHERE UPPER(column_name) = ? " +
                         "ORDER BY file_path, variable_name";
            
            try (PreparedStatement stmt = connection.prepareStatement(sql)) {
                stmt.setString(1, columnName.toUpperCase());
                
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        Map<String, String> def = new LinkedHashMap<>();
                        def.put("filePath", rs.getString("file_path"));
                        def.put("variableName", rs.getString("variable_name"));
                        def.put("level", rs.getString("level_number"));
                        def.put("picClause", rs.getString("pic_clause"));
                        def.put("description", rs.getString("description"));
                        definitions.add(def);
                    }
                }
            }
        } catch (SQLException e) {
            System.err.println("[DatabaseQueryAgent] 変数定義クエリエラー: " + e.getMessage());
        }
        
        return definitions;
    }

    /**
     * 指定したカラムに関連する代入文をデータベースから取得します。
     * （注意：現在は column_name に依存しており、関連変数の代入文を取得）
     *
     * @param columnName カラム名
     * @return 代入文リスト
     */
    public List<Map<String, String>> queryVariableAssignments(String columnName) {
        // 指定されたカラムに関連する変数名を取得
        Set<String> relatedVariables = new HashSet<>();
        try {
            String varSql = "SELECT DISTINCT variable_name FROM variable_definitions " +
                           "WHERE UPPER(column_name) = ?";
            try (PreparedStatement stmt = connection.prepareStatement(varSql)) {
                stmt.setString(1, columnName.toUpperCase());
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        relatedVariables.add(rs.getString("variable_name").toUpperCase());
                    }
                }
            }
        } catch (SQLException e) {
            System.err.println("[DatabaseQueryAgent] 関連変数取得エラー: " + e.getMessage());
        }
        
        // すべての代入文を取得してから、関連変数でフィルタリング
        List<Map<String, String>> assignments = new ArrayList<>();
        
        ensureConnected();
        if (connection == null) {
            return assignments;
        }
        
        try {
            // 関連変数がある場合のみ、その変数についての代入文を取得
            if (!relatedVariables.isEmpty()) {
                String sql = "SELECT file_path, variable_name, line_number, statement_type, source_line " +
                             "FROM variable_assignments " +
                             "ORDER BY file_path, line_number";
                
                try (Statement stmt = connection.createStatement();
                     ResultSet rs = stmt.executeQuery(sql)) {
                    while (rs.next()) {
                        String varName = rs.getString("variable_name").toUpperCase();
                        // 関連変数またはカラム名そのものの代入文を取得
                        if (relatedVariables.contains(varName) || varName.equals(columnName.toUpperCase())) {
                            Map<String, String> assign = new LinkedHashMap<>();
                            assign.put("filePath", rs.getString("file_path"));
                            assign.put("variableName", rs.getString("variable_name"));
                            assign.put("lineNumber", String.valueOf(rs.getInt("line_number")));
                            assign.put("statementType", rs.getString("statement_type"));
                            assign.put("sourceLine", rs.getString("source_line"));
                            assignments.add(assign);
                        }
                    }
                }
            } else {
                // 関連変数がない場合は、カラム名そのものの代入文を取得
                String sql = "SELECT file_path, variable_name, line_number, statement_type, source_line " +
                             "FROM variable_assignments " +
                             "WHERE UPPER(variable_name) = ? " +
                             "ORDER BY file_path, line_number";
                
                try (PreparedStatement stmt = connection.prepareStatement(sql)) {
                    stmt.setString(1, columnName.toUpperCase());
                    
                    try (ResultSet rs = stmt.executeQuery()) {
                        while (rs.next()) {
                            Map<String, String> assign = new LinkedHashMap<>();
                            assign.put("filePath", rs.getString("file_path"));
                            assign.put("variableName", rs.getString("variable_name"));
                            assign.put("lineNumber", String.valueOf(rs.getInt("line_number")));
                            assign.put("statementType", rs.getString("statement_type"));
                            assign.put("sourceLine", rs.getString("source_line"));
                            assignments.add(assign);
                        }
                    }
                }
            }
        } catch (SQLException e) {
            System.err.println("[DatabaseQueryAgent] 代入文クエリエラー: " + e.getMessage());
        }
        
        return assignments;
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
