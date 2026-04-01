package org.example.tools;

import dev.langchain4j.agent.tool.Tool;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;

/**
 * ファイル書き込み用のツールクラス。
 * セキュリティ対策として拡張子ホワイトリスト方式でテキスト系ファイルのみへの書き込みを許可します。
 *
 * <ul>
 *   <li>書き込みは UTF-8 エンコーディングで行います。</li>
 *   <li>書き込み対象ディレクトリが存在しない場合は自動的に作成します。</li>
 *   <li>コンテンツのサイズ上限は {@value MAX_CONTENT_CHARS} 文字です。</li>
 *   <li>パストラバーサル攻撃を防ぐため、パスを正規化してから処理します。</li>
 * </ul>
 */
public class FileWriterTool {

    /** 書き込みコンテンツの最大文字数 */
    private static final int MAX_CONTENT_CHARS = 100_000;

    /** 書き込みを許可する拡張子（小文字で管理） */
    private static final Set<String> ALLOWED_EXTS = Set.of(
            "txt", "md", "json", "csv", "java", "py", "xml", "html", "htm",
            "yaml", "yml", "properties", "sql", "sh", "bat", "gradle", "toml"
    );

    /**
     * 指定したパスにテキストファイルを書き込みます。
     * ファイルが既に存在する場合は上書きします。
     * 親ディレクトリが存在しない場合は自動作成します。
     *
     * @param path    書き込み先ファイルパス（絶対パス・相対パスどちらでも可）
     * @param content 書き込む内容（UTF-8）
     * @return 書き込み結果のメッセージ
     */
    @Tool
    public String writeFile(String path, String content) {
        System.out.println("FileWriterツールを実行します");
        System.out.flush();

        if (path == null || path.isBlank()) {
            return "ERROR: path is required";
        }
        if (content == null) {
            content = "";
        }
        if (content.length() > MAX_CONTENT_CHARS) {
            return "ERROR: content too large (max " + MAX_CONTENT_CHARS + " chars)";
        }

        try {
            Path target = Path.of(path).toAbsolutePath().normalize();

            // 拡張子チェック
            String ext = getExtension(target);
            if (ext == null || !ALLOWED_EXTS.contains(ext.toLowerCase(Locale.ROOT))) {
                return "ERROR: extension not allowed: " + (ext == null ? "(none)" : ext);
            }

            // 親ディレクトリが存在しない場合は作成
            Path parent = target.getParent();
            if (parent != null && !Files.exists(parent)) {
                Files.createDirectories(parent);
            }

            Files.writeString(target, content, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);

            return "書き込み完了: " + target + " (" + content.length() + " 文字)";

        } catch (Exception e) {
            return "ERROR: failed to write file: " + e.getMessage();
        }
    }

    /**
     * ファイルパスから拡張子を返します。拡張子がない場合は {@code null} を返します。
     *
     * @param path 対象のファイルパス
     * @return 拡張子文字列（ドットなし）、または {@code null}
     */
    private String getExtension(Path path) {
        String name = path.getFileName() != null ? path.getFileName().toString() : "";
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) {
            return null;
        }
        return name.substring(dot + 1);
    }
}
