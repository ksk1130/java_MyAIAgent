package org.example.tools;

import dev.langchain4j.agent.tool.Tool;

import java.nio.file.Path;
import java.nio.file.Files;
import java.nio.file.FileSystems;
import java.nio.file.PathMatcher;
import java.nio.file.LinkOption;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import java.text.Normalizer;

/**
 * ファイル検索ツール。
 * LLM から呼び出され、指定したディレクトリ配下を glob パターンで検索して一致するファイルの絶対パスを返します。
 *
 * 注意: rootDir に任意のパスを許可します（運用上のリスクを理解してください）。
 */
public class FileSearchTool {

    private static final int MAX_RESULTS = 1000;

    /**
     * JSON 文字列として安全に扱えるように文字列をエスケープします。
     *
     * @param s 変換対象文字列
     * @return エスケープ済み文字列
     */
    private static String escapeJson(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
    }

    /**
     * 指定ディレクトリ配下から glob パターンに一致するファイルを検索します。
     *
     * @param rootDir 検索ルートディレクトリ
     * @param fileNamePattern ファイル名 glob パターン
     * @return JSON 形式の検索結果、またはエラーメッセージ
     */
    @Tool
    public String findFiles(String rootDir, String fileNamePattern) {
        System.out.println("FileSearchツールを実行します");
        System.out.flush();
        try {
            Path root = Path.of(rootDir).toAbsolutePath().normalize();
            if (!Files.exists(root) || !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
                return "ERROR: rootDir does not exist or is not a directory: " + root.toString();
            }

            // normalize fileNamePattern to NFD/NFC for safe matching
            String normPattern = Normalizer.normalize(fileNamePattern, Normalizer.Form.NFC);
            PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + normPattern);

            List<Path> matches = new ArrayList<>();
            try (Stream<Path> stream = Files.walk(root)) {
                stream.filter(p -> Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS))
                        .forEach(p -> {
                            Path name = p.getFileName();
                            if (name != null && matcher.matches(name)) {
                                if (matches.size() < MAX_RESULTS) {
                                    matches.add(p);
                                }
                            }
                        });
            }

            if (matches.isEmpty()) {
                return "ERROR: No files found for pattern '" + fileNamePattern + "' under '" + root.toString() + "'";
            }

            // Build JSON array with machine-safe URI and human-readable path
            StringBuilder sb = new StringBuilder();
            sb.append('[');
            boolean first = true;
            for (Path p : matches) {
                if (!first) sb.append(',');
                first = false;
                String uri = p.toUri().toASCIIString();
                String pathStr = p.toAbsolutePath().toString();
                sb.append('{')
                        .append("\"uri\":\"")
                        .append(escapeJson(uri))
                        .append("\",\"path\":\"")
                        .append(escapeJson(pathStr))
                        .append("\"}");
            }
            sb.append(']');

            return sb.toString();
        } catch (Exception e) {
            return "ERROR: Failed to search files: " + e.getMessage();
        }
    }
}
