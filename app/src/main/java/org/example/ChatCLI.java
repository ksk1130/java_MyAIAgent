package org.example;

import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.service.AiServices;
import org.example.tools.Calculator;

import org.example.tools.FileReaderTool;

import org.example.tools.FileSearchTool;

import org.example.tools.InteractiveFileSearchTool;

import java.util.Scanner;
import java.io.PrintStream;
import java.io.BufferedOutputStream;
import java.io.FileOutputStream;
import java.io.FileDescriptor;
import java.io.UnsupportedEncodingException;
import java.util.regex.Pattern;
import java.util.regex.Matcher;
import java.net.URI;
import java.nio.file.Paths;
import java.util.List;
import java.util.ArrayList;

/**
 * CLI chat application using langchain4j MessageWindowChatMemory and
 * AiServices.
 */
/**
 * CLI チャットアプリケーションのエントリポイント。
 *
 * LangChain4j の OpenAiChatModel と MessageWindowChatMemory を使い、
 * AiServices を通して Assistant インタフェースの proxy を生成します。
 * 対話モード（runChat）と単発メッセージ送信（runChatOnce）の両方を提供します。
 */
public class ChatCLI {

    public static void setLastSearchResults(List<java.nio.file.Path> results) {
        lastSearchResults = results;
    }

    private static boolean isSelectionInput(String input) {
        if (input == null) return false;
        String t = input.trim();
        if (t.equalsIgnoreCase("all") || t.equalsIgnoreCase("none")) return true;
        if (t.matches("^\\d+(?:\\s*,\\s*\\d+)*$")) return true;
        if (t.matches("(?i)^(read|open|show|select)\\s+\\d+(?:\\s*,\\s*\\d+)*$")) return true;
        return false;
    }

    private static String handleSelectionInput(String input) {
        // helper remains the same
        if (lastSearchResults == null || lastSearchResults.isEmpty()) {
            System.err.println("No recent search results available. Please perform a search first.");
            return null;
        }

        String t = input.trim();
        List<Integer> selected = new ArrayList<>();
        if (t.equalsIgnoreCase("none") || t.equalsIgnoreCase("cancel")) {
            return null;
        } else if (t.equalsIgnoreCase("all")) {
            for (int i = 1; i <= Math.min(lastSearchResults.size(), 5); i++) selected.add(i);
        } else if (t.matches("(?i)^(read|open|show|select)\\s+.*$")) {
            // extract numbers after the command
            Matcher m = Pattern.compile("(\\d+)(?:\\s*,\\s*(\\d+))*").matcher(t);
            while (m.find()) {
                try {
                    selected.add(Integer.parseInt(m.group(1)));
                } catch (Exception ignored) {}
            }
            if (selected.isEmpty()) return null;
        } else {
            // comma separated numbers
            String[] parts = t.split("\\s*,\\s*");
            for (String part : parts) {
                try { selected.add(Integer.parseInt(part)); } catch (NumberFormatException ignored) {}
            }
            if (selected.isEmpty()) return null;
        }

        // normalize and validate
        List<java.nio.file.Path> toRead = new ArrayList<>();
        for (int idx : selected) {
            if (idx >= 1 && idx <= lastSearchResults.size()) {
                toRead.add(lastSearchResults.get(idx - 1));
            }
        }
        if (toRead.isEmpty()) {
            System.err.println("No valid selections found in the recent search results.");
            return null;
        }

        // read files using FileReaderTool
        FileReaderTool reader = new FileReaderTool();
        StringBuilder sb = new StringBuilder();
        int count = 0;
        for (java.nio.file.Path p : toRead) {
            if (count >= 5) break;
            String content = reader.readFile(p.toAbsolutePath().toString());
            sb.append("==== FILE ").append(p.toAbsolutePath().toString()).append(" ====\n");
            sb.append(content).append("\n");
            count++;
        }

        // clear lastSearchResults after use to avoid accidental reuse
        lastSearchResults = null;
        return sb.toString();
    }

    private static boolean isSearchCommand(String text) {
        if (text == null) return false;
        String t = text.trim().toLowerCase();
        // crude check: contains keywords like "search", "探" or pattern like "から*.shを探して"
        if (t.startsWith("search ") || t.startsWith("find ")) return true;
        if (t.contains("探して") || t.contains("探す") || t.contains("検索")) return true;
        // pattern: "...から*.shを探して"
        if (t.matches(".*から.*\\*.*\\.\\w+.*")) return true;
        return false;
    }

    private static boolean handleSearchCommand(String text) {
        try {
            // Attempt to extract a rootDir and a simple glob pattern using naive parsing
            Pattern p = Pattern.compile("(.*?)から\\s*([^\\s]+)", Pattern.CASE_INSENSITIVE);
            Matcher m = p.matcher(text);
            String root = null;
            String pattern = "*";
            if (m.find()) {
                root = m.group(1).trim();
                pattern = m.group(2).trim();
            } else {
                // fallback: look for first path-like token
                String[] parts = text.split("\\s+");
                for (String part : parts) {
                    if (part.contains(":\\") || part.startsWith("/")) { root = part; break; }
                }
                // find pattern token like *.sh
                for (String part : parts) { if (part.contains("*")) { pattern = part; break; } }
            }

            if (root == null) {
                System.err.println("Could not determine root directory for search.");
                return false;
            }

            // call FileSearchTool directly
            FileSearchTool fst = new FileSearchTool();
            String result = fst.findFiles(root, pattern);
            if (result == null || result.startsWith("ERROR:")) {
                System.out.println(result);
                return true;
            }

            // parse URIs returned (one per line) and populate lastSearchResults
            String[] lines = result.split("\\r?\\n");
            List<java.nio.file.Path> paths = new ArrayList<>();
            for (String line : lines) {
                try {
                    URI uri = URI.create(line);
                    java.nio.file.Path pth = Paths.get(uri);
                    paths.add(pth);
                } catch (Exception ex) {
                    // ignore unparsable lines
                }
            }
            setLastSearchResults(paths);

            // print human readable listing using absolute paths
            System.out.println(paths.size() + " files found:");
            for (int i = 0; i < paths.size(); i++) {
                System.out.println((i+1) + ") " + paths.get(i).toAbsolutePath().toString());
            }
            System.out.println("Which file do you want to read? (number / 1,3 / all / none):");
            return true;
        } catch (Exception e) {
            System.err.println("Search handling failed: " + e.getMessage());
            return false;
        }
    }




    private static final String DEFAULT_SYSTEM_PROMPT = "あなたはプロフェッショナルなアシスタントです。常に日本語で、丁寧かつ簡潔に答えてください。技術的な説明は箇条書きやコード例を使って分かりやすく示し、必要なら出力の最後に要約を短く付けてください。決して英語で返答しないでください。";

    private static final String SYSTEM_PROMPT = System.getenv().getOrDefault("CHAT_SYSTEM_PROMPT", DEFAULT_SYSTEM_PROMPT);


    // Assistant interface used by AiServices dynamic proxy
    /**
     * アシスタントの抽象インタフェース。AiServices によって実装が生成されます。
     * chat メソッドは与えたメッセージに対する応答テキストを返します。
     */
    public interface Assistant {
        String chat(String message);
    }

    /**
     * 環境変数から OpenAI モデルを構築して返すヘルパー。
     * 必要な環境変数: OPENAI_API_KEY（必須）、OPENAI_MODEL（任意）。
     *
     * @return 初期化された OpenAiChatModel
     * @throws IllegalStateException API キーが未設定の場合
     */
    private static OpenAiChatModel buildModel() {
        String apiKey = System.getenv("OPENAI_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("OPENAI_API_KEY is not set");
        }
        String modelName = System.getenv().getOrDefault("OPENAI_MODEL", "gpt-4o-mini");
        return OpenAiChatModel.builder()
                .apiKey(apiKey)
                .modelName(modelName)
                .build();
    }

    /**
     * 対話モードでチャットを行う。標準入力からユーザ入力を読み、Assistant を通じて応答を取得します。
     * チャット履歴は MessageWindowChatMemory によって管理されます。
     * <p>
     * 環境変数:
     * - CHAT_MEMORY_WINDOW: 履歴ウィンドウのサイズ（既定 10）
     * - OPENAI_API_KEY: OpenAI API キー（必須）
     * - OPENAI_MODEL: 使用モデル（既定 gpt-4o-mini）
     *
     * @throws Exception ランタイム例外やネットワーク例外を伝搬する可能性があります
     */
    private static List<java.nio.file.Path> lastSearchResults = null;

    public static void runChat() throws Exception {
        OpenAiChatModel model = buildModel();

        int window = Integer.parseInt(System.getenv().getOrDefault("CHAT_MEMORY_WINDOW", "10"));
        ChatMemory chatMemory = MessageWindowChatMemory.withMaxMessages(window);

        Assistant assistant = AiServices.builder(Assistant.class)
                .chatModel(model)
                .chatMemory(chatMemory)
                .tools(new Calculator(), new FileReaderTool(), new FileSearchTool(), new InteractiveFileSearchTool())
                .build();

        Scanner scanner = new Scanner(System.in);
        System.out.println("=== AI Assistant (Type 'exit' to quit) ===");

        while (true) {
            System.out.print("User: ");
            if (!scanner.hasNextLine())
                break;
            String userMessage = scanner.nextLine();
            if (userMessage == null)
                break;
            if ("exit".equalsIgnoreCase(userMessage.trim()))
                break;

            try {
                // If the input looks like a search command, handle it locally by calling FileSearchTool
                if (isSearchCommand(userMessage)) {
                    boolean handled = handleSearchCommand(userMessage);
                    if (handled) continue; // search handled and results displayed
                }

                // If the input looks like a selection (e.g., "3" or "1,3" or "all"), handle locally
                if (isSelectionInput(userMessage)) {
                    String selectionResult = handleSelectionInput(userMessage);
                    if (selectionResult != null) {
                        // Send the selected file contents to the assistant for follow-up processing
                        String aiResponse = assistant.chat(SYSTEM_PROMPT + "\n\n" + selectionResult);
                        System.out.println("AI: " + aiResponse);
                        continue;
                    }
                }

                String aiResponse = assistant.chat(SYSTEM_PROMPT + "\n\n" + userMessage);
                System.out.println("AI: " + aiResponse);
            } catch (Exception e) {
                System.err.println("Request error: " + e.getMessage());
            }
        }
    }

    /**
     * 単発メッセージをモデルに送信して応答を表示するユーティリティ。
     * 非対話環境（CI 等）での利用を想定しています。
     *
     * @param message 送信するユーザメッセージ
     * @throws Exception 送信時の例外を伝搬する可能性があります
     */
    public static void runChatOnce(String message) throws Exception {
        OpenAiChatModel model = buildModel();
        ChatMemory chatMemory = MessageWindowChatMemory
                .withMaxMessages(Integer.parseInt(System.getenv().getOrDefault("CHAT_MEMORY_WINDOW", "10")));
        Assistant assistant = AiServices.builder(Assistant.class)
                .chatModel(model)
                .chatMemory(chatMemory)
                .tools(new Calculator(), new FileReaderTool(), new FileSearchTool(), new InteractiveFileSearchTool())
                .build();
        String aiResponse = assistant.chat(SYSTEM_PROMPT + "\n\n" + message);
        System.out.println(aiResponse);
    }

    /**
     * アプリケーションのエントリポイント。引数に "chat" を指定するとチャットモードを起動します。
     * 例:
     * - chat        -> 対話モード
     * - chat Hello  -> 単発メッセージ送信
     *
     * @param args コマンドライン引数
     * @throws Exception 実行中の例外
     */

    public static void main(String[] args) throws Exception {
        // Force System.out/System.err to use Windows-31J encoding so Windows cmd displays Japanese correctly.
        try {
            PrintStream psOut = new PrintStream(new BufferedOutputStream(new FileOutputStream(FileDescriptor.out)), true, "Windows-31J");
            PrintStream psErr = new PrintStream(new BufferedOutputStream(new FileOutputStream(FileDescriptor.err)), true, "Windows-31J");
            System.setOut(psOut);
            System.setErr(psErr);
        } catch (UnsupportedEncodingException e) {
            // Fallback: ignore and use default encoding
        }

        // 対話モード専用に変更: --file や単発メッセージは無効化します
        if (args.length > 0 && "chat".equals(args[0])) {
            if (args.length == 1) {
                // 対話モードを起動
                runChat();
            } else {
                System.err.println("このアプリケーションは対話モード専用です。ファイル指定や単発メッセージはサポートしていません。'chat' とだけ実行してください。");
            }
        } else {
            System.out.println("Usage: chat");
        }
    }
}
