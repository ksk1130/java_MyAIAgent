package org.example.tools;

import dev.langchain4j.agent.tool.Tool;

import java.nio.file.Path;
import java.nio.file.Files;
import java.nio.file.FileSystems;
import java.nio.file.PathMatcher;
import java.nio.file.LinkOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;
import java.util.stream.Stream;

/**
 * 対話型ファイル検索ツール。
 * LLM が呼び出すと検索結果を表示し、ユーザに読み込み確認を求めて選択されたファイルを読み込んで返します。
 * このツールは対話モード前提で動作します。
 */
public class InteractiveFileSearchTool {

    private static final int MAX_RESULTS = 1000;
    private static final int MAX_READS = 5;

    @Tool
    public String interactiveFindAndRead(String rootDir, String fileNamePattern) {
        try {
            Path root = Path.of(rootDir).toAbsolutePath().normalize();
            if (!Files.exists(root) || !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
                return "ERROR: rootDir does not exist or is not a directory: " + root.toString();
            }

            PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + fileNamePattern);

            List<Path> results = new ArrayList<>();
            try (Stream<Path> stream = Files.walk(root)) {
                stream.filter(p -> Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS))
                        .forEach(p -> {
                            Path name = p.getFileName();
                            if (name != null && matcher.matches(name)) {
                                if (results.size() < MAX_RESULTS) {
                                    results.add(p);
                                }
                            }
                        });
            }

            if (results.isEmpty()) {
                return "ERROR: No files found for pattern '" + fileNamePattern + "' under '" + root.toString() + "'";
            }

            // Print numbered list and ask user which to read
            System.out.println(results.size() + " files found:");
            for (int i = 0; i < results.size(); i++) {
                System.out.println((i + 1) + ") " + results.get(i).toAbsolutePath().toString()); // human-readable display
            }
            System.out.println("Which file do you want to read? (number / 1,3 / all / none):");

            Scanner scanner = new Scanner(System.in);
            String input = scanner.nextLine();
            if (input == null) return "ERROR: no input";
            input = input.trim();
            if (input.equalsIgnoreCase("none") || input.equalsIgnoreCase("cancel")) {
                return "USER_CANCELLED";
            }

            List<Integer> selected = new ArrayList<>();
            if (input.equalsIgnoreCase("all")) {
                for (int i = 1; i <= Math.min(results.size(), MAX_READS); i++) selected.add(i);
            } else {
                String[] parts = input.split("\\s*,\\s*");
                for (String part : parts) {
                    try {
                        int n = Integer.parseInt(part);
                        if (n >= 1 && n <= results.size()) selected.add(n);
                    } catch (NumberFormatException ignored) {
                    }
                }
                if (selected.isEmpty()) {
                    return "ERROR: invalid selection";
                }
            }

            StringBuilder sb = new StringBuilder();
            FileReaderTool reader = new FileReaderTool();
            int count = 0;
            for (int sel : selected) {
                if (count >= MAX_READS) break;
                Path p = results.get(sel - 1);
                String content = reader.readFile(p.toAbsolutePath().toString());
                sb.append("==== FILE ").append(p.toString()).append(" ====\n");
                sb.append(content).append("\n");
                count++;
            }

            return sb.toString();

        } catch (Exception e) {
            return "ERROR: Failed to interactive search: " + e.getMessage();
        }
    }
}
