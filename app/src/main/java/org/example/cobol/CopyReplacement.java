package org.example.cobol;

import java.util.regex.Pattern;

/**
 * COPY REPLACING の置換定義。
 */
public record CopyReplacement(String sourceText, String targetText, boolean wholeWord) {
    /**
     * 文字列へ置換を適用します。
     * @param content 対象文字列
     * @return 置換後文字列
     */
    public String apply(String content) {
        var pattern = wholeWord
                ? Pattern.compile("(?i)(?<![A-Za-z0-9_-])" + Pattern.quote(sourceText)
                        + "(?![A-Za-z0-9_-])")
                : Pattern.compile("(?i)" + Pattern.quote(sourceText));
        return pattern.matcher(content).replaceAll(java.util.regex.Matcher.quoteReplacement(targetText));
    }
    /**
     * 置換定義の署名を返します。
     * @return 署名文字列
     */
    public String signature() {
        return sourceText + "->" + targetText + ":" + wholeWord;
    }
}
