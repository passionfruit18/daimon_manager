package com.daimon.contracts;

/**
 * Any contract payload that can be rendered as plain text.
 *
 * <p>Text-based contracts (such as {@link TalkContract}) accept this interface, so callers can
 * pass a {@link ContractInputTypeRawText} or a structured value like
 * {@link ContractInputTypeInteractionOutputLogAsText} without the executor caring which.
 */
public interface ContractInputTypeText {

    /** The text form of this payload. Never {@code null}. */
    String asText();
}
