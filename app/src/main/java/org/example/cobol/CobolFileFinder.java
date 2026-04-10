package org.example.cobol;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * COBOL/COPYファイル探索・インデックス化を担当するクラス。
 */
public class CobolFileFinder {
    private final String cobolDir;
    private final String copyDir;

    public CobolFileFinder(String cobolDir, String copyDir) {
        this.cobolDir = cobolDir;
        this.copyDir = copyDir;
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
        var copybookMap = new HashMap<String, Path>();
        List<String> extensions = List.of(".cpy", ".copy", ".cbl", ".cob");

        Path explicitCopyDir = resolveDirectory(copyDir);
        if (explicitCopyDir != null && Files.exists(explicitCopyDir)) {
            indexCopybooksUnder(copybookMap, explicitCopyDir, extensions);
        }

        Path cobolRootDir = resolveDirectory(cobolDir);
        Path siblingCopyDir = cobolRootDir != null && cobolRootDir.getParent() != null
                ? cobolRootDir.getParent().resolve("copy")
                : null;
        if (siblingCopyDir != null && Files.exists(siblingCopyDir)) {
            indexCopybooksUnder(copybookMap, siblingCopyDir, extensions);
        }
        if (cobolRootDir != null && Files.exists(cobolRootDir)) {
            indexCopybooksUnder(copybookMap, cobolRootDir, extensions);
        }

        return copybookMap;
    }

    /**
     * 指定ディレクトリ配下の COPY ブックをインデックス化します。
     * 既に登録済みの同名ファイルがある場合は上書きしません。
     *
     * @param copybookMap インデックス先
     * @param dir 対象ディレクトリ
     * @param extensions 対象拡張子
     * @throws IOException ファイル走査に失敗した場合
     */
    private void indexCopybooksUnder(Map<String, Path> copybookMap, Path dir, List<String> extensions) throws IOException {
        try (var paths = Files.walk(dir)) {
            paths.filter(p -> extensions.stream().anyMatch(ext -> p.toString().endsWith(ext)))
                    .forEach(p -> registerCopybookPath(copybookMap, p));
        }
    }

    /**
     * COPY ブック索引へファイル名と拡張子なし名の両方を登録します。
     *
     * @param copybookMap 索引先
     * @param path 対象パス
     */
    private void registerCopybookPath(Map<String, Path> copybookMap, Path path) {
        String fileName = path.getFileName().toString();
        copybookMap.putIfAbsent(fileName, path);
        copybookMap.putIfAbsent(fileName.toUpperCase(Locale.ROOT), path);
        copybookMap.putIfAbsent(fileName.toLowerCase(Locale.ROOT), path);

        int extensionIndex = fileName.lastIndexOf('.');
        if (extensionIndex > 0) {
            String baseName = fileName.substring(0, extensionIndex);
            copybookMap.putIfAbsent(baseName, path);
            copybookMap.putIfAbsent(baseName.toUpperCase(Locale.ROOT), path);
            copybookMap.putIfAbsent(baseName.toLowerCase(Locale.ROOT), path);
        }
    }

    /**
     * 相対/絶対指定を吸収してディレクトリパスを解決します。
     *
     * @param dirPath ディレクトリ文字列
     * @return 解決後のパス。未指定の場合は null
     */
    private Path resolveDirectory(String dirPath) {
        if (dirPath == null || dirPath.isBlank()) {
            return null;
        }

        Path dir = Path.of(dirPath);
        if (!dir.isAbsolute()) {
            dir = Path.of(System.getProperty("user.dir")).resolve(dirPath);
        }
        return dir;
    }
}
