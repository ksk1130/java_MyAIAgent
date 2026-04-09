package org.example.cobol;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 依存グラフ生成・表示を担当するクラス。
 */
public class CobolDependencyGraph {
    private final Connection connection;
    private final String targetColumn;
    private static final Logger logger = LoggerFactory.getLogger(CobolDependencyGraph.class);
    private static final Path DEFAULT_REPORT_PATH = Paths.get("build", "reports", "cobol-dependency-report.md");

    public CobolDependencyGraph(Connection connection, String targetColumn) {
        this.connection = connection;
        this.targetColumn = targetColumn;
    }

    /**
     * データベースに登録された情報を元に依存関係グラフを表示します。
     */
    public void displayDependencyGraph() {
        var targetDependencyMarkdown = buildTargetDependencyMarkdown();
        logger.info("\n{}", targetDependencyMarkdown);

        var programDependencyMarkdown = buildProgramDependencyMarkdown();
        logger.info("\n{}", programDependencyMarkdown);

        saveMarkdownReport(targetDependencyMarkdown, programDependencyMarkdown);
    }

    /**
     * 対象カラムに対する依存関係の Markdown を生成します。
     */
    private String buildTargetDependencyMarkdown() {
        var sectionTitle = "## 依存関係グラフ (カラム: " + targetColumn + ")";
        var rows = new ArrayList<DependencyGraphRow>();

        var sql = """
            SELECT program_name, file_path, dependency_type, location
            FROM (
            SELECT DISTINCT p.program_name, p.file_path, 'DIRECT' AS dependency_type,
                cta.sql_location AS location
              FROM cobol_table_access cta
              JOIN cobol_programs p ON cta.program_id = p.program_id
              WHERE cta.column_id LIKE ?
              UNION ALL
              SELECT DISTINCT p1.program_name, p1.file_path,
                'INDIRECT (via ' || p2.program_name || ')' AS dependency_type,
                ccd.call_location AS location
              FROM cobol_call_dependency ccd
              JOIN cobol_programs p1 ON ccd.caller_program_id = p1.program_id
              JOIN cobol_programs p2 ON ccd.callee_program_id = p2.program_id
            ) AS results
            ORDER BY dependency_type DESC, program_name
        """;

        try (var pstmt = connection.prepareStatement(sql)) {
            pstmt.setString(1, "%:" + targetColumn);
            try (var rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    rows.add(new DependencyGraphRow(
                            rs.getString("program_name"),
                            rs.getString("file_path"),
                            rs.getString("dependency_type"),
                            rs.getString("location")));
                }
            }
        } catch (Exception e) {
            logger.error("グラフ表示エラー: {}", e.getMessage());
        }

        if (rows.isEmpty()) {
            return sectionTitle + "\n\n" + "（" + targetColumn + " に依存するプログラムが見つかりません）";
        }

        return sectionTitle + "\n\n" + toMarkdownTable(
            List.of("プログラム名", "ファイルパス", "依存タイプ", "出現箇所"),
                rows.stream()
                .map(row -> List.of(row.programName(), row.filePath(), row.dependencyType(), row.location()))
                        .toList());
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
                  ccd.call_location AS dependency_location
              FROM cobol_call_dependency ccd
              JOIN cobol_programs p ON ccd.caller_program_id = p.program_id
            UNION ALL
            SELECT p.program_name,
                   p.file_path,
                   'COPY' AS dependency_kind,
                   ccd.copybook_name AS dependency_target,
                  ccd.copy_location AS dependency_location
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
                rows.add(new ProgramDependencyRow(
                        rs.getString("program_name"),
                        rs.getString("file_path"),
                        dependencyKind,
                        dependencyTarget,
                        "DIRECT",
                        rs.getString("dependency_location")));
            }
        } catch (Exception e) {
            logger.error("CALL / COPY 依存関係表示エラー: {}", e.getMessage());
            return "## CALL / COPY 依存関係\n\nCALL / COPY 依存関係の生成中にエラーが発生しました。";
        }

        if (rows.isEmpty()) {
            return "## CALL / COPY 依存関係\n\n（CALL / COPY 依存関係は見つかりませんでした）";
        }

        return "## CALL / COPY 依存関係\n\n" + toMarkdownTable(
            List.of("プログラム名", "ファイルパス", "依存種別", "参照先", "出現箇所"),
                rows.stream()
                        .map(row -> List.of(
                                row.programName(),
                                row.filePath(),
                                row.dependencyKind(),
                                row.dependencyTarget(),
                                row.location()))
                        .toList());
    }

    /**
     * Markdown レポートをファイルへ保存します。
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
     */
    private String escapeMarkdownCell(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("|", "\\|").replace("\n", "<br>");
    }
}
