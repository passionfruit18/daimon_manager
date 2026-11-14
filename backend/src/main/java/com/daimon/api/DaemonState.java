package com.daimon.api;

import com.daimon.api.Dtos.ChannelDto;
import com.daimon.api.Dtos.CommitDto;
import com.daimon.api.Dtos.InstanceDto;
import com.daimon.api.Dtos.InteractionDto;
import com.daimon.api.Dtos.LogEntryDto;
import com.daimon.api.Dtos.ParticipantDto;
import com.daimon.core.DAIMonInstance;
import com.daimon.core.DAIMonInstanceImplementations.DAIMonInstanceTalkMemoryMarkdown;
import com.daimon.core.DAIMonRuntimeConfig;
import com.daimon.core.SerializationCommit;
import com.daimon.core.SerializationCommitStore;
import com.daimon.core.WorkspaceSigner;
import com.daimon.interactions.ChatInteraction;
import com.daimon.interactions.InteractionLog;

import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.ws.rs.NotFoundException;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * The daemon's single source of truth: commit graph, live instances, channels and running
 * interactions. REST resources are thin adapters over this bean.
 */
@ApplicationScoped
public class DaemonState {

    private static final Logger LOG = Logger.getLogger(DaemonState.class);
    public static final String VERSION = "0.1.0";
    private static final int MAX_CONCURRENT_INTERACTIONS = 16;

    @ConfigProperty(name = "daimon.home")
    String home;

    @ConfigProperty(name = "daimon.gemini.api-key")
    Optional<String> geminiApiKey;

    @ConfigProperty(name = "daimon.gemini.model", defaultValue = DAIMonRuntimeConfig.DEFAULT_GEMINI_VERSION)
    String geminiModel;

    @ConfigProperty(name = "daimon.seed-demo", defaultValue = "true")
    boolean seedDemo;

    private DAIMonRuntimeConfig config;
    private final SerializationCommitStore store = new SerializationCommitStore();
    private final WorkspaceSigner signer = WorkspaceSigner.generate();
    private final Map<String, DAIMonInstance> instances = Collections.synchronizedMap(new LinkedHashMap<>());
    private final Map<String, String> instanceBranches = new ConcurrentHashMap<>();
    private final Map<String, Channel> channels = Collections.synchronizedMap(new LinkedHashMap<>());
    private final Map<String, ChatRecord> interactions = Collections.synchronizedMap(new LinkedHashMap<>());
    private final ExecutorService interactionExecutor = Executors.newVirtualThreadPerTaskExecutor();

    private record Channel(String name, String description, Set<String> teamIds, Instant createdAt) {
    }

    private record ChatRecord(ChatInteraction chat, String channel) {
    }

    void onStart(@Observes StartupEvent event) {
        config = new DAIMonRuntimeConfig(Path.of(home), geminiApiKey.orElse(null), geminiModel);
        LOG.infof("DAIMon daemon %s starting with %s", VERSION, config);
        if (seedDemo) {
            try {
                DemoSeeder.seed(this);
            } catch (IOException | RuntimeException e) {
                LOG.error("Failed to seed demo DAIMons", e);
            }
        }
    }

    void onStop(@Observes ShutdownEvent event) {
        interactions.values().forEach(r -> r.chat().cancel());
        interactionExecutor.shutdown();
        try {
            interactionExecutor.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ------------------------------------------------------------------ accessors

    public DAIMonRuntimeConfig config() {
        return config;
    }

    public SerializationCommitStore store() {
        return store;
    }

    public WorkspaceSigner signer() {
        return signer;
    }

    public int instanceCount() {
        return instances.size();
    }

    public DAIMonInstance requireInstance(String id) {
        DAIMonInstance instance = instances.get(id);
        if (instance == null) {
            throw new NotFoundException("No DAIMon instance " + id);
        }
        return instance;
    }

    public SerializationCommit requireCommit(String ref) {
        return store.resolve(ref).orElseThrow(() -> new NotFoundException("No commit or branch " + ref));
    }

    // ------------------------------------------------------------------ instances & commits

    public List<InstanceDto> listInstances() {
        synchronized (instances) {
            return instances.values().stream().map(this::toDto).toList();
        }
    }

    /** Registers a live instance and the branch its future commits will advance. */
    DAIMonInstance register(DAIMonInstance instance, String branch) {
        instances.put(instance.getId(), instance);
        instanceBranches.put(instance.getId(), branch);
        return instance;
    }

    /** Creates a brand-new DAIMon with a genesis commit. */
    public InstanceDto createInstance(String name, String memory) throws IOException {
        requireText(name, "name");
        String slug = slug(name);
        Path workspace = config.workspacesRoot().resolve(slug + "-" + UUID.randomUUID().toString().substring(0, 8));
        DAIMonInstance instance = DAIMonInstance.createNew(DAIMonInstanceTalkMemoryMarkdown.TYPE, name.strip(),
                workspace, config);
        instance.getManagementInterface().writeMemory(memory == null || memory.isBlank()
                ? "# " + name.strip() + "\n\n- I was just hatched.\n"
                : memory);
        register(instance, slug + "/main");
        commit(instance.getId(), "Hatch " + name.strip(), "operator");
        return toDto(instance);
    }

    /** Serializes an instance's workspace into a signed commit and advances its branch. */
    public CommitDto commit(String instanceId, String message, String author) throws IOException {
        requireText(message, "message");
        DAIMonInstance instance = requireInstance(instanceId);
        SerializationCommit commit = store.put(instance.serialize(
                author == null || author.isBlank() ? "operator" : author, message, signer));
        store.updateBranch(instanceBranches.get(instanceId), commit.hash());
        return toDto(commit);
    }

    /** Checks out a commit into a new workspace and starts a new live instance from it. */
    public InstanceDto checkout(String ref) {
        SerializationCommit commit = requireCommit(ref);
        String slug = slug(commit.instanceName());
        Path workspace = config.workspacesRoot().resolve(slug + "-" + commit.shortHash() + "-"
                + UUID.randomUUID().toString().substring(0, 4));
        DAIMonInstance instance = DAIMonInstance.createFromSerializationCommit(commit, workspace, config);
        register(instance, slug + "/fork-" + commit.shortHash() + "-" + instance.getId().substring(0, 4));
        return toDto(instance);
    }

    public CommitDto commitDto(String ref) {
        return toDto(requireCommit(ref));
    }

    public Dtos.CommitGraphDto graph() {
        return new Dtos.CommitGraphDto(store.all().stream().map(this::toDto).toList(), store.branches());
    }

    public String readMemory(String id) {
        return requireInstance(id).getMemory();
    }

    public InstanceDto writeMemory(String id, String content) throws IOException {
        Objects.requireNonNull(content, "content");
        DAIMonInstance instance = requireInstance(id);
        instance.getManagementInterface().writeMemory(content);
        return toDto(instance);
    }

    public InstanceDto appendMemory(String id, String entry) throws IOException {
        requireText(entry, "entry");
        DAIMonInstance instance = requireInstance(id);
        instance.getManagementInterface().appendMemory(entry.startsWith("- ") ? entry : "- " + entry);
        return toDto(instance);
    }

    // ------------------------------------------------------------------ channels

    public List<ChannelDto> listChannels() {
        synchronized (channels) {
            return channels.values().stream().map(DaemonState::toDto).toList();
        }
    }

    public ChannelDto createChannel(String name, String description) {
        requireText(name, "name");
        String normalized = name.strip().startsWith("#") ? name.strip() : "#" + name.strip();
        if (!normalized.matches("#[a-z0-9][a-z0-9-]{0,40}")) {
            throw new IllegalArgumentException("Channel names are lowercase letters, digits and dashes");
        }
        Channel channel = new Channel(normalized, description == null ? "" : description.strip(),
                Collections.synchronizedSet(new LinkedHashSet<>()), Instant.now());
        if (channels.putIfAbsent(normalized, channel) != null) {
            throw new IllegalStateException("Channel " + normalized + " already exists");
        }
        return toDto(channel);
    }

    public ChannelDto setTeam(String channelName, List<String> instanceIds) {
        Channel channel = channels.get(channelName.startsWith("#") ? channelName : "#" + channelName);
        if (channel == null) {
            throw new NotFoundException("No channel " + channelName);
        }
        List<String> ids = instanceIds == null ? List.of() : instanceIds;
        ids.forEach(this::requireInstance);
        synchronized (channel.teamIds()) {
            channel.teamIds().clear();
            channel.teamIds().addAll(ids);
        }
        return toDto(channel);
    }

    // ------------------------------------------------------------------ interactions

    public InteractionDto startChat(Dtos.ChatRequest request) {
        Objects.requireNonNull(request, "request");
        long running = interactions.values().stream().filter(r -> !r.chat().status().isTerminal()).count();
        if (running >= MAX_CONCURRENT_INTERACTIONS) {
            throw new IllegalStateException("Too many concurrent interactions (" + running + ")");
        }
        List<String> ids = request.participantIds() == null ? List.of() : request.participantIds();
        if (ids.isEmpty() && request.channel() != null) {
            Channel channel = channels.get(request.channel());
            if (channel != null) {
                ids = new ArrayList<>(channel.teamIds());
            }
        }
        if (ids.isEmpty()) {
            throw new IllegalArgumentException("Choose at least one participant (or a channel with a team)");
        }
        List<DAIMonInstance> participants = ids.stream().map(this::requireInstance).toList();
        ChatInteraction chat = ChatInteraction.builder()
                .topic(request.topic() == null || request.topic().isBlank() ? "open conversation" : request.topic())
                .turnLimit(request.turnLimit() == null ? 6 : request.turnLimit())
                .turnDelay(Duration.ofMillis(Math.max(0, Math.min(10_000,
                        request.turnDelayMs() == null ? 1_200 : request.turnDelayMs()))))
                .participants(participants)
                .build();
        interactions.put(chat.id(), new ChatRecord(chat, request.channel()));
        chat.runAsync(interactionExecutor).whenComplete((status, error) -> rememberChat(chat, participants));
        return toDto(interactions.get(chat.id()));
    }

    /** After a chat, each participant remembers it — leaving its workspace dirty until committed. */
    private void rememberChat(ChatInteraction chat, List<DAIMonInstance> participants) {
        if (chat.completedTurns() == 0) {
            return;
        }
        for (DAIMonInstance participant : participants) {
            String others = participants.stream().filter(p -> p != participant).map(DAIMonInstance::getName)
                    .distinct().reduce((a, b) -> a + ", " + b).orElse("myself");
            try {
                participant.getManagementInterface().appendMemory(
                        "- Chatted with " + others + " about \"" + chat.topic() + "\".");
            } catch (IOException e) {
                LOG.warnf(e, "Could not update memory of %s", participant.getName());
            }
        }
    }

    public List<InteractionDto> listInteractions() {
        synchronized (interactions) {
            List<InteractionDto> result = new ArrayList<>(interactions.values().stream().map(this::toDto).toList());
            Collections.reverse(result);
            return result;
        }
    }

    public InteractionDto interaction(String id) {
        return toDto(requireChat(id));
    }

    public InteractionDto submitHumanInput(String id, String author, String text) {
        ChatRecord record = requireChat(id);
        record.chat().submitHumanInput(author, text);
        return toDto(record);
    }

    public InteractionDto cancel(String id) {
        ChatRecord record = requireChat(id);
        record.chat().cancel();
        return toDto(record);
    }

    private ChatRecord requireChat(String id) {
        ChatRecord record = interactions.get(id);
        if (record == null) {
            throw new NotFoundException("No interaction " + id);
        }
        return record;
    }

    // ------------------------------------------------------------------ mapping

    InstanceDto toDto(DAIMonInstance instance) {
        try {
            return new InstanceDto(instance.getId(), instance.getName(), instance.implementationType(),
                    instance.getWorkspace().toString(), instanceBranches.get(instance.getId()),
                    instance.getSerializationCommitMostRecentSnapshotHash().orElse(null),
                    instance.getSerializationCommitCreatedFromSnapshotHash().orElse(null),
                    instance.isChangedFromSerializationCommit(), instance.getMemory(),
                    instance.getManagementInterface().listFiles(),
                    instance.supportedContracts().stream().map(c -> c.id()).sorted().toList());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    CommitDto toDto(SerializationCommit commit) {
        List<String> branches = store.branches().entrySet().stream()
                .filter(e -> e.getValue().equals(commit.hash())).map(Map.Entry::getKey).toList();
        return new CommitDto(commit.hash(), commit.shortHash(), commit.parentHashes(), commit.treeHash(),
                commit.instanceName(), commit.implementationType(), commit.author(), commit.message(),
                commit.createdAt().toString(), commit.isSigned(), WorkspaceSigner.verify(commit), branches,
                List.copyOf(commit.snapshot().files().keySet()));
    }

    private static ChannelDto toDto(Channel channel) {
        synchronized (channel.teamIds()) {
            return new ChannelDto(channel.name(), channel.description(), List.copyOf(channel.teamIds()),
                    channel.createdAt().toString());
        }
    }

    private InteractionDto toDto(ChatRecord record) {
        ChatInteraction chat = record.chat();
        List<ParticipantDto> participants = chat.bindings().entrySet().stream()
                .map(e -> new ParticipantDto(e.getValue().participantId(), e.getValue().displayName(),
                        e.getKey().name()))
                .toList();
        List<LogEntryDto> log = chat.interactionLog().snapshot().stream().map(DaemonState::toDto).toList();
        return new InteractionDto(chat.id(), chat.title(), chat.topic(), record.channel(), chat.status().name(),
                chat.turnLimit(), chat.completedTurns(), participants, log, chat.failureReason().orElse(null),
                chat.createdAt().toString());
    }

    private static LogEntryDto toDto(InteractionLog.Entry e) {
        return new LogEntryDto(e.sequence(), e.turn(), e.kind().name(), e.speaker(), e.participantId(), e.roleName(),
                e.text(), e.timestamp().toString());
    }

    // ------------------------------------------------------------------ helpers

    static String slug(String name) {
        String slug = name.toLowerCase().replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
        return slug.isEmpty() ? "daimon" : slug;
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }

    /** Puts a pre-built commit (e.g. a merge) into the store and points a branch at it. */
    SerializationCommit storeCommit(SerializationCommit commit, String branch) {
        SerializationCommit stored = store.put(signer.sign(commit));
        store.updateBranch(branch, stored.hash());
        return stored;
    }
}
