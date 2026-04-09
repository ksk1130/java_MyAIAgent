package org.example.cobol;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

/**
 * Derby DBの接続・テーブル作成・リセットを担当するクラス。
 */
public class CobolDatabaseManager {
    private final String dbUrl;
    private Connection connection;

    public CobolDatabaseManager(String dbUrl) {
        this.dbUrl = dbUrl;
    }

    /**
     * DB接続を確立
     */
    public void connect() throws SQLException {
        this.connection = DriverManager.getConnection(dbUrl);
    }

    /**
     * テーブル作成（存在しなければ）
     */
    public void createTablesIfNotExist() {
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
        } catch (Exception e) {
        }
        try (var stmt = connection.createStatement()) {
            var createCobolTableAccess = """
                    CREATE TABLE cobol_table_access (
                        access_id VARCHAR(600) PRIMARY KEY,
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
        } catch (Exception e) {
        }
        try (var stmt = connection.createStatement()) {
            var createCobolCallDependency = """
                    CREATE TABLE cobol_call_dependency (
                        dep_id VARCHAR(400) PRIMARY KEY,
                        caller_program_id VARCHAR(100) NOT NULL,
                        callee_program_id VARCHAR(100) NOT NULL,
                        call_location VARCHAR(500),
                        created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                        FOREIGN KEY (caller_program_id) REFERENCES cobol_programs(program_id),
                        FOREIGN KEY (callee_program_id) REFERENCES cobol_programs(program_id)
                    )
                    """;
            stmt.execute(createCobolCallDependency);
        } catch (Exception e) {
        }
        try (var stmt = connection.createStatement()) {
            var createCobolCopyDependency = """
                    CREATE TABLE cobol_copy_dependency (
                        dep_id VARCHAR(400) PRIMARY KEY,
                        program_id VARCHAR(100) NOT NULL,
                        copybook_name VARCHAR(100) NOT NULL,
                        copy_depth INT,
                        via_copybook VARCHAR(100),
                        location VARCHAR(500),
                        created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                        FOREIGN KEY (program_id) REFERENCES cobol_programs(program_id)
                    )
                    """;
            stmt.execute(createCobolCopyDependency);
        } catch (Exception e) {
        }
    }

    /**
     * DBリセット
     */
    public void resetDatabase() {
        // ディレクトリ削除はAnalyzerのstatic utilを流用するか、ここに移植
        throw new UnsupportedOperationException("Not implemented yet");
    }

    public Connection getConnection() {
        return connection;
    }
}
