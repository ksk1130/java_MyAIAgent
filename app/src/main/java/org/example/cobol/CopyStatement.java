package org.example.cobol;

import java.util.List;

/**
 * COPY文の解析結果。
 */
public record CopyStatement(String copybookName, List<CopyReplacement> replacements, int lineNumber) {
}
