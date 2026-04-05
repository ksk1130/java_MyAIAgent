package org.example;

import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.service.AiServices;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.tools.Calculator;
import org.example.tools.FileReaderTool;
import org.example.tools.FileWriterTool;
import org.example.tools.ImpactAnalysisTool;
import org.example.tools.LocalCommandTool;
import org.example.tools.GrepTool;
import org.example.agents.FileSearchWorkflow;

import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.UserInterruptException;
import org.jline.reader.EndOfFileException;
import org.jline.reader.impl.DefaultParser;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;

import java.io.PrintWriter;
import java.util.concurrent.Callable;
import java.util.Set;
import java.util.HashSet;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.regex.Matcher;
import java.nio.file.Paths;
import java.nio.file.Files;
import java.util.List;
import java.util.ArrayList;
import java.nio.file.Path;

/**
 * Agent対応版のCLIチャットアプリケーション。
 * FileSearchWorkflow などのエージェントを利用可能にします。
 * ChatCLI の Agent Step Loop や確認ダイアログ機構も統合しています。
 */
public class AgentChatCLI {

    private static final String ANSI_RESET = "\u001B[0m";
    private static final String ANSI_BOLD = "\u001B[1m";
    private static final String ANSI_BRIGHT_BLACK = "\u001B[90m";
    private static final String ANSI_GREEN = "\u001B[32m";
    private static final String ANSI_YELLOW = "\u001B[33m";
    private static final String ANSI_CYAN = "\u001B[36m";
    private static final String ANSI_BLUE_BOLD = "\u001B[1;34m";
    private static final String EXIT_COMMAND = "exit";
    private static final String CLEAR_HISTORY_COMMAND = "/clear";
    private static final String FILE_SEARCH_COMMAND = "/filesearch";
    private static final String CHAT_MODE_ARGUMENT = "chat";
    private static final String UNKNOWN_ARGUMENT_MESSAGE = "不明な引数です。引数なしで実行すると対話モードが起動します。";
    private static final String AI_THINKING_LABEL = "AI 考え中...";
    private static final String COMMAND_RUNNING_LABEL = "コマンド実行中...";
    private static final String AGENT_WORKING_LABEL = "エージェント処理中...";
    private static final ObjectMapper mapper = new ObjectMapper();

    // Agent Step Loop 関連定数
    private static final String STEP_CONTINUE_PREFIX = "STEP_CONTINUE:";
    private static final String STEP_FINAL_PREFIX = "STEP_FINAL:";
    private static final int DEFAULT_AGENT_MAX_STEPS = 5;
    private static final Set<String> APPROVE_INPUTS = Set.of("はい", "yes", "y");
    private static final Set<String> REJECT_INPUTS = Set.of("いいえ", "no", "n", "キャンセル");
    private static final Set<String> SELECTION_VERBS = Set.of("read", "open", "show", "select");
    private static final String SELECTION_ALL = "all";
    private static final String SELECTION_NONE = "none";
    private static final String SELECTION_CANCEL = "cancel";

    private static final Pattern FENCED_CODE_BLOCK = Pattern.compile("(?s)```([a-zA-Z0-9_+\\-]*)\\R(.*?)```");
    private static final Pattern CODE_TOKEN_PATTERN = Pattern
            .compile("\\\"(?:\\\\.|[^\\\"])*\\\"|'(?:\\\\.|[^'])*'|\\b\\d+(?:\\.\\d+)?\\b|\\b[A-Za-z_][A-Za-z0-9_]*\\b|#.*$|//.*$|--.*$", Pattern.MULTILINE);

    /**
     * エージェント1ステップの判定種別です。
     */
    private enum AgentStepDecision {
        CONTINUE,
        FINAL,
        FALLBACK
    }

    /**
     * 1ステップ応答の解析結果を表すレコードです。
     *
     * @param decision 継続/完了/フォールバックの判定
     * @param body 接頭辞を除いた本文
     * @param rawResponse モデルの生レスポンス
     */
    private record AgentStepResult(AgentStepDecision decision, String body, String rawResponse) {
    }

    // Agent Step Loop 関連の状態
    private static PendingAgentExecution pendingAgentExecution = null;
    private static List<Path> lastSearchResults = new ArrayList<>();

    /**
     * エージェントの継続実行待ち状態を保持するレコードです。
     *
     * @param taskUserMessage 元のタスク
     * @param scratch これまでのステップ記録
     * @param nextStep 次に実行するステップ番号
     * @param maxSteps 最大ステップ数
     */
    private record PendingAgentExecution(String taskUserMessage, String scratch, int nextStep, int maxSteps) {
    }

    // Assistant interface used by AiServices dynamic proxy
    public interface Assistant {
        String chat(String message);
    }

    /**
     * 環境変数から OpenAI モデルを構築して返すヘルパー。
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
     * Windows パスをそのまま入力できるようにした JLine パーサを返します。
     *
     * @return 設定済みのパーサ
     */
    private static DefaultParser buildInputParser() {
        return new DefaultParser().escapeChars(null);
    }

    /**
     * ユーザ入力プロンプト文字列を返します。
     *
     * @param colorEnabled カラー表示フラグ
     * @return プロンプト文字列
     */
    private static String userPromptLabel(boolean colorEnabled) {
        if (!colorEnabled) {
            return "User: ";
        }
        return ANSI_BOLD + ANSI_YELLOW + "User" + ANSI_RESET + ANSI_BOLD + ": " + ANSI_RESET;
    }

    /**
     * AI ラベル文字列を返します。
     *
     * @param colorEnabled カラー表示フラグ
     * @return AI ラベル文字列
     */
    private static String aiLabel(boolean colorEnabled) {
        if (!colorEnabled) {
            return "AI:";
        }
        return ANSI_BOLD + ANSI_CYAN + "AI" + ANSI_RESET + ANSI_BOLD + ":" + ANSI_RESET;
    }

    /**
     * AI応答中のコードブロックを簡易シンタックスハイライトして返します。
     *
     * @param text 入力テキスト
     * @param colorEnabled カラー表示フラグ
     * @return ハイライト済みテキスト
     */
    private static String renderWithSyntaxHighlight(String text, boolean colorEnabled) {
        if (!colorEnabled || text == null || text.isBlank()) {
            return text;
        }

        Matcher blockMatcher = FENCED_CODE_BLOCK.matcher(text);
        StringBuffer rendered = new StringBuffer();
        while (blockMatcher.find()) {
            String lang = blockMatcher.group(1) == null ? "" : blockMatcher.group(1).trim().toLowerCase();
            String code = blockMatcher.group(2);

            // 簡易シンタックスハイライト（言語別）
            String highlighted = code;
            if (!lang.isEmpty()) {
                highlighted = highlightCodeByLanguage(code, lang);
            }

            String replacement = "```" + lang + "\n" + highlighted + "```";
            blockMatcher.appendReplacement(rendered, Matcher.quoteReplacement(replacement));
        }
        blockMatcher.appendTail(rendered);
        return rendered.toString();
    }

    /**
     * 言語別にコードをシンタックスハイライトします。
     *
     * @param code コード文字列
     * @param language 言語（java, python, sql など）
     * @return ハイライト済みコード
     */
    private static String highlightCodeByLanguage(String code, String language) {
        // 簡易実装：キーワード色付け
        Set<String> keywords = keywordsFor(language);
        String result = code;
        for (String kw : keywords) {
            result = result.replaceAll("\\b" + kw + "\\b",
                    ANSI_GREEN + kw + ANSI_RESET);
        }
        return result;
    }

    /**
     * 言語別のキーワード一覧を返します。
     *
     * @param language プログラミング言語
     * @return キーワード集合
     */
    private static Set<String> keywordsFor(String language) {
        Set<String> common = new HashSet<>(Set.of(
                "if", "else", "for", "while", "return", "class", "public", "private", "static",
                "new", "null", "true", "false", "try", "catch", "finally", "import", "from",
                "def", "function", "const", "let", "var", "void", "int", "double", "string"));

        if ("python".equals(language) || "py".equals(language)) {
            common.addAll(Set.of("in", "and", "or", "not", "with", "as", "lambda", "None", "pass"));
        } else if ("sql".equals(language)) {
            common.addAll(Set.of("select", "from", "where", "join", "inner", "left", "right", "group",
                    "by", "order", "insert", "into", "update", "delete", "create", "table", "values"));
        }
        return common;
    }

    /**
     * バックグラウンドスレッドでスピナーアニメーションを表示します。
     *
     * @param writer 出力先
     * @param label  スピナーの前に表示するラベル
     * @return 起動済みスピナースレッド
     */
    private static Thread startSpinner(PrintWriter writer, String label) {
        Thread t = new Thread(() -> {
            char[] frames = {'|', '/', '-', '\\'};
            int i = 0;
            try {
                while (!Thread.currentThread().isInterrupted()) {
                    writer.print("\r" + label + " " + frames[i % frames.length] + " ");
                    writer.flush();
                    i++;
                    Thread.sleep(100);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                writer.print("\r" + " ".repeat(label.length() + 3) + "\r");
                writer.flush();
            }
        });
        t.setDaemon(true);
        t.start();
        return t;
    }

    /**
     * 起動済みスピナーを停止し、終了を短時間待機します。
     *
     * @param spinner 停止対象のスピナー
     */
    private static void stopSpinner(Thread spinner) {
        if (spinner == null) {
            return;
        }
        spinner.interrupt();
        try {
            spinner.join(100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * スピナーを表示しながら処理を実行します。
     *
     * @param writer 出力先
     * @param label スピナー表示ラベル
     * @param action 実行処理
     * @return 処理結果
     * @param <T> 戻り値型
     * @throws Exception 実行処理の例外
     */
    private static <T> T withSpinner(PrintWriter writer, String label, Callable<T> action) throws Exception {
        Thread spinner = startSpinner(writer, label);
        try {
            return action.call();
        } finally {
            stopSpinner(spinner);
        }
    }

    /**
     * ANSI カラー表示を有効にするかどうかを判定します。
     *
     * @return 有効の場合 true
     */
    private static boolean isColorEnabled() {
        return System.getenv().getOrDefault("CHAT_NO_COLOR", "").isEmpty();
    }

    /**
     * ユーザ入力を正規化します。
     *
     * @param input ユーザ入力
     * @return 正規化済み入力
     */
    private static Optional<String> normalizeInput(String input) {
        return Optional.ofNullable(input)
                .map(String::strip)
                .filter(text -> !text.isEmpty());
    }

    /**
     * FileSearchWorkflow を実行します。
     *
     * @param model OpenAiChatModel
     * @param userInput ユーザー入力
     * @return 処理結果
     */
    private static String runFileSearchWorkflow(OpenAiChatModel model, String userInput) {
        try {
            FileSearchWorkflow workflow = new FileSearchWorkflow(model);
            return workflow.executeWorkflow(userInput);
        } catch (Exception e) {
            return "エラー: ファイル検索ワークフロー実行中に問題が発生しました。\n" + e.getMessage();
        }
    }

    /**
     * 対話モードでチャットを行います。
     *
     * @throws Exception ランタイム例外やネットワーク例外を伝搬する可能性があります
     */
    public static void runChat() throws Exception {
        OpenAiChatModel model = buildModel();

        int window = Integer.parseInt(System.getenv().getOrDefault("CHAT_MEMORY_WINDOW", "50"));
        ChatMemory chatMemory = MessageWindowChatMemory.withMaxMessages(window);

        LocalCommandTool localCommandTool = new LocalCommandTool();
        Assistant assistant = AiServices.builder(Assistant.class)
                .chatModel(model)
                .chatMemory(chatMemory)
                .tools(new Calculator(), new FileReaderTool(), new FileWriterTool(), 
                       new ImpactAnalysisTool(), localCommandTool, new GrepTool())
                .build();

        // JLine3 Terminal
        try (Terminal terminal = TerminalBuilder.builder()
                .system(true)
                .build()) {

            final boolean colorEnabled = isColorEnabled();

            LineReader lineReader = LineReaderBuilder.builder()
                    .terminal(terminal)
                    .parser(buildInputParser())
                    .variable(LineReader.HISTORY_FILE,
                            Paths.get(System.getProperty("user.home"), ".ai_history"))
                    .variable(LineReader.SECONDARY_PROMPT_PATTERN, "> ")
                    .option(LineReader.Option.INSERT_TAB, true)
                    .build();

            PrintWriter writer = terminal.writer();
            writer.println("=== AI Assistant (Agent対応版) (Ctrl+D or 'exit' to quit) ===");
            writer.println("(入力方法: Enter で改行、Ctrl+D で送信)");
            writer.println("(コマンド: /filesearch <ディレクトリ> <キーワード>, /clear)");
            writer.flush();

            StringBuilder inputBuffer = new StringBuilder();
            while (true) {
                String userMessage;
                try {
                    String prompt = inputBuffer.length() == 0 ? userPromptLabel(colorEnabled)
                            : ANSI_BRIGHT_BLACK + "... " + ANSI_RESET;
                    userMessage = lineReader.readLine(prompt);
                    if (inputBuffer.length() > 0) {
                        inputBuffer.append('\n');
                    }
                    inputBuffer.append(userMessage);
                    continue;
                } catch (UserInterruptException e) {
                    inputBuffer.setLength(0);
                    continue;
                } catch (EndOfFileException e) {
                    if (inputBuffer.length() == 0) {
                        break;
                    }
                    userMessage = inputBuffer.toString();
                    inputBuffer.setLength(0);
                }

                Optional<String> normalizedUserMessage = normalizeInput(userMessage);
                if (normalizedUserMessage.isEmpty()) {
                    continue;
                }
                String normalizedMessage = normalizedUserMessage.orElseThrow();

                if (EXIT_COMMAND.equalsIgnoreCase(normalizedMessage)) {
                    break;
                }

                // 履歴クリアコマンド
                if (CLEAR_HISTORY_COMMAND.equalsIgnoreCase(normalizedMessage)) {
                    try {
                        chatMemory.clear();
                        Path historyFile = Paths.get(System.getProperty("user.home"), ".ai_history");
                        Files.deleteIfExists(historyFile);
                        writer.println(aiLabel(colorEnabled));
                        writer.println("✓ 会話履歴と入力履歴をクリアしました");
                        writer.flush();
                        continue;
                    } catch (Exception e) {
                        writer.println("[ERROR] 履歴クリア中にエラーが発生しました: " + e.getMessage());
                        writer.flush();
                        continue;
                    }
                }

                // ファイル検索コマンド（エージェント）
                if (normalizedMessage.startsWith(FILE_SEARCH_COMMAND)) {
                    String searchQuery = normalizedMessage.substring(FILE_SEARCH_COMMAND.length()).strip();
                    if (searchQuery.isEmpty()) {
                        writer.println(aiLabel(colorEnabled));
                        writer.println("使用方法: /filesearch ディレクトリ キーワード");
                        writer.println("例: /filesearch . report");
                        writer.flush();
                        continue;
                    }

                    try {
                        String result = withSpinner(writer, AGENT_WORKING_LABEL,
                                () -> runFileSearchWorkflow(model, searchQuery));
                        writer.println(aiLabel(colorEnabled));
                        writer.println(renderWithSyntaxHighlight(result, colorEnabled));
                        writer.flush();
                        continue;
                    } catch (Exception e) {
                        writer.println("[ERROR] " + e.getMessage());
                        writer.flush();
                        continue;
                    }
                }

                // 通常の AI チャット
                final String messageToSend = normalizedMessage;

                try {
                    String aiResponse = withSpinner(writer, AI_THINKING_LABEL,
                            () -> assistant.chat(messageToSend));
                    writer.println(aiLabel(colorEnabled));
                    writer.println(renderWithSyntaxHighlight(aiResponse, colorEnabled));
                    writer.flush();
                } catch (Exception e) {
                    writer.println("[ERROR] " + e.getMessage());
                    writer.flush();
                }
            }
        }
    }

    /**
     * 単発メッセージモード。
     *
     * @param message ユーザーメッセージ
     * @throws Exception ネットワーク例外など
     */
    public static void runChatOnce(String message) throws Exception {
        OpenAiChatModel model = buildModel();
        ChatMemory chatMemory = MessageWindowChatMemory
                .withMaxMessages(Integer.parseInt(System.getenv().getOrDefault("CHAT_MEMORY_WINDOW", "50")));
        LocalCommandTool localCommandTool = new LocalCommandTool();
        Assistant assistant = AiServices.builder(Assistant.class)
                .chatModel(model)
                .chatMemory(chatMemory)
                .tools(new Calculator(), new FileReaderTool(), new FileWriterTool(), 
                       new ImpactAnalysisTool(), localCommandTool, new GrepTool())
                .build();

        String aiResponse = assistant.chat(message);
        System.out.println(renderWithSyntaxHighlight(aiResponse, isColorEnabled()));
    }

    /**
     * エントリポイント。
     *
     * @param args コマンドライン引数
     * @throws Exception 処理中の例外
     */
    public static void main(String[] args) throws Exception {
        if (args.length == 0 || "chat".equalsIgnoreCase(args[0])) {
            // 対話モード: 引数なしか "chat" 引数で起動
            runChat();
        } else {
            // 単発メッセージモード: すべての引数を空白区切りで連結
            // 例: args = ["agent", "コードを解析"] -> "agent コードを解析" （"agent"が誤って含まれた場合でも処理）
            String message = String.join(" ", args);
            runChatOnce(message);
        }
    }
}
