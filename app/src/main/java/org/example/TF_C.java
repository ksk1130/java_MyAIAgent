package org.example;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * JDBC を使ったダミー問い合わせのサンプルクラスです。
 */
public class TF_C {

    private static final String DUMMY_JDBC_URL = "jdbc:dummy://localhost:5432/sampledb";
    private static final String DUMMY_USER = "dummy_user";
    private static final String DUMMY_PASSWORD = "dummy_password";
    private static final String DUMMY_SQL = "SELECT A, B, C FROM dummy_table WHERE id = 1";

    /**
     * サンプル処理を実行し、ダミー JDBC 問い合わせを試行します。
     */
    public void run() {
        // leaf
        System.out.println("TF_C running");
        executeDummyQuery();
    }

    /**
     * JDBC を使ったダミー SQL 問い合わせを実行します。
     * 実運用の接続先ではないため、接続失敗は想定内としてログ出力します。
     */
    private void executeDummyQuery() {
        try (Connection connection = DriverManager.getConnection(DUMMY_JDBC_URL, DUMMY_USER, DUMMY_PASSWORD);
             PreparedStatement statement = connection.prepareStatement(DUMMY_SQL);
             ResultSet resultSet = statement.executeQuery()) {

            if (resultSet.next()) {
                int value = resultSet.getInt("dummy_value");
                System.out.println("Dummy query result: " + value);
            } else {
                System.out.println("Dummy query executed, but no rows were returned.");
            }
        } catch (SQLException e) {
            System.out.println("Dummy JDBC query failed (expected in sample environment): " + e.getMessage());
        }
    }
}
