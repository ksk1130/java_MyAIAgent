package org.example.cobol;

/**
 * COPY解析用の論理行情報。
 */
public record LogicalLine(int lineNumber, String text) {
}
