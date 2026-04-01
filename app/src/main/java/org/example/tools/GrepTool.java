package org.example.tools;

import dev.langchain4j.agent.tool.Tool;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.FileSystems;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * ルート配下のテキストファイルをキーワード検索する Grep ツール。
 */
public class GrepTool {

    private static final int MAX_MATCHES = 200;
    private static final int MAX_LINE_LENGTH = 300;
    private static final Charset SHIFT_JIS = Charset.forName("Windows-31J");
    private static final String REGEX_META = "[](){}.*+?$^|\\";

    /**
     * 指定ディレクトリ配下を再帰走査し、キーワードに一致した行を返します。
     *
     * @param rootDir 検索ルートディレクトリ
     * @param keyword 検索キーワード
     * @return "path:line:content" 形式の結果（改行区切り）
     */
    @Tool
    public String grep(String rootDir, String keyword) {
        System.out.println("Grepツールを実行します");
        System.out.flush();

        return grepInternal(rootDir, keyword, null, "Grep");
    }

    /**
     * 指定ディレクトリ配下の Java ファイルのみを対象に、キーワードまたは正規表現に一致した行を返します。
     * 「Java ファイルを検索して」のような依頼ではこのツールを優先してください。
     *
     * @param rootDir 検索ルートディレクトリ
     * @param keyword 検索キーワードまたは正規表現
     * @return "path:line:content" 形式の結果（改行区切り）
     */
    @Tool
    public String grepJavaFiles(String rootDir, String keyword) {
        System.out.println("GrepJavaFilesツールを実行します");
        System.out.flush();

        return grepInternal(rootDir, keyword, "glob:**/*.java", "GrepJavaFiles");
    }

    /**
     * 共通の grep 処理を実行します。
     *
     * @param rootDir 検索ルートディレクトリ
     * @param keyword 検索キーワードまたは正規表現
     * @param fileGlob 対象ファイル絞り込み用の glob。不要な場合は null
     * @param toolName エラーメッセージ用のツール名
     * @return 検索結果、またはエラーメッセージ
     */
    private String grepInternal(String rootDir, String keyword, String fileGlob, String toolName) {
        PathMatcher pathMatcher = null;
        if (fileGlob != null && !fileGlob.isBlank()) {
            pathMatcher = FileSystems.getDefault().getPathMatcher(fileGlob);
        }
        final PathMatcher effectivePathMatcher = pathMatcher;

        if (rootDir == null || rootDir.isBlank()) {
            return "ERROR: rootDir is required";
        }
        if (keyword == null || keyword.isBlank()) {
            return "ERROR: keyword is required";
        }

        final String normalizedKeyword = keyword.trim();
        final Pattern regexPattern;
        try {
            // 正規表現メタ文字を含む場合は regex、含まない場合は文字列そのものを検索
            String expr = containsRegexMeta(normalizedKeyword) ? normalizedKeyword : Pattern.quote(normalizedKeyword);
            regexPattern = Pattern.compile(expr, Pattern.CASE_INSENSITIVE);
        } catch (PatternSyntaxException e) {
            return "ERROR: invalid regex keyword: " + e.getDescription();
        }

        try {
            Path root = Path.of(rootDir).toAbsolutePath().normalize();
            if (!Files.exists(root) || !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
                return "ERROR: rootDir does not exist or is not a directory: " + root;
            }

            List<String> hits = new ArrayList<>();
            try (Stream<Path> stream = Files.walk(root)) {
                stream.filter(p -> Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS))
                        .forEach(path -> collectMatches(path, regexPattern, effectivePathMatcher, hits));
            }

            if (hits.isEmpty()) {
                return "NO_HIT: keyword '" + normalizedKeyword + "' was not found under '" + root + "'";
            }

            StringBuilder sb = new StringBuilder();
            sb.append(hits.size()).append(" 件ヒットしました").append('\n');
            for (String hit : hits) {
                sb.append(hit).append('\n');
            }
            return sb.toString().trim();
        } catch (Exception e) {
            return "ERROR: Failed to " + toolName + ": " + e.getMessage();
        }
    }

    /**
     * 単一ファイルの各行を確認し、パターンに一致した行を結果一覧へ追加します。
     *
     * @param path 対象ファイルパス
     * @param pattern 検索パターン
     * @param pathMatcher 対象ファイルフィルタ
     * @param hits ヒット結果格納先
     */
    private static void collectMatches(Path path, Pattern pattern, PathMatcher pathMatcher, List<String> hits) {
        if (hits.size() >= MAX_MATCHES) {
            return;
        }

        if (pathMatcher != null && !pathMatcher.matches(path)) {
            return;
        }

        List<String> lines = readAllLinesFallback(path);
        if (lines == null) {
            return;
        }

        for (int i = 0; i < lines.size(); i++) {
            if (hits.size() >= MAX_MATCHES) {
                return;
            }
            String line = lines.get(i);
            if (pattern.matcher(line).find()) {
                hits.add(path.toAbsolutePath() + ":" + (i + 1) + ":" + clip(line));
            }
        }
    }

    /**
     * キーワードに正規表現メタ文字が含まれているか判定します。
     *
     * @param keyword 判定対象キーワード
     * @return 正規表現メタ文字を含む場合は true
     */
    private static boolean containsRegexMeta(String keyword) {
        for (int i = 0; i < keyword.length(); i++) {
            if (REGEX_META.indexOf(keyword.charAt(i)) >= 0) {
                return true;
            }
        }
        return false;
    }

    /**
     * UTF-8 または Shift_JIS を使ってファイルを行単位で読み込みます。
     *
     * @param path 読み込むファイルパス
     * @return 行一覧。読み込めない場合は null
     */
    private static List<String> readAllLinesFallback(Path path) {
        try {
            return Files.readAllLines(path, StandardCharsets.UTF_8);
        } catch (IOException utf8Ex) {
            try {
                return Files.readAllLines(path, SHIFT_JIS);
            } catch (IOException sjisEx) {
                return null;
            }
        }
    }

    /**
     * 長い行を表示用に切り詰めます。
     *
     * @param line 対象行
     * @return 必要に応じて短縮した文字列
     */
    private static String clip(String line) {
        if (line == null) {
            return "";
        }
        if (line.length() <= MAX_LINE_LENGTH) {
            return line;
        }
        return line.substring(0, MAX_LINE_LENGTH) + "...";
    }
}
