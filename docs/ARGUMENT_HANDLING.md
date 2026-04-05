# 引数処理フローと互換性ガイド

## 引数処理の階層

```
run.bat [arguments...]
    ↓
org.example.App.main(args)
    ↓
    ├─ args[0] == "agent" ?
    │  ├─ YES → org.example.AgentChatCLI.main(args[1:])
    │  │  └─ /filesearch コマンド利用可能
    │  └─ NO  → org.example.ChatCLI.main(args)
    │
    └─ その他 → ChatCLI で処理
```

## 正しい引数順序

### ChatCLI（従来版）

| コマンド | 説明 | 引数処理 |
|---------|------|--------|
| `.\run.bat` | デフォルト対話モード | args = [] → ChatCLI.runChat() |
| `.\run.bat chat` | 明示的に対話モード | args = ["chat"] → ChatCLI.runChat() |
| `.\run.bat "メッセージ"` | 単発メッセージ | args = ["メッセージ"] → ChatCLI.runOneShot() |

### AgentChatCLI（Agent対応版）

| コマンド | 説明 | 引数処理 |
|---------|------|--------|
| `.\run.bat agent` | Agent対話モード | args = ["agent"] → App分岐 → AgentChatCLI.runChat() |
| `.\run.bat agent chat` | Agent対話モード（明示的） | args = ["agent", "chat"] → App分岐 → AgentChatCLI.runChat() |
| `.\run.bat agent "メッセージ"` | Agent単発メッセージ | args = ["agent", "メッセージ"] → App分岐 → AgentChatCLI.runChatOnce() |

## よくある誤り と改善

### ❌ 誤った引数順序

```powershell
.\run.bat chat agent
```

**何が起こるか：**
1. App.main(["chat", "agent"]) が呼ばれる
2. args[0] = "chat" (≠ "agent") なので ChatCLI に渡される
3. ChatCLI.main(["chat", "agent"]) が呼ばれる
4. **以前:** "不明な引数です" エラー → 失敗
5. **現在:** 警告メッセージを表示して対話モード起動 → ChatCLI が起動

**改善:** ChatCLI の引数処理が改善され、複数引数でも最初の引数が "chat" または "agent" なら無視して対話モードを実行するようになりました。

```java
default -> {
    // Multiple arguments: if first arg is "chat" or "agent", ignore it and run interactive mode
    String firstArg = args[0].strip().toLowerCase();
    if ("chat".equalsIgnoreCase(firstArg) || "agent".equalsIgnoreCase(firstArg)) {
        System.err.println("警告: 正しい引数順序は 'agent chat' です。ChatCLI 対話モードで起動します。");
        runChat();
    } else {
        System.err.println(UNKNOWN_ARGUMENT_MESSAGE);
    }
}
```

### ✅ 正しい引数順序

```powershell
.\run.bat agent chat
```

**何が起こるか：**
1. App.main(["agent", "chat"]) が呼ばれる
2. args[0] = "agent" なので AgentChatCLI に渡される
3. AgentChatCLI.main(["chat"]) が呼ばれる（最初の "agent" は削除済み）
4. args[0] = "chat" なので AgentChatCLI.runChat() が実行される
5. Agent対応の対話モードが起動 → `/filesearch` コマンド利用可能

## App.java のルーティングロジック

```java
public static void main(String[] args) throws Exception {
    if (args.length > 0 && "agent".equalsIgnoreCase(args[0])) {
        // Agent 対応版を使用
        String[] agentArgs = new String[args.length - 1];
        System.arraycopy(args, 1, agentArgs, 0, args.length - 1);
        AgentChatCLI.main(agentArgs);
    } else {
        // 従来版を使用（デフォルト）
        ChatCLI.main(args);
    }
}
```

**重要:** 最初の "agent" 引数のみを削除し、残りを AgentChatCLI に渡します。

## 引数の優先度（推奨順）

### ChatCLI での引数解釈

1. **引数なし** → 対話モード
2. **"chat"** → 対話モード
3. **"chat" または "agent" + 他の引数** → 警告表示後、対話モード（互換性向上）
4. **ファイルパス** → ファイル内容を単発メッセージとして実行
5. **その他** → エラー

### AgentChatCLI での引数解釈

1. **引数なし** → 対話モード
2. **"chat"** → 対話モード
3. **その他（1つ以上）** → 空白区切りで連結して単発メッセージモード

## 実行例

### パターンA: ChatCLI デフォルト

```powershell
# すべて同じ動作（対話モード）
.\run.bat
.\run.bat chat

# エラーの場合も対話モード（互換性）
.\run.bat chat agent  # 警告を表示して対話モード起動
```

### パターンB: AgentCLI 対話モード

```powershell
# 正しい順序
.\run.bat agent
.\run.bat agent chat

# エラー: 順序が逆
.\run.bat chat agent  # ChatCLI の対話モードで起動（Agent機能なし）
```

### パターンC: 単発メッセージ

```powershell
# ChatCLI
.\run.bat "このコードを解析してください"

# AgentCLI
.\run.bat agent "このコードを解析してください"
```

## トラブルシューティング

### Q: `.\run.bat chat agent` で Agent 機能が利用できない
**A:** 引数順序が逆です。`.\run.bat agent chat` を使用してください。
   - 現在のコマンドは ChatCLI（従来版）で起動されます
   - Agent 対応版を使うには、最初の引数が `agent` である必要があります

### Q: Agent 対話モードでも `/filesearch` コマンドが見つからない
**A:** 正しく `.\run.bat agent chat` で起動されているか確認してください。
   - ChatCLI では `/filesearch` はサポートされていません
   - `agent` 引数が最初にないと AgentChatCLI が起動されません

### Q: ファイルパスを引数に渡したい
**A:** ファイルパスをクォートで囲んでください。
   ```powershell
   .\run.bat "C:\path\to\prompt.txt"
   .\run.bat agent "C:\path\to\message.txt"  # ファイル内容が単発メッセージになる
   ```

## 関連ドキュメント

- [README.md](../README.md) - 全体的な使用方法
- [PORTABLE_PACKAGE.md](PORTABLE_PACKAGE.md) - ポータブルパッケージの実行方法
- [AgentChatCLI.md](AgentChatCLI.md) - Agent 機能の詳細
