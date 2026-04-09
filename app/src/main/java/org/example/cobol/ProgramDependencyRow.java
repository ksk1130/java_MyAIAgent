package org.example.cobol;

/**
 * CALL / COPY 依存関係表示用の1行分データ。
 */
public record ProgramDependencyRow(String programName, String filePath, String dependencyKind,
        String dependencyTarget, String dependencyType, String location) {
}
