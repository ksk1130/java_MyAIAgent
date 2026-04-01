package org.example.tools;

import dev.langchain4j.agent.tool.Tool;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * テーブル変更時に影響範囲を推移的に推定するツールです。
 *
 * SQL 文字列中のテーブル参照を起点に、ソース参照を逆方向へたどって
 * 影響候補の Java/COBOL ファイルを抽出します。
 */
public class ImpactAnalysisTool {

    private static final Pattern CLASS_PATTERN = Pattern.compile("\\bclass\\s+([A-Za-z_][A-Za-z0-9_]*)");
    private static final Pattern COBOL_PROGRAM_ID_PATTERN = Pattern.compile(
        "(?im)^\\s*PROGRAM-ID\\.\\s+([A-Za-z0-9_-]+)\\.?\\s*$");
    private static final Set<String> SUPPORTED_EXTENSIONS = Set.of(".java", ".cbl", ".cob", ".cpy");
    private static final Pattern TABLE_REFERENCE_PATTERN_TEMPLATE = Pattern.compile(
        "(?is)\\b(from|join|update|into)\\s+`?%s`?\\b");

    /**
     * 解析対象のソース単位を表すレコードです。
     *
     * @param symbolName シンボル名（Java クラス名 / COBOL プログラム名）
     * @param filePath ファイルパス
     * @param content ファイル内容
     */
    private record SourceUnit(String symbolName, Path filePath, String content) {
    }

    /**
     * テーブル名から影響候補ファイルを推移的に列挙します。
     *
     * @param tableName 変更されたテーブル名（例: dummy_table）
     * @return 影響候補のレポート
     */
    @Tool
    public String analyzeTableImpact(String tableName) {
        return analyzeTableImpactInternal(tableName, null);
    }

    /**
     * テーブル名と探索ルートから影響候補ファイルを推移的に列挙します。
     * rootDir が相対パスの場合は user.dir 基準で解決します。
     *
     * @param tableName 変更されたテーブル名（例: dummy_table）
     * @param rootDir 探索ルートディレクトリ（絶対/相対）
     * @return 影響候補のレポート
     */
    @Tool
    public String analyzeTableImpactInRoot(String tableName, String rootDir) {
        return analyzeTableImpactInternal(tableName, rootDir);
    }

    /**
     * 影響分析の共通本体です。
     *
     * @param tableName 変更されたテーブル名
     * @param rootDir 探索ルートディレクトリ（null/blank で user.dir）
     * @return 影響候補のレポート
     */
    private String analyzeTableImpactInternal(String tableName, String rootDir) {
        System.out.println("ImpactAnalysisツールを実行します");
        System.out.flush();

        if (tableName == null || tableName.isBlank()) {
            return "ERROR: tableName is required";
        }

        String normalizedTable = tableName.strip().toLowerCase(Locale.ROOT);
        Path workspaceRoot = resolveRootDirectory(rootDir);
        if (workspaceRoot == null) {
            return "ERROR: invalid rootDir (directory not found): " + rootDir;
        }

        List<Path> sourceFiles = listSupportedSourceFiles(workspaceRoot);
        if (sourceFiles.isEmpty()) {
            return "ERROR: no supported source files found (*.java/*.cbl/*.cob/*.cpy)";
        }

        Map<String, SourceUnit> unitBySymbol = new LinkedHashMap<>();

        for (Path file : sourceFiles) {
            String content = readText(file);
            if (content == null) {
                continue;
            }
            String symbolName = detectSymbolName(file, content);
            if (symbolName == null || symbolName.isBlank()) {
                continue;
            }
            // 同名シンボルが複数ある場合は後勝ちにし、最後に見つかった定義を採用します。
            unitBySymbol.put(symbolName, new SourceUnit(symbolName, file, content));
        }

        Set<String> directUnits = unitBySymbol.values().stream()
                .filter(unit -> containsTableReference(unit.content(), normalizedTable))
                .map(SourceUnit::symbolName)
                .collect(Collectors.toCollection(HashSet::new));

        if (directUnits.isEmpty()) {
            return "table=" + tableName + "\n"
                    + "directMatches=0\n"
                    + "transitiveMatches=0\n"
                    + "details=No direct SQL reference found.";
        }

        Map<String, Set<String>> callersByCallee = buildCallersByCallee(unitBySymbol);
        Map<String, Integer> distanceBySymbol = bfsReverseDependencies(directUnits, callersByCallee);

        List<Map.Entry<String, Integer>> sorted = new ArrayList<>(distanceBySymbol.entrySet());
        sorted.sort(Comparator.comparingInt(Map.Entry<String, Integer>::getValue)
                .thenComparing(Map.Entry::getKey));

        StringBuilder sb = new StringBuilder();
        sb.append("table=").append(tableName).append('\n');
        sb.append("directMatches=").append(directUnits.size()).append('\n');
        sb.append("transitiveMatches=").append(distanceBySymbol.size()).append('\n');
        sb.append("files:\n");

        for (Map.Entry<String, Integer> entry : sorted) {
            String symbolName = entry.getKey();
            int distance = entry.getValue();
            SourceUnit unit = unitBySymbol.get(symbolName);
            if (unit == null) {
                continue;
            }

            String relative = workspaceRoot.relativize(unit.filePath()).toString().replace('\\', '/');
            sb.append("- distance=").append(distance)
                    .append(" symbol=").append(symbolName)
                    .append(" file=").append(relative)
                    .append('\n');
        }

        return sb.toString().trim();
    }

    /**
     * 探索ルートディレクトリを解決します。
     *
     * @param rootDir 入力ルート
     * @return 解決済みルート。無効な場合は null
     */
    private static Path resolveRootDirectory(String rootDir) {
        Path base = Paths.get(System.getProperty("user.dir"));
        Path candidate;

        if (rootDir == null || rootDir.isBlank()) {
            candidate = base;
        } else {
            Path raw = Paths.get(rootDir.strip());
            candidate = raw.isAbsolute() ? raw : base.resolve(raw);
        }

        Path normalized = candidate.normalize();
        if (!Files.isDirectory(normalized)) {
            return null;
        }
        return normalized;
    }

    /**
     * ワークスペース配下のサポート対象ソースファイルを列挙します。
     *
     * @param workspaceRoot ワークスペースルート
     * @return ソースファイル一覧
     */
    private static List<Path> listSupportedSourceFiles(Path workspaceRoot) {
        try (Stream<Path> stream = Files.walk(workspaceRoot)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(path -> {
                        String lower = path.toString().toLowerCase(Locale.ROOT);
                        return SUPPORTED_EXTENSIONS.stream().anyMatch(lower::endsWith);
                    })
                    .filter(path -> {
                        String normalized = path.toString().replace('\\', '/').toLowerCase(Locale.ROOT);
                        return !normalized.contains("/build/")
                                && !normalized.contains("/.gradle/")
                                && !normalized.contains("/out/");
                    })
                    .collect(Collectors.toList());
        } catch (IOException e) {
            return List.of();
        }
    }

    /**
     * ファイル内容を UTF-8 で読み込みます。
     *
     * @param file 対象ファイル
     * @return 読み込み文字列。失敗時は null
     */
    private static String readText(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * シンボル名（Java クラス名 / COBOL PROGRAM-ID / ファイル名）を推定します。
     *
     * @param file ファイル
     * @param content ファイル内容
     * @return シンボル名
     */
    private static String detectSymbolName(Path file, String content) {
        String lowerFile = file.getFileName().toString().toLowerCase(Locale.ROOT);

        if (lowerFile.endsWith(".java")) {
            Matcher matcher = CLASS_PATTERN.matcher(content);
            if (matcher.find()) {
                return matcher.group(1);
            }
        }

        if (lowerFile.endsWith(".cbl") || lowerFile.endsWith(".cob") || lowerFile.endsWith(".cpy")) {
            Matcher programId = COBOL_PROGRAM_ID_PATTERN.matcher(content);
            if (programId.find()) {
                return programId.group(1).toUpperCase(Locale.ROOT);
            }
        }

        // フォールバックとして拡張子なしファイル名をシンボル名に使います。
        String fileName = file.getFileName().toString();
        int dot = fileName.lastIndexOf('.');
        if (dot > 0) {
            return fileName.substring(0, dot);
        }
        return fileName;
    }

    /**
     * SQL 文字列内で指定テーブルが参照されるかを判定します。
     *
     * @param content ファイル内容
     * @param tableLower 小文字化済みテーブル名
     * @return 参照されていれば true
     */
    private static boolean containsTableReference(String content, String tableLower) {
        String lower = content.toLowerCase(Locale.ROOT);
        String pattern = TABLE_REFERENCE_PATTERN_TEMPLATE.pattern().formatted(Pattern.quote(tableLower));
        return Pattern.compile(pattern).matcher(lower).find();
    }

    /**
     * 参照関係から逆依存マップを構築します。
     *
     * @param unitBySymbol シンボルとソース単位の対応
     * @return 被参照シンボル -> 参照元シンボル集合
     */
    private static Map<String, Set<String>> buildCallersByCallee(Map<String, SourceUnit> unitBySymbol) {
        Map<String, Set<String>> callersByCallee = new LinkedHashMap<>();
        List<String> symbols = new ArrayList<>(unitBySymbol.keySet());

        for (String callee : symbols) {
            callersByCallee.put(callee, new HashSet<>());
        }

        for (Map.Entry<String, SourceUnit> callerEntry : unitBySymbol.entrySet()) {
            String caller = callerEntry.getKey();
            String content = callerEntry.getValue().content();
            String lowerContent = content.toLowerCase(Locale.ROOT);

            for (String callee : symbols) {
                if (caller.equals(callee)) {
                    continue;
                }

                if (containsSymbolReference(lowerContent, callee)) {
                    callersByCallee.computeIfAbsent(callee, key -> new HashSet<>()).add(caller);
                }
            }
        }

        return callersByCallee;
    }

    /**
     * 内容中にシンボル参照が存在するか判定します。
     *
     * @param lowerContent 小文字化済み内容
     * @param callee シンボル名
     * @return 参照があれば true
     */
    private static boolean containsSymbolReference(String lowerContent, String callee) {
        String calleeLower = callee.toLowerCase(Locale.ROOT);

        Pattern javaLikeRef = Pattern.compile("\\b" + Pattern.quote(calleeLower) + "\\b");
        if (javaLikeRef.matcher(lowerContent).find()) {
            return true;
        }

        Pattern cobolCallRef = Pattern.compile("(?im)\\bcall\\s+['\"]?" + Pattern.quote(calleeLower) + "['\"]?");
        if (cobolCallRef.matcher(lowerContent).find()) {
            return true;
        }

        Pattern cobolPerformRef = Pattern.compile("(?im)\\bperform\\s+" + Pattern.quote(calleeLower) + "\\b");
        return cobolPerformRef.matcher(lowerContent).find();
    }

    /**
     * 逆依存を BFS でたどって距離を計算します。
     *
     * @param startClasses 直接影響クラス
     * @param callersByCallee 逆依存マップ
     * @return クラスごとの距離
     */
    private static Map<String, Integer> bfsReverseDependencies(Set<String> startClasses,
                                                               Map<String, Set<String>> callersByCallee) {
        Map<String, Integer> distance = new LinkedHashMap<>();
        ArrayDeque<String> queue = new ArrayDeque<>();

        for (String start : startClasses) {
            distance.put(start, 0);
            queue.add(start);
        }

        while (!queue.isEmpty()) {
            String current = queue.removeFirst();
            int currentDistance = distance.getOrDefault(current, 0);

            for (String caller : callersByCallee.getOrDefault(current, Set.of())) {
                if (!distance.containsKey(caller)) {
                    distance.put(caller, currentDistance + 1);
                    queue.addLast(caller);
                }
            }
        }

        return distance;
    }
}
