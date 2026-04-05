package org.example.agents;

import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;
import dev.langchain4j.model.openai.OpenAiChatModel;

/**
 * ファイル内容要約エージェント。
 * <p>
 * 指定されたファイル本文を受け取り、LLM を用いて日本語で簡潔に要約を生成します。
 * 要約は本文の内容のみを根拠とし、余計な説明や推測を含めないよう指示しています。
 */
public interface FileSummarizer {

    /**
     * ファイル内容を要約します。
     *
     * @param fileContent 要約対象のファイル内容（最大トークン数に注意）
     * @return 要約テキスト（日本語）
     */
    @SystemMessage("あなたは与えられたファイル本文を要約するアシスタントです。入力本文のみを根拠に日本語で要約してください。")
    @UserMessage("""
        以下のファイル内容を日本語で簡潔に要約してください。
        最大3段落程度にまとめてください。
         
        ファイル内容:
        ```
        {{fileContent}}
        ```
        """)
    String summarize(@V("fileContent") String fileContent);

    /**
     * デフォルト実装を提供するスタティックファクトリメソッド。
     * 
     * @param model ChatLanguageModel インスタンス
     * @return FileSummarizer の実装
     */
    static FileSummarizer create(OpenAiChatModel model) {
        return AiServices.builder(FileSummarizer.class)
                .chatModel(model)
                .build();
    }
}
