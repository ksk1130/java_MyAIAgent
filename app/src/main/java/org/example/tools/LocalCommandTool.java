package org.example.tools;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * LLM が生成した読み取り系コマンドをローカルで実行するツール。
 * 安全性のため、変更系操作は許可しません。
 */
public class LocalCommandTool {

    private static final int MAX_COMMAND_LENGTH = 500;
    private static final int MAX_OUTPUT_CHARS = 12000;
    private static final long TIMEOUT_SECONDS = 20;
    private static final ObjectMapper mapper = new ObjectMapper();

    private static final Pattern FORBIDDEN_PATTERN = Pattern.compile(
            "(?i)(\\brm\\b|\\bdel\\b|remove-item|format-|shutdown|restart|stop-process|kill|taskkill|" +
                    "git\\s+reset|git\\s+checkout\\s+--|out-file|set-content|add-content|>\\s*\\S|>>\\s*\\S)");

    private String pendingCommand;

    /**
     * 検索目的のローカルコマンドを実行します。
     * 例: rg -n --glob "*.java" "SELECT .* FROM" C:\\work
     *
     * @param command 実行する検索コマンド
     * @return 実行結果（標準出力・標準エラーを統合）
     */
    @Tool("読み取り系のローカルコマンドを実行します（変更系は禁止）")
    public String runSearchCommand(@P("実行する検索コマンド（読み取り系）") String command) {
        if (command == null || command.isBlank()) {
            return "ERROR: command is required";
        }

        String trimmed = command.trim();
        String singleLineCommand = trimmed.replace("\r", " ").replace("\n", " ");
        System.out.println("LocalCommandツールを実行します: runSearchCommand(command=" + singleLineCommand + ")");
        System.out.flush();

        if (trimmed.length() > MAX_COMMAND_LENGTH) {
            return "ERROR: command is too long";
        }

        if (FORBIDDEN_PATTERN.matcher(trimmed).find()) {
            return "ERROR: command contains forbidden operation";
        }

        if (!looksLikeAllowedReadOnlyCommand(trimmed)) {
            return "ERROR: only read-only commands are allowed (rg/grep/findstr/Select-String/Get-ChildItem/dir/ls/git-status/log/show/diff/branch)";
        }

        pendingCommand = trimmed;
        return "確認: 「" + singleLineCommand + "」を実行します。よろしいですか？（はい/yes で実行、いいえ/no でキャンセル）";
    }

    /**
     * 承認待ちコマンドがあるかどうかを返します。
     *
     * @return 承認待ちコマンドがある場合 true
     */
    public boolean hasPendingCommand() {
        return pendingCommand != null && !pendingCommand.isBlank();
    }

    /**
     * 承認待ちコマンドの表示用文字列を返します。
     *
     * @return 承認待ちコマンド（1行化）
     */
    public String pendingCommandPreview() {
        if (!hasPendingCommand()) {
            return "";
        }
        return pendingCommand.replace("\r", " ").replace("\n", " ");
    }

    /**
     * 承認待ちコマンドを実行し、結果を返します。
     *
     * @return 実行結果
     */
    public String executePendingCommand() {
        if (!hasPendingCommand()) {
            return "ERROR: no pending command";
        }

        String commandToRun = pendingCommand;
        pendingCommand = null;
        String singleLineCommand = commandToRun.replace("\r", " ").replace("\n", " ");

        System.out.println("LocalCommandツールを実行します: executePendingCommand(command=" + singleLineCommand + ")");
        System.out.flush();

        return executeCommand(commandToRun, singleLineCommand);
    }

    /**
     * 承認待ちコマンドをキャンセルします。
     *
     * @return キャンセル結果
     */
    public String cancelPendingCommand() {
        if (!hasPendingCommand()) {
            return "ERROR: no pending command";
        }
        String canceled = pendingCommandPreview();
        pendingCommand = null;
        return "キャンセルしました: " + canceled;
    }

    /**
     * 実コマンドを実行して結果を返します。
     *
     * @param command           実行コマンド
     * @param singleLineCommand 表示用1行コマンド
     * @return 実行結果
     */
    private String executeCommand(String command, String singleLineCommand) {
        try {
            ProcessBuilder builder = new ProcessBuilder("pwsh", "-NoProfile", "-Command", command);
            builder.redirectErrorStream(true);
            Process process = builder.start();

            boolean finished = process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return "ERROR: command timed out after " + TIMEOUT_SECONDS + " seconds";
            }

            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (output.length() >= MAX_OUTPUT_CHARS) {
                        break;
                    }
                    output.append(line).append('\n');
                }
            }

            int exitCode = process.exitValue();
            String body = output.toString();
            if (body.length() > MAX_OUTPUT_CHARS) {
                body = body.substring(0, MAX_OUTPUT_CHARS) + "\n... (truncated)";
            }

            if (body.isBlank()) {
                body = "(no output)";
            }

            ObjectNode result = mapper.createObjectNode();
            result.put("command", singleLineCommand);
            result.put("exitCode", exitCode);
            result.put("status", exitCode == 0 ? "SUCCESS" : "FAILURE");
            result.put("stdout_stderr", body.trim());
            result.put("truncated", output.length() >= MAX_OUTPUT_CHARS);

            return result.toString();
        } catch (Exception e) {
            ObjectNode errorResult = mapper.createObjectNode();
            errorResult.put("command", singleLineCommand);
            errorResult.put("status", "ERROR");
            errorResult.put("message", e.getMessage());
            return errorResult.toString();
        }
    }

    /**
     * コマンドが読み取り専用の許可コマンドに見えるかを判定します。
     *
     * @param command 判定対象コマンド
     * @return 許可候補の読み取り専用コマンドであれば true
     */
    private static boolean looksLikeAllowedReadOnlyCommand(String command) {
        String lower = command.toLowerCase(Locale.ROOT);
        String first = firstCommandToken(lower);
        if ("git".equals(first)) {
            return isAllowedReadOnlyGitCommand(lower);
        }

        // Allow common read-only search/listing commands and also allow JVM invocation
        // (javac/java) which are non-destructive but may execute user code.
        // We accept both plain names and absolute paths like C:\\path\\to\\java.exe
        if ("rg".equals(first)
                || "grep".equals(first)
                || "findstr".equals(first)
                || "select-string".equals(first)
                || "get-childitem".equals(first)
                || "gci".equals(first)
                || "find".equals(first)
                || "dir".equals(first)
                || "ls".equals(first)) {
            return true;
        }

        // Allow javac/java invocations (including full paths ending with java or
        // java.exe/javac)
        if (first.endsWith("java") || first.endsWith("java.exe") || first.endsWith("javac")) {
            return true;
        }

        return false;
    }

    /**
     * git コマンドが読み取り専用サブコマンドかを判定します。
     *
     * @param lowerCommand 小文字化済みコマンド
     * @return 許可された git 読み取り専用コマンドであれば true
     */
    private static boolean isAllowedReadOnlyGitCommand(String lowerCommand) {
        String[] parts = lowerCommand.trim().split("\\s+");
        if (parts.length < 2) {
            return false;
        }

        int index = 1;
        while (index < parts.length && parts[index].startsWith("-")) {
            index++;
        }
        if (index >= parts.length) {
            return false;
        }

        String sub = parts[index];
        return "status".equals(sub)
                || "log".equals(sub)
                || "show".equals(sub)
                || "diff".equals(sub)
                || "branch".equals(sub)
                || "rev-parse".equals(sub)
                || "remote".equals(sub)
                || "tag".equals(sub)
                || "blame".equals(sub)
                || "stash".equals(sub) && (index + 1 < parts.length && "list".equals(parts[index + 1]));
    }

    /**
     * コマンド文字列の先頭トークン（実行コマンド名）を抽出します。
     *
     * @param command コマンド文字列
     * @return 先頭トークン。抽出できない場合は空文字
     */
    private static String firstCommandToken(String command) {
        String trimmed = command == null ? "" : command.trim();
        if (trimmed.isEmpty()) {
            return "";
        }
        String[] parts = trimmed.split("\\s+", 2);
        return parts.length > 0 ? parts[0] : "";
    }
}
