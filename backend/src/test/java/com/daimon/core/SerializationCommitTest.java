package com.daimon.core;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SerializationCommitTest {

    private static final Instant T0 = Instant.parse("2026-11-14T12:00:00Z");

    private static SerializationCommit commit(List<String> parents, String memory, String message) {
        return SerializationCommit.create(parents, WorkspaceSnapshot.of(Map.of("memory.md", memory)),
                "test/v1", "Testmon", "tester", message, T0);
    }

    @Test
    void hashIsDeterministicAndCoversContentAndMetadata() {
        SerializationCommit a = commit(List.of(), "- fact", "genesis");
        assertEquals(a.hash(), commit(List.of(), "- fact", "genesis").hash());
        assertNotEquals(a.hash(), commit(List.of(), "- other fact", "genesis").hash());
        assertNotEquals(a.hash(), commit(List.of(), "- fact", "different message").hash());
        assertNotEquals(a.hash(), commit(List.of(a.hash()), "- fact", "genesis").hash());
        assertTrue(Hashes.isHash(a.hash()));
    }

    @Test
    void signatureVerifiesAndDoesNotChangeHash() {
        WorkspaceSigner signer = WorkspaceSigner.generate();
        SerializationCommit unsigned = commit(List.of(), "- fact", "genesis");
        SerializationCommit signed = signer.sign(unsigned);
        assertEquals(unsigned.hash(), signed.hash());
        assertTrue(WorkspaceSigner.verify(signed));
        assertFalse(WorkspaceSigner.verify(unsigned));

        // A signature transplanted onto a different commit must fail.
        SerializationCommit other = commit(List.of(), "- forged", "genesis");
        SerializationCommit forged = other.withSignature(signed.signature().orElseThrow(),
                signed.signerPublicKey().orElseThrow());
        assertFalse(WorkspaceSigner.verify(forged));
    }

    @Test
    void storeRequiresParentsAndResolvesRefs() {
        SerializationCommitStore store = new SerializationCommitStore();
        SerializationCommit root = commit(List.of(), "- a", "root");
        SerializationCommit child = commit(List.of(root.hash()), "- a\n- b", "child");
        assertThrows(IllegalArgumentException.class, () -> store.put(child));

        store.put(root);
        store.put(child);
        store.updateBranch("testmon/main", child.hash());
        assertEquals(child, store.resolve("testmon/main").orElseThrow());
        assertEquals(root, store.resolve(root.hash().substring(0, 8)).orElseThrow());
        assertEquals(List.of(child, root), store.log(child.hash()));
        assertTrue(store.isAncestor(root.hash(), child.hash()));
    }

    @Test
    void workspacePathsCannotEscape() {
        assertThrows(IllegalArgumentException.class, () -> WorkspaceSnapshot.of(Map.of("../evil.md", "x")));
        assertThrows(IllegalArgumentException.class, () -> WorkspaceSnapshot.of(Map.of("/etc/passwd", "x")));
        assertEquals("notes/a.md", WorkspacePaths.normalizeRelative("./notes\\a.md"));
    }
}
