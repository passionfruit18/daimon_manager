package com.daimon.core;

import com.daimon.contracts.Contract;
import com.daimon.contracts.ContractExecutor;
import com.daimon.contracts.ContractExecutorRegistry;
import com.daimon.contracts.ContractParticipant;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A live DAIMon: a local workspace directory, the contracts it can fulfil, and its position in
 * the serialization commit graph.
 *
 * <h2>Serialization tracking</h2>
 * <ul>
 *   <li>{@link #getSerializationCommitCreatedFromSnapshotHash()} — the commit this instance was
 *       checked out from ({@code null} for a brand-new DAIMon). Never changes.</li>
 *   <li>{@link #getSerializationCommitMostRecentSnapshotHash()} — the latest commit produced by
 *       or loaded into this instance; the parent of the next {@link #serialize}. Like Git
 *       {@code HEAD}.</li>
 *   <li>{@link #isChangedFromSerializationCommit()} — whether the workspace has been modified
 *       since that commit (a dirty working tree).</li>
 * </ul>
 *
 * <p>All writes through the {@link ManagementInterface} set the changed flag automatically.
 * Edits made outside the daemon can be detected with {@link #detectWorkspaceDrift}.
 */
public abstract class DAIMonInstance implements ContractParticipant {

    /** Default name of the markdown memory file inside a workspace. */
    public static final String DEFAULT_MEMORY_FILE = "memory.md";

    // ------------------------------------------------------------------ implementation registry

    /** Creates a concrete instance for an implementation type. */
    @FunctionalInterface
    public interface Factory {
        DAIMonInstance create(Seed seed);
    }

    /**
     * Everything a {@link Factory} needs to construct an instance.
     *
     * @param createdFromHash commit the workspace was materialised from, or {@code null}
     */
    public record Seed(String name, Path workspace, String createdFromHash, DAIMonRuntimeConfig config) {
        public Seed {
            Objects.requireNonNull(name, "name");
            if (name.isBlank() || name.contains("\n")) {
                throw new IllegalArgumentException("name must be a non-blank single line");
            }
            workspace = Objects.requireNonNull(workspace, "workspace").toAbsolutePath().normalize();
            if (createdFromHash != null) {
                Hashes.requireHash(createdFromHash, "createdFromHash");
            }
            Objects.requireNonNull(config, "config");
        }
    }

    private static final Map<String, Factory> IMPLEMENTATIONS = new ConcurrentHashMap<>();

    static {
        registerImplementation(DAIMonInstanceImplementations.DAIMonInstanceTalkMemoryMarkdown.TYPE,
                DAIMonInstanceImplementations.DAIMonInstanceTalkMemoryMarkdown::new);
    }

    /** Registers a new implementation type so commits of that type can be checked out. */
    public static void registerImplementation(String implementationType, Factory factory) {
        Objects.requireNonNull(implementationType, "implementationType");
        Objects.requireNonNull(factory, "factory");
        IMPLEMENTATIONS.put(implementationType, factory);
    }

    public static Set<String> registeredImplementations() {
        return Set.copyOf(IMPLEMENTATIONS.keySet());
    }

    // ------------------------------------------------------------------ factories

    /**
     * Checks out {@code serializationCommit} into a fresh workspace under the default DAIMon
     * home (see {@link DAIMonRuntimeConfig#fromEnvironment()}).
     */
    public static DAIMonInstance createFromSerializationCommit(SerializationCommit serializationCommit) {
        DAIMonRuntimeConfig config = DAIMonRuntimeConfig.fromEnvironment();
        return createFromSerializationCommit(serializationCommit,
                uniqueWorkspaceFor(config.workspacesRoot(), serializationCommit), config);
    }

    /**
     * Checks out {@code serializationCommit} into {@code workspace}: writes the commit's files
     * (overwriting same-named files) and instantiates its implementation type. The new instance
     * is clean, with both tracking hashes set to the commit hash.
     */
    public static DAIMonInstance createFromSerializationCommit(SerializationCommit serializationCommit,
            Path workspace, DAIMonRuntimeConfig config) {
        Objects.requireNonNull(serializationCommit, "serializationCommit");
        Factory factory = factoryFor(serializationCommit.implementationType());
        try {
            serializationCommit.snapshot().materialize(workspace);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to materialise " + serializationCommit.shortHash(), e);
        }
        return factory.create(new Seed(serializationCommit.instanceName(), workspace, serializationCommit.hash(),
                config));
    }

    /** Creates a brand-new DAIMon (no commit yet) over an existing or empty workspace. */
    public static DAIMonInstance createNew(String implementationType, String name, Path workspace,
            DAIMonRuntimeConfig config) {
        Factory factory = factoryFor(implementationType);
        try {
            Files.createDirectories(workspace);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to create workspace " + workspace, e);
        }
        DAIMonInstance instance = factory.create(new Seed(name, workspace, null, config));
        instance.changedFromSerializationCommit = true; // nothing has been serialized yet
        return instance;
    }

    private static Factory factoryFor(String implementationType) {
        Factory factory = IMPLEMENTATIONS.get(implementationType);
        if (factory == null) {
            throw new IllegalArgumentException("Unknown DAIMon implementation type: " + implementationType
                    + " (registered: " + IMPLEMENTATIONS.keySet() + ")");
        }
        return factory;
    }

    private static Path uniqueWorkspaceFor(Path root, SerializationCommit commit) {
        String base = commit.instanceName().toLowerCase().replaceAll("[^a-z0-9-_]+", "-") + "-" + commit.shortHash();
        Path candidate = root.resolve(base);
        for (int i = 2; Files.exists(candidate); i++) {
            candidate = root.resolve(base + "-" + i);
        }
        return candidate;
    }

    // ------------------------------------------------------------------ instance state

    private final String id = UUID.randomUUID().toString();
    private final String name;
    private final Path workspace;
    private final DAIMonRuntimeConfig config;
    private final String serializationCommitCreatedFromSnapshotHash;
    private final ContractExecutorRegistry executors = new ContractExecutorRegistry();
    private final ManagementInterface managementInterface = new WorkspaceManagementInterface();
    private final Object serializationLock = new Object();

    private volatile String serializationCommitMostRecentSnapshotHash;
    private volatile boolean changedFromSerializationCommit;

    protected DAIMonInstance(Seed seed) {
        Objects.requireNonNull(seed, "seed");
        this.name = seed.name().strip();
        this.workspace = seed.workspace();
        this.config = seed.config();
        this.serializationCommitCreatedFromSnapshotHash = seed.createdFromHash();
        this.serializationCommitMostRecentSnapshotHash = seed.createdFromHash();
        this.changedFromSerializationCommit = false;
    }

    /** Implementation type recorded in commits, used to re-instantiate on checkout. */
    public abstract String implementationType();

    /** Workspace-relative path of this DAIMon's markdown memory. */
    protected String memoryRelativePath() {
        return DEFAULT_MEMORY_FILE;
    }

    public final String getId() {
        return id;
    }

    public final String getName() {
        return name;
    }

    public final Path getWorkspace() {
        return workspace;
    }

    protected final DAIMonRuntimeConfig getConfig() {
        return config;
    }

    public final ManagementInterface getManagementInterface() {
        return managementInterface;
    }

    /** Current memory contents, or an empty string if the memory file does not exist yet. */
    public String getMemory() {
        try {
            return managementInterface.readMemory();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read memory of " + name, e);
        }
    }

    public final Optional<String> getSerializationCommitMostRecentSnapshotHash() {
        return Optional.ofNullable(serializationCommitMostRecentSnapshotHash);
    }

    public final Optional<String> getSerializationCommitCreatedFromSnapshotHash() {
        return Optional.ofNullable(serializationCommitCreatedFromSnapshotHash);
    }

    public final boolean isChangedFromSerializationCommit() {
        return changedFromSerializationCommit;
    }

    /** Marks the workspace dirty. Called automatically by the management interface. */
    protected final void markChangedFromSerializationCommit() {
        changedFromSerializationCommit = true;
    }

    // ------------------------------------------------------------------ serialization

    /**
     * Snapshots the workspace into a new commit whose parent is the most recent commit, signs it
     * when a signer is given, and advances the most-recent hash. The caller is responsible for
     * adding the commit to a {@link SerializationCommitStore}.
     *
     * @param signer may be {@code null} for an unsigned commit
     */
    public final SerializationCommit serialize(String author, String message, WorkspaceSigner signer)
            throws IOException {
        synchronized (serializationLock) {
            WorkspaceSnapshot snapshot = WorkspaceSnapshot.capture(workspace);
            List<String> parents = serializationCommitMostRecentSnapshotHash == null
                    ? List.of()
                    : List.of(serializationCommitMostRecentSnapshotHash);
            SerializationCommit commit = SerializationCommit.create(parents, snapshot, implementationType(), name,
                    author, message, Instant.now());
            if (signer != null) {
                commit = signer.sign(commit);
            }
            serializationCommitMostRecentSnapshotHash = commit.hash();
            changedFromSerializationCommit = false;
            return commit;
        }
    }

    /**
     * Compares the on-disk workspace with {@code head}'s tree and updates the changed flag. Use
     * this to pick up edits made outside the daemon (e.g. in a text editor).
     *
     * @return the updated value of {@link #isChangedFromSerializationCommit()}
     */
    public final boolean detectWorkspaceDrift(SerializationCommit head) throws IOException {
        Objects.requireNonNull(head, "head");
        if (!head.hash().equals(serializationCommitMostRecentSnapshotHash)) {
            throw new IllegalArgumentException("Commit " + head.shortHash() + " is not this instance's most recent");
        }
        boolean drifted = !WorkspaceSnapshot.capture(workspace).treeHash().equals(head.treeHash());
        changedFromSerializationCommit = drifted;
        return drifted;
    }

    // ------------------------------------------------------------------ contracts

    /** Declares that this DAIMon fulfils the executor's contract. */
    protected final <I, O> void registerExecutor(ContractExecutor<I, O> executor) {
        executors.register(executor);
    }

    @Override
    public final String participantId() {
        return id;
    }

    @Override
    public final String displayName() {
        return name;
    }

    @Override
    public final Set<Contract<?, ?>> supportedContracts() {
        return executors.contracts();
    }

    @Override
    public final <I, O> Optional<ContractExecutor<I, O>> executorFor(Contract<I, O> contract) {
        return executors.find(contract);
    }

    @Override
    public String toString() {
        return getClass().getSimpleName() + "[" + name + " @ "
                + getSerializationCommitMostRecentSnapshotHash().map(Hashes::shortHash).orElse("(uncommitted)")
                + (changedFromSerializationCommit ? "*" : "") + "]";
    }

    // ------------------------------------------------------------------ management interface

    /**
     * Sandboxed file access to a DAIMon's workspace. Paths are workspace-relative and cannot
     * escape the workspace. Every mutating call marks the instance as changed.
     */
    public interface ManagementInterface {

        Path workspace();

        /** Non-hidden files, workspace-relative, sorted. */
        List<String> listFiles() throws IOException;

        Optional<String> readFile(String relativePath) throws IOException;

        void writeFile(String relativePath, String content) throws IOException;

        void appendToFile(String relativePath, String content) throws IOException;

        /** @return {@code true} if the file existed */
        boolean deleteFile(String relativePath) throws IOException;

        /** Reads {@code memory.md} (or the implementation's memory path); empty if absent. */
        String readMemory() throws IOException;

        void writeMemory(String content) throws IOException;

        /** Appends {@code entry} as a new markdown line to the memory file. */
        void appendMemory(String entry) throws IOException;
    }

    private final class WorkspaceManagementInterface implements ManagementInterface {

        @Override
        public Path workspace() {
            return workspace;
        }

        @Override
        public List<String> listFiles() throws IOException {
            if (!Files.isDirectory(workspace)) {
                return List.of();
            }
            return List.copyOf(WorkspaceSnapshot.capture(workspace).files().keySet());
        }

        @Override
        public Optional<String> readFile(String relativePath) throws IOException {
            Path file = WorkspacePaths.resolveInside(workspace, relativePath);
            try {
                return Optional.of(Files.readString(file, StandardCharsets.UTF_8));
            } catch (NoSuchFileException e) {
                return Optional.empty();
            }
        }

        @Override
        public void writeFile(String relativePath, String content) throws IOException {
            Objects.requireNonNull(content, "content");
            Path file = WorkspacePaths.resolveInside(workspace, relativePath);
            createParent(file);
            Files.writeString(file, content, StandardCharsets.UTF_8);
            markChangedFromSerializationCommit();
        }

        @Override
        public void appendToFile(String relativePath, String content) throws IOException {
            Objects.requireNonNull(content, "content");
            Path file = WorkspacePaths.resolveInside(workspace, relativePath);
            createParent(file);
            Files.writeString(file, content, StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND);
            markChangedFromSerializationCommit();
        }

        @Override
        public boolean deleteFile(String relativePath) throws IOException {
            boolean existed = Files.deleteIfExists(WorkspacePaths.resolveInside(workspace, relativePath));
            if (existed) {
                markChangedFromSerializationCommit();
            }
            return existed;
        }

        @Override
        public String readMemory() throws IOException {
            return readFile(memoryRelativePath()).orElse("");
        }

        @Override
        public void writeMemory(String content) throws IOException {
            writeFile(memoryRelativePath(), content);
        }

        @Override
        public void appendMemory(String entry) throws IOException {
            Objects.requireNonNull(entry, "entry");
            String existing = readMemory();
            String separator = existing.isEmpty() || existing.endsWith("\n") ? "" : "\n";
            appendToFile(memoryRelativePath(), separator + entry.strip() + "\n");
        }

        private void createParent(Path file) throws IOException {
            Path parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
        }
    }
}
