package com.daimon.contracts;

import java.util.Optional;
import java.util.Set;

/**
 * Anything that can be bound to an interaction role: a DAIMon instance, a human operator, or a
 * remote peer. A participant advertises which contracts it can fulfil.
 */
public interface ContractParticipant {

    /** Stable unique id within this daemon. */
    String participantId();

    /** Human-readable name shown in logs. */
    String displayName();

    /** The contracts this participant can fulfil. */
    Set<Contract<?, ?>> supportedContracts();

    /** Returns the executor for {@code contract}, or empty if this participant cannot fulfil it. */
    <I, O> Optional<ContractExecutor<I, O>> executorFor(Contract<I, O> contract);

    default boolean canFulfil(Contract<?, ?> contract) {
        return supportedContracts().contains(contract);
    }
}
