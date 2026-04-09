package org.example.cobol;

/**
 * COPY依存関係の登録情報。
 */
public record CopyDependencyInfo(int depth, String viaCopybook, String location) {
}
