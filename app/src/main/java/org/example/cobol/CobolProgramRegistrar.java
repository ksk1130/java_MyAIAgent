package org.example.cobol;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * プログラム登録・管理を担当するクラス。
 */
public class CobolProgramRegistrar {
    private final Connection connection;
    private final Map<String, String> programIdMap;
    private final CobolColumnAnalysisUtil columnAnalysisUtil;
    private static final Logger logger = LoggerFactory.getLogger(CobolProgramRegistrar.class);

    public CobolProgramRegistrar(Connection connection) {
        this.connection = connection;
        this.programIdMap = new HashMap<>();
        this.columnAnalysisUtil = new CobolColumnAnalysisUtil();
    }

    /**
     * プログラム自体の存在をデータベースに登録します（フェーズ1）。
     */
    public void registerProgramOnly(Path cobolFile) throws IOException {
        var content = columnAnalysisUtil.readNormalizedFile(cobolFile);
        var programId = extractProgramId(content);
        if (programId == null) {
            return;
        }
        programIdMap.put(cobolFile.getFileName().toString(), programId);
        registerProgram(programId, cobolFile.toString());
    }

    /**
     * COBOL ソースから PROGRAM-ID を抽出します。
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
     * プログラムをデータベースに登録します。
     */
    private void registerProgram(String programId, String filePath) {
        var sql = "INSERT INTO cobol_programs (program_id, program_name, file_path) VALUES (?, ?, ?)";
        try (var pstmt = connection.prepareStatement(sql)) {
            pstmt.setString(1, programId);
            pstmt.setString(2, programId);
            pstmt.setString(3, filePath);
            pstmt.execute();
        } catch (Exception e) {
            logger.error("プログラム登録エラー: {}", e.getMessage());
        }
    }
    public Map<String, String> getProgramIdMap() {
        return programIdMap;
    }
}
