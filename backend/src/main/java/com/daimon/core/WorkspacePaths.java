package com.daimon.core;

import java.nio.file.Path;
import java.util.Objects;

/** Path rules for workspace files: relative, portable, and never escaping the workspace root. */
public final class WorkspacePaths {

    private WorkspacePaths() {
    }

    /**
     * Normalises a workspace-relative path to {@code a/b/c} form and rejects absolute paths,
     * {@code ..} segments, and empty paths.
     */
    public static String normalizeRelative(String relativePath) {
        Objects.requireNonNull(relativePath, "relativePath");
        String portable = relativePath.replace('\\', '/').strip();
        while (portable.startsWith("./")) {
            portable = portable.substring(2);
        }
        if (portable.isEmpty() || portable.startsWith("/") || portable.matches("^[A-Za-z]:.*")) {
            throw new IllegalArgumentException("Workspace path must be relative: " + relativePath);
        }
        for (String segment : portable.split("/")) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) {
                throw new IllegalArgumentException("Illegal workspace path segment in: " + relativePath);
            }
        }
        return portable;
    }

    /** Resolves {@code relativePath} under {@code root}, guaranteeing the result stays inside it. */
    public static Path resolveInside(Path root, String relativePath) {
        Path normalizedRoot = root.toAbsolutePath().normalize();
        Path resolved = normalizedRoot.resolve(normalizeRelative(relativePath)).normalize();
        if (!resolved.startsWith(normalizedRoot)) {
            throw new IllegalArgumentException("Path escapes workspace: " + relativePath);
        }
        return resolved;
    }

    static String toPortable(Path relative) {
        StringBuilder sb = new StringBuilder();
        for (Path segment : relative) {
            if (!sb.isEmpty()) {
                sb.append('/');
            }
            sb.append(segment);
        }
        return sb.toString();
    }
}
