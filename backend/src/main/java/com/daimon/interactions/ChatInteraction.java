package com.daimon.interactions;

import com.daimon.contracts.ContractInputTypeInteractionOutputLogAsText;
import com.daimon.contracts.ContractInputTypeRawText;
import com.daimon.contracts.ContractParticipant;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Round-robin multi-agent chat. Each turn, the next {@link TalkerRole} receives the recent
 * {@link InteractionLog} as a {@link ContractInputTypeInteractionOutputLogAsText} and its reply
 * is appended to the log. The loop stops after {@code turnLimit} agent turns or on cancel.
 *
 * <p>Human-in-the-loop: {@link #submitHumanInput(String, String)} queues a message that is
 * appended to the log at the start of the next turn, so every subsequent speaker sees it. To
 * let a human take an actual turn, bind a {@link HumanParticipant} to one of the talker roles.
 */
public final class ChatInteraction extends Interaction {

    public static final int MAX_TURN_LIMIT = 500;
    private static final int DEFAULT_CONTEXT_WINDOW = 40;

    private final String topic;
    private final int turnLimit;
    private final List<TalkerRole> talkers;
    private final int contextWindow;
    private final Duration turnDelay;
    private final Queue<HumanInput> pendingHumanInputs = new ConcurrentLinkedQueue<>();
    private volatile int completedTurns;

    private record HumanInput(String author, String text) {
    }

    private ChatInteraction(String topic, int turnLimit, List<TalkerRole> talkers, int contextWindow,
            Duration turnDelay) {
        super("Chat: " + topic, talkers);
        this.topic = topic;
        this.turnLimit = turnLimit;
        this.talkers = List.copyOf(talkers);
        this.contextWindow = contextWindow;
        this.turnDelay = turnDelay;
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    protected void executeLoop() throws Exception {
        InteractionLog log = interactionLog();
        log.appendSystem("Chat started on topic: " + topic + " (turn limit " + turnLimit + ")");
        for (int turn = 0; turn < turnLimit; turn++) {
            if (isCancellationRequested()) {
                log.appendSystem("Chat cancelled after " + turn + " turns.");
                return;
            }
            drainHumanInputs(turn);

            TalkerRole role = talkers.get(turn % talkers.size());
            ContractParticipant speaker = requireParticipant(role);
            ContractInputTypeInteractionOutputLogAsText context = log.asContractInput(preambleFor(role, speaker),
                    contextWindow);
            ContractInputTypeRawText reply = role.talk(speaker, context);
            log.append(turn, kindOf(speaker), role.name(), speaker.participantId(), speaker.displayName(),
                    reply.text());
            completedTurns = turn + 1;

            if (!turnDelay.isZero() && turn + 1 < turnLimit) {
                Thread.sleep(turnDelay.toMillis());
            }
        }
        drainHumanInputs(turnLimit);
        log.appendSystem("Chat reached its turn limit (" + turnLimit + ").");
    }

    /** Queues a human message for inclusion before the next turn. Safe from any thread. */
    public void submitHumanInput(String author, String text) {
        Objects.requireNonNull(text, "text");
        if (text.isBlank()) {
            throw new IllegalArgumentException("Human input must not be blank");
        }
        if (status().isTerminal()) {
            throw new IllegalStateException("Chat has already finished");
        }
        pendingHumanInputs.add(new HumanInput(author == null || author.isBlank() ? "operator" : author.strip(), text.strip()));
    }

    private void drainHumanInputs(int turn) {
        HumanInput input;
        while ((input = pendingHumanInputs.poll()) != null) {
            interactionLog().append(turn, InteractionLog.Kind.HUMAN, null, null, input.author(), input.text());
        }
    }

    private String preambleFor(TalkerRole role, ContractParticipant speaker) {
        return "You are " + speaker.displayName() + ", playing the role '" + role.name()
                + "' in a group chat between DAIMons. Topic: " + topic
                + ". Reply with your next chat message only.";
    }

    private static InteractionLog.Kind kindOf(ContractParticipant participant) {
        return participant instanceof HumanParticipant ? InteractionLog.Kind.HUMAN : InteractionLog.Kind.AGENT;
    }

    public String topic() {
        return topic;
    }

    public int turnLimit() {
        return turnLimit;
    }

    public int completedTurns() {
        return completedTurns;
    }

    public List<TalkerRole> talkers() {
        return talkers;
    }

    /** Builds a chat and binds one {@link TalkerRole} per participant, in speaking order. */
    public static final class Builder {
        private String topic = "open conversation";
        private int turnLimit = 6;
        private int contextWindow = DEFAULT_CONTEXT_WINDOW;
        private Duration turnDelay = Duration.ZERO;
        private final List<ContractParticipant> participants = new ArrayList<>();

        public Builder topic(String topic) {
            Objects.requireNonNull(topic, "topic");
            if (topic.isBlank()) {
                throw new IllegalArgumentException("topic must not be blank");
            }
            this.topic = topic.strip();
            return this;
        }

        public Builder turnLimit(int turnLimit) {
            if (turnLimit < 1 || turnLimit > MAX_TURN_LIMIT) {
                throw new IllegalArgumentException("turnLimit must be between 1 and " + MAX_TURN_LIMIT);
            }
            this.turnLimit = turnLimit;
            return this;
        }

        public Builder contextWindow(int lines) {
            if (lines < 1) {
                throw new IllegalArgumentException("contextWindow must be >= 1");
            }
            this.contextWindow = lines;
            return this;
        }

        /** Pause between turns; makes live UIs readable. */
        public Builder turnDelay(Duration delay) {
            Objects.requireNonNull(delay, "delay");
            if (delay.isNegative()) {
                throw new IllegalArgumentException("turnDelay must not be negative");
            }
            this.turnDelay = delay;
            return this;
        }

        public Builder participant(ContractParticipant participant) {
            participants.add(Objects.requireNonNull(participant, "participant"));
            return this;
        }

        public Builder participants(List<? extends ContractParticipant> list) {
            list.forEach(this::participant);
            return this;
        }

        public ChatInteraction build() {
            if (participants.isEmpty()) {
                throw new IllegalStateException("A chat needs at least one participant");
            }
            List<TalkerRole> roles = new ArrayList<>();
            for (int i = 0; i < participants.size(); i++) {
                roles.add(new TalkerRole("talker-" + (i + 1)));
            }
            ChatInteraction chat = new ChatInteraction(topic, turnLimit, roles, contextWindow, turnDelay);
            for (int i = 0; i < roles.size(); i++) {
                chat.bind(roles.get(i), participants.get(i));
            }
            return chat;
        }
    }
}
