package org.example;

/**
 * アプリケーションの最小エントリポイントです。
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
     * アプリケーションを起動し、挨拶メッセージを標準出力へ表示します。
     *
     * @param args コマンドライン引数
     * @throws Exception 実行時例外
     */
    public static void main(String[] args) throws Exception {
        System.out.println(new App().getGreeting());
    }
}
