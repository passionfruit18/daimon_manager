package com.daimon.core;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Map;
import java.util.Objects;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * The content of a DAIMon workspace at a point in time: a sorted map of relative paths
 * ({@code /}-separated) to UTF-8 text, plus its content-addressed tree hash.
 *
 * <p>Hidden files and directories (any path segment starting with {@code .}) are excluded, so a
 * workspace can hold local-only state such as {@code .daimon/} or a nested {@code .git/}.
 */
public record WorkspaceSnapshot(SortedMap<String, String> files, String treeHash) {

    /** Per-file size cap. Workspaces hold agent memory and config, not binaries. */
    public static final long MAX_FILE_BYTES = 1_048_576;

    public WorkspaceSnapshot {
        Objects.requireNonNull(files, "files");
        files = java.util.Collections.unmodifiableSortedMap(new TreeMap<>(files));
        Hashes.requireHash(treeHash, "treeHash");
        if (!treeHash.equals(computeTreeHash(files))) {
            throw new IllegalArgumentException("treeHash does not match file contents");
        }
    }

    public static WorkspaceSnapshot of(Map<String, String> files) {
        TreeMap<String, String> sorted = new TreeMap<>();
        files.forEach((path, content) -> sorted.put(WorkspacePaths.normalizeRelative(path),
                Objects.requireNonNull(content, "content of " + path)));
        return new WorkspaceSnapshot(sorted, computeTreeHash(sorted));
    }

    /** Reads every non-hidden regular file under {@code workspace}. */
    public static WorkspaceSnapshot capture(Path workspace) throws IOException {
        Objects.requireNonNull(workspace, "workspace");
        if (!Files.isDirectory(workspace)) {
            throw new IOException("Workspace is not a directory: " + workspace);
        }
        Path root = workspace.toAbsolutePath().normalize();
        TreeMap<String, String> files = new TreeMap<>();
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path path : (Iterable<Path>) paths::iterator) {
                if (path.equals(root) || !Files.isRegularFile(path)) {
                    continue;
                }
                Path relative = root.relativize(path);
                if (isHidden(relative)) {
                    continue;
                }
                if (Files.size(path) > MAX_FILE_BYTES) {
                    throw new IOException("Workspace file exceeds " + MAX_FILE_BYTES + " bytes: " + relative);
                }
                files.put(WorkspacePaths.toPortable(relative), readUtf8Strict(path));
            }
        }
        return new WorkspaceSnapshot(files, computeTreeHash(files));
    }

    /** Writes every file of this snapshot into {@code target}, overwriting existing files. */
    public void materialize(Path target) throws IOException {
        Files.createDirectories(target);
        for (Map.Entry<String, String> entry : files.entrySet()) {
            Path file = WorkspacePaths.resolveInside(target, entry.getKey());
            Path parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(file, entry.getValue(), StandardCharsets.UTF_8);
        }
    }

    /**
     * Content-addressed tree hash: SHA-256 over each {@code path NUL byteLength NUL content}
     * record in path order. Length-prefixing makes the encoding unambiguous.
     */
    public static String computeTreeHash(SortedMap<String, String> files) {
        MessageDigest digest = Hashes.newSha256();
        digest.update("daimon-tree v1\n".getBytes(StandardCharsets.UTF_8));
        for (Map.Entry<String, String> entry : files.entrySet()) {
            byte[] content = entry.getValue().getBytes(StandardCharsets.UTF_8);
            digest.update(entry.getKey().getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(Integer.toString(content.length).getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(content);
        }
        return java.util.HexFormat.of().formatHex(digest.digest());
    }

    private static boolean isHidden(Path relative) {
        for (Path segment : relative) {
            if (segment.toString().startsWith(".")) {
                return true;
            }
        }
        return false;
    }

    private static String readUtf8Strict(Path path) throws IOException {
        byte[] bytes = Files.readAllBytes(path);
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException e) {
            throw new IOException("Workspace file is not valid UTF-8 text: " + path, e);
        }
    }
}
