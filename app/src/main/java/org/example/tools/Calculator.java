package org.example.tools;

import dev.langchain4j.agent.tool.P;
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
    @Tool("2つの整数を加算します")
    public double add(@P("第1引数") int a, @P("第2引数") int b) {
        System.out.println("Calculatorツールを実行します: add(a=" + a + ", b=" + b + ")");
        System.out.flush();
        return a + b;
    }

    /**
     * 平方根を計算します。
     *
     * @param x 対象の数値
     * @return 平方根
     */
    @Tool("平方根を計算します")
    public double squareRoot(@P("対象の数値") double x) {
        System.out.println("Calculatorツールを実行します: squareRoot(x=" + x + ")");
        System.out.flush();
        return Math.sqrt(x);
    }
}
