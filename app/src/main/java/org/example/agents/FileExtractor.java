package org.example.agents;

import dev.langchain4j.service.V;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * ファイル検索エージェント（非AI）。
 * 指定されたディレクトリ配下を再帰的に検索し、
 * ファイル名またはファイル本文に指定したキーワードを含むファイルを返します。
 * <p>
 * 実行ポリシー：
 * - まずファイル名を小文字比較でチェックします。
 * - ファイル名に含まれない場合、サイズが10MB以下のファイルについて本文を読み込んで検索します。
 * - 読み込みに失敗したファイルは無視します。
 */
public class FileExtractor {

    /**
     * 指定されたディレクトリからキーワードを含むファイルを検索します。
     *
     * @param directory 検索対象のディレクトリパス（絶対または相対パス）
     * @param keyword   検索キーワード（大文字小文字を区別しません）
     * @return マッチしたファイルパスのリスト。エラー時は空リストを返します。
     */
    public List<Path> search( // 戻り値の型を String から Path に変更
            @V("directory") String directory,
            @V("keyword") String keyword) {

        System.out.println("  [FileExtractor] ディレクトリ: " + directory);
        System.out.println("  [FileExtractor] キーワード: " + keyword);

        try {
            Path startPath = Paths.get(directory);
            if (!Files.exists(startPath)) {
                System.err.println("ディレクトリが見つかりません: " + directory);
                return Collections.emptyList();
            }

            String lowerKeyword = keyword == null ? "" : keyword.toLowerCase();

            List<Path> results = Files.walk(startPath) // String から Path に変更
                    .filter(Files::isRegularFile)
                    .filter(path -> {
                        try {
                            String fileName = path.getFileName().toString();
                            if (fileName.toLowerCase().contains(lowerKeyword)) return true;

                            // Skip very large files to avoid OOM/perf issues
                            long size = Files.size(path);
                            if (size > 10_000_000) return false; // skip >10MB

                            // Try to read file as text and search content
                            String content = Files.readString(path);
                            return content.toLowerCase().contains(lowerKeyword);
                        } catch (IOException e) {
                            // ignore files that cannot be read as text
                            return false;
                        }
                    })
                    .collect(Collectors.toList()); // Path のリストとして収集

            System.out.println("  見つかったファイル数: " + results.size());
            return results;

        } catch (IOException e) {
            System.err.println("ファイル検索中にエラーが発生しました: " + e.getMessage());
            return Collections.emptyList();
        }
    }
}
