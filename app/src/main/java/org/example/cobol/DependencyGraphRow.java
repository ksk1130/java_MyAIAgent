package org.example.cobol;

/**
 * 依存関係グラフ表示用の1行分データ。
 */
public record DependencyGraphRow(String programName, String filePath, String dependencyType, String location) {
}
