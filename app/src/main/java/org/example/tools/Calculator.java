package org.example.tools;

import dev.langchain4j.agent.tool.Tool;

/**
 * シンプルな数値計算ツールです。
 * AiServices に tools(...) として渡し、LLM から呼び出せるメソッドを定義します。
 */
public class Calculator {

    /**
     * 2 つの整数を加算します。
     *
     * @param a 第1引数
     * @param b 第2引数
     * @return 加算結果
     */
    @Tool
    public double add(int a, int b) {
        System.out.println("Calculatorツールを実行します");
        System.out.flush();
        return a + b;
    }

    /**
     * 平方根を計算します。
     *
     * @param x 対象の数値
     * @return 平方根
     */
    @Tool
    public double squareRoot(double x) {
        System.out.println("Calculatorツールを実行します");
        System.out.flush();
        return Math.sqrt(x);
    }
}
