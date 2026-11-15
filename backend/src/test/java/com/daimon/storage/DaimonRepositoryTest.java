package com.daimon.storage;

import com.daimon.core.DaimonInstance;
import com.daimon.core.DaimonInstanceImplementations.DaimonInstanceTalkMemoryMarkdown;
import com.daimon.core.DaimonRuntimeConfig;
import com.daimon.core.SerializationCommit;
import com.daimon.core.WorkspaceSigner;
import com.daimon.core.WorkspaceSnapshot;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DaimonRepositoryTest {

    @TempDir
    Path home;

    private DaimonRuntimeConfig config() {
        return new DaimonRuntimeConfig(home, null, null);
    }

    @Test
    void commitsRefsAndInstancesSurviveARestart() throws IOException {
        DaimonRepository repo = new DaimonRepository(home);
        WorkspaceSigner signer = repo.loadOrCreateSigner();

        DaimonInstance glitch = DaimonInstance.createNew(DaimonInstanceTalkMemoryMarkdown.TYPE, "Glitchling",
                home.resolve("workspaces/glitchling"), config());
        glitch.getManagementInterface().writeMemory("# Glitchling\n- likes edge cases\n");
        SerializationCommit c1 = glitch.serialize("tester", "hatch", signer);
        glitch.getManagementInterface().appendMemory("- found a bug");
        SerializationCommit c2 = glitch.serialize("tester", "learn", signer);
        // A merge with a second root, to exercise parent-first loading order.
        SerializationCommit other = signer.sign(SerializationCommit.create(List.of(),
                WorkspaceSnapshot.of(Map.of("memory.md", "- other")), DaimonInstanceTalkMemoryMarkdown.TYPE, "Nyxbyte",
                "tester", "hatch", Instant.now()));
        SerializationCommit merge = signer.sign(SerializationCommit.create(List.of(c2.hash(), other.hash()),
                WorkspaceSnapshot.of(Map.of("memory.md", "- bred")), DaimonInstanceTalkMemoryMarkdown.TYPE, "Moxbyte",
                "tester", "breed", Instant.now()));
        for (SerializationCommit c : List.of(merge, c1, other, c2)) { // deliberately out of order
            repo.saveCommit(c);
        }
        repo.saveRefs(Map.of("glitchling/main", c2.hash(), "moxbyte/main", merge.hash()));
        repo.saveInstance(new DaimonRepository.InstanceRecord(DaimonRepository.FORMAT_VERSION, glitch.getId(),
                glitch.getName(), glitch.implementationType(), glitch.getWorkspace().toString(), "glitchling/main",
                null, c2.hash(), Instant.now().toString()));
        // Uncommitted edit made before the "restart".
        glitch.getManagementInterface().appendMemory("- unsaved thought");

        // --- restart ---
        DaimonRepository reopened = new DaimonRepository(home);
        assertEquals(signer.publicKeyBase64(), reopened.loadOrCreateSigner().publicKeyBase64());

        DaimonRepository.LoadResult<SerializationCommit> loaded = reopened.loadCommits();
        assertTrue(loaded.problems().isEmpty(), loaded.problems().toString());
        List<String> order = loaded.items().stream().map(SerializationCommit::hash).toList();
        assertEquals(4, order.size());
        assertTrue(order.indexOf(c1.hash()) < order.indexOf(c2.hash()));
        assertTrue(order.indexOf(c2.hash()) < order.indexOf(merge.hash()));
        assertTrue(order.indexOf(other.hash()) < order.indexOf(merge.hash()));
        assertTrue(loaded.items().stream().allMatch(WorkspaceSigner::verify));
        assertEquals(Map.of("glitchling/main", c2.hash(), "moxbyte/main", merge.hash()), reopened.loadRefs());

        DaimonRepository.InstanceRecord record = reopened.loadInstances().items().get(0);
        SerializationCommit head = loaded.items().stream().filter(c -> c.hash().equals(record.mostRecentHash()))
                .findFirst().orElseThrow();
        DaimonInstance restored = DaimonInstance.restore(record.id(), record.implementationType(), record.name(),
                Path.of(record.workspace()), record.createdFromHash(), head, config());
        assertEquals(glitch.getId(), restored.getId());
        assertEquals(c2.hash(), restored.getSerializationCommitMostRecentSnapshotHash().orElseThrow());
        assertTrue(restored.isChangedFromSerializationCommit(), "uncommitted edit should be detected as dirty");
        assertTrue(restored.getMemory().contains("unsaved thought"));

        // Committing after the restart continues the same lineage.
        SerializationCommit c3 = restored.serialize("tester", "after restart", signer);
        assertEquals(List.of(c2.hash()), c3.parentHashes());
    }

    @Test
    void missingWorkspaceIsRematerialisedFromItsCommit() throws IOException {
        DaimonInstance d = DaimonInstance.createNew(DaimonInstanceTalkMemoryMarkdown.TYPE, "Quarkle",
                home.resolve("ws"), config());
        d.getManagementInterface().writeMemory("- fast\n");
        SerializationCommit c = d.serialize("tester", "hatch", null);

        DaimonInstance restored = DaimonInstance.restore(d.getId(), d.implementationType(), "Quarkle",
                home.resolve("gone"), null, c, config());
        assertEquals("- fast\n", restored.getMemory());
        assertFalse(restored.isChangedFromSerializationCommit());
    }

    @Test
    void tamperedObjectsAreRejected() throws IOException {
        DaimonRepository repo = new DaimonRepository(home);
        SerializationCommit c = SerializationCommit.create(List.of(), WorkspaceSnapshot.of(Map.of("memory.md", "- a")),
                DaimonInstanceTalkMemoryMarkdown.TYPE, "Nyxbyte", "tester", "hatch", Instant.now());
        repo.saveCommit(c);

        Path object;
        try (Stream<Path> s = Files.walk(home.resolve("objects"))) {
            object = s.filter(p -> p.toString().endsWith(".json")).findFirst().orElseThrow();
        }
        Files.writeString(object, Files.readString(object).replace("\"hatch\"", "\"forged\""));

        DaimonRepository.LoadResult<SerializationCommit> loaded = new DaimonRepository(home).loadCommits();
        assertTrue(loaded.items().isEmpty());
        assertEquals(1, loaded.problems().size());
        assertTrue(loaded.problems().get(0).contains("hash mismatch"), loaded.problems().get(0));
    }

    @Test
    void signingKeyIsOwnerOnly() throws IOException {
        new DaimonRepository(home).loadOrCreateSigner();
        Path key = home.resolve("keys/signer.json");
        if (Files.getFileStore(key).supportsFileAttributeView("posix")) {
            assertEquals("rw-------",
                    java.nio.file.attribute.PosixFilePermissions.toString(Files.getPosixFilePermissions(key)));
        }
    }
}
