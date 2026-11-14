package com.daimon.contracts;

import java.util.List;
import java.util.Objects;

/**
 * An immutable snapshot of an interaction log, rendered as a transcript.
 *
 * <p>This type deliberately has no dependency on the interactions package: interactions build
 * it from their log (see {@code InteractionLog#asContractInput}), and contracts read it as
 * text.
 *
 * @param preamble instructions placed above the transcript (may be empty)
 * @param lines    transcript lines, oldest first
 */
public record ContractInputTypeInteractionOutputLogAsText(String preamble, List<Line> lines)
        implements ContractInputTypeText {

    public ContractInputTypeInteractionOutputLogAsText {
        Objects.requireNonNull(preamble, "preamble");
        lines = List.copyOf(Objects.requireNonNull(lines, "lines"));
    }

    /** One transcript line. */
    public record Line(String speaker, String text) {
        public Line {
            Objects.requireNonNull(speaker, "speaker");
            Objects.requireNonNull(text, "text");
        }
    }

    /** The most recent line, if any. */
    public java.util.Optional<Line> lastLine() {
        return lines.isEmpty() ? java.util.Optional.empty() : java.util.Optional.of(lines.get(lines.size() - 1));
    }

    @Override
    public String asText() {
        StringBuilder sb = new StringBuilder();
        if (!preamble.isBlank()) {
            sb.append(preamble.strip()).append("\n\n");
        }
        sb.append("--- transcript ---\n");
        for (Line line : lines) {
            sb.append(line.speaker()).append(": ").append(line.text().strip()).append('\n');
        }
        sb.append("--- end transcript ---");
        return sb.toString();
    }
}
