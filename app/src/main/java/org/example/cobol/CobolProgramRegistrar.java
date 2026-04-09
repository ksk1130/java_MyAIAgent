package org.example.cobol;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;
import org.example.tools.FileReaderTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.google.common.base.Strings;

/**
 * プログラム登録・管理を担当するクラス。
 */
public class CobolProgramRegistrar {
    private final Connection connection;
    private final Map<String, String> programIdMap;
    private static final Logger logger = LoggerFactory.getLogger(CobolProgramRegistrar.class);

    public CobolProgramRegistrar(Connection connection) {
        this.connection = connection;
        this.programIdMap = new HashMap<>();
    }

    /**
     * プログラム自体の存在をデータベースに登録します（フェーズ1）。
     */
    public void registerProgramOnly(Path cobolFile) throws IOException {
        var content = getFileContent(cobolFile);
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

    /**
     * 指定されたファイルパスからファイル内容を読み取ります。
     */
    private static String getFileContent(Path p) {
        var fr = new FileReaderTool();
        var raw = fr.readFile(p.toString());
        if (Strings.isNullOrEmpty(raw)) {
            return "";
        }
        // 各行先頭6文字を取り除く正規化
        var sb = new StringBuilder(raw.length());
        var lines = raw.split("\\r?\\n", -1);
        for (var i = 0; i < lines.length; i++) {
            var line = lines[i];
            if (line.length() > 6) {
                sb.append(line.substring(6));
            } else {
                sb.append("");
            }
            if (i < lines.length - 1) {
                sb.append('\n');
            }
        }
        return sb.toString();
    }

    public Map<String, String> getProgramIdMap() {
        return programIdMap;
    }
}
