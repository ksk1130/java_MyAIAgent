package org.example.tools;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;

/**
 * ファイルの一部テキストを置換するためのツールクラス。
 * 巨大なソースコードファイルなどを丸ごと書き換えるのではなく、
 * 必要な部分のみを安全に（単一一致の場合のみ）書き換えます。
 */
public class FileEditorTool {

    private static final int MAX_FILE_CHARS = 500_000;

    /** 書き換えを許可する拡張子（小文字で管理） */
    private static final Set<String> ALLOWED_EXTS = Set.of(
            "txt", "md", "json", "csv", "java", "py", "xml", "html", "htm",
            "yaml", "yml", "properties", "sql", "sh", "bat", "gradle", "toml"
    );

    @Tool("指定したファイル内の特定のテキスト（TargetContent）を新しいテキスト（ReplacementContent）に置換します。対象文字列が1箇所だけ存在する場合のみ成功します。")
    public String replaceFileContent(
            @P("対象のファイルパス（絶対/相対）") String path,
            @P("置換前のテキスト（ファイル内の文字列と完全に一致する必要があります）") String targetContent,
            @P("置換後のテキスト") String replacementContent
    ) {
        System.out.println("FileEditorToolを実行します: replaceFileContent(path=" + path + ")");
        System.out.flush();

        if (path == null || path.isBlank()) {
            return "ERROR: path is required";
        }
        if (targetContent == null || targetContent.isEmpty()) {
            return "ERROR: targetContent is required and cannot be empty";
        }
        if (replacementContent == null) {
            replacementContent = "";
        }

        try {
            Path target = Path.of(path).toAbsolutePath().normalize();

            // 存在チェック
            if (!Files.exists(target)) {
                return "ERROR: file does not exist: " + target;
            }
            if (Files.isDirectory(target)) {
                return "ERROR: target is a directory, not a file.";
            }

            // 拡張子チェック
            String ext = getExtension(target);
            if (ext == null || !ALLOWED_EXTS.contains(ext.toLowerCase(Locale.ROOT))) {
                return "ERROR: extension not allowed for editing: " + (ext == null ? "(none)" : ext);
            }

            // ファイルサイズ制限の簡易チェック（文字数に近似）
            long fileSize = Files.size(target);
            if (fileSize > MAX_FILE_CHARS * 3L) { // UTF-8を考慮して大まかに制限
                return "ERROR: file is too large to edit.";
            }

            // 内容の読み込み
            String content = Files.readString(target, StandardCharsets.UTF_8);
            if (content.length() > MAX_FILE_CHARS) {
                return "ERROR: file content exceeds maximum length of " + MAX_FILE_CHARS + " chars.";
            }

            // マッチ箇所のカウント
            int index = content.indexOf(targetContent);
            if (index == -1) {
                return "ERROR: targetContent not found in the file. 指定された文字列がファイル内に見つかりません。改行やインデント（スペースとタブ）が完全に一致しているか確認してください。";
            }
            
            int nextIndex = content.indexOf(targetContent, index + targetContent.length());
            if (nextIndex != -1) {
                return "ERROR: targetContent is not unique. 見つかった箇所が複数 (" + countOccurrences(content, targetContent) + "箇所) あります。どの部分を置換するか特定できません。前後の行も含めて、よりユニークな文字列を指定してください。";
            }

            // 置換処理
            String updatedContent = content.replace(targetContent, replacementContent);
            
            // 保存
            Files.writeString(target, updatedContent, StandardCharsets.UTF_8);
            return "置換完了: " + target;

        } catch (Exception e) {
            return "ERROR: failed to edit file: " + e.getMessage();
        }
    }

    private int countOccurrences(String str, String subStr) {
        int count = 0;
        int idx = 0;
        while ((idx = str.indexOf(subStr, idx)) != -1) {
            count++;
            idx += subStr.length();
        }
        return count;
    }

    private String getExtension(Path path) {
        String name = path.getFileName() != null ? path.getFileName().toString() : "";
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) {
            return null;
        }
        return name.substring(dot + 1);
    }
}
