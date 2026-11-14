package com.daimon.contracts;

/**
 * Behaviour that fulfils a {@link Contract}.
 *
 * <p>{@link #execute(Object)} is final: it validates the input and output against the contract
 * around the subclass hook {@link #executeUnchecked(Object)}, so every executor enforces the
 * contract the same way.
 *
 * @param <I> the input type of the fulfilled contract
 * @param <O> the output type of the fulfilled contract
 */
public abstract class ContractExecutor<I, O> {

    private final Contract<I, O> contract;

    protected ContractExecutor(Contract<I, O> contract) {
        this.contract = java.util.Objects.requireNonNull(contract, "contract");
    }

    public final Contract<I, O> contract() {
        return contract;
    }

    /** Runs the executor with contract validation on both sides. */
    public final O execute(I input) throws ContractExecutionException {
        contract.validateInput(input);
        O output;
        try {
            output = executeUnchecked(input);
        } catch (ContractExecutionException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new ContractExecutionException(contract.id() + ": executor failed: " + e.getMessage(), e);
        }
        contract.validateOutput(output);
        return output;
    }

    /** The actual behaviour. Input has already been validated. Must not return {@code null}. */
    protected abstract O executeUnchecked(I input) throws ContractExecutionException;
}
