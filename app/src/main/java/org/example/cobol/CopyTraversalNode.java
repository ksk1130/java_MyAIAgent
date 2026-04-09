package org.example.cobol;

/**
 * COPY探索用のキュー要素。
 */
public record CopyTraversalNode(CopyStatement statement, int depth, String viaCopybook, String location) {
    /**
     * キュー要素の再訪問防止キーを返します。
     * @return 一意キー
     */
    public String stateKey() {
        var builder = new StringBuilder(statement.copybookName());
        for (var replacement : statement.replacements()) {
            builder.append('|').append(replacement.signature());
        }
        builder.append('|').append(depth);
        builder.append('|').append(viaCopybook == null ? "" : viaCopybook);
        return builder.toString();
    }
}
