package org.example.tools;

import dev.langchain4j.agent.tool.Tool;

import java.nio.file.Path;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.CharacterCodingException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.util.Set;
import java.util.Locale;

/**
 * ファイル読み取り用のツールクラス。
 * セキュリティ対策として拡張子ホワイトリスト方式でテキスト系ファイルのみを許可します。
 *
 * 読み込みはまず UTF-8 を試し、デコードエラーが発生した場合は Shift_JIS (Windows-31J) にフォールバックします。
 */
public class FileReaderTool {

    private static final int MAX_CHARS = 20000;

    // 許可する拡張子（小文字で管理）
    private static final Set<String> ALLOWED_EXTS = Set.of(
            "txt", "md", "json", "csv", "java", "py", "xml", "html", "htm", "yaml", "yml", "properties", "sql", "sh"
    );

    // 代替エンコーディング（Windows 環境の SJIS 互換）
    private static final Charset SHIFT_JIS = Charset.forName("Windows-31J");

    /**
     * テキストファイルを読み込み、内容を返します。
     *
     * @param path 読み込むファイルパス
     * @return ファイル内容、またはエラーメッセージ
     */
    @Tool
    public String readFile(String path) {
        System.out.println("FileReaderツールを実行します: readFile(path=" + path + ")");
        System.out.flush();
        try {
            Path p = Path.of(path).toAbsolutePath().normalize();

            if (!Files.exists(p)) {
                return "ERROR: File not found: " + path;
            }

            String ext = getExtension(p);
            if (ext == null || !ALLOWED_EXTS.contains(ext.toLowerCase(Locale.ROOT))) {
                return "ERROR: extension not allowed: " + (ext == null ? "(none)" : ext);
            }

            byte[] bytes = Files.readAllBytes(p);

            // Try UTF-8 first with strict decoder
            String content;
            try {
                content = decode(bytes, StandardCharsets.UTF_8);
            } catch (CharacterCodingException e) {
                // fallback to Shift_JIS
                try {
                    content = decode(bytes, SHIFT_JIS);
                } catch (CharacterCodingException ex) {
                    return "ERROR: Failed to decode file with UTF-8 and Shift_JIS: " + ex.getMessage();
                }
            }

            if (content.length() > MAX_CHARS) {
                return "WARNING: file truncated to " + MAX_CHARS + " chars.\n" + content.substring(0, MAX_CHARS);
            }
            return content;
        } catch (Exception e) {
            return "ERROR: Failed to read file: " + e.getMessage();
        }
    }

    /**
     * 指定文字コードでバイト列をデコードします。
     *
     * @param bytes デコード対象のバイト列
     * @param charset 使用する文字コード
     * @return デコード結果
     * @throws CharacterCodingException デコードできない場合
     */
    private static String decode(byte[] bytes, Charset charset) throws CharacterCodingException {
        CharsetDecoder decoder = charset.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        ByteBuffer bb = ByteBuffer.wrap(bytes);
        CharBuffer cb = decoder.decode(bb);
        return cb.toString();
    }

    /**
     * ファイルパスから拡張子を取得します。
     *
     * @param p 対象パス
     * @return 拡張子。存在しない場合は null
     */
    private static String getExtension(Path p) {
        String name = p.getFileName().toString();
        int idx = name.lastIndexOf('.');
        if (idx == -1 || idx == name.length() - 1) return null;
        return name.substring(idx + 1);
    }
}
