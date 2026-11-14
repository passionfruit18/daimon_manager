package com.daimon.core;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe, in-memory object database for serialization commits plus named branch refs,
 * i.e. the DAIMon equivalent of {@code .git/objects} and {@code .git/refs/heads}.
 *
 * <p>Commits are append-only and must reference parents already in the store, so the store is
 * always a closed DAG.
 */
public final class SerializationCommitStore {

    private final Map<String, SerializationCommit> commits = new ConcurrentHashMap<>();
    private final Map<String, String> branches = new ConcurrentHashMap<>();

    /**
     * Adds a commit. Idempotent for identical commits; a signed copy replaces an unsigned one.
     *
     * @throws IllegalArgumentException if a parent is missing
     */
    public synchronized SerializationCommit put(SerializationCommit commit) {
        Objects.requireNonNull(commit, "commit");
        for (String parent : commit.parentHashes()) {
            if (!commits.containsKey(parent)) {
                throw new IllegalArgumentException(
                        "Commit " + commit.shortHash() + " references unknown parent " + Hashes.shortHash(parent));
            }
        }
        SerializationCommit existing = commits.get(commit.hash());
        if (existing == null || (!existing.isSigned() && commit.isSigned())) {
            commits.put(commit.hash(), commit);
            return commit;
        }
        return existing;
    }

    public Optional<SerializationCommit> get(String hash) {
        return hash == null ? Optional.empty() : Optional.ofNullable(commits.get(hash));
    }

    /** Resolves a full hash, unique hash prefix (≥ 4 chars), or branch name. */
    public Optional<SerializationCommit> resolve(String ref) {
        if (ref == null || ref.isBlank()) {
            return Optional.empty();
        }
        String branchHead = branches.get(ref);
        if (branchHead != null) {
            return get(branchHead);
        }
        if (commits.containsKey(ref)) {
            return get(ref);
        }
        if (ref.length() >= 4) {
            List<SerializationCommit> matches = commits.values().stream()
                    .filter(c -> c.hash().startsWith(ref)).limit(2).toList();
            if (matches.size() == 1) {
                return Optional.of(matches.get(0));
            }
        }
        return Optional.empty();
    }

    public boolean contains(String hash) {
        return commits.containsKey(hash);
    }

    public int size() {
        return commits.size();
    }

    /** All commits, newest first (ties broken by hash for determinism). */
    public List<SerializationCommit> all() {
        return commits.values().stream()
                .sorted(Comparator.comparing(SerializationCommit::createdAt).reversed()
                        .thenComparing(SerializationCommit::hash))
                .toList();
    }

    /** Points {@code branch} at {@code hash}, creating the branch if needed. */
    public void updateBranch(String branch, String hash) {
        if (branch == null || branch.isBlank() || branch.chars().anyMatch(Character::isWhitespace)) {
            throw new IllegalArgumentException("Invalid branch name: " + branch);
        }
        if (!commits.containsKey(hash)) {
            throw new IllegalArgumentException("Unknown commit: " + hash);
        }
        branches.put(branch, hash);
    }

    /** Branch name → head hash, sorted by name. */
    public Map<String, String> branches() {
        return java.util.Collections.unmodifiableMap(new TreeMap<>(branches));
    }

    /** Ancestors of {@code hash} (inclusive), breadth-first, i.e. {@code git log}. */
    public List<SerializationCommit> log(String hash) {
        List<SerializationCommit> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        Deque<String> queue = new ArrayDeque<>();
        queue.add(hash);
        while (!queue.isEmpty()) {
            String next = queue.poll();
            if (!seen.add(next)) {
                continue;
            }
            SerializationCommit commit = commits.get(next);
            if (commit != null) {
                result.add(commit);
                queue.addAll(commit.parentHashes());
            }
        }
        return result;
    }

    /** True if {@code ancestor} is reachable from {@code descendant} (inclusive). */
    public boolean isAncestor(String ancestor, String descendant) {
        return log(descendant).stream().anyMatch(c -> c.hash().equals(ancestor));
    }
}
