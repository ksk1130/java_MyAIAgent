package org.example.tools;

import dev.langchain4j.agent.tool.Tool;

/**
 * シンプルなツールクラス。AiServices に tools(...) として渡し、LLM から呼び出せるメソッドを定義する。
 */
public class Calculator {

    @Tool
    public double add(int a, int b) {
        return a + b;
    }

    @Tool
    public double squareRoot(double x) {
        return Math.sqrt(x);
    }
}
