package org.example.cobol;

/**
 * 対象カラムへ到達するCOPY依存関係の情報。
 */
public record CopyColumnDependency(String copybookName, int depth, String viaCopybook, String location) {
    /**
     * 依存関係の一意キーを返します。
     * @return 一意キー
     */
    public String uniqueKey() {
        return copybookName + "|" + depth + "|" + (viaCopybook == null ? "" : viaCopybook)
                + "|" + location;
    }
}
