package org.example.agents;

import dev.langchain4j.model.openai.OpenAiChatModel;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;

/**
 * ファイル検索・選択・要約ワークフロー。
 * <p>
 * 本クラスは以下の一連の処理を提供します：
 * 1. ユーザー入力から検索条件（ディレクトリ、キーワード）を抽出
 * 2. 指定ディレクトリ配下のファイルを検索
 * 3. 見つかったファイルをユーザーに提示し選択を受け付ける
 * 4. 選択ファイルを読み込み、LLM によって要約を生成する
 * <p>
 * 設計上の注意点：
 * - Intent 抽出は正規表現による直接パースを優先し、失敗時に LLM フォールバックするハイブリッド方式を採用しています。
 * - ファイル読み込みは UTF-8 を想定しています（必要に応じてデコーディングロジックを拡張してください）。
 */
public class FileSearchWorkflow {

    private final OpenAiChatModel model;
    private final Map<String, Object> workflowState = new HashMap<>();

    /**
     * ワークフロー状態を保持するコンテキスト。
     */
    private static class WorkflowContext {
        String directory;
        String keyword;
        List<String> foundFiles;
        String selectedFile;
        String fileContent;
        String summary;
    }

    /**
     * コンストラクタ。
     *
     * @param model OpenAiChatModel インスタンス
     */
    public FileSearchWorkflow(OpenAiChatModel model) {
        this.model = model;
    }

    /**
     * ユーザーの自然言語入力からディレクトリとキーワードを抽出します。
     *
     * @param userInput ユーザーの入力テキスト
     * @return 抽出されたパラメータを含むマップ（"directory", "keyword"キーを持つ）
     */
    public Map<String, String> extractIntents(String userInput) {
        // 新しいエージェントを使用（正規表現パース＋LLMフォールバック）
        FileSearchIntentExtractor extractor = FileSearchIntentExtractor.create(model);
        FileSearchIntentExtractor.FileSearchIntent intent = extractor.extract(userInput);
        Map<String, String> result = new HashMap<>();
        if (intent != null) {
            result.put("directory", intent.directory());
            result.put("keyword", intent.keyword());
            System.out.println("[DEBUG] 抽出されたディレクトリ: " + intent.directory());
            System.out.println("[DEBUG] 抽出されたキーワード: " + intent.keyword());
        }
        return result;
    }

    /**
     * 指定されたディレクトリからキーワードを含むファイルを検索します。
     *
     * @param directory ディレクトリパス
     * @param keyword   検索キーワード
     * @return マッチしたファイルパスのリスト
     */
    public List<String> searchFiles(String directory, String keyword) {
        FileExtractor extractor = new FileExtractor();
        return extractor.search(directory, keyword);
    }

    /**
     * 見つかったファイルをユーザーに提示し、選択を促します。
     *
     * @param foundFiles 見つかったファイルのリスト
     * @return ユーザーが選択したファイルパス
     */
    public String selectFileInteractive(List<String> foundFiles) {
        if (foundFiles.isEmpty()) {
            System.out.println("マッチするファイルが見つかりませんでした。");
            return null;
        }

        System.out.println("\n========================================");
        System.out.println("見つかったファイル:");
        System.out.println("========================================");
        for (int i = 0; i < foundFiles.size(); i++) {
            System.out.printf("%d. %s%n", i + 1, foundFiles.get(i));
        }
        System.out.println("========================================");

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
            while (true) {
                System.out.print("要約したいファイルの番号を入力してください (1-" + foundFiles.size() + "): ");
                String input = reader.readLine();

                try {
                    int index = Integer.parseInt(input);
                    if (index >= 1 && index <= foundFiles.size()) {
                        return foundFiles.get(index - 1);
                    } else {
                        System.out.println("無効な番号です。もう一度入力してください。");
                    }
                } catch (NumberFormatException e) {
                    System.out.println("数値を入力してください。");
                }
            }
        } catch (IOException e) {
            System.err.println("入力エラー: " + e.getMessage());
            return null;
        }
    }

    /**
     * 指定されたファイルの内容を読み込みます。
     *
     * @param filePath ファイルパス
     * @return ファイルの内容
     */
    public String readFileContent(String filePath) {
        try {
            return Files.readString(Paths.get(filePath), StandardCharsets.UTF_8);
        } catch (IOException e) {
            System.err.println("ファイル読み込みエラー: " + e.getMessage());
            return null;
        }
    }

    /**
     * ファイルの内容をAIで要約します。
     *
     * @param fileContent ファイルの内容
     * @return 要約テキスト
     */
    public String summarizeContent(String fileContent) {
        // 新しいエージェントを使用
        FileSummarizer summarizer = FileSummarizer.create(model);
        return summarizer.summarize(fileContent);
    }

    /**
     * 全体のワークフローを実行します。
     *
     * @param userInput ユーザーの自然言語入力
     * @return 要約結果
     */
    public String executeWorkflow(String userInput) {
        System.out.println("\n[ステップ1] ユーザーの入力を解析中...");
        Map<String, String> intents = extractIntents(userInput);

        String directory = intents.get("directory");
        String keyword = intents.get("keyword");

        if (directory == null || keyword == null) {
            return "ディレクトリまたはキーワードを抽出できませんでした。入力形式を確認してください。";
        }

        System.out.println("  抽出されたディレクトリ: " + directory);
        System.out.println("  抽出されたキーワード: " + keyword);

        System.out.println("\n[ステップ2] ファイルを検索中...");
        List<String> foundFiles = searchFiles(directory, keyword);
        System.out.println("  見つかったファイル数: " + foundFiles.size());

        if (foundFiles.isEmpty()) {
            return "マッチするファイルが見つかりませんでした。";
        }

        System.out.println("\n[ステップ3] ユーザーによるファイル選択...");
        String selectedFile = selectFileInteractive(foundFiles);

        if (selectedFile == null) {
            return "ファイルが選択されませんでした。";
        }

        System.out.println("  選択されたファイル: " + selectedFile);

        System.out.println("\n[ステップ4] ファイルを読み込み中...");
        String fileContent = readFileContent(selectedFile);

        if (fileContent == null) {
            return "ファイルの読み込みに失敗しました。";
        }

        System.out.println("  ファイルサイズ: " + fileContent.length() + " 文字");

        System.out.println("\n[ステップ5] AIで要約中...");
        String summary = summarizeContent(fileContent);

        System.out.println("\n========================================");
        System.out.println("要約結果:");
        System.out.println("========================================");
        System.out.println(summary);
        System.out.println("========================================\n");

        return summary;
    }

    /**
     * Intent 解析結果を JSON から解析します。
     *
     * @param jsonResult JSON 形式の抽出結果
     * @return パラメータマップ
     */
    private Map<String, String> parseIntentResult(String jsonResult) {
        Map<String, String> result = new HashMap<>();

        try {
            // LLMの応答から JSON 部分を抽出（テキストが含まれる場合への対応）
            String cleanedJson = extractJsonFromResponse(jsonResult);
            System.out.println("[DEBUG] クリーンアップ後のJSON: " + cleanedJson);
            
            // シンプルな JSON パースロジック
            String directory = extractJsonValue(cleanedJson, "directory");
            String keyword = extractJsonValue(cleanedJson, "keyword");

            System.out.println("[DEBUG] 抽出されたディレクトリ: " + directory);
            System.out.println("[DEBUG] 抽出されたキーワード: " + keyword);

            if (directory != null) {
                result.put("directory", directory);
            }
            if (keyword != null) {
                result.put("keyword", keyword);
            }
        } catch (Exception e) {
            System.err.println("Intent 解析エラー: " + e.getMessage());
            e.printStackTrace();
        }

        return result;
    }

    /**
     * LLMの応答からJSON部分を抽出します。
     * テキストが混在している場合、最初に見つかった {...} を抽出します。
     *
     * @param response LLMからの応答
     * @return JSON文字列
     */
    private String extractJsonFromResponse(String response) {
        // {...} で囲まれた最初のJSON部分を抽出
        int startIdx = response.indexOf('{');
        int endIdx = response.lastIndexOf('}');
        
        if (startIdx >= 0 && endIdx > startIdx) {
            return response.substring(startIdx, endIdx + 1);
        }
        
        // JSON が見つからない場合は元の文字列を返す
        return response;
    }

    /**
     * JSON 文字列から指定されたキーの値を抽出します。
     *
     * @param json JSON 文字列
     * @param key  抽出するキー
     * @return キーに対応する値（存在しない場合は null）
     */
    private String extractJsonValue(String json, String key) {
        String pattern = "\"" + key + "\"\\s*:\\s*\"([^\"]*)\"";
        java.util.regex.Pattern p = java.util.regex.Pattern.compile(pattern);
        java.util.regex.Matcher m = p.matcher(json);
        if (m.find()) {
            return m.group(1);
        }
        return null;
    }
}
