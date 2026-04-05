package org.example.agents;

import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

/**
 * ファイル検索Intent抽出エージェント。
 * <p>
 * ユーザーの自然言語入力またはコマンド形式（/filesearch）から
 * 検索対象のディレクトリとキーワードを抽出して構造化された結果を返します。
 * <p>
 * 実装はまずコマンド形式を直接パースし、失敗した場合に LLM（AiServices）による構造化抽出をフォールバックします。
 */
public class FileSearchIntentExtractor {

    /**
     * 抽出結果の構造化出力。
     *
     * @param directory 検索対象ディレクトリ
     * @param keyword 検索キーワード
     */
    public record FileSearchIntent(String directory, String keyword) {
    }

    interface StructuredExtractor {
        @SystemMessage("あなたはユーザー入力からディレクトリとキーワードを抽出する専門家です。必ずJSON形式のみを、かつ戻り値の型に合うように返してください。余計な説明は一切しないでください。")
        @UserMessage("""
                以下のユーザー入力から、ファイル検索のディレクトリとキーワードを抽出してください。
                返答はこのメソッドの戻り値型に従ってください。
                ユーザー入力: {{userInput}}
                """)
        FileSearchIntent extract(@V("userInput") String userInput);
    }

    private final StructuredExtractor extractor;

    /**
     * コンストラクタ。
     *
     * @param model OpenAIチャットモデル
     */
    public FileSearchIntentExtractor(OpenAiChatModel model) {
        this.extractor = AiServices.builder(StructuredExtractor.class)
                .chatModel(model)
                .build();
    }

    /**
     * ユーザー入力から directory/keyword を抽出します。
     * まず /filesearch 形式を直接パースし、失敗時のみ LLM を使います。
     *
     * @param userInput ユーザー入力
     * @return 抽出結果
     */
    public FileSearchIntent extract(String userInput) {
        FileSearchIntent parsed = parseDirectCommand(userInput);
        if (parsed != null) {
            return parsed;
        }
        return extractor.extract(userInput);
    }

    private FileSearchIntent parseDirectCommand(String userInput) {
        if (userInput == null) {
            return null;
        }
        String trimmed = userInput.trim();
        if (!trimmed.toLowerCase().startsWith("/filesearch ")) {
            return null;
        }

        String rest = trimmed.substring("/filesearch ".length()).trim();
        System.out.println("[DEBUG parseDirectCommand] rest='" + rest + "'");
        if (rest.isEmpty()) {
            return null;
        }

        String directory = null;
        String keyword = null;

        java.util.List<String> tokens = new java.util.ArrayList<>();
        int i = 0;
        int n = rest.length();
        while (i < n) {
            while (i < n && Character.isWhitespace(rest.charAt(i))) i++;
            if (i >= n) break;
            if (rest.charAt(i) == '"') {
                int j = i + 1;
                StringBuilder sb = new StringBuilder();
                while (j < n) {
                    char c = rest.charAt(j);
                    if (c == '\\' && j + 1 < n) {
                        sb.append(rest.charAt(j + 1));
                        j += 2;
                        continue;
                    }
                    if (c == '"') break;
                    sb.append(c);
                    j++;
                }
                if (j >= n) return null; // unclosed quote
                tokens.add(sb.toString());
                i = j + 1;
            } else {
                int j = i;
                while (j < n && !Character.isWhitespace(rest.charAt(j))) j++;
                tokens.add(rest.substring(i, j));
                i = j;
            }
        }

        if (tokens.size() < 2) return null;
        directory = tokens.get(0);
        keyword = String.join(" ", tokens.subList(1, tokens.size()));

        System.out.println("[DEBUG parseDirectCommand] directory='" + directory + "' keyword='" + keyword + "'");

        if (directory == null || directory.isEmpty() || keyword == null || keyword.isEmpty()) {
            return null;
        }

        return new FileSearchIntent(directory, keyword);
    }

    /**
     * デフォルト実装ファクトリ。
     *
     * @param model OpenAIチャットモデル
     * @return 抽出エージェント
     */
    public static FileSearchIntentExtractor create(OpenAiChatModel model) {
        return new FileSearchIntentExtractor(model);
    }
}

