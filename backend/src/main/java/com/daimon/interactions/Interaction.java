package com.daimon.interactions;

import com.daimon.contracts.ContractParticipant;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A structured exchange between participants, each bound to a {@link Role}.
 *
 * <p>Lifecycle: {@code PENDING} (bind roles) → {@code RUNNING} → {@code COMPLETED} |
 * {@code CANCELLED} | {@code FAILED}. {@link #run()} is a template method that handles the
 * lifecycle; subclasses implement only {@link #executeLoop()}.
 */
public abstract class Interaction {

    public enum Status {
        PENDING, RUNNING, COMPLETED, CANCELLED, FAILED;

        public boolean isTerminal() {
            return this == COMPLETED || this == CANCELLED || this == FAILED;
        }
    }

    private final String id = UUID.randomUUID().toString();
    private final String title;
    private final List<Role> roles;
    private final Map<Role, ContractParticipant> bindings = Collections.synchronizedMap(new LinkedHashMap<>());
    private final InteractionLog interactionLog = new InteractionLog();
    private final AtomicReference<Status> status = new AtomicReference<>(Status.PENDING);
    private final AtomicBoolean cancellationRequested = new AtomicBoolean();
    private final Instant createdAt = Instant.now();
    private volatile String failureReason;

    protected Interaction(String title, List<? extends Role> roles) {
        this.title = Objects.requireNonNull(title, "title");
        this.roles = List.copyOf(Objects.requireNonNull(roles, "roles"));
        if (this.roles.isEmpty()) {
            throw new IllegalArgumentException("An interaction needs at least one role");
        }
        if (this.roles.stream().map(Role::name).distinct().count() != this.roles.size()) {
            throw new IllegalArgumentException("Role names must be unique");
        }
    }

    /** The interaction-specific loop. Called once, on the running thread. */
    protected abstract void executeLoop() throws Exception;

    // ------------------------------------------------------------------ binding

    public final void bind(Role role, ContractParticipant participant) {
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(participant, "participant");
        if (status.get() != Status.PENDING) {
            throw new IllegalStateException("Roles can only be bound while PENDING");
        }
        if (!roles.contains(role)) {
            throw new IllegalArgumentException(role + " is not part of this interaction");
        }
        if (!role.canBeFilledBy(participant)) {
            throw new IllegalArgumentException(participant.displayName() + " cannot fulfil "
                    + role.requiredContract().id() + " required by " + role);
        }
        bindings.put(role, participant);
    }

    public final Optional<ContractParticipant> participantFor(Role role) {
        return Optional.ofNullable(bindings.get(role));
    }

    protected final ContractParticipant requireParticipant(Role role) {
        return participantFor(role).orElseThrow(() -> new IllegalStateException(role + " is unbound"));
    }

    // ------------------------------------------------------------------ lifecycle

    /** Runs the interaction on the calling thread. */
    public final Status run() {
        List<Role> unbound = roles.stream().filter(r -> !bindings.containsKey(r)).toList();
        if (!unbound.isEmpty()) {
            throw new IllegalStateException("Unbound roles: " + unbound);
        }
        if (!status.compareAndSet(Status.PENDING, Status.RUNNING)) {
            throw new IllegalStateException("Interaction already started (" + status.get() + ")");
        }
        try {
            executeLoop();
            status.set(cancellationRequested.get() ? Status.CANCELLED : Status.COMPLETED);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            interactionLog.appendSystem("Interaction interrupted.");
            status.set(Status.CANCELLED);
        } catch (Exception e) {
            failureReason = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            interactionLog.appendSystem("Interaction failed: " + failureReason);
            status.set(Status.FAILED);
        }
        return status.get();
    }

    /** Runs the interaction on {@code executor}; the future completes with the final status. */
    public final CompletableFuture<Status> runAsync(Executor executor) {
        return CompletableFuture.supplyAsync(this::run, executor);
    }

    /** Requests cooperative cancellation; the loop stops at the next turn boundary. */
    public final void cancel() {
        cancellationRequested.set(true);
        status.compareAndSet(Status.PENDING, Status.CANCELLED);
    }

    protected final boolean isCancellationRequested() {
        return cancellationRequested.get() || Thread.currentThread().isInterrupted();
    }

    // ------------------------------------------------------------------ accessors

    public final String id() {
        return id;
    }

    public final String title() {
        return title;
    }

    public final List<Role> roles() {
        return roles;
    }

    public final Map<Role, ContractParticipant> bindings() {
        synchronized (bindings) {
            return Collections.unmodifiableMap(new LinkedHashMap<>(bindings));
        }
    }

    public final InteractionLog interactionLog() {
        return interactionLog;
    }

    public final Status status() {
        return status.get();
    }

    public final Optional<String> failureReason() {
        return Optional.ofNullable(failureReason);
    }

    public final Instant createdAt() {
        return createdAt;
    }
}
