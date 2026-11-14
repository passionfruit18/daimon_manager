package com.daimon.interactions;

import com.daimon.contracts.Contract;
import com.daimon.contracts.ContractParticipant;

import java.util.Objects;

/**
 * A slot in an {@link Interaction} that a participant must fill. A role requires exactly one
 * {@link Contract}; any participant that fulfils it can play the role.
 */
public abstract class Role {

    private final String name;

    protected Role(String name) {
        Objects.requireNonNull(name, "name");
        if (name.isBlank()) {
            throw new IllegalArgumentException("Role name must not be blank");
        }
        this.name = name;
    }

    public final String name() {
        return name;
    }

    /** The contract a participant must fulfil to play this role. */
    public abstract Contract<?, ?> requiredContract();

    public final boolean canBeFilledBy(ContractParticipant participant) {
        return participant != null && participant.canFulfil(requiredContract());
    }

    @Override
    public String toString() {
        return getClass().getSimpleName() + "[" + name + "]";
    }
}
