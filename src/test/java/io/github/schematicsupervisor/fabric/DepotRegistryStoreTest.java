package io.github.schematicsupervisor.fabric;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.schematicsupervisor.core.DepotId;
import io.github.schematicsupervisor.core.Material;
import io.github.schematicsupervisor.core.MaterialQuantities;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DepotRegistryStoreTest {
    private static final String WORLD_A = "sha256:" + "aa".repeat(32);
    private static final String WORLD_B = "sha256:" + "bb".repeat(32);

    @TempDir
    Path temporaryDirectory;

    @Test
    void savesLocationsAtomicallyAndRequiresFreshSessionScans() throws IOException {
        Path path = temporaryDirectory.resolve("nested").resolve("depots.json");
        DepotRegistryStore store = new DepotRegistryStore(path);
        RegisteredDepot later = RegisteredDepot.unscanned(
                new DepotId("depot-010"),
                WORLD_A,
                "minecraft:overworld",
                12,
                70,
                -4
        ).withScan(MaterialQuantities.of(Material.DIRT, 64));
        RegisteredDepot earlier = RegisteredDepot.unscanned(
                new DepotId("depot-002"),
                WORLD_B,
                "minecraft:the_nether",
                1,
                65,
                2
        ).withScan(MaterialQuantities.of(Material.FOOD, 8));

        store.save(List.of(later, earlier));
        List<RegisteredDepot> loaded = store.load();

        assertEquals(List.of(new DepotId("depot-002"), new DepotId("depot-010")),
                loaded.stream().map(RegisteredDepot::id).toList());
        assertTrue(Files.isRegularFile(path));
        assertTrue(loaded.stream().noneMatch(RegisteredDepot::scanned));
        assertTrue(loaded.stream().allMatch(depot -> depot.cachedStock().isEmpty()));
        String persisted = Files.readString(path, StandardCharsets.UTF_8);
        assertFalse(persisted.contains("cachedStock"));
        assertTrue(persisted.contains("\"schemaVersion\": 2"));
        assertTrue(persisted.contains(WORLD_A));
        assertTrue(persisted.contains(WORLD_B));
    }

    @Test
    void overwriteReplacesTheCompleteRegistry() throws IOException {
        Path path = temporaryDirectory.resolve("depots.json");
        DepotRegistryStore store = new DepotRegistryStore(path);
        store.save(List.of(RegisteredDepot.unscanned(
                new DepotId("depot-001"),
                WORLD_A,
                "minecraft:overworld",
                0,
                64,
                0
        )));

        store.save(List.of());

        assertTrue(store.load().isEmpty());
        try (var files = Files.list(temporaryDirectory)) {
            assertEquals(List.of(path), files.toList());
        }
    }

    @Test
    void malformedAndDuplicateEntriesFailClosed() throws IOException {
        Path path = temporaryDirectory.resolve("depots.json");
        DepotRegistryStore store = new DepotRegistryStore(path);
        Files.writeString(
                path,
                """
                {
                  "schemaVersion": 2,
                  "depots": [
                    {
                      "id":"depot-001",
                      "worldIdentityHash":"sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                      "dimension":"minecraft:overworld","x":1,"y":2,"z":3
                    },
                    {
                      "id":"depot-002",
                      "worldIdentityHash":"sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                      "dimension":"minecraft:overworld","x":1,"y":2,"z":3
                    }
                  ]
                }
                """,
                StandardCharsets.UTF_8
        );

        assertThrows(IOException.class, store::load);

        Files.writeString(path, "{broken", StandardCharsets.UTF_8);
        assertThrows(IOException.class, store::load);
    }

    @Test
    void sameCoordinatesInDifferentWorldsAreDistinctLocations() throws IOException {
        Path path = temporaryDirectory.resolve("depots.json");
        DepotRegistryStore store = new DepotRegistryStore(path);
        RegisteredDepot first = RegisteredDepot.unscanned(
                new DepotId("depot-001"),
                WORLD_A,
                "minecraft:overworld",
                1,
                2,
                3
        );
        RegisteredDepot second = RegisteredDepot.unscanned(
                new DepotId("depot-002"),
                WORLD_B,
                "minecraft:overworld",
                1,
                2,
                3
        );

        store.save(List.of(first, second));

        assertEquals(List.of(first, second), store.load());
    }

    @Test
    void rejectsLegacyUnboundSchema() throws IOException {
        Path path = temporaryDirectory.resolve("depots.json");
        Files.writeString(
                path,
                """
                {
                  "schemaVersion": 1,
                  "depots": [
                    {"id":"depot-001","dimension":"minecraft:overworld","x":1,"y":2,"z":3}
                  ]
                }
                """,
                StandardCharsets.UTF_8
        );

        assertThrows(IOException.class, () -> new DepotRegistryStore(path).load());
    }

    @Test
    void missingRegistryLoadsAsEmpty() throws IOException {
        DepotRegistryStore store = new DepotRegistryStore(
                temporaryDirectory.resolve("missing.json")
        );

        assertTrue(store.load().isEmpty());
    }
}
