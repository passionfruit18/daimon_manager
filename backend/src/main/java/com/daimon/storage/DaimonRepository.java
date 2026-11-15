package com.daimon.storage;

import com.daimon.core.Hashes;
import com.daimon.core.SerializationCommit;
import com.daimon.core.WorkspacePaths;
import com.daimon.core.WorkspaceSigner;
import com.daimon.core.WorkspaceSnapshot;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * On-disk store for everything the daemon needs to survive a restart. Layout under the DAIMon
 * home (Git-inspired):
 *
 * <pre>
 * repository.json                 format version
 * objects/ab/cdef….json           one serialization commit (metadata + file → blob hash map)
 * blobs/12/34ab…                  file contents, content-addressed and shared between commits
 * refs.json                       branch name → commit hash
 * instances/&lt;id&gt;.json            live instance registry (name, workspace, tracking hashes, branch)
 * keys/signer.json                Ed25519 signing key (owner-only permissions)
 * channels.json, interactions/…   application documents written through {@link #writeJson}
 * workspaces/…                    the workspaces themselves (written by DaimonInstance)
 * </pre>
 *
 * <p>Every write goes to a temp file in the target directory and is then atomically moved into
 * place, so a crash never leaves a half-written file. Commits are re-hashed on load, so corrupted
 * or edited objects are reported rather than silently trusted.
 */
public final class DaimonRepository {

    public static final int FORMAT_VERSION = 1;
    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,127}");

    private final Path root;
    private final ObjectMapper json = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    /** Serialises read-modify-write of commit objects. Other writes replace whole files. */
    private final Object objectLock = new Object();

    // ------------------------------------------------------------------ records

    public record RepositoryInfo(int formatVersion, String createdAt) {
    }

    /** A commit as stored on disk. File contents live in blobs. */
    public record CommitRecord(int formatVersion, String hash, List<String> parents, String treeHash,
            Map<String, String> files, String implementationType, String instanceName, String author, String message,
            long createdAtMillis, String signature, String signerPublicKey) {
    }

    /** A live instance as stored on disk. The changed flag is recomputed on load, not stored. */
    public record InstanceRecord(int formatVersion, String id, String name, String implementationType,
            String workspace, String branch, String createdFromHash, String mostRecentHash, String createdAt) {
    }

    public record RefsRecord(int formatVersion, Map<String, String> branches) {
    }

    public record SignerRecord(String algorithm, String publicKey, String privateKey) {
    }

    /** Loaded items plus human-readable descriptions of anything that had to be skipped. */
    public record LoadResult<T>(List<T> items, List<String> problems) {
    }

    // ------------------------------------------------------------------ setup

    public DaimonRepository(Path root) throws IOException {
        this.root = Objects.requireNonNull(root, "root").toAbsolutePath().normalize();
        for (String dir : List.of("objects", "blobs", "instances", "interactions", "keys")) {
            Files.createDirectories(this.root.resolve(dir));
        }
        restrictToOwner(this.root.resolve("keys"), "rwx------");
        Path info = this.root.resolve("repository.json");
        if (Files.exists(info)) {
            RepositoryInfo existing = json.readValue(info.toFile(), RepositoryInfo.class);
            if (existing.formatVersion() > FORMAT_VERSION) {
                throw new IOException("Repository at " + this.root + " uses format " + existing.formatVersion()
                        + "; this daemon understands up to " + FORMAT_VERSION);
            }
        } else {
            writeJson("repository.json", new RepositoryInfo(FORMAT_VERSION, Instant.now().toString()));
        }
    }

    public Path root() {
        return root;
    }

    // ------------------------------------------------------------------ blobs

    /** Stores {@code content} (if not already present) and returns its SHA-256. */
    public String putBlob(String content) throws IOException {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        String hash = Hashes.sha256Hex(bytes);
        Path path = blobPath(hash);
        if (!Files.exists(path)) {
            writeAtomic(path, bytes);
        }
        return hash;
    }

    /** Reads a blob and verifies its content still matches its hash. */
    public String getBlob(String hash) throws IOException {
        byte[] bytes = Files.readAllBytes(blobPath(Hashes.requireHash(hash, "blob hash")));
        if (!Hashes.sha256Hex(bytes).equals(hash)) {
            throw new IOException("Blob " + Hashes.shortHash(hash) + " is corrupted");
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }

    // ------------------------------------------------------------------ commits

    /** Persists a commit and its file contents. A signed commit replaces a stored unsigned copy. */
    public void saveCommit(SerializationCommit commit) throws IOException {
        Map<String, String> files = new TreeMap<>();
        for (Map.Entry<String, String> file : commit.snapshot().files().entrySet()) {
            files.put(file.getKey(), putBlob(file.getValue()));
        }
        CommitRecord record = new CommitRecord(FORMAT_VERSION, commit.hash(), commit.parentHashes(),
                commit.treeHash(), files, commit.implementationType(), commit.instanceName(), commit.author(),
                commit.message(), commit.createdAt().toEpochMilli(), commit.signature().orElse(null),
                commit.signerPublicKey().orElse(null));
        Path path = objectPath(commit.hash());
        synchronized (objectLock) {
            if (Files.exists(path)) {
                CommitRecord existing = json.readValue(path.toFile(), CommitRecord.class);
                if (existing.signature() != null || record.signature() == null) {
                    return; // already stored, and no new signature to add
                }
            }
            writeAtomic(path, json.writeValueAsBytes(record));
        }
    }

    /**
     * Loads every commit, re-deriving and checking its tree hash and commit hash, and returns them
     * parents-first so they can be inserted into a {@code SerializationCommitStore} in order.
     */
    public LoadResult<SerializationCommit> loadCommits() throws IOException {
        List<String> problems = new ArrayList<>();
        Map<String, SerializationCommit> byHash = new HashMap<>();
        for (Path file : listJson(root.resolve("objects"), true)) {
            try {
                SerializationCommit commit = rebuild(json.readValue(file.toFile(), CommitRecord.class));
                String expectedName = commit.hash().substring(2) + ".json";
                if (!file.getFileName().toString().equals(expectedName)) {
                    throw new IOException("stored under the wrong name");
                }
                byHash.put(commit.hash(), commit);
            } catch (IOException | RuntimeException e) {
                problems.add("Skipped commit object " + root.relativize(file) + ": " + e.getMessage());
            }
        }

        // Topological order: emit a commit once all of its parents have been emitted.
        List<SerializationCommit> ordered = new ArrayList<>();
        Set<String> emitted = new HashSet<>();
        Map<String, SerializationCommit> remaining = new LinkedHashMap<>(byHash);
        boolean progress = true;
        while (!remaining.isEmpty() && progress) {
            progress = false;
            for (var it = remaining.values().iterator(); it.hasNext();) {
                SerializationCommit commit = it.next();
                if (emitted.containsAll(commit.parentHashes())) {
                    ordered.add(commit);
                    emitted.add(commit.hash());
                    it.remove();
                    progress = true;
                }
            }
        }
        for (SerializationCommit orphan : remaining.values()) {
            problems.add("Skipped commit " + orphan.shortHash() + ": a parent commit is missing");
        }
        return new LoadResult<>(ordered, problems);
    }

    private SerializationCommit rebuild(CommitRecord record) throws IOException {
        Map<String, String> contents = new TreeMap<>();
        for (Map.Entry<String, String> file : record.files().entrySet()) {
            contents.put(file.getKey(), getBlob(file.getValue()));
        }
        WorkspaceSnapshot snapshot = WorkspaceSnapshot.of(contents);
        if (!snapshot.treeHash().equals(record.treeHash())) {
            throw new IOException("tree hash mismatch");
        }
        SerializationCommit commit = SerializationCommit.create(record.parents(), snapshot,
                record.implementationType(), record.instanceName(), record.author(), record.message(),
                Instant.ofEpochMilli(record.createdAtMillis()));
        if (record.signature() != null) {
            commit = commit.withSignature(record.signature(), record.signerPublicKey());
        }
        if (!commit.hash().equals(record.hash())) {
            throw new IOException("commit hash mismatch (content was modified)");
        }
        return commit;
    }

    // ------------------------------------------------------------------ refs

    public void saveRefs(Map<String, String> branches) throws IOException {
        writeJson("refs.json", new RefsRecord(FORMAT_VERSION, new TreeMap<>(branches)));
    }

    public Map<String, String> loadRefs() throws IOException {
        return readJson("refs.json", RefsRecord.class)
                .map(r -> r.branches() == null ? Map.<String, String>of() : r.branches())
                .orElse(Map.of());
    }

    // ------------------------------------------------------------------ instances

    public void saveInstance(InstanceRecord record) throws IOException {
        writeJson("instances/" + requireSafeId(record.id()) + ".json", record);
    }

    public LoadResult<InstanceRecord> loadInstances() throws IOException {
        return readAllJson("instances", InstanceRecord.class);
    }

    // ------------------------------------------------------------------ signing key

    /** Loads the daemon's signing key, generating and saving one on first run. */
    public WorkspaceSigner loadOrCreateSigner() throws IOException {
        Path path = root.resolve("keys/signer.json");
        if (Files.exists(path)) {
            SignerRecord record = json.readValue(path.toFile(), SignerRecord.class);
            return WorkspaceSigner.fromEncoded(record.publicKey(), record.privateKey());
        }
        WorkspaceSigner signer = WorkspaceSigner.generate();
        writeAtomic(path, json.writeValueAsBytes(new SignerRecord("Ed25519", signer.publicKeyBase64(),
                signer.exportPrivateKeyPkcs8Base64())));
        restrictToOwner(path, "rw-------");
        return signer;
    }

    // ------------------------------------------------------------------ generic JSON documents

    /** Atomically writes {@code value} as JSON to a path relative to the repository root. */
    public void writeJson(String relativePath, Object value) throws IOException {
        writeAtomic(WorkspacePaths.resolveInside(root, relativePath), json.writeValueAsBytes(value));
    }

    public <T> Optional<T> readJson(String relativePath, Class<T> type) throws IOException {
        Path path = WorkspacePaths.resolveInside(root, relativePath);
        return Files.exists(path) ? Optional.of(json.readValue(path.toFile(), type)) : Optional.empty();
    }

    /** Reads every {@code *.json} directly inside {@code relativeDir}; unreadable files are reported. */
    public <T> LoadResult<T> readAllJson(String relativeDir, Class<T> type) throws IOException {
        List<T> items = new ArrayList<>();
        List<String> problems = new ArrayList<>();
        for (Path file : listJson(WorkspacePaths.resolveInside(root, relativeDir), false)) {
            try {
                items.add(json.readValue(file.toFile(), type));
            } catch (IOException e) {
                problems.add("Skipped " + root.relativize(file) + ": " + e.getMessage());
            }
        }
        return new LoadResult<>(items, problems);
    }

    public static String requireSafeId(String id) {
        if (id == null || !SAFE_ID.matcher(id).matches()) {
            throw new IllegalArgumentException("Unsafe id for a file name: " + id);
        }
        return id;
    }

    // ------------------------------------------------------------------ internals

    private Path objectPath(String hash) {
        return root.resolve("objects").resolve(hash.substring(0, 2)).resolve(hash.substring(2) + ".json");
    }

    private Path blobPath(String hash) {
        return root.resolve("blobs").resolve(hash.substring(0, 2)).resolve(hash.substring(2));
    }

    private static List<Path> listJson(Path dir, boolean recursive) throws IOException {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> paths = recursive ? Files.walk(dir) : Files.list(dir)) {
            return paths.filter(p -> Files.isRegularFile(p))
                    .filter(p -> p.getFileName().toString().endsWith(".json"))
                    .filter(p -> !p.getFileName().toString().startsWith("."))
                    .sorted()
                    .toList();
        }
    }

    private static void writeAtomic(Path target, byte[] bytes) throws IOException {
        Path dir = target.getParent();
        Files.createDirectories(dir);
        Path tmp = Files.createTempFile(dir, ".tmp-", ".part");
        try {
            Files.write(tmp, bytes);
            try {
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    private static void restrictToOwner(Path path, String permissions) {
        try {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(permissions));
        } catch (UnsupportedOperationException | IOException ignored) {
            // Non-POSIX file systems (e.g. Windows) rely on the user profile's ACLs instead.
        }
    }
}
