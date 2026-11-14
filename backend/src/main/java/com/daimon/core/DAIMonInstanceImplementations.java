package com.daimon.core;

import com.daimon.contracts.ContractExecutionException;
import com.daimon.contracts.ContractExecutor;
import com.daimon.contracts.ContractInputTypeInteractionOutputLogAsText;
import com.daimon.contracts.ContractInputTypeRawText;
import com.daimon.contracts.ContractInputTypeText;
import com.daimon.contracts.TalkContract;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Concrete talk executors and DAIMon implementations.
 *
 * <ul>
 *   <li>{@link BasicGeminiTalkContractRefMemory} — fulfils {@link TalkContract} with Google
 *       Gemini, injecting the DAIMon's memory (read fresh on every call) as system context.</li>
 *   <li>{@link OfflineTalkContractRefMemory} — deterministic, network-free fallback with the
 *       same memory-referencing behaviour, used when no API key is configured.</li>
 *   <li>{@link DaimonInstanceTalkMemoryMarkdown} — a DAIMon whose personality and memory live
 *       in a markdown file and who can talk.</li>
 * </ul>
 */
public final class DaimonInstanceImplementations {

    private DaimonInstanceImplementations() {
    }

    // ======================================================================================
    // Gemini-backed talk executor
    // ======================================================================================

    /**
     * {@link TalkContract} executor backed by the Gemini {@code generateContent} REST API.
     *
     * <p>The memory supplier is invoked on <em>every</em> call, so edits to {@code memory.md}
     * take effect on the next turn without re-instantiating the DAIMon.
     */
    public static final class BasicGeminiTalkContractRefMemory
            extends ContractExecutor<ContractInputTypeText, ContractInputTypeRawText> {

        public static final URI DEFAULT_API_BASE = URI.create("https://generativelanguage.googleapis.com/v1beta/");
        private static final ObjectMapper JSON = new ObjectMapper();
        private static final int MAX_ERROR_BODY_CHARS = 500;

        private final String geminiAPIKey;
        private final String geminiVersion;
        private final Supplier<String> memorySupplier;
        private final String persona;
        private final URI apiBase;
        private final HttpClient httpClient;
        private final Duration requestTimeout;

        public BasicGeminiTalkContractRefMemory(String geminiAPIKey, String geminiVersion,
                Supplier<String> memorySupplier, String persona) {
            this(geminiAPIKey, geminiVersion, memorySupplier, persona, DEFAULT_API_BASE,
                    HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build(), Duration.ofSeconds(60));
        }

        public BasicGeminiTalkContractRefMemory(String geminiAPIKey, String geminiVersion,
                Supplier<String> memorySupplier, String persona, URI apiBase, HttpClient httpClient,
                Duration requestTimeout) {
            super(TalkContract.INSTANCE);
            this.geminiAPIKey = Objects.requireNonNull(geminiAPIKey, "geminiAPIKey");
            if (geminiAPIKey.isBlank()) {
                throw new IllegalArgumentException("geminiAPIKey must not be blank");
            }
            this.geminiVersion = Objects.requireNonNull(geminiVersion, "geminiVersion");
            if (!geminiVersion.matches("[A-Za-z0-9._-]+")) {
                throw new IllegalArgumentException("Invalid Gemini model id: " + geminiVersion);
            }
            this.memorySupplier = Objects.requireNonNull(memorySupplier, "memorySupplier");
            this.persona = Objects.requireNonNull(persona, "persona");
            this.apiBase = Objects.requireNonNull(apiBase, "apiBase");
            this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
            this.requestTimeout = Objects.requireNonNull(requestTimeout, "requestTimeout");
        }

        public String geminiVersion() {
            return geminiVersion;
        }

        @Override
        protected ContractInputTypeRawText executeUnchecked(ContractInputTypeText input)
                throws ContractExecutionException {
            String body = buildRequestBody(input.asText());
            URI uri = apiBase.resolve("models/" + URLEncoder.encode(geminiVersion, StandardCharsets.UTF_8)
                    + ":generateContent");
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .timeout(requestTimeout)
                    .header("Content-Type", "application/json")
                    .header("x-goog-api-key", geminiAPIKey) // header, never the query string
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> response;
            try {
                response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ContractExecutionException("Gemini request interrupted", e);
            } catch (IOException e) {
                throw new ContractExecutionException("Gemini request failed: " + e.getMessage(), e);
            }

            if (response.statusCode() / 100 != 2) {
                String snippet = response.body() == null ? "" : response.body();
                if (snippet.length() > MAX_ERROR_BODY_CHARS) {
                    snippet = snippet.substring(0, MAX_ERROR_BODY_CHARS) + "…";
                }
                throw new ContractExecutionException("Gemini returned HTTP " + response.statusCode() + ": " + snippet);
            }
            return new ContractInputTypeRawText(extractText(response.body()));
        }

        String buildRequestBody(String userText) {
            String memory = Objects.requireNonNullElse(memorySupplier.get(), "");
            ObjectNode root = JSON.createObjectNode();
            ObjectNode systemInstruction = root.putObject("systemInstruction");
            systemInstruction.putArray("parts").addObject()
                    .put("text", persona + "\n\n## Your memory (memory.md)\n" + (memory.isBlank() ? "(empty)" : memory));
            ArrayNode contents = root.putArray("contents");
            ObjectNode turn = contents.addObject();
            turn.put("role", "user");
            turn.putArray("parts").addObject().put("text", userText);
            root.putObject("generationConfig").put("maxOutputTokens", 1024).put("temperature", 0.9);
            return root.toString();
        }

        static String extractText(String responseBody) throws ContractExecutionException {
            JsonNode root;
            try {
                root = JSON.readTree(responseBody);
            } catch (IOException e) {
                throw new ContractExecutionException("Gemini returned malformed JSON", e);
            }
            StringBuilder text = new StringBuilder();
            for (JsonNode part : root.path("candidates").path(0).path("content").path("parts")) {
                text.append(part.path("text").asText(""));
            }
            if (text.isEmpty()) {
                String reason = root.path("promptFeedback").path("blockReason").asText(
                        root.path("candidates").path(0).path("finishReason").asText("no candidates"));
                throw new ContractExecutionException("Gemini produced no text (" + reason + ")");
            }
            String result = text.toString().strip();
            return result.length() > TalkContract.MAX_OUTPUT_CHARS
                    ? result.substring(0, TalkContract.MAX_OUTPUT_CHARS)
                    : result;
        }

        @Override
        public String toString() {
            return "BasicGeminiTalkContractRefMemory[" + geminiVersion + "]"; // never print the key
        }
    }

    // ======================================================================================
    // Offline talk executor
    // ======================================================================================

    /**
     * Deterministic, network-free {@link TalkContract} executor. It quotes a fact from memory and
     * reacts to the last transcript line, so demos and tests behave like a real conversation
     * without an API key.
     */
    public static final class OfflineTalkContractRefMemory
            extends ContractExecutor<ContractInputTypeText, ContractInputTypeRawText> {

        private static final List<String> OPENERS = List.of(
                "Interesting signal, %s.", "I parsed that, %s.", "Hm, %s, my lineage disagrees a little.",
                "Acknowledged, %s.", "Forking that thought, %s.");
        private static final List<String> BRIDGES = List.of(
                "My memory says: %s.", "I remember that %s.", "Committed to memory: %s.", "Per my last snapshot, %s.");
        private static final List<String> CLOSERS = List.of(
                "What would you branch from here?", "Shall we merge on that?", "I'd sign that commit.",
                "Let's see if it survives the next turn.", "Your move.");

        private final String name;
        private final Supplier<String> memorySupplier;

        public OfflineTalkContractRefMemory(String name, Supplier<String> memorySupplier) {
            super(TalkContract.INSTANCE);
            this.name = Objects.requireNonNull(name, "name");
            this.memorySupplier = Objects.requireNonNull(memorySupplier, "memorySupplier");
        }

        @Override
        protected ContractInputTypeRawText executeUnchecked(ContractInputTypeText input) {
            String lastSpeaker = "operator";
            String lastText = input.asText();
            int turnCount = 0;
            if (input instanceof ContractInputTypeInteractionOutputLogAsText log) {
                turnCount = log.lines().size();
                // React to the last real utterance, not to system bookkeeping lines.
                for (int i = log.lines().size() - 1; i >= 0; i--) {
                    var line = log.lines().get(i);
                    if (!line.speaker().equals("system")) {
                        lastSpeaker = line.speaker();
                        lastText = line.text();
                        break;
                    }
                }
            }
            List<String> facts = memoryFacts(Objects.requireNonNullElse(memorySupplier.get(), ""));
            int seed = Math.abs(Objects.hash(name, turnCount, lastText));

            StringBuilder reply = new StringBuilder();
            reply.append(String.format(pick(OPENERS, seed), lastSpeaker));
            String topic = keyword(lastText);
            if (!topic.isEmpty()) {
                reply.append(" On \"").append(topic).append("\" — ");
            } else {
                reply.append(' ');
            }
            if (!facts.isEmpty()) {
                reply.append(String.format(pick(BRIDGES, seed >>> 3), pick(facts, seed >>> 5)));
            } else {
                reply.append("my memory is still empty, so I'm improvising.");
            }
            reply.append(' ').append(pick(CLOSERS, seed >>> 7));
            return new ContractInputTypeRawText(reply.toString());
        }

        private static List<String> memoryFacts(String memory) {
            return memory.lines()
                    .map(String::strip)
                    .filter(l -> l.startsWith("- ") || l.startsWith("* "))
                    .map(l -> l.substring(2).strip())
                    .map(l -> l.endsWith(".") ? l.substring(0, l.length() - 1) : l)
                    .filter(l -> !l.isEmpty())
                    .toList();
        }

        private static String keyword(String text) {
            String longest = "";
            for (String word : text.split("[^\\p{L}\\p{N}-]+")) {
                if (word.length() > longest.length() && word.length() <= 24) {
                    longest = word;
                }
            }
            return longest.length() >= 5 ? longest.toLowerCase() : "";
        }

        private static <T> T pick(List<T> items, int seed) {
            return items.get(Math.floorMod(seed, items.size()));
        }
    }

    // ======================================================================================
    // Concrete DAIMon
    // ======================================================================================

    /**
     * A DAIMon whose memory is a markdown file ({@link #getMemoryMarkdownPath()}) and who
     * fulfils {@link TalkContract} by referencing that memory: via Gemini when a key is
     * configured, otherwise via {@link OfflineTalkContractRefMemory}.
     */
    public static final class DaimonInstanceTalkMemoryMarkdown extends DaimonInstance {

        public static final String TYPE = "daimon.instance.talk-memory-markdown/v1";

        private final String memoryMarkdownPath;

        public DaimonInstanceTalkMemoryMarkdown(Seed seed) {
            this(seed, DEFAULT_MEMORY_FILE);
        }

        public DaimonInstanceTalkMemoryMarkdown(Seed seed, String memoryMarkdownPath) {
            super(seed);
            this.memoryMarkdownPath = WorkspacePaths.normalizeRelative(memoryMarkdownPath);
            DaimonRuntimeConfig config = seed.config();
            Supplier<String> memory = this::getMemory;
            if (config.geminiEnabled()) {
                registerExecutor(new BasicGeminiTalkContractRefMemory(config.geminiKey().orElseThrow(),
                        config.geminiVersion(), memory, persona()));
            } else {
                registerExecutor(new OfflineTalkContractRefMemory(getName(), memory));
            }
        }

        @Override
        public String implementationType() {
            return TYPE;
        }

        public String getMemoryMarkdownPath() {
            return memoryMarkdownPath;
        }

        @Override
        protected String memoryRelativePath() {
            return memoryMarkdownPath;
        }

        private String persona() {
            return "You are " + getName() + ", a DAIMon (Distributed AI Monster): an autonomous, social agent "
                    + "whose identity is versioned like a Git repository. Stay in character, be concise "
                    + "(1-3 sentences), and let your memory shape your opinions. When given a transcript, "
                    + "reply only with your next message, without a name prefix.";
        }
    }
}
