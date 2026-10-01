package io.github.schematicsupervisor.fabric;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RunContextStoreTest {
    private static final RunContext CONTEXT = new RunContext(
            "sha256:" + "ab".repeat(32),
            "minecraft:overworld"
    );

    @TempDir
    Path temporaryDirectory;

    @Test
    void roundTripsAndClearsOpaqueContext() throws IOException {
        RunContextStore store = new RunContextStore(temporaryDirectory.resolve("context.json"));

        assertTrue(store.load().isEmpty());
        store.save(CONTEXT);
        assertEquals(CONTEXT, store.load().orElseThrow());
        store.clear();
        assertTrue(store.load().isEmpty());
    }

    @Test
    void rejectsMalformedOrNonOpaqueIdentity() throws IOException {
        Path path = temporaryDirectory.resolve("context.json");
        Files.writeString(
                path,
                "{\"schemaVersion\":1,\"worldIdentityHash\":\"server-name\","
                        + "\"dimension\":\"minecraft:overworld\"}"
        );

        assertThrows(IOException.class, () -> new RunContextStore(path).load());
    }

    @Test
    void rejectsUnboundedDimension() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new RunContext("sha256:" + "00".repeat(32), "")
        );
    }
}
