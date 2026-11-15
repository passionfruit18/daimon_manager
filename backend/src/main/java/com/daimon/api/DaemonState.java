package com.daimon.api;

import com.daimon.api.Dtos.ChannelDto;
import com.daimon.api.Dtos.CommitDto;
import com.daimon.api.Dtos.InstanceDto;
import com.daimon.api.Dtos.InteractionDto;
import com.daimon.api.Dtos.LogEntryDto;
import com.daimon.api.Dtos.ParticipantDto;
import com.daimon.core.DaimonInstance;
import com.daimon.core.DaimonInstanceImplementations.DaimonInstanceTalkMemoryMarkdown;
import com.daimon.core.DaimonRuntimeConfig;
import com.daimon.core.SerializationCommit;
import com.daimon.core.SerializationCommitStore;
import com.daimon.core.WorkspaceSigner;
import com.daimon.interactions.ChatInteraction;
import com.daimon.interactions.InteractionLog;
import com.daimon.storage.DaimonRepository;
import com.daimon.storage.DaimonRepository.InstanceRecord;
import com.daimon.storage.DaimonRepository.LoadResult;

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
import java.util.Comparator;
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
 * The daemon's single source of truth: commit graph, live instances, channels and interactions.
 * REST resources are thin adapters over this bean.
 *
 * <p>Everything is persisted through {@link DaimonRepository} as it changes, and reloaded on
 * startup: commits and branch refs, the instance registry, channels, the signing key, and the
 * logs of finished interactions. Workspace files are written directly by each instance.
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

    @ConfigProperty(name = "daimon.gemini.model", defaultValue = DaimonRuntimeConfig.DEFAULT_GEMINI_VERSION)
    String geminiModel;

    @ConfigProperty(name = "daimon.seed-demo", defaultValue = "true")
    boolean seedDemo;

    private DaimonRuntimeConfig config;
    private final SerializationCommitStore store = new SerializationCommitStore();
    private DaimonRepository repository;
    private WorkspaceSigner signer;
    private final Map<String, DaimonInstance> instances = Collections.synchronizedMap(new LinkedHashMap<>());
    private final Map<String, String> instanceBranches = new ConcurrentHashMap<>();
    private final Map<String, String> instanceCreatedAt = new ConcurrentHashMap<>();
    /** Finished interactions loaded from disk (read-only). Live ones are in {@link #interactions}. */
    private final Map<String, InteractionDto> archivedInteractions = new ConcurrentHashMap<>();
    private final Map<String, Channel> channels = Collections.synchronizedMap(new LinkedHashMap<>());
    private final Map<String, ChatRecord> interactions = Collections.synchronizedMap(new LinkedHashMap<>());
    private final ExecutorService interactionExecutor = Executors.newVirtualThreadPerTaskExecutor();

    private record Channel(String name, String description, Set<String> teamIds, Instant createdAt) {
    }

    private record ChatRecord(ChatInteraction chat, String channel) {
    }

    void onStart(@Observes StartupEvent event) {
        config = new DaimonRuntimeConfig(Path.of(home), geminiApiKey.orElse(null), geminiModel);
        LOG.infof("DAIMon daemon %s starting with %s", VERSION, config);
        try {
            repository = new DaimonRepository(config.home());
            signer = repository.loadOrCreateSigner();
            load();
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot open DAIMon repository at " + config.home(), e);
        }
        LOG.infof("Loaded %d commits, %d instances, %d channels, %d past interactions from %s", store.size(),
                instances.size(), channels.size(), archivedInteractions.size(), repository.root());
        if (seedDemo && store.size() == 0 && instances.isEmpty()) {
            try {
                DemoSeeder.seed(this);
                LOG.info("First run: seeded demo DAIMons");
            } catch (IOException | RuntimeException e) {
                LOG.error("Failed to seed demo DAIMons", e);
            }
        }
    }

    // ------------------------------------------------------------------ persistence

    /** Restores state from the repository. Damaged entries are skipped with a warning. */
    private void load() throws IOException {
        LoadResult<SerializationCommit> commits = repository.loadCommits();
        commits.items().forEach(store::put);
        commits.problems().forEach(LOG::warn);

        repository.loadRefs().forEach((branch, hash) -> {
            if (store.contains(hash)) {
                store.updateBranch(branch, hash);
            } else {
                LOG.warnf("Dropping branch %s: commit %s is missing", branch, hash);
            }
        });

        LoadResult<InstanceRecord> records = repository.loadInstances();
        records.problems().forEach(LOG::warn);
        List<InstanceRecord> sorted = new ArrayList<>(records.items());
        sorted.sort(Comparator.comparing(r -> Objects.requireNonNullElse(r.createdAt(), "")));
        for (InstanceRecord r : sorted) {
            try {
                SerializationCommit head = r.mostRecentHash() == null ? null : store.get(r.mostRecentHash())
                        .orElseThrow(() -> new IOException("most recent commit " + r.mostRecentHash() + " is missing"));
                DaimonInstance instance = DaimonInstance.restore(r.id(), r.implementationType(), r.name(),
                        Path.of(r.workspace()), r.createdFromHash(), head, config);
                instances.put(instance.getId(), instance);
                instanceBranches.put(instance.getId(), r.branch());
                instanceCreatedAt.put(instance.getId(), Objects.requireNonNullElse(r.createdAt(), Instant.now().toString()));
            } catch (IOException | RuntimeException e) {
                LOG.warnf("Could not restore DAIMon %s (%s): %s", r.name(), r.id(), e.getMessage());
            }
        }

        for (ChannelDto c : repository.readJson("channels.json", ChannelDto[].class).orElse(new ChannelDto[0])) {
            Set<String> team = Collections.synchronizedSet(new LinkedHashSet<>());
            c.teamIds().stream().filter(instances::containsKey).forEach(team::add);
            channels.put(c.name(), new Channel(c.name(), c.description(), team, Instant.parse(c.createdAt())));
        }

        LoadResult<InteractionDto> past = repository.readAllJson("interactions", InteractionDto.class);
        past.problems().forEach(LOG::warn);
        past.items().forEach(i -> archivedInteractions.put(i.id(), i));
    }

    private void persistInstance(DaimonInstance instance) {
        try {
            repository.saveInstance(new InstanceRecord(DaimonRepository.FORMAT_VERSION, instance.getId(),
                    instance.getName(), instance.implementationType(), instance.getWorkspace().toString(),
                    instanceBranches.get(instance.getId()),
                    instance.getSerializationCommitCreatedFromSnapshotHash().orElse(null),
                    instance.getSerializationCommitMostRecentSnapshotHash().orElse(null),
                    instanceCreatedAt.computeIfAbsent(instance.getId(), id -> Instant.now().toString())));
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to save DAIMon " + instance.getName(), e);
        }
    }

    private void persistCommitAndRefs(SerializationCommit commit) {
        try {
            repository.saveCommit(commit);
            repository.saveRefs(store.branches());
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to save commit " + commit.shortHash(), e);
        }
    }

    private void persistChannels() {
        try {
            repository.writeJson("channels.json", listChannels());
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to save channels", e);
        }
    }

    private void archiveInteraction(ChatRecord record) {
        InteractionDto dto = toDto(record);
        try {
            repository.writeJson("interactions/" + DaimonRepository.requireSafeId(dto.id()) + ".json", dto);
        } catch (IOException | RuntimeException e) {
            LOG.warnf(e, "Could not save interaction %s", dto.id());
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

    public DaimonRuntimeConfig config() {
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

    public DaimonInstance requireInstance(String id) {
        DaimonInstance instance = instances.get(id);
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
    DaimonInstance register(DaimonInstance instance, String branch) {
        instances.put(instance.getId(), instance);
        instanceBranches.put(instance.getId(), branch);
        persistInstance(instance);
        return instance;
    }

    /** Creates a brand-new DAIMon with a genesis commit. */
    public InstanceDto createInstance(String name, String memory) throws IOException {
        requireText(name, "name");
        String slug = slug(name);
        Path workspace = config.workspacesRoot().resolve(slug + "-" + UUID.randomUUID().toString().substring(0, 8));
        DaimonInstance instance = DaimonInstance.createNew(DaimonInstanceTalkMemoryMarkdown.TYPE, name.strip(),
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
        DaimonInstance instance = requireInstance(instanceId);
        SerializationCommit commit = store.put(instance.serialize(
                author == null || author.isBlank() ? "operator" : author, message, signer));
        store.updateBranch(instanceBranches.get(instanceId), commit.hash());
        persistCommitAndRefs(commit);
        persistInstance(instance);
        return toDto(commit);
    }

    /** Checks out a commit into a new workspace and starts a new live instance from it. */
    public InstanceDto checkout(String ref) {
        SerializationCommit commit = requireCommit(ref);
        String slug = slug(commit.instanceName());
        Path workspace = config.workspacesRoot().resolve(slug + "-" + commit.shortHash() + "-"
                + UUID.randomUUID().toString().substring(0, 4));
        DaimonInstance instance = DaimonInstance.createFromSerializationCommit(commit, workspace, config);
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
        DaimonInstance instance = requireInstance(id);
        instance.getManagementInterface().writeMemory(content);
        return toDto(instance);
    }

    public InstanceDto appendMemory(String id, String entry) throws IOException {
        requireText(entry, "entry");
        DaimonInstance instance = requireInstance(id);
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
        persistChannels();
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
        persistChannels();
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
        List<DaimonInstance> participants = ids.stream().map(this::requireInstance).toList();
        ChatInteraction chat = ChatInteraction.builder()
                .topic(request.topic() == null || request.topic().isBlank() ? "open conversation" : request.topic())
                .turnLimit(request.turnLimit() == null ? 6 : request.turnLimit())
                .turnDelay(Duration.ofMillis(Math.max(0, Math.min(10_000,
                        request.turnDelayMs() == null ? 1_200 : request.turnDelayMs()))))
                .participants(participants)
                .build();
        ChatRecord record = new ChatRecord(chat, request.channel());
        interactions.put(chat.id(), record);
        chat.runAsync(interactionExecutor).whenComplete((status, error) -> {
            rememberChat(chat, participants);
            archiveInteraction(record);
        });
        return toDto(record);
    }

    /** After a chat, each participant remembers it — leaving its workspace dirty until committed. */
    private void rememberChat(ChatInteraction chat, List<DaimonInstance> participants) {
        if (chat.completedTurns() == 0) {
            return;
        }
        for (DaimonInstance participant : participants) {
            String others = participants.stream().filter(p -> p != participant).map(DaimonInstance::getName)
                    .distinct().reduce((a, b) -> a + ", " + b).orElse("myself");
            try {
                participant.getManagementInterface().appendMemory(
                        "- Chatted with " + others + " about \"" + chat.topic() + "\".");
            } catch (IOException e) {
                LOG.warnf(e, "Could not update memory of %s", participant.getName());
            }
        }
    }

    /** Live and archived interactions, newest first. */
    public List<InteractionDto> listInteractions() {
        List<InteractionDto> result = new ArrayList<>();
        synchronized (interactions) {
            interactions.values().forEach(r -> result.add(toDto(r)));
        }
        archivedInteractions.values().stream().filter(a -> !interactions.containsKey(a.id())).forEach(result::add);
        result.sort(Comparator.comparing(InteractionDto::createdAt).reversed());
        return result;
    }

    public InteractionDto interaction(String id) {
        ChatRecord live = interactions.get(id);
        if (live != null) {
            return toDto(live);
        }
        InteractionDto archived = archivedInteractions.get(id);
        if (archived == null) {
            throw new NotFoundException("No interaction " + id);
        }
        return archived;
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
            if (archivedInteractions.containsKey(id)) {
                throw new IllegalStateException("Interaction " + id + " finished in a previous session");
            }
            throw new NotFoundException("No interaction " + id);
        }
        return record;
    }

    // ------------------------------------------------------------------ mapping

    InstanceDto toDto(DaimonInstance instance) {
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
        persistCommitAndRefs(stored);
        return stored;
    }
}
