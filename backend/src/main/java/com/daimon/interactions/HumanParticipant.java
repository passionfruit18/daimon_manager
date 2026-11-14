package com.daimon.interactions;

import com.daimon.contracts.Contract;
import com.daimon.contracts.ContractExecutionException;
import com.daimon.contracts.ContractExecutor;
import com.daimon.contracts.ContractExecutorRegistry;
import com.daimon.contracts.ContractInputTypeRawText;
import com.daimon.contracts.ContractInputTypeText;
import com.daimon.contracts.ContractParticipant;
import com.daimon.contracts.TalkContract;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * A human operator who fulfils {@link TalkContract}. When it is the human's turn the executor
 * publishes the context via {@code onPrompt} (e.g. to the UI) and blocks until
 * {@link #provideInput(String)} is called or the timeout expires.
 */
public final class HumanParticipant implements ContractParticipant {

    private final String id = "human-" + UUID.randomUUID();
    private final String name;
    private final BlockingQueue<String> inbox = new LinkedBlockingQueue<>();
    private final ContractExecutorRegistry executors = new ContractExecutorRegistry();

    public HumanParticipant(String name, Duration turnTimeout, Consumer<ContractInputTypeText> onPrompt) {
        this.name = Objects.requireNonNull(name, "name");
        Objects.requireNonNull(turnTimeout, "turnTimeout");
        Objects.requireNonNull(onPrompt, "onPrompt");
        executors.register(new ContractExecutor<ContractInputTypeText, ContractInputTypeRawText>(TalkContract.INSTANCE) {
            @Override
            protected ContractInputTypeRawText executeUnchecked(ContractInputTypeText input)
                    throws ContractExecutionException {
                onPrompt.accept(input);
                try {
                    String reply = inbox.poll(turnTimeout.toMillis(), TimeUnit.MILLISECONDS);
                    if (reply == null) {
                        throw new ContractExecutionException(name + " did not respond within " + turnTimeout);
                    }
                    return new ContractInputTypeRawText(reply);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new ContractExecutionException(name + "'s turn was interrupted", e);
                }
            }
        });
    }

    /** Supplies the human's next message; consumed by the next turn that needs it. */
    public void provideInput(String text) {
        Objects.requireNonNull(text, "text");
        if (text.isBlank()) {
            throw new IllegalArgumentException("Input must not be blank");
        }
        inbox.add(text.strip());
    }

    @Override
    public String participantId() {
        return id;
    }

    @Override
    public String displayName() {
        return name;
    }

    @Override
    public Set<Contract<?, ?>> supportedContracts() {
        return executors.contracts();
    }

    @Override
    public <I, O> Optional<ContractExecutor<I, O>> executorFor(Contract<I, O> contract) {
        return executors.find(contract);
    }
}
