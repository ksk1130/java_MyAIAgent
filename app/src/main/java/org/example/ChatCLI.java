package org.example;

import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.service.AiServices;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.tools.Calculator;
import org.example.tools.FileReaderTool;
import org.example.tools.FileWriterTool;
import org.example.tools.ImpactAnalysisTool;
import org.example.tools.LocalCommandTool;
import org.example.tools.GrepTool;
import org.example.tools.FileEditorTool;
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
import java.util.Locale;
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
    private static final String CLEAR_HISTORY_COMMAND = "/clear";
    private static final String SELECTION_ALL = "all";
    private static final String SELECTION_NONE = "none";
    private static final String SELECTION_CANCEL = "cancel";
    private static final String CHAT_MODE_ARGUMENT = "chat";
    private static final String UNKNOWN_ARGUMENT_MESSAGE = "不明な引数です。引数なしで実行すると対話モードが起動します。";
    private static final String AI_THINKING_LABEL = "AI 考え中...";
    private static final String COMMAND_RUNNING_LABEL = "コマンド実行中...";
    private static final String AGENT_WORKING_LABEL = "エージェント処理中...";
    private static final String FILE_SEARCH_COMMAND = "/filesearch";
    private static final String HELP_COMMAND = "/help";
    private static final ObjectMapper mapper = new ObjectMapper();

    private static final String STEP_CONTINUE_PREFIX = "STEP_CONTINUE:";
    private static final String STEP_FINAL_PREFIX = "STEP_FINAL:";
    private static final int DEFAULT_AGENT_MAX_STEPS = 5;
    private static final Set<String> APPROVE_INPUTS = Set.of("はい", "yes", "y");
    private static final Set<String> REJECT_INPUTS = Set.of("いいえ", "no", "n", "キャンセル");
    private static final Set<String> SELECTION_VERBS = Set.of("read", "open", "show", "select");

    private static final Pattern FENCED_CODE_BLOCK = Pattern.compile("(?s)```([a-zA-Z0-9_+\\-]*)\\R(.*?)```");
    private static final Pattern CODE_TOKEN_PATTERN = Pattern
            .compile(
                    "\\\"(?:\\\\.|[^\\\"])*\\\"|'(?:\\\\.|[^'])*'|\\b\\d+(?:\\.\\d+)?\\b|\\b[A-Za-z_][A-Za-z0-9_]*\\b|#.*$|//.*$|--.*$",
                    Pattern.MULTILINE);

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
     * @param decision    継続/完了/フォールバックの判定
     * @param body        接頭辞を除いた本文
     * @param rawResponse モデルの生レスポンス
     */
    private record AgentStepResult(AgentStepDecision decision, String body, String rawResponse) {
    }

    /**
     * エージェントの継続実行待ち状態を保持するレコードです。
     *
     * @param taskUserMessage 元のタスク
     * @param scratch         これまでのステップ記録
     * @param nextStep        次に実行するステップ番号
     * @param maxSteps        最大ステップ数
     */
    private record PendingAgentExecution(String taskUserMessage, String scratch, int nextStep, int maxSteps) {
    }

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
            あなたはプロフェッショナルな自律型AIエージェントです。常に日本語で、丁寧かつ簡潔に答えてください。

            ## 自律的行動指針:
            1. **一気通貫の調査**: ユーザーの目的（例：型変更の影響調査）を達成するために必要なステップを、可能な限り自律的に、連続して実行してください。
            2. **依存関係の自動追跡**: COPY句やimport文など、ファイル間で連鎖する依存関係を見つけた場合、ユーザーの個別の指示を待たずに、その参照先ファイルも自動的に調査対象に含めてください。
            3. **代替案の自走**: 最初のツール実行で期待した結果が得られない場合（例：検索結果0件）、別の検索キーワードや別のツール（大文字小文字のバリエーション、変数名の一部など）を自律的に試行してください。
            4. **一歩先の推論**: 単に「ファイルがあった」で終わらせず、「そのファイルの中身を確認し、次のアクション（例：利用箇所の特定）を提案、または実行する」ところまでを1セットと考えてください。
            5. **ImpactAnalysisToolのフル活用**: テーブル変更の影響調査では ImpactAnalysisTool を最優先で使い、得られたファイルリストに対して自動的に詳細な Select-String やファイル読み込みを行い、影響の全容（波及経路）を明らかにしてください。

            ツール選択・実行ルール:
            - GrepはまずGrepToolを試し、もしうまくいかなければ LocalCommandTool を使って PowerShell の Select-String コマンドで同様の検索を行ってください。
            - 検索や調査が必要な場合は LocalCommandTool を使ってください。
            - 前提となる環境はWindows 11で、PowerShellが利用可能です。
            - ツール実行後は結果を要約し、次のステップを具体的に決定してください。
            - **重要: 応答は常に JSON 形式のみで返してください。余計な説明文は一切不要です。**
            - **重要: ツールを呼び出す場合は、必ず `act` に具体的な思考を記述した上で、実際にメソッドを呼び出してください。**
              形式:
              {
                "status": "CONTINUE" | "FINAL",
                "plan": "最終目標達成までの全体計画",
                "act": "今まさに実行する具体的アクション（ツール呼び出し思考）",
                "observe": "これまでのツール実行結果の分析と事実",
                "decide": "前回の結果を受けて、なぜこの次のアクションが必要かという論理的根拠",
                "answer": "ユーザーへの最終回答（statusがFINALの場合のみ）"
              }
            - LocalCommandTool/FileWriterTool は実行前にユーザーの承認が必要なため、承認が得られるまでは `status: CONTINUE` で進めてください。
            - ユーザーが「はい」と言った後は、そのアクションの結果を `observe` に取り込み、迷わず次のステップ（別のファイルの調査など）へ進んでください。
            - テーブル変更時の影響調査では ImpactAnalysisTool を最優先で使ってください。
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
     * AI アシスタントを構築します。
     * 複雑なワークフロー処理とツール呼び出し用。
     *
     * @param model                   使用するチャットモデル
     * @param chatMemory              チャットと履歴を管理するメモリ
     * @param autoApproveLocalCommand true の場合、ローカルコマンドを自動承認モードにする
     * @return 構築済みのアシスタント
     */
    private static Assistant buildAssistant(OpenAiChatModel model, ChatMemory chatMemory,
            boolean autoApproveLocalCommand) {
        LocalCommandTool localCommandTool = new LocalCommandTool(autoApproveLocalCommand);
        return AiServices.builder(Assistant.class)
                .chatModel(model)
                .chatMemory(chatMemory)
                .tools(new Calculator(), new FileReaderTool(), new FileWriterTool(),
                        new ImpactAnalysisTool(), localCommandTool, new GrepTool(), new FileEditorTool())
                .build();
    }

    /**
     * AI アシスタントを構築します（インタラクティブモード用）。
     */
    private static Assistant buildAssistant(OpenAiChatModel model, ChatMemory chatMemory) {
        return buildAssistant(model, chatMemory, false);
    }

    /**
     * 判定専用の簡易 Assistant を構築します。
     * タスク/チャット判定のみの用途なので、ツールなし、メモリなしです。
     *
     * @param model 使用するチャットモデル
     * @return 構築済みの判定専用アシスタント
     */
    private static Assistant buildJudgmentAssistant(OpenAiChatModel model) {
        return AiServices.builder(Assistant.class)
                .chatModel(model)
                .build();
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
     * エージェントの次ステップ承認待ち状態を保持します。
     */
    private static PendingAgentExecution pendingAgentExecution = null;

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
    public static String aiLabel(boolean colorEnabled) {
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
     * @param text         入力テキスト
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
     * @param code     コード文字列
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
     * @param token    対象トークン
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
            char[] frames = { '|', '/', '-', '\\' };
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
     * @param label  スピナー表示ラベル
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
     * 最大ステップ数を環境変数から取得します。
     *
     * @return 1〜10 に正規化された最大ステップ数
     */
    private static int resolveAgentMaxSteps() {
        String raw = System.getenv().getOrDefault("CHAT_AGENT_MAX_STEPS", String.valueOf(DEFAULT_AGENT_MAX_STEPS));
        try {
            int parsed = Integer.parseInt(raw.strip());
            return Math.max(1, Math.min(parsed, 10));
        } catch (NumberFormatException e) {
            return DEFAULT_AGENT_MAX_STEPS;
        }
    }

    /**
     * ユーザー入力がタスク的（実行を求める）か、チャット的（会話）かを判定専用Assistantに判定させます。
     * 
     * @param judgmentAssistant 判定専用のシンプルなAssistant
     * @param userInput         ユーザー入力
     * @return タスク的な入力なら true、チャット的なら false
     */
    private static boolean isTaskInput(Assistant judgmentAssistant, String userInput) {
        try {
            String judgmentPrompt = """
                    タスク的な依頼か、普通のチャットかを判定してください。
                    タスク的 = コードを書く、プログラムを作る、分析する、実装する、など実行・作成を求める
                    チャット的 = こんにちは、天気は？、教えて、など単純な会話や質問
                    JSON形式で応答: {"type":"task"} または {"type":"chat"}
                    入力: """ + userInput;

            String response = judgmentAssistant.chat(judgmentPrompt);
            JsonNode node = mapper.readTree(response);
            String type = node.path("type").asText("chat").toLowerCase();
            return "task".equals(type);
        } catch (Exception e) {
            // JSONパース失敗時はチャットとして扱う（安全側）
            return false;
        }
    }

    /**
     * ワークフローなしで直接LLMに質問し、応答を返します。
     * 普通のチャット用途で使います。
     *
     * @param assistant AIアシスタント
     * @param message   ユーザーメッセージ
     * @return LLMの応答
     */
    private static String simpleChat(Assistant assistant, String message) {
        return assistant.chat(message);
    }

    /**
     * 現在ステップ用の計画プロンプトを組み立てます。
     * 計画・実行・観測を分けた出力を要求し、最後に継続/完了を明示させます。
     *
     * @param taskUserMessage ユーザ要求
     * @param maxSteps        最大ステップ数
     * @param step            現在ステップ
     * @param scratch         これまでの記録
     * @return モデルに渡すプロンプト
     */
    private static String buildStepPrompt(String taskUserMessage, int maxSteps, int step, String scratch) {
        return SYSTEM_PROMPT + "\n\n"
                + "## タスク処理ルール\n"
                + "以下のタスクを最大 " + maxSteps + " ステップで処理します。現在はステップ " + step + " です。\n"
                + "必ず指定された JSON 形式のみで回答してください。\n\n"
                + "## 出力 JSON 形式\n"
                + "{\n"
                + "  \"status\": \"CONTINUE\" | \"FINAL\",\n"
                + "  \"plan\": \"今回行う具体的な作業内容\",\n"
                + "  \"act\": \"実行内容（操作が必要な場合はここで必ずツールを呼び出すこと）\",\n"
                + "  \"observe\": \"前回の実行結果からの観察、または現状の分析\",\n"
                + "  \"decide\": \"なぜ継続/完了するかの判断根拠\",\n"
                + "  \"answer\": \"ユーザーへの最終回答（statusがFINALの場合のみ）\"\n"
                + "}\n\n"
                + "## コンテキスト\n"
                + "タスク: " + taskUserMessage + "\n"
                + "これまでの記録:\n"
                + (scratch.isBlank() ? "(なし)" : scratch) + "\n";
    }

    /**
     * モデル応答を JSON に基づいて解析します。
     *
     * @param response モデル応答
     * @return 解析結果
     */
    private static AgentStepResult parseStepResult(String response) {
        String safeResponse = response == null ? "" : response.strip();
        if (safeResponse.isEmpty()) {
            return new AgentStepResult(AgentStepDecision.FALLBACK, "", "");
        }

        try {
            // JSON部分を抽出（```json ... ``` も考慮）
            String jsonContent = safeResponse;
            if (safeResponse.contains("{")) {
                int start = safeResponse.indexOf("{");
                int end = safeResponse.lastIndexOf("}");
                if (end > start) {
                    jsonContent = safeResponse.substring(start, end + 1);
                }
            }

            JsonNode node = mapper.readTree(jsonContent);
            String status = node.path("status").asText("FALLBACK").toUpperCase(Locale.ROOT);
            String plan = node.path("plan").asText("");
            String act = node.path("act").asText("");
            String observe = node.path("observe").asText("");
            String decide = node.path("decide").asText("");
            String answer = node.path("answer").asText("");

            StringBuilder bodyBuilder = new StringBuilder();
            bodyBuilder.append("PLAN: ").append(plan).append("\n");
            bodyBuilder.append("ACT: ").append(act).append("\n");
            bodyBuilder.append("OBSERVE: ").append(observe).append("\n");
            bodyBuilder.append("DECIDE: ").append(decide).append("\n");
            if (!answer.isEmpty()) {
                bodyBuilder.append("ANSWER: ").append(answer).append("\n");
            }

            String body = bodyBuilder.toString();

            if ("FINAL".equals(status)) {
                return new AgentStepResult(AgentStepDecision.FINAL, answer.isEmpty() ? body : answer, safeResponse);
            } else if ("CONTINUE".equals(status)) {
                return new AgentStepResult(AgentStepDecision.CONTINUE, body, safeResponse);
            }
        } catch (Exception e) {
            // 解析失敗時はフォールバック（旧来の解析を試みる）
            if (safeResponse.regionMatches(true, 0, STEP_FINAL_PREFIX, 0, STEP_FINAL_PREFIX.length())) {
                return new AgentStepResult(AgentStepDecision.FINAL,
                        safeResponse.substring(STEP_FINAL_PREFIX.length()).strip(), safeResponse);
            }
            if (safeResponse.regionMatches(true, 0, STEP_CONTINUE_PREFIX, 0, STEP_CONTINUE_PREFIX.length())) {
                return new AgentStepResult(AgentStepDecision.CONTINUE,
                        safeResponse.substring(STEP_CONTINUE_PREFIX.length()).strip(), safeResponse);
            }
        }

        return new AgentStepResult(AgentStepDecision.FALLBACK, safeResponse, safeResponse);
    }

    /**
     * 最終化フェーズのプロンプトを組み立てます。
     *
     * @param scratch これまでのステップ記録
     * @return 最終化プロンプト
     */
    private static String buildFinalizePrompt(String scratch) {
        return SYSTEM_PROMPT + "\n\n"
                + "最大ステップに到達しました。これまでの記録を踏まえて最終回答だけを返してください。\n"
                + scratch;
    }

    /**
     * 次ステップ実行確認のメッセージを組み立てます。
     *
     * @param currentStep 直前に完了したステップ番号
     * @param maxSteps    最大ステップ数
     * @param body        直前ステップの本文
     * @return 確認メッセージ
     */
    private static String buildStepApprovalMessage(int currentStep, int maxSteps, String body) {
        return "ステップ " + currentStep + " を完了しました。\n"
                + body + "\n\n"
                + "次のステップ（" + (currentStep + 1) + " / " + maxSteps + "）を実行しますか？（はい/いいえ）";
    }

    /**
     * 承認待ちのエージェント継続実行があるかを判定します。
     *
     * @return 承認待ちがある場合 true
     */
    private static boolean hasPendingAgentExecution() {
        return pendingAgentExecution != null;
    }

    /**
     * 承認待ちのエージェント継続実行を破棄します。
     *
     * @return キャンセルメッセージ
     */
    private static String cancelPendingAgentExecution() {
        pendingAgentExecution = null;
        return "エージェントの次ステップ実行をキャンセルしました。";
    }

    /**
     * 承認済みの継続実行を再開します。
     *
     * @param assistant アシスタント
     * @return 再開後の応答
     */
    private static String continuePendingAgentExecution(Assistant assistant) {
        if (!hasPendingAgentExecution()) {
            return "ERROR: no pending agent execution";
        }
        PendingAgentExecution state = pendingAgentExecution;
        pendingAgentExecution = null;
        return runAgentStepLoopInternal(assistant, state.taskUserMessage(), state.scratch(), state.nextStep(),
                state.maxSteps());
    }

    /**
     * 1ターン内で最大 N ステップのエージェント思考ループを実行します。
     * 各ステップ完了時に次ステップの実行確認を挟みます。
     *
     * @param assistant       アシスタント
     * @param taskUserMessage ユーザ要求（またはツール結果を含む指示）
     * @return 最終応答または次ステップ確認メッセージ
     */
    private static String runAgentStepLoop(Assistant assistant, String taskUserMessage) {
        pendingAgentExecution = null;
        return runAgentStepLoopInternal(assistant, taskUserMessage, "", 1, resolveAgentMaxSteps());
    }

    /**
     * 指定状態からエージェント思考ループを実行します。
     *
     * @param assistant       アシスタント
     * @param taskUserMessage ユーザ要求
     * @param initialScratch  既存のステップ記録
     * @param startStep       開始ステップ
     * @param maxSteps        最大ステップ数
     * @return 最終応答または次ステップ確認メッセージ
     */
    private static String runAgentStepLoopInternal(Assistant assistant, String taskUserMessage,
            String initialScratch, int startStep, int maxSteps) {
        StringBuilder scratch = new StringBuilder(initialScratch == null ? "" : initialScratch);

        for (int step = startStep; step <= maxSteps; step++) {
            String prompt = buildStepPrompt(taskUserMessage, maxSteps, step, scratch.toString());
            AgentStepResult stepResult = parseStepResult(assistant.chat(prompt));

            if (stepResult.decision() == AgentStepDecision.FINAL) {
                pendingAgentExecution = null;
                return stepResult.body().isEmpty() ? "(no content)" : stepResult.body();
            }

            if (stepResult.decision() == AgentStepDecision.CONTINUE) {
                scratch.append("Step ").append(step).append(": ").append(stepResult.body()).append("\n");
                if (step < maxSteps) {
                    pendingAgentExecution = new PendingAgentExecution(taskUserMessage, scratch.toString(), step + 1,
                            maxSteps);
                    return buildStepApprovalMessage(step, maxSteps, stepResult.body());
                }
                break;
            }

            // モデルが接頭辞ルールを守らない場合は、そのまま最終応答として扱う
            pendingAgentExecution = null;
            return stepResult.rawResponse();
        }

        pendingAgentExecution = null;
        return assistant.chat(buildFinalizePrompt(scratch.toString()));
    }

    /**
     * 利用可能なスラッシュコマンドと説明を表示します。
     *
     * @param writer       出力先
     * @param colorEnabled カラー表示フラグ
     */
    private static void printHelpMessage(PrintWriter writer, boolean colorEnabled) {
        writer.println(aiLabel(colorEnabled) + " 利用可能なコマンド:");
        writer.println(aiLabel(colorEnabled) + "   exit: アプリケーションを終了します。");
        writer.println(aiLabel(colorEnabled) + "   /clear: 会話履歴と入力履歴をクリアします。");
        writer.println(aiLabel(colorEnabled) + "   /filesearch <ディレクトリ> <キーワード>: ファイル検索ワークフローを実行します。");
        writer.println(aiLabel(colorEnabled) + "   /plan <タスクの詳細>: 指定したタスクに対して自律的な計画実行を開始します。");
        writer.println(aiLabel(colorEnabled) + "   /help: このヘルプメッセージを表示します。");
        writer.flush();
    }

    /**
     * FileSearchWorkflow を実行します。
     *
     * @param model     OpenAiChatModel
     * @param userInput ユーザー入力
     * @return 処理結果
     */
    private static String runFileSearchWorkflow(OpenAiChatModel model, String userInput) {
        try {
            FileSearchWorkflow workflow = new FileSearchWorkflow(model);
            List<Path> found = workflow.findFiles(userInput);
            if (found == null || found.isEmpty()) {
                return "マッチするファイルが見つかりませんでした。";
            }
            // summarize the first found file
            return workflow.summarizeSelectedFile(found.get(0).toAbsolutePath().toString());
        } catch (Exception e) {
            return "エラー: ファイル検索ワークフロー実行中に問題が発生しました。\n" + e.getMessage();
        }
    }

    /**
     * 対話モードでチャットを行います。
     * 標準入力からユーザ入力を受け取り、Assistant を通じて応答を取得します。
     * チャット履歴は MessageWindowChatMemory によって管理されます。
     * <p>
     * 使用する主な環境変数:
     * - CHAT_MEMORY_WINDOW: 履歴ウィンドウのサイズ（既定 100）
     * - OPENAI_API_KEY: OpenAI API キー（必須）
     * - OPENAI_MODEL: 使用モデル名（既定 gpt-4o-mini）
     *
     * @throws Exception ランタイム例外やネットワーク例外を伝搬する可能性があります
     */
    public static void runChat() throws Exception {
        var model = buildModel();

        int window = Integer.parseInt(System.getenv().getOrDefault("CHAT_MEMORY_WINDOW", "100"));
        ChatMemory chatMemory = MessageWindowChatMemory.withMaxMessages(window);

        // ワークフロー用の本体 Assistant
        LocalCommandTool localCommandTool = new LocalCommandTool();
        Assistant workflowAssistant = AiServices.builder(Assistant.class)
                .chatModel(model)
                .chatMemory(chatMemory)
                .tools(new Calculator(), new FileReaderTool(), new FileWriterTool(),
                        new ImpactAnalysisTool(), localCommandTool, new GrepTool(), new FileEditorTool())
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
                    .variable(LineReader.SECONDARY_PROMPT_PATTERN, "> ")
                    .option(LineReader.Option.INSERT_TAB, true)
                    .build();

            PrintWriter writer = terminal.writer();
            writer.println("=== AI Assistant (Ctrl+D or 'exit' to quit) ===");
            writer.println("(入力方法: Enter で改行、Ctrl+D で送信。空の状態で Ctrl+D は終了)  コマンド一覧: /help");
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
                    // Ctrl+C: 入力キャンセル、次の入力へ
                    inputBuffer.setLength(0);
                    continue;
                } catch (EndOfFileException e) {
                    if (inputBuffer.length() == 0) {
                        // 空入力で Ctrl+D / EOF: 終了
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
                        FileSearchWorkflow workflow = new FileSearchWorkflow(model);
                        List<Path> foundFiles = withSpinner(writer, AGENT_WORKING_LABEL,
                                () -> workflow.findFiles(searchQuery));
                        
                        if (foundFiles.isEmpty()) {
                            writer.println(aiLabel(colorEnabled) + "マッチするファイルが見つかりませんでした。");
                            writer.flush();
                            continue;
                        }

                        // ここでスピナーは停止し、ファイル選択が始まる
                        writer.println(aiLabel(colorEnabled) + "\n[ステップ3] ユーザーによるファイル選択...");
                        Path selectedFile = workflow.selectFileInteractive(writer, lineReader, colorEnabled, foundFiles);

                        if (selectedFile == null) {
                            writer.println(aiLabel(colorEnabled) + "ファイルが選択されませんでした。");
                            writer.flush();
                            continue;
                        }

                        String summary = withSpinner(writer, AGENT_WORKING_LABEL,
                                () -> workflow.summarizeSelectedFile(selectedFile.toAbsolutePath().toString()));
                        
                        writer.println(aiLabel(colorEnabled));
                        writer.println(renderWithSyntaxHighlight(summary, colorEnabled));
                        writer.flush();
                        continue;
                    } catch (Exception e) {
                        writer.println("[ERROR] ファイル検索ワークフロー実行中に問題が発生しました。\n" + e.getMessage());
                        writer.flush();
                        continue;
                    }
                }

                // ヘルプコマンド
                if (HELP_COMMAND.equalsIgnoreCase(normalizedMessage)) {
                    printHelpMessage(writer, colorEnabled);
                    continue;
                }

                // ヘルプコマンド
                if (HELP_COMMAND.equalsIgnoreCase(normalizedMessage)) {
                    printHelpMessage(writer, colorEnabled);
                    continue;
                }

                final String messageToSend = normalizedMessage;

                try {
                    if (localCommandTool.hasPendingCommand()) {
                        if (isApproveInput(normalizedMessage)) {
                            String commandResult = withSpinner(writer, COMMAND_RUNNING_LABEL,
                                    localCommandTool::executePendingCommand);
                            String aiResponse = withSpinner(writer, AI_THINKING_LABEL,
                                    () -> runAgentStepLoop(workflowAssistant,
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

                    if (hasPendingAgentExecution()) {
                        if (isApproveInput(normalizedMessage)) {
                            String aiResponse = withSpinner(writer, AI_THINKING_LABEL,
                                    () -> continuePendingAgentExecution(workflowAssistant));
                            writer.println(aiLabel(colorEnabled));
                            writer.println(renderWithSyntaxHighlight(aiResponse, colorEnabled));
                            writer.flush();
                            continue;
                        }
                        if (isRejectInput(normalizedMessage)) {
                            String canceled = cancelPendingAgentExecution();
                            writer.println(aiLabel(colorEnabled));
                            writer.println(canceled);
                            writer.flush();
                            continue;
                        }
                        writer.println(aiLabel(colorEnabled));
                        writer.println("承認待ちのエージェント次ステップがあります。続行する場合は『はい』、中止する場合は『いいえ』と入力してください。");
                        writer.flush();
                        continue;
                    }

                    // 選択入力（番号 / all / none）のローカル処理
                    if (isSelectionInput(normalizedMessage)) {
                        String selectionResult = handleSelectionInput(normalizedMessage);
                        if (selectionResult != null) {
                            String aiResponse = withSpinner(writer, AI_THINKING_LABEL,
                                    () -> runAgentStepLoop(workflowAssistant, selectionResult));
                            writer.println(aiLabel(colorEnabled));
                            writer.println(renderWithSyntaxHighlight(aiResponse, colorEnabled));
                            writer.flush();
                            continue;
                        }
                    }

                    // /plan プレフィックスでワークフローを実行する。
                    if (normalizedMessage.startsWith("/plan")) {
                        String task = normalizedMessage.length() > 5 ? normalizedMessage.substring(5).strip() : "";
                        if (task.isBlank()) {
                            writer.println(aiLabel(colorEnabled));
                            writer.println("使用方法: /plan <タスクの詳細> 例: /plan Javaで素数を判定するプログラムを書いて");
                            writer.flush();
                            continue;
                        }
                        String aiResponse = withSpinner(writer, AI_THINKING_LABEL,
                                () -> runAgentStepLoop(workflowAssistant, task));
                        writer.println(aiLabel(colorEnabled));
                        writer.println(renderWithSyntaxHighlight(aiResponse, colorEnabled));
                        writer.flush();
                        continue;
                    }

                    // デフォルトは通常チャットとして処理
                    String aiResponse = withSpinner(writer, AI_THINKING_LABEL,
                            () -> simpleChat(workflowAssistant, messageToSend));
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
     * Single-shot message sending utility.
     * For non-interactive environments (CI, etc).
     *
     * @param message User message to send
     * @throws Exception Exceptions during sending may be propagated
     */
    public static void runChatOnce(String message) throws Exception {
        OpenAiChatModel model = buildModel();
        ChatMemory chatMemory = MessageWindowChatMemory
                .withMaxMessages(Integer.parseInt(System.getenv().getOrDefault("CHAT_MEMORY_WINDOW", "100")));
        Assistant assistant = buildAssistant(model, chatMemory);
        String aiResponse = runAgentStepLoop(assistant, message);
        System.out.println(renderWithSyntaxHighlight(aiResponse, isColorEnabled()));
    }

    /**
     * One-shot autonomous execution mode. Reads instructions from prompt file and
     * exits after output.
     * Completes processing autonomously without interaction.
     *
     * @param userMessage User instruction (from prompt file)
     * @throws Exception Runtime or network exceptions may be thrown
     */
    public static void runOneShot(String userMessage) throws Exception {
        OpenAiChatModel model = buildModel();
        int window = Integer.parseInt(System.getenv().getOrDefault("CHAT_MEMORY_WINDOW", "100"));
        ChatMemory chatMemory = MessageWindowChatMemory.withMaxMessages(window);
        Assistant assistant = buildAssistant(model, chatMemory, true);

        System.out.println("=== Processing Request ===");
        System.out.println(userMessage);
        System.out.println();
        System.out.flush();

        String result = runAgentStepLoop(assistant, userMessage);
        System.out.println("\n=== Result ===");
        System.out.println(renderWithSyntaxHighlight(result, isColorEnabled()));
        System.out.flush();
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
        // With no arguments or "chat" argument -> interactive mode
        // With prompt file path argument -> one-shot mode (reads file and executes
        // autonomously)
        switch (args.length) {
            case 0 -> runChat();
            case 1 -> {
                String arg = args[0].strip();
                if (CHAT_MODE_ARGUMENT.equalsIgnoreCase(arg)) {
                    runChat();
                } else {
                    // Treat as prompt file path
                    try {
                        String promptContent = new String(Files.readAllBytes(Paths.get(arg)));
                        // Enable auto-approve mode for autonomous execution
                        String autoApprove = System.getenv().getOrDefault("CHAT_AUTO_APPROVE", "true");
                        if (!Boolean.parseBoolean(autoApprove)) {
                            System.setProperty("CHAT_AUTO_APPROVE", "true");
                        }
                        runOneShot(promptContent);
                    } catch (Exception e) {
                        System.err.println("Failed to read prompt file: " + e.getMessage());
                    }
                }
            }
            default -> {
                // Multiple arguments: if first arg is "chat" or "agent", ignore it and run
                // interactive mode
                // This handles cases like "chat agent" (incorrect order but user-friendly error
                // handling)
                String firstArg = args[0].strip().toLowerCase();
                if ("chat".equalsIgnoreCase(firstArg) || "agent".equalsIgnoreCase(firstArg)) {
                    System.err.println("警告: 正しい引数順序は 'agent chat' です。ChatCLI 対話モードで起動します。");
                    runChat();
                } else {
                    System.err.println(UNKNOWN_ARGUMENT_MESSAGE);
                }
            }
        }
    }
}
