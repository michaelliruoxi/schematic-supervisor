package io.github.schematicsupervisor.fabric;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonIOException;
import io.github.schematicsupervisor.core.DepotId;
import io.github.schematicsupervisor.core.Material;
import io.github.schematicsupervisor.core.MaterialQuantities;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class DepotRegistryPrunerTest {
    private static final RunContext CONTEXT = new RunContext("sha256:" + "aa".repeat(32), "minecraft:overworld");
    private static final DepotRegistryPruner.Presence PRESENT = new DepotRegistryPruner.Presence(true, false, true);
    private static final DepotRegistryPruner.Presence MISSING = new DepotRegistryPruner.Presence(true, false, false);
    private static final DepotRegistryPruner.Presence UNLOADED = new DepotRegistryPruner.Presence(false, false, false);
    private static final DepotRegistryPruner.Presence PREDICTED_BREAK = new DepotRegistryPruner.Presence(true, true, false);

    @TempDir
    Path temporaryDirectory;

    @Test
    void removesOnlyMissingChestsAndPersistsTheResultAcrossReload() throws IOException {
        RegisteredDepot missing = depot("missing", CONTEXT, 1).withScan(MaterialQuantities.of(Material.DIRT, 64));
        RegisteredDepot present = depot("present", CONTEXT, 2).withScan(MaterialQuantities.of(Material.GLOWSTONE, 32));
        RegisteredDepot anotherMissing = depot("another-missing", CONTEXT, 3).withError("chest screen did not open");
        Harness harness = new Harness(missing, present, anotherMissing);
        harness.presence.put(present.id(), PRESENT);

        assertEquals(List.of(missing.id(), anotherMissing.id()), harness.tick(CONTEXT, true));

        assertEquals(List.of(present), new ArrayList<>(harness.depots.values()));
        assertEquals(List.of(present.id()), new ArrayList<>(harness.queue));
        assertEquals(List.of(present.markUnscanned()), harness.store.load());
        assertEquals(32, harness.depots.get(present.id()).cachedStock().get(Material.GLOWSTONE));
        assertEquals(1, harness.writes);
    }

    @Test
    void retainsUnloadedAndLocallyPredictedChestsUntilTheirAbsenceIsConfirmed() throws IOException {
        RegisteredDepot unloaded = depot("unloaded", CONTEXT, 1);
        RegisteredDepot predicted = depot("predicted", CONTEXT, 2);
        Harness harness = new Harness(unloaded, predicted);
        harness.presence.put(unloaded.id(), UNLOADED);
        harness.presence.put(predicted.id(), PREDICTED_BREAK);

        assertTrue(harness.tick(CONTEXT, true).isEmpty());
        assertEquals(0, harness.writes);
        assertEquals(2, harness.store.load().size());

        // The server rejects the predicted break, but confirms the other chest was removed.
        harness.presence.put(unloaded.id(), MISSING);
        harness.presence.put(predicted.id(), PRESENT);
        harness.elapseCheckInterval(CONTEXT);

        assertEquals(List.of(predicted), new ArrayList<>(harness.depots.values()));
        assertEquals(List.of(predicted.id()), new ArrayList<>(harness.queue));
        assertEquals(List.of(predicted), harness.store.load());
    }

    @Test
    void neverInspectsOrRemovesChestsInOtherWorldsOrDimensions() throws IOException {
        RunContext otherWorld = new RunContext("sha256:" + "bb".repeat(32), CONTEXT.dimension());
        RunContext otherDimension = new RunContext(CONTEXT.worldIdentityHash(), "minecraft:the_nether");
        RegisteredDepot local = depot("local", CONTEXT, 1);
        RegisteredDepot foreign = depot("foreign", otherWorld, 1);
        RegisteredDepot nether = depot("nether", otherDimension, 1);
        Harness harness = new Harness(local, foreign, nether);

        assertTrue(harness.tick(null, true).isEmpty());
        assertTrue(harness.reads.isEmpty());
        assertEquals(List.of(local.id()), harness.tick(CONTEXT, true));
        assertEquals(List.of(local.id()), harness.reads);
        assertEquals(List.of(foreign, nether), harness.store.load());

        harness.tick(null, true);
        assertEquals(List.of(foreign.id()), harness.tick(otherWorld, true));
        assertEquals(List.of(nether), harness.store.load());
    }

    @Test
    void defersAllRegistryChangesUntilChestOperationsAndCleanupHaveSettled() throws IOException {
        RegisteredDepot missing = depot("missing", CONTEXT, 1);
        Harness harness = new Harness(missing);
        for (int tick = 0; tick < 60; tick++) {
            assertTrue(harness.tick(CONTEXT, false).isEmpty());
        }
        assertTrue(harness.reads.isEmpty());
        assertEquals(0, harness.writes);
        assertEquals(List.of(missing.id()), new ArrayList<>(harness.queue));
        assertEquals(List.of(missing), harness.store.load());

        assertEquals(List.of(missing.id()), harness.tick(CONTEXT, true));
        assertTrue(harness.depots.isEmpty());
        assertTrue(harness.queue.isEmpty());
        assertTrue(harness.store.load().isEmpty());
    }

    @Test
    void boundsWorldReadsAndAvoidsWritesWhenNothingIsMissing() throws IOException {
        RegisteredDepot present = depot("present", CONTEXT, 1);
        Harness harness = new Harness(present);
        harness.presence.put(present.id(), PRESENT);
        for (int tick = 0; tick < 40; tick++) {
            assertTrue(harness.tick(CONTEXT, true).isEmpty());
        }
        assertEquals(List.of(present.id(), present.id()), harness.reads);
        assertEquals(0, harness.writes);

        harness.presence.put(present.id(), MISSING);
        assertEquals(List.of(present.id()), harness.tick(CONTEXT, true));
        assertEquals(1, harness.writes);
    }

    @Test
    void failedSavePreservesRegistryStockAndQueuedScansAndRetriesLater() throws IOException {
        RegisteredDepot missing = depot("missing", CONTEXT, 1).withScan(MaterialQuantities.of(Material.DIRT, 64));
        RegisteredDepot present = depot("present", CONTEXT, 2);
        Harness harness = new Harness(missing, present);
        harness.presence.put(present.id(), PRESENT);
        String originalFile = Files.readString(harness.registryPath);
        harness.failWrite = true;

        assertThrows(IOException.class, () -> harness.tick(CONTEXT, true));
        assertEquals(List.of(missing, present), new ArrayList<>(harness.depots.values()));
        assertEquals(List.of(missing.id(), present.id()), new ArrayList<>(harness.queue));
        assertEquals(originalFile, Files.readString(harness.registryPath));
        assertEquals(0, harness.writes);

        harness.failWrite = false;
        harness.elapseCheckInterval(CONTEXT);
        assertEquals(List.of(present), harness.store.load());
        assertEquals(List.of(present.id()), new ArrayList<>(harness.queue));
        assertEquals(1, harness.writes);
    }

    @Test
    void serializationFailureAlsoLeavesLiveAndPersistedStateIntact() throws IOException {
        RegisteredDepot missing = depot("missing", CONTEXT, 1);
        Harness harness = new Harness(missing);
        DepotRegistryPruner failing = new DepotRegistryPruner(harness.depots, harness.queue,
                retained -> { throw new JsonIOException("write failed"); });

        assertThrows(JsonIOException.class, () -> failing.tick(CONTEXT, true, depot -> MISSING));
        assertFalse(harness.depots.isEmpty());
        assertEquals(List.of(missing.id()), new ArrayList<>(harness.queue));
        assertEquals(List.of(missing), harness.store.load());
    }

    private static RegisteredDepot depot(String id, RunContext context, int x) {
        return RegisteredDepot.unscanned(new DepotId(id), context.worldIdentityHash(), context.dimension(),
                x, 64, 0);
    }

    private final class Harness {
        private final Map<DepotId, RegisteredDepot> depots = new LinkedHashMap<>();
        private final Deque<DepotId> queue = new ArrayDeque<>();
        private final Map<DepotId, DepotRegistryPruner.Presence> presence = new LinkedHashMap<>();
        private final List<DepotId> reads = new ArrayList<>();
        private final Path registryPath = temporaryDirectory.resolve("depots.json");
        private final DepotRegistryStore store = new DepotRegistryStore(registryPath);
        private final DepotRegistryPruner pruner;
        private boolean failWrite;
        private int writes;

        private Harness(RegisteredDepot... registered) throws IOException {
            for (RegisteredDepot depot : registered) {
                depots.put(depot.id(), depot);
                queue.addLast(depot.id());
            }
            store.save(new ArrayList<>(depots.values()));
            pruner = new DepotRegistryPruner(depots, queue, retained -> {
                if (failWrite) { throw new IOException("registry is not writable"); }
                store.save(retained);
                writes++;
            });
        }

        private List<DepotId> tick(RunContext context, boolean settled) throws IOException {
            return pruner.tick(context, settled, depot -> {
                reads.add(depot.id());
                return presence.getOrDefault(depot.id(), MISSING);
            });
        }

        private void elapseCheckInterval(RunContext context) throws IOException {
            for (int tick = 0; tick < 20; tick++) { tick(context, true); }
        }
    }
}
