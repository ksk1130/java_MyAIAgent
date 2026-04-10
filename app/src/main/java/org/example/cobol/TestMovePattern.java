package org.example.cobol;

import java.util.regex.Pattern;

/**
 * MOVE パターンのマッチングをテスト
 */
public class TestMovePattern {
    
    public static void main(String[] args) {
        String line = "                MOVE F1ZIPCODE TO ZIPCODE";
        String upperLine = line.toUpperCase();
        String variableName = "ZIPCODE";
        String escapedVariable = Pattern.quote(variableName.toUpperCase());
        
        System.out.println("=== MOVE パターンマッチングテスト ===\n");
        System.out.println("元の行: [" + line + "]");
        System.out.println("大文字化: [" + upperLine + "]");
        System.out.println("変数名: " + variableName);
        System.out.println("エスケープ済み: " + escapedVariable);
        System.out.println();
        
        // MOVE TO パターン
        String pattern = "\\bMOVE\\b.*\\bTO\\s+:?" + escapedVariable + "\\b";
        System.out.println("パターン: " + pattern);
        
        boolean matches = Pattern.compile(pattern).matcher(upperLine).find();
        System.out.println("マッチ結果: " + matches);
        
        if (matches) {
            System.out.println("✅ 検出されました！");
        } else {
            System.out.println("❌ 検出されませんでした");
            
            // デバッグ: 部分的にテスト
            System.out.println("\n=== デバッグ ===");
            System.out.println("MOVE を含む: " + upperLine.contains("MOVE"));
            System.out.println("TO を含む: " + upperLine.contains("TO"));
            System.out.println("ZIPCODE を含む: " + upperLine.contains("ZIPCODE"));
            
            // 簡略化したパターンでテスト
            System.out.println("\n簡略化パターンテスト:");
            System.out.println("  MOVE.*TO: " + Pattern.compile("MOVE.*TO").matcher(upperLine).find());
            System.out.println("  TO.*ZIPCODE: " + Pattern.compile("TO.*ZIPCODE").matcher(upperLine).find());
            System.out.println("  \\bTO\\s+ZIPCODE\\b: " + Pattern.compile("\\bTO\\s+ZIPCODE\\b").matcher(upperLine).find());
        }
    }
}
