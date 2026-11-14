package com.daimon.core;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * An immutable, content-addressed record of a DAIMon workspace, modelled on a Git commit.
 *
 * <p>The {@link #hash()} covers the tree hash, parents, implementation type, instance name,
 * author, timestamp and message, so any change to history changes every descendant hash. The
 * optional Ed25519 {@link #signature()} signs the hash and is <em>not</em> part of it, so a
 * commit can be signed after creation without changing its identity.
 */
public final class SerializationCommit {

    private final String hash;
    private final List<String> parentHashes;
    private final WorkspaceSnapshot snapshot;
    private final String implementationType;
    private final String instanceName;
    private final String author;
    private final String message;
    private final Instant createdAt;
    private final String signature;
    private final String signerPublicKey;

    private SerializationCommit(List<String> parentHashes, WorkspaceSnapshot snapshot, String implementationType,
            String instanceName, String author, String message, Instant createdAt, String signature,
            String signerPublicKey) {
        this.parentHashes = List.copyOf(Objects.requireNonNull(parentHashes, "parentHashes"));
        this.parentHashes.forEach(p -> Hashes.requireHash(p, "parentHash"));
        if (this.parentHashes.size() != this.parentHashes.stream().distinct().count()) {
            throw new IllegalArgumentException("Duplicate parent hashes");
        }
        this.snapshot = Objects.requireNonNull(snapshot, "snapshot");
        this.implementationType = singleLine(implementationType, "implementationType");
        this.instanceName = singleLine(instanceName, "instanceName");
        this.author = singleLine(author, "author");
        this.message = Objects.requireNonNull(message, "message").strip();
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt").truncatedTo(ChronoUnit.MILLIS);
        if ((signature == null) != (signerPublicKey == null)) {
            throw new IllegalArgumentException("signature and signerPublicKey must be set together");
        }
        this.signature = signature;
        this.signerPublicKey = signerPublicKey;
        this.hash = Hashes.sha256Hex(canonicalPayload().getBytes(StandardCharsets.UTF_8));
    }

    /** Creates a new unsigned commit. */
    public static SerializationCommit create(List<String> parentHashes, WorkspaceSnapshot snapshot,
            String implementationType, String instanceName, String author, String message, Instant createdAt) {
        return new SerializationCommit(parentHashes, snapshot, implementationType, instanceName, author, message,
                createdAt, null, null);
    }

    /** Returns a copy carrying the given detached signature. The hash is unchanged. */
    public SerializationCommit withSignature(String signatureBase64, String signerPublicKeyBase64) {
        return new SerializationCommit(parentHashes, snapshot, implementationType, instanceName, author, message,
                createdAt, Objects.requireNonNull(signatureBase64, "signature"),
                Objects.requireNonNull(signerPublicKeyBase64, "signerPublicKey"));
    }

    /** The exact bytes (as UTF-8) that are hashed to produce {@link #hash()}. */
    public String canonicalPayload() {
        StringBuilder sb = new StringBuilder("daimon-commit v1\n");
        sb.append("tree ").append(snapshot.treeHash()).append('\n');
        for (String parent : parentHashes) {
            sb.append("parent ").append(parent).append('\n');
        }
        sb.append("implementation ").append(implementationType).append('\n');
        sb.append("instance ").append(instanceName).append('\n');
        sb.append("author ").append(author).append('\n');
        sb.append("created ").append(createdAt.toEpochMilli()).append('\n');
        sb.append('\n').append(message);
        return sb.toString();
    }

    public String hash() {
        return hash;
    }

    public String shortHash() {
        return Hashes.shortHash(hash);
    }

    public List<String> parentHashes() {
        return parentHashes;
    }

    public boolean isRoot() {
        return parentHashes.isEmpty();
    }

    public boolean isMerge() {
        return parentHashes.size() > 1;
    }

    public WorkspaceSnapshot snapshot() {
        return snapshot;
    }

    public String treeHash() {
        return snapshot.treeHash();
    }

    public String implementationType() {
        return implementationType;
    }

    public String instanceName() {
        return instanceName;
    }

    public String author() {
        return author;
    }

    public String message() {
        return message;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Optional<String> signature() {
        return Optional.ofNullable(signature);
    }

    public Optional<String> signerPublicKey() {
        return Optional.ofNullable(signerPublicKey);
    }

    public boolean isSigned() {
        return signature != null;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof SerializationCommit that && hash.equals(that.hash)
                && Objects.equals(signature, that.signature);
    }

    @Override
    public int hashCode() {
        return hash.hashCode();
    }

    @Override
    public String toString() {
        return "SerializationCommit[" + shortHash() + " " + instanceName + ": " + message.lines().findFirst().orElse("")
                + "]";
    }

    private static String singleLine(String value, String name) {
        Objects.requireNonNull(value, name);
        String stripped = value.strip();
        if (stripped.isEmpty() || stripped.contains("\n") || stripped.contains("\r")) {
            throw new IllegalArgumentException(name + " must be a non-blank single line");
        }
        return stripped;
    }
}
