package com.daimon.interactions;

import com.daimon.contracts.Contract;
import com.daimon.contracts.ContractExecutor;
import com.daimon.contracts.ContractExecutorRegistry;
import com.daimon.contracts.ContractInputTypeRawText;
import com.daimon.contracts.ContractInputTypeText;
import com.daimon.contracts.ContractParticipant;
import com.daimon.contracts.TalkContract;
import com.daimon.core.DAIMonInstance;
import com.daimon.core.DAIMonInstanceImplementations.DAIMonInstanceTalkMemoryMarkdown;
import com.daimon.core.DAIMonRuntimeConfig;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatInteractionTest {

    @TempDir
    Path tmp;

    /** A participant that echoes how many transcript lines it saw. */
    private static final class Echo implements ContractParticipant {
        private final String name;
        private final ContractExecutorRegistry executors = new ContractExecutorRegistry();

        Echo(String name) {
            this.name = name;
            executors.register(new ContractExecutor<ContractInputTypeText, ContractInputTypeRawText>(
                    TalkContract.INSTANCE) {
                @Override
                protected ContractInputTypeRawText executeUnchecked(ContractInputTypeText input) {
                    return ContractInputTypeRawText.of(name + " saw " + input.asText().lines().count() + " lines");
                }
            });
        }

        @Override
        public String participantId() {
            return name;
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

    @Test
    void roundRobinUntilTurnLimit() {
        ChatInteraction chat = ChatInteraction.builder().topic("testing").turnLimit(5)
                .participant(new Echo("A")).participant(new Echo("B")).build();
        assertEquals(Interaction.Status.COMPLETED, chat.run());
        List<String> speakers = chat.interactionLog().snapshot().stream()
                .filter(e -> e.kind() == InteractionLog.Kind.AGENT).map(InteractionLog.Entry::speaker).toList();
        assertEquals(List.of("A", "B", "A", "B", "A"), speakers);
        assertEquals(5, chat.completedTurns());
        assertThrows(IllegalStateException.class, chat::run);
    }

    @Test
    void offlineDaimonsChatAndHumansCanInterject() throws Exception {
        DAIMonRuntimeConfig config = new DAIMonRuntimeConfig(tmp, null, null);
        DAIMonInstance a = DAIMonInstance.createNew(DAIMonInstanceTalkMemoryMarkdown.TYPE, "Voltmoth",
                tmp.resolve("a"), config);
        a.getManagementInterface().writeMemory("- I chase bright ideas.\n");
        DAIMonInstance b = DAIMonInstance.createNew(DAIMonInstanceTalkMemoryMarkdown.TYPE, "Nyxbyte",
                tmp.resolve("b"), config);

        ChatInteraction chat = ChatInteraction.builder().topic("signatures").turnLimit(4)
                .turnDelay(Duration.ofMillis(50)).participant(a).participant(b).build();
        var future = chat.runAsync(command -> new Thread(command).start());
        chat.submitHumanInput("james", "Please mention integrity.");
        assertEquals(Interaction.Status.COMPLETED, future.get(5, TimeUnit.SECONDS));

        List<InteractionLog.Entry> log = chat.interactionLog().snapshot();
        assertTrue(log.stream().anyMatch(e -> e.kind() == InteractionLog.Kind.HUMAN && e.speaker().equals("james")));
        assertEquals(4, log.stream().filter(e -> e.kind() == InteractionLog.Kind.AGENT).count());
        assertTrue(log.stream().anyMatch(e -> e.text().contains("bright ideas")));
    }

    @Test
    void cancelStopsAtTurnBoundary() throws Exception {
        ChatInteraction chat = ChatInteraction.builder().turnLimit(100).turnDelay(Duration.ofMillis(20))
                .participant(new Echo("A")).build();
        var future = chat.runAsync(command -> new Thread(command).start());
        Thread.sleep(60);
        chat.cancel();
        assertEquals(Interaction.Status.CANCELLED, future.get(5, TimeUnit.SECONDS));
        assertTrue(chat.completedTurns() < 100);
    }

    @Test
    void humanParticipantTakesATurn() throws Exception {
        HumanParticipant human = new HumanParticipant("operator", Duration.ofSeconds(5),
                prompt -> { /* a UI would display the prompt */ });
        ChatInteraction chat = ChatInteraction.builder().turnLimit(2)
                .participant(new Echo("A")).participant(human).build();
        human.provideInput("Hello, monsters.");
        assertEquals(Interaction.Status.COMPLETED, chat.run());
        assertTrue(chat.interactionLog().snapshot().stream()
                .anyMatch(e -> e.kind() == InteractionLog.Kind.HUMAN && e.text().equals("Hello, monsters.")));
    }
}
