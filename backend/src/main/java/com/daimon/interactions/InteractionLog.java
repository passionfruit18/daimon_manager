package com.daimon.interactions;

import com.daimon.contracts.ContractInputTypeInteractionOutputLogAsText;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Append-only, thread-safe transcript of an interaction. Listeners are notified on every append,
 * which is how live UIs stream the log.
 */
public final class InteractionLog {

    public enum Kind {
        SYSTEM, AGENT, HUMAN
    }

    /**
     * One log line.
     *
     * @param turn          zero-based turn index, or {@code -1} for out-of-turn entries
     * @param participantId {@code null} for system entries
     */
    public record Entry(long sequence, int turn, Kind kind, String roleName, String participantId, String speaker,
            String text, Instant timestamp) {
        public Entry {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(speaker, "speaker");
            Objects.requireNonNull(text, "text");
            Objects.requireNonNull(timestamp, "timestamp");
        }
    }

    private final List<Entry> entries = new ArrayList<>();
    private final List<Consumer<Entry>> listeners = new CopyOnWriteArrayList<>();

    public Entry append(int turn, Kind kind, String roleName, String participantId, String speaker, String text) {
        Entry entry;
        synchronized (entries) {
            entry = new Entry(entries.size(), turn, kind, roleName, participantId, speaker, text, Instant.now());
            entries.add(entry);
        }
        for (Consumer<Entry> listener : listeners) {
            try {
                listener.accept(entry);
            } catch (RuntimeException ignored) {
                // A misbehaving listener must never break the interaction loop.
            }
        }
        return entry;
    }

    public Entry appendSystem(String text) {
        return append(-1, Kind.SYSTEM, null, null, "system", text);
    }

    public List<Entry> snapshot() {
        synchronized (entries) {
            return List.copyOf(entries);
        }
    }

    public int size() {
        synchronized (entries) {
            return entries.size();
        }
    }

    public void addListener(Consumer<Entry> listener) {
        listeners.add(Objects.requireNonNull(listener, "listener"));
    }

    public void removeListener(Consumer<Entry> listener) {
        listeners.remove(listener);
    }

    /**
     * Renders the most recent {@code maxLines} entries as a contract input, so the log itself can
     * be fed back into a {@code TalkContract}.
     */
    public ContractInputTypeInteractionOutputLogAsText asContractInput(String preamble, int maxLines) {
        if (maxLines < 1) {
            throw new IllegalArgumentException("maxLines must be >= 1");
        }
        List<Entry> all = snapshot();
        List<Entry> window = all.subList(Math.max(0, all.size() - maxLines), all.size());
        return new ContractInputTypeInteractionOutputLogAsText(preamble, window.stream()
                .map(e -> new ContractInputTypeInteractionOutputLogAsText.Line(e.speaker(), e.text()))
                .toList());
    }
}
