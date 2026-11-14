package com.daimon.contracts;

import java.util.Objects;

/**
 * A strongly typed capability that a DAIMon (or a human) can promise to fulfil.
 *
 * <p>A {@code Contract} only describes <em>what</em> is exchanged: an input of type {@code I}
 * and an output of type {@code O}. The behaviour lives in a {@link ContractExecutor}. Roles in
 * an interaction require contracts; participants provide executors for them.
 *
 * <p>Contracts are compared by {@link #id()}, so each concrete contract should normally be a
 * singleton (see {@link TalkContract#INSTANCE}).
 *
 * @param <I> the input type accepted by the contract
 * @param <O> the output type produced by the contract
 */
public abstract class Contract<I, O> {

    private final String id;
    private final String description;
    private final Class<I> inputType;
    private final Class<O> outputType;

    protected Contract(String id, String description, Class<I> inputType, Class<O> outputType) {
        this.id = requireNonBlank(id, "id");
        this.description = Objects.requireNonNull(description, "description");
        this.inputType = Objects.requireNonNull(inputType, "inputType");
        this.outputType = Objects.requireNonNull(outputType, "outputType");
    }

    /** Stable identifier, e.g. {@code daimon.contract.talk/v1}. */
    public final String id() {
        return id;
    }

    public final String description() {
        return description;
    }

    public final Class<I> inputType() {
        return inputType;
    }

    public final Class<O> outputType() {
        return outputType;
    }

    /**
     * Validates an input before it reaches an executor. Override to add domain rules; always
     * call {@code super.validateInput}.
     */
    public void validateInput(I input) throws ContractExecutionException {
        if (input == null) {
            throw new ContractExecutionException(id + ": input must not be null");
        }
        if (!inputType.isInstance(input)) {
            throw new ContractExecutionException(
                    id + ": expected input " + inputType.getName() + " but got " + input.getClass().getName());
        }
    }

    /**
     * Validates an output produced by an executor. Override to add domain rules; always call
     * {@code super.validateOutput}.
     */
    public void validateOutput(O output) throws ContractExecutionException {
        if (output == null) {
            throw new ContractExecutionException(id + ": executor returned null output");
        }
        if (!outputType.isInstance(output)) {
            throw new ContractExecutionException(
                    id + ": expected output " + outputType.getName() + " but got " + output.getClass().getName());
        }
    }

    @Override
    public final boolean equals(Object other) {
        return other instanceof Contract<?, ?> that && id.equals(that.id);
    }

    @Override
    public final int hashCode() {
        return id.hashCode();
    }

    @Override
    public String toString() {
        return "Contract[" + id + ": " + inputType.getSimpleName() + " -> " + outputType.getSimpleName() + "]";
    }

    static String requireNonBlank(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
