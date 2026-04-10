package org.example.cobol;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

/**
 * Derby DB の内容を確認するユーティリティ
 */
public class CheckDatabaseContent {
    
    public static void main(String[] args) {
        String dbUrl = "jdbc:derby:cobol_dependencies";
        
        try (Connection conn = DriverManager.getConnection(dbUrl);
             Statement stmt = conn.createStatement()) {
            
            System.out.println("=== variable_definitions テーブル ===");
            try (ResultSet rs = stmt.executeQuery(
                "SELECT file_path, column_name, variable_name, level_number, pic_clause FROM variable_definitions")) {
                int count = 0;
                while (rs.next()) {
                    count++;
                    System.out.printf("%d. %s | %s | %s | %s | %s%n",
                        count,
                        shortenPath(rs.getString("file_path")),
                        rs.getString("column_name"),
                        rs.getString("variable_name"),
                        rs.getString("level_number"),
                        rs.getString("pic_clause"));
                }
                System.out.println("Total: " + count + " rows\n");
            }
            
            System.out.println("=== variable_assignments テーブル ===");
            try (ResultSet rs = stmt.executeQuery(
                "SELECT file_path, column_name, variable_name, line_number, statement_type, source_line FROM variable_assignments")) {
                int count = 0;
                while (rs.next()) {
                    count++;
                    System.out.printf("%d. %s | %s | %s | Line %d | %s | %s%n",
                        count,
                        shortenPath(rs.getString("file_path")),
                        rs.getString("column_name"),
                        rs.getString("variable_name"),
                        rs.getInt("line_number"),
                        rs.getString("statement_type"),
                        truncate(rs.getString("source_line"), 60));
                }
                System.out.println("Total: " + count + " rows\n");
            }
            
        } catch (Exception e) {
            System.err.println("エラー: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    private static String shortenPath(String path) {
        if (path == null) return "";
        String[] parts = path.replace("\\", "/").split("/");
        if (parts.length >= 2) {
            return parts[parts.length - 2] + "/" + parts[parts.length - 1];
        }
        return path;
    }
    
    private static String truncate(String str, int maxLen) {
        if (str == null) return "";
        if (str.length() <= maxLen) return str;
        return str.substring(0, maxLen) + "...";
    }
}
