package org.example;

/**
 * アプリケーションの最小エントリポイントです。
 * ChatCLI（従来版）と AgentChatCLI（Agent対応版）を選択可能にします。
 */
public class App {

    /**
     * 挨拶メッセージを返します。
     *
     * @return 表示用の挨拶文字列
     */
    public String getGreeting() {
        return "Hello World!";
    }

    /**
     * アプリケーションを起動します。
     * 引数: agent chat → AgentChatCLI の対話モード
     *       agent message → AgentChatCLI の単発モード
     *       chat → ChatCLI の対話モード
     *       message → ChatCLI の単発モード
     *
     * @param args コマンドライン引数
     * @throws Exception 実行時例外
     */
    public static void main(String[] args) throws Exception {
        if (args.length > 0 && "agent".equalsIgnoreCase(args[0])) {
            // Agent 対応版を使用
            String[] agentArgs = new String[args.length - 1];
            System.arraycopy(args, 1, agentArgs, 0, args.length - 1);
            AgentChatCLI.main(agentArgs);
        } else {
            // 従来版を使用（デフォルト）
            ChatCLI.main(args);
        }
    }
}

