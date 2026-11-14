package com.daimon.contracts;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Type-safe map from {@link Contract} to the {@link ContractExecutor} that fulfils it.
 *
 * <p>Registration ties the executor's own contract to the key, so the unchecked cast in
 * {@link #find(Contract)} is always sound.
 */
public final class ContractExecutorRegistry {

    private final Map<Contract<?, ?>, ContractExecutor<?, ?>> executors = new ConcurrentHashMap<>();

    /** Registers (or replaces) the executor for its own contract. */
    public <I, O> void register(ContractExecutor<I, O> executor) {
        Objects.requireNonNull(executor, "executor");
        executors.put(executor.contract(), executor);
    }

    public void unregister(Contract<?, ?> contract) {
        executors.remove(contract);
    }

    @SuppressWarnings("unchecked") // sound: keys are always executor.contract()
    public <I, O> Optional<ContractExecutor<I, O>> find(Contract<I, O> contract) {
        Objects.requireNonNull(contract, "contract");
        return Optional.ofNullable((ContractExecutor<I, O>) executors.get(contract));
    }

    public Set<Contract<?, ?>> contracts() {
        return Set.copyOf(executors.keySet());
    }
}
