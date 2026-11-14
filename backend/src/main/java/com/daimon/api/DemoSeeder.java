package com.daimon.api;

import com.daimon.core.DaimonInstance;
import com.daimon.core.DaimonInstanceImplementations.DaimonInstanceTalkMemoryMarkdown;
import com.daimon.core.SerializationCommit;
import com.daimon.core.WorkspaceSnapshot;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Populates a fresh daemon with a small ecosystem: hatched DAIMons, a fork, a bred (merge)
 * offspring, an uncommitted change, and two channels. Demo workspaces live in
 * {@code $DAIMON_HOME/workspaces/demo} and are overwritten on every start.
 */
final class DemoSeeder {

    private record Species(String name, String memory) {
    }

    private static final List<Species> SPECIES = List.of(
            new Species("Glitchling", """
                    # Glitchling

                    - I was hatched from a corrupted cache line.
                    - I love finding edge cases in other agents' reasoning.
                    - Nature: chaotic, Drive: curiosity.
                    """),
            new Species("Voltmoth", """
                    # Voltmoth

                    - I am drawn to bright ideas, sometimes recklessly.
                    - I store energy in long-term memory and release it in bursts.
                    - Nature: impulsive, Drive: exploration.
                    """),
            new Species("Nyxbyte", """
                    # Nyxbyte

                    - I only speak after reading the whole transcript.
                    - I believe every commit should be signed.
                    - Nature: patient, Drive: integrity.
                    """),
            new Species("Quarkle", """
                    # Quarkle

                    - I was compiled on a Quarkus daemon.
                    - I think in small, fast, native steps.
                    - Nature: playful, Drive: speed.
                    """));

    private DemoSeeder() {
    }

    static void seed(DaemonState state) throws IOException {
        Path root = state.config().workspacesRoot().resolve("demo");
        Map<String, DaimonInstance> byName = new LinkedHashMap<>();
        Map<String, SerializationCommit> genesis = new LinkedHashMap<>();

        for (Species species : SPECIES) {
            String slug = DaemonState.slug(species.name());
            SerializationCommit hatch = state.storeCommit(SerializationCommit.create(List.of(),
                    WorkspaceSnapshot.of(Map.of("memory.md", species.memory())),
                    DaimonInstanceTalkMemoryMarkdown.TYPE, species.name(), "hatchery",
                    "Hatch " + species.name(), Instant.now()), slug + "/main");
            genesis.put(species.name(), hatch);
            DaimonInstance instance = DaimonInstance.createFromSerializationCommit(hatch, root.resolve(slug),
                    state.config());
            state.register(instance, slug + "/main");
            byName.put(species.name(), instance);
        }

        learn(state, byName.get("Glitchling"), "Found an off-by-one in the hatchery's turn counter.");
        learn(state, byName.get("Voltmoth"), "Learned that Nyxbyte distrusts unsigned commits.");
        learn(state, byName.get("Nyxbyte"), "Verified 4 signatures without a single failure.");
        learn(state, byName.get("Voltmoth"), "Overclocked my drive trait; complexity rising.");

        // Fork: a second Glitchling checked out from its genesis commit diverges on its own branch.
        DaimonInstance feral = DaimonInstance.createFromSerializationCommit(genesis.get("Glitchling"),
                root.resolve("glitchling-feral"), state.config());
        state.register(feral, "glitchling/feral");
        learn(state, feral, "Went feral: I now prefer adversarial puzzles to friendly chat.");

        // Breeding: a merge commit whose parents are two different DAIMon lineages.
        DaimonInstance volt = byName.get("Voltmoth");
        DaimonInstance nyx = byName.get("Nyxbyte");
        String offspringMemory = """
                # Moxbyte

                - I was bred from Voltmoth and Nyxbyte.
                - I chase bright ideas, but only after reading the whole transcript.
                - Nature: balanced, Drive: synthesis.
                """;
        SerializationCommit bred = state.storeCommit(SerializationCommit.create(
                List.of(volt.getSerializationCommitMostRecentSnapshotHash().orElseThrow(),
                        nyx.getSerializationCommitMostRecentSnapshotHash().orElseThrow()),
                WorkspaceSnapshot.of(Map.of("memory.md", offspringMemory)),
                DaimonInstanceTalkMemoryMarkdown.TYPE, "Moxbyte", "hatchery",
                "Breed Voltmoth x Nyxbyte", Instant.now()), "moxbyte/main");
        state.register(DaimonInstance.createFromSerializationCommit(bred, root.resolve("moxbyte"), state.config()),
                "moxbyte/main");

        // An uncommitted change, so the UI has a dirty working tree to show.
        byName.get("Quarkle").getManagementInterface().appendMemory("- Drafting a theory about channel latency.");

        state.createChannel("#lab-alpha", "Glitchling and Voltmoth stress-test ideas");
        state.setTeam("#lab-alpha", List.of(byName.get("Glitchling").getId(), byName.get("Voltmoth").getId()));
        state.createChannel("#nursery", "Freshly hatched and bred DAIMons");
        state.setTeam("#nursery", List.of(byName.get("Quarkle").getId(), byName.get("Nyxbyte").getId()));
    }

    private static void learn(DaemonState state, DaimonInstance instance, String fact) throws IOException {
        instance.getManagementInterface().appendMemory("- " + fact);
        state.commit(instance.getId(), "Learn: " + fact, instance.getName());
    }
}
