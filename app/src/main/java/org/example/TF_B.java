package org.example;

/**
 * TF_C を呼び出す中継役のサンプルクラスです。
 */
public class TF_B {

    /**
     * TF_C の処理を呼び出した後、自身の実行ログを出力します。
     */
    public void run() {
        // Simulate call to TF_C
        // TF_C.java
        TF_C c = new TF_C();
        c.run();
        System.out.println("TF_B running");
    }
}
