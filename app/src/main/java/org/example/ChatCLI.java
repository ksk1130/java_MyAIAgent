package org.example;

import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.service.AiServices;
import org.example.tools.Calculator;
import org.example.tools.FileReaderTool;
import org.example.tools.FileWriterTool;
import org.example.tools.ImpactAnalysisTool;
import org.example.tools.LocalCommandTool;

import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.UserInterruptException;
import org.jline.reader.EndOfFileException;
import org.jline.reader.impl.DefaultParser;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;

import java.io.PrintWriter;
import java.util.concurrent.Callable;
import java.util.Locale;
import java.util.Set;
import java.util.HashSet;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.regex.Matcher;
import java.nio.file.Paths;
import java.util.List;
import java.util.ArrayList;
import java.nio.file.Path;


/**
 * CLI チャットアプリケーションのエントリポイントです。
 *
 * LangChain4j の OpenAiChatModel と MessageWindowChatMemory を使い、
 * AiServices を通して Assistant インタフェースの proxy を生成します。
 * 対話モードと単発メッセージ送信の両方を提供し、検索用途では
 * LocalCommandTool を使った Function Calling を前提とします。
 */
public class ChatCLI {

    private static final String ANSI_RESET = "\u001B[0m";
    private static final String ANSI_BOLD = "\u001B[1m";
    private static final String ANSI_BRIGHT_BLACK = "\u001B[90m";
    private static final String ANSI_GREEN = "\u001B[32m";
    private static final String ANSI_YELLOW = "\u001B[33m";
    private static final String ANSI_CYAN = "\u001B[36m";
    private static final String ANSI_BLUE_BOLD = "\u001B[1;34m";
    private static final String EXIT_COMMAND = "exit";
    private static final String SELECTION_ALL = "all";
    private static final String SELECTION_NONE = "none";
    private static final String SELECTION_CANCEL = "cancel";
    private static final String CHAT_MODE_ARGUMENT = "chat";
    private static final String UNKNOWN_ARGUMENT_MESSAGE = "不明な引数です。引数なしで実行すると対話モードが起動します。";
    private static final String AI_THINKING_LABEL = "AI 考え中...";
    private static final String COMMAND_RUNNING_LABEL = "コマンド実行中...";
    private static final Set<String> APPROVE_INPUTS = Set.of("はい", "yes", "y");
    private static final Set<String> REJECT_INPUTS = Set.of("いいえ", "no", "n", "キャンセル");
    private static final Set<String> SELECTION_VERBS = Set.of("read", "open", "show", "select");

    private static final Pattern FENCED_CODE_BLOCK = Pattern.compile("(?s)```([a-zA-Z0-9_+\\-]*)\\R(.*?)```");
    private static final Pattern CODE_TOKEN_PATTERN = Pattern
            .compile("\\\"(?:\\\\.|[^\\\"])*\\\"|'(?:\\\\.|[^'])*'|\\b\\d+(?:\\.\\d+)?\\b|\\b[A-Za-z_][A-Za-z0-9_]*\\b|#.*$|//.*$|--.*$", Pattern.MULTILINE);

    /**
     * 直近の検索結果一覧を保持します。
     * 選択入力時に参照される一時状態です。
     *
     * @param results 保持する検索結果一覧
     */
    public static void setLastSearchResults(List<Path> results) {
        lastSearchResults = results;
    }

    /**
     * ユーザ入力を正規化します。
     * null を除外し、前後空白を除去したうえで空文字を捨てます。
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
     * 判定用にユーザ入力を小文字へ正規化します。
     *
     * @param input ユーザ入力
     * @return 小文字化済み入力
     */
    private static Optional<String> normalizeCaseFoldedInput(String input) {
        return normalizeInput(input)
                .map(text -> text.toLowerCase(Locale.ROOT));
    }

    /**
     * ユーザ入力が検索結果の選択入力かどうかを判定します。
     *
     * @param input ユーザ入力
     * @return 選択入力であれば true
     */
    private static boolean isSelectionInput(String input) {
        return normalizeInput(input)
            .map(text -> text.equalsIgnoreCase(SELECTION_ALL)
                || text.equalsIgnoreCase(SELECTION_NONE)
                        || text.matches("^\\d+(?:\\s*,\\s*\\d+)*$")
                || text.matches("(?i)^(" + String.join("|", SELECTION_VERBS)
                    + ")\\s+\\d+(?:\\s*,\\s*\\d+)*$"))
                .orElse(false);
    }

    /**
     * ユーザ入力が承認（はい/yes）かどうかを判定します。
     *
     * @param input ユーザ入力
     * @return 承認入力であれば true
     */
    private static boolean isApproveInput(String input) {
        return normalizeCaseFoldedInput(input)
                .map(APPROVE_INPUTS::contains)
                .orElse(false);
    }

    /**
     * ユーザ入力が拒否（いいえ/no）かどうかを判定します。
     *
     * @param input ユーザ入力
     * @return 拒否入力であれば true
     */
    private static boolean isRejectInput(String input) {
        return normalizeCaseFoldedInput(input)
                .map(REJECT_INPUTS::contains)
                .orElse(false);
    }

    /**
     * 直近の検索結果に対する選択入力を解釈し、選択ファイルの内容を返します。
     *
     * @param input ユーザ入力
     * @return 選択されたファイル内容。処理しない場合は null
     */
    private static String handleSelectionInput(String input) {
        // helper remains the same
        if (lastSearchResults == null || lastSearchResults.isEmpty()) {
            System.err.println("No recent search results available. Please perform a search first.");
            return null;
        }

        String t = normalizeInput(input).orElse("");
        String lower = t.toLowerCase(Locale.ROOT);
        List<Integer> selected = new ArrayList<>();
        if (lower.equals(SELECTION_NONE) || lower.equals(SELECTION_CANCEL)) {
            return null;
        } else if (lower.equals(SELECTION_ALL)) {
            for (int i = 1; i <= Math.min(lastSearchResults.size(), 5); i++)
                selected.add(i);
        } else if (t.matches("(?i)^(" + String.join("|", SELECTION_VERBS) + ")\\s+.*$")) {
            // extract numbers after the command
            Matcher m = Pattern.compile("(\\d+)(?:\\s*,\\s*(\\d+))*").matcher(t);
            while (m.find()) {
                try {
                    selected.add(Integer.parseInt(m.group(1)));
                } catch (Exception ignored) {
                }
            }
            if (selected.isEmpty())
                return null;
        } else {
            // comma separated numbers
            String[] parts = t.split("\\s*,\\s*");
            for (String part : parts) {
                try {
                    selected.add(Integer.parseInt(part));
                } catch (NumberFormatException ignored) {
                }
            }
            if (selected.isEmpty())
                return null;
        }

        // normalize and validate
        List<Path> toRead = new ArrayList<>();
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
        for (Path p : toRead) {
            if (count >= 5)
                break;
            String content = reader.readFile(p.toAbsolutePath().toString());
            sb.append("==== FILE ").append(p.toAbsolutePath().toString()).append(" ====\n");
            sb.append(content).append("\n");
            count++;
        }

        // clear lastSearchResults after use to avoid accidental reuse
        lastSearchResults = null;
        return sb.toString();
    }

    private static final String DEFAULT_SYSTEM_PROMPT = """
            あなたはプロフェッショナルなアシスタントです。常に日本語で、丁寧かつ簡潔に答えてください。技術的な説明は箇条書きやコード例を使って分かりやすく示し、必要なら出力の最後に要約を短く付けてください。決して英語で返答しないでください。

            ツール選択ルール:
            - 検索や調査が必要な場合は LocalCommandTool を使ってください。
            - ファイル内容の検索、正規表現検索、拡張子限定検索、ディレクトリ配下の列挙は LocalCommandTool で実現してください。
            - Windows 環境ではまず rg を優先し、必要に応じて Select-String や findstr を使ってください。
            - Java ファイルだけを対象にしたい場合は *.java の絞り込みをコマンドに含めてください。
            - Git 情報の確認が必要な場合は LocalCommandTool で読み取り専用コマンド（git status/log/show/diff/branch など）を使ってください。
            - SELECT .* FROM のようなパターンは正規表現として扱ってください。
            - 目的を満たすための検索コマンドを自分で考えてから LocalCommandTool を呼び出してください。
            - ツール実行後は結果を要約し、必要なら次の検索条件を提案してください。
            - LocalCommandTool は最初の呼び出しでは実行されず、確認メッセージを返します。
            - 確認メッセージが出たら、ユーザに「はい/いいえ」で意思確認し、はいの場合にのみ実行してください。
            - ファイルへの書き込みが必要な場合は FileWriterTool を使ってください。
            - FileWriterTool はテキスト系ファイル（txt, md, java, json など）のみ対応しています。
            - ファイルを書き込む前に、書き込み内容をユーザに確認してから実行してください。
            - テーブル名変更時の影響調査では ImpactAnalysisTool を使って、Java/COBOL を含む推移的な影響ファイルを抽出してください。
            """;

    private static final String SYSTEM_PROMPT = System.getenv().getOrDefault("CHAT_SYSTEM_PROMPT",
            DEFAULT_SYSTEM_PROMPT);

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
     * 直近の検索結果一覧を保持します。
     */
    private static List<Path> lastSearchResults = null;

    /**
     * ANSI カラー表示を有効にするかどうかを判定します。
     *
     * @return 有効なら true
     */
    private static boolean isColorEnabled() {
        String noColor = System.getenv("CHAT_NO_COLOR");
        return noColor == null || !"true".equalsIgnoreCase(noColor.trim());
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
     * Windows パスをそのまま入力できるようにした JLine パーサを返します。
     * バックスラッシュをエスケープ文字として扱わないため、C:\hoge のような入力が崩れません。
     *
     * @return 設定済みのパーサ
     */
    private static DefaultParser buildInputParser() {
        return new DefaultParser().escapeChars(null);
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
            String highlighted = highlightCode(code, lang);
            String replacement = ANSI_CYAN + "```" + lang + ANSI_RESET + "\n"
                    + highlighted
                    + "\n" + ANSI_CYAN + "```" + ANSI_RESET;
            blockMatcher.appendReplacement(rendered, Matcher.quoteReplacement(replacement));
        }
        blockMatcher.appendTail(rendered);
        return rendered.toString();
    }

    /**
     * 単一コードブロックをトークン単位で色付けします。
     *
     * @param code コード文字列
     * @param language 言語ヒント
     * @return 色付け済みコード
     */
    private static String highlightCode(String code, String language) {
        Set<String> keywords = keywordsFor(language);
        Matcher tokenMatcher = CODE_TOKEN_PATTERN.matcher(code);
        StringBuffer sb = new StringBuffer();
        while (tokenMatcher.find()) {
            String token = tokenMatcher.group();
            String colored = colorizeToken(token, keywords);
            tokenMatcher.appendReplacement(sb, Matcher.quoteReplacement(colored));
        }
        tokenMatcher.appendTail(sb);
        return sb.toString();
    }

    /**
     * トークンの種類に応じて ANSI カラーを付与します。
     *
     * @param token 対象トークン
     * @param keywords キーワード集合
     * @return 色付け済みトークン
     */
    private static String colorizeToken(String token, Set<String> keywords) {
        if (token.startsWith("#") || token.startsWith("//") || token.startsWith("--")) {
            return ANSI_BRIGHT_BLACK + token + ANSI_RESET;
        }
        if (token.startsWith("\"") || token.startsWith("'")) {
            return ANSI_GREEN + token + ANSI_RESET;
        }
        if (token.matches("\\d+(?:\\.\\d+)?")) {
            return ANSI_YELLOW + token + ANSI_RESET;
        }
        if (keywords.contains(token.toLowerCase())) {
            return ANSI_BLUE_BOLD + token + ANSI_RESET;
        }
        return token;
    }

    /**
     * 言語に応じたキーワード集合を返します。
     *
     * @param language コードフェンスの言語
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
        } else if ("javascript".equals(language) || "js".equals(language)
                || "typescript".equals(language) || "ts".equals(language)) {
            common.addAll(Set.of("async", "await", "extends", "implements", "export", "default", "typeof"));
        }
        return common;
    }

    /**
     * バックグラウンドスレッドでスピナーアニメーションを表示します。
     * {@code interrupt()} を呼ぶと停止し、表示行をクリアします。
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
            spinner.join(500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * スピナー表示中に処理を実行し、完了後に必ず停止します。
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
     * 対話モードでチャットを行います。
     * 標準入力からユーザ入力を受け取り、Assistant を通じて応答を取得します。
     * チャット履歴は MessageWindowChatMemory によって管理されます。
     * <p>
     * 使用する主な環境変数:
     * - CHAT_MEMORY_WINDOW: 履歴ウィンドウのサイズ（既定 50）
     * - OPENAI_API_KEY: OpenAI API キー（必須）
     * - OPENAI_MODEL: 使用モデル名（既定 gpt-4o-mini）
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
                .tools(new Calculator(), new FileReaderTool(), new FileWriterTool(), new ImpactAnalysisTool(), localCommandTool)
                .build();

        // JLine3 Terminal: Windows ネイティブコンソール API を使用し Unicode 入力を正しく処理
        try (Terminal terminal = TerminalBuilder.builder()
                .system(true)
                .build()) {

            final boolean colorEnabled = isColorEnabled();

            // 入力履歴を ~/.ai_history に保存
            LineReader lineReader = LineReaderBuilder.builder()
                    .terminal(terminal)
                    .parser(buildInputParser())
                    .variable(LineReader.HISTORY_FILE,
                            Paths.get(System.getProperty("user.home"), ".ai_history"))
                    .build();

            PrintWriter writer = terminal.writer();
            writer.println("=== AI Assistant (Ctrl+D or 'exit' to quit) ===");
            writer.flush();

            while (true) {
                String userMessage;
                try {
                    userMessage = lineReader.readLine(userPromptLabel(colorEnabled));
                } catch (UserInterruptException e) {
                    // Ctrl+C: 入力キャンセル、次の入力へ
                    continue;
                } catch (EndOfFileException e) {
                    // Ctrl+D / EOF: 終了
                    break;
                }

                Optional<String> normalizedUserMessage = normalizeInput(userMessage);
                if (normalizedUserMessage.isEmpty()) {
                    continue;
                }
                String normalizedMessage = normalizedUserMessage.orElseThrow();

                if (EXIT_COMMAND.equalsIgnoreCase(normalizedMessage)) {
                    break;
                }

                try {
                    if (localCommandTool.hasPendingCommand()) {
                        if (isApproveInput(normalizedMessage)) {
                                String commandResult = withSpinner(writer, COMMAND_RUNNING_LABEL,
                                    localCommandTool::executePendingCommand);
                                String aiResponse = withSpinner(writer, AI_THINKING_LABEL,
                                    () -> assistant.chat(SYSTEM_PROMPT + "\n\n" +
                                            "以下は承認後に実行したローカルコマンド結果です。要約してください。\n" + commandResult));
                            writer.println(aiLabel(colorEnabled));
                            writer.println(renderWithSyntaxHighlight(aiResponse, colorEnabled));
                            writer.flush();
                            continue;
                        }
                        if (isRejectInput(normalizedMessage)) {
                            String canceled = localCommandTool.cancelPendingCommand();
                            writer.println(aiLabel(colorEnabled));
                            writer.println(canceled);
                            writer.flush();
                            continue;
                        }
                        writer.println(aiLabel(colorEnabled));
                        writer.println("承認待ちのコマンドがあります。実行する場合は『はい』、中止する場合は『いいえ』と入力してください。対象: "
                                + localCommandTool.pendingCommandPreview());
                        writer.flush();
                        continue;
                    }

                    // 選択入力（番号 / all / none）のローカル処理
                    if (isSelectionInput(normalizedMessage)) {
                        String selectionResult = handleSelectionInput(normalizedMessage);
                        if (selectionResult != null) {
                                String aiResponse = withSpinner(writer, AI_THINKING_LABEL,
                                    () -> assistant.chat(SYSTEM_PROMPT + "\n\n" + selectionResult));
                            writer.println(aiLabel(colorEnabled));
                            writer.println(renderWithSyntaxHighlight(aiResponse, colorEnabled));
                            writer.flush();
                            continue;
                        }
                    }

                    // 通常は LLM(Function Calling) を優先してツール選択させる
                        String aiResponse = withSpinner(writer, AI_THINKING_LABEL,
                () -> assistant.chat(SYSTEM_PROMPT + "\n\n" + normalizedMessage));
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
     * 単発メッセージをモデルに送信して応答を表示するユーティリティ。
     * 非対話環境（CI 等）での利用を想定しています。
     *
     * @param message 送信するユーザメッセージ
     * @throws Exception 送信時の例外を伝搬する可能性があります
     */
    public static void runChatOnce(String message) throws Exception {
        OpenAiChatModel model = buildModel();
        ChatMemory chatMemory = MessageWindowChatMemory
                .withMaxMessages(Integer.parseInt(System.getenv().getOrDefault("CHAT_MEMORY_WINDOW", "50")));
        LocalCommandTool localCommandTool = new LocalCommandTool();
        Assistant assistant = AiServices.builder(Assistant.class)
                .chatModel(model)
                .chatMemory(chatMemory)
                .tools(new Calculator(), new FileReaderTool(), new FileWriterTool(), new ImpactAnalysisTool(), localCommandTool)
                .build();
        String aiResponse = assistant.chat(SYSTEM_PROMPT + "\n\n" + message);
        System.out.println(renderWithSyntaxHighlight(aiResponse, isColorEnabled()));
    }

    /**
     * アプリケーションのエントリポイント。引数なしで対話モードを起動します。
     * "chat" 引数を付けた場合も対話モードとして動作します（後方互換性のため）。
     * 例:
     * - （引数なし） -> 対話モード
     * - chat -> 対話モード
     *
     * @param args コマンドライン引数
     * @throws Exception 実行中の例外
     */

    public static void main(String[] args) throws Exception {
        // 引数なし、または "chat" 引数で対話モードを起動
        // JLine3 の Terminal がエンコーディングを自動処理するため、手動設定は不要
        switch (args.length) {
            case 0 -> runChat();
            case 1 -> {
                if (CHAT_MODE_ARGUMENT.equalsIgnoreCase(args[0].strip())) {
                    runChat();
                } else {
                    System.err.println(UNKNOWN_ARGUMENT_MESSAGE);
                }
            }
            default -> System.err.println(UNKNOWN_ARGUMENT_MESSAGE);
        }
    }
}
