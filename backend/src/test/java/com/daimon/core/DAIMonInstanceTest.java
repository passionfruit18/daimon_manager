package com.daimon.core;

import com.daimon.contracts.ContractInputTypeRawText;
import com.daimon.contracts.TalkContract;
import com.daimon.core.DaimonInstanceImplementations.DaimonInstanceTalkMemoryMarkdown;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DaimonInstanceTest {

    @TempDir
    Path tmp;

    private DaimonRuntimeConfig offline() {
        return new DaimonRuntimeConfig(tmp.resolve("home"), null, null);
    }

    @Test
    void serializationTrackingFollowsTheWorkspace() throws IOException {
        DaimonInstance instance = DaimonInstance.createNew(DaimonInstanceTalkMemoryMarkdown.TYPE, "Glitchling",
                tmp.resolve("ws1"), offline());
        assertTrue(instance.getSerializationCommitCreatedFromSnapshotHash().isEmpty());
        assertTrue(instance.isChangedFromSerializationCommit());

        instance.getManagementInterface().writeMemory("# Glitchling\n");
        instance.getManagementInterface().appendMemory("- likes edge cases");
        SerializationCommit first = instance.serialize("tester", "genesis", null);
        assertEquals(first.hash(), instance.getSerializationCommitMostRecentSnapshotHash().orElseThrow());
        assertFalse(instance.isChangedFromSerializationCommit());
        assertTrue(first.isRoot());

        instance.getManagementInterface().appendMemory("- found a bug");
        assertTrue(instance.isChangedFromSerializationCommit());
        SerializationCommit second = instance.serialize("tester", "learn", WorkspaceSigner.generate());
        assertEquals(java.util.List.of(first.hash()), second.parentHashes());
        assertTrue(WorkspaceSigner.verify(second));

        // External edit is detected as drift.
        Files.writeString(tmp.resolve("ws1/memory.md"), "tampered", StandardCharsets.UTF_8);
        assertTrue(instance.detectWorkspaceDrift(second));
    }

    @Test
    void createFromSerializationCommitMaterialisesTheWorkspace() throws IOException {
        DaimonInstance parent = DaimonInstance.createNew(DaimonInstanceTalkMemoryMarkdown.TYPE, "Nyxbyte",
                tmp.resolve("parent"), offline());
        parent.getManagementInterface().writeMemory("# Nyxbyte\n- signs everything\n");
        parent.getManagementInterface().writeFile("notes/today.md", "hello");
        parent.getManagementInterface().writeFile(".daimon/local-only", "ignored");
        SerializationCommit commit = parent.serialize("tester", "genesis", null);
        assertEquals(java.util.Set.of("memory.md", "notes/today.md"), commit.snapshot().files().keySet());

        DaimonInstance child = DaimonInstance.createFromSerializationCommit(commit, tmp.resolve("child"), offline());
        assertEquals("Nyxbyte", child.getName());
        assertEquals(commit.hash(), child.getSerializationCommitCreatedFromSnapshotHash().orElseThrow());
        assertEquals(commit.hash(), child.getSerializationCommitMostRecentSnapshotHash().orElseThrow());
        assertFalse(child.isChangedFromSerializationCommit());
        assertEquals(parent.getMemory(), child.getMemory());
        assertFalse(child.detectWorkspaceDrift(commit));
        assertTrue(child.canFulfil(TalkContract.INSTANCE));
    }

    @Test
    void managementInterfaceIsSandboxed() {
        DaimonInstance instance = DaimonInstance.createNew(DaimonInstanceTalkMemoryMarkdown.TYPE, "Quarkle",
                tmp.resolve("ws"), offline());
        assertThrows(IllegalArgumentException.class,
                () -> instance.getManagementInterface().writeFile("../escape.md", "x"));
        assertThrows(IllegalArgumentException.class,
                () -> instance.getManagementInterface().readFile("/etc/hosts"));
    }

    @Test
    void geminiExecutorSendsMemoryAndParsesReply() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>();
        AtomicReference<String> apiKeyHeader = new AtomicReference<>();
        AtomicReference<String> path = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            path.set(exchange.getRequestURI().getPath());
            apiKeyHeader.set(exchange.getRequestHeaders().getFirst("x-goog-api-key"));
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] reply = """
                    {"candidates":[{"content":{"parts":[{"text":"Hello from "},{"text":"Gemini"}]}}]}
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, reply.length);
            exchange.getResponseBody().write(reply);
            exchange.close();
        });
        server.start();
        try {
            var executor = new DaimonInstanceImplementations.BasicGeminiTalkContractRefMemory("secret-key",
                    "gemini-2.5-flash", () -> "- I love moths", "persona",
                    URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/v1beta/"),
                    HttpClient.newHttpClient(), Duration.ofSeconds(5));
            ContractInputTypeRawText reply = executor.execute(ContractInputTypeRawText.of("hi"));
            assertEquals("Hello from Gemini", reply.text());
            assertEquals("/v1beta/models/gemini-2.5-flash:generateContent", path.get());
            assertEquals("secret-key", apiKeyHeader.get());
            assertTrue(requestBody.get().contains("I love moths"));
            assertFalse(executor.toString().contains("secret-key"));
        } finally {
            server.stop(0);
        }
    }
}
