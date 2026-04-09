package org.example.cobol;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * COBOL/COPYファイル探索・インデックス化を担当するクラス。
 */
public class CobolFileFinder {
    private final String cobolDir;

    public CobolFileFinder(String cobolDir, String copyDir) {
        this.cobolDir = cobolDir;
        // copyDirは将来の拡張時に使用予定
    }

    /**
     * COBOLファイルを再帰的に探索
     */
    public List<Path> discoverCobolFiles() throws IOException {
        var targetExtensions = List.of(".cbl", ".cob", ".scob");
        var cobolFiles = new ArrayList<Path>();
        Path dir = Path.of(cobolDir);
        if (!dir.isAbsolute()) {
            dir = Path.of(System.getProperty("user.dir")).resolve(cobolDir);
        }
        if (!Files.exists(dir)) {
            throw new IOException("ディレクトリが見つかりません: " + dir.toAbsolutePath());
        }
        try (var paths = Files.walk(dir)) {
            paths.filter(p -> targetExtensions.stream().anyMatch(ext -> p.toString().endsWith(ext)))
                    .forEach(cobolFiles::add);
        }
        return cobolFiles;
    }

    /**
     * COPYブックのインデックス化
     */
    public Map<String, Path> indexCopybookFiles() throws IOException {
        // 仮実装: COBOL探索ディレクトリ配下のCOPYBOOK拡張子ファイルをすべて集める
        var copybookMap = new HashMap<String, Path>();
        Path dir = Path.of(cobolDir);
        if (!dir.isAbsolute()) {
            dir = Path.of(System.getProperty("user.dir")).resolve(cobolDir);
        }
        List<String> extensions = List.of(".cpy", ".copy", ".cbl", ".cob");
        if (!Files.exists(dir)) {
            return copybookMap;
        }
        try (var paths = Files.walk(dir)) {
            paths.filter(p -> extensions.stream().anyMatch(ext -> p.toString().endsWith(ext)))
                    .forEach(p -> copybookMap.put(p.getFileName().toString(), p));
        }
        return copybookMap;
    }
}
