package io.github.schematicsupervisor.fabric;

import static org.junit.jupiter.api.Assertions.*;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class HoeRepairFileStoreTest {
    @TempDir Path directory;

    @Test void pendingSurvivesReloadAndOnlyExactConfirmationMayReplaceIt() throws IOException {
        Path path = directory.resolve("hoe-repair.json");
        var store = new HoeRepairFileStore(path);
        var pending = intent();
        store.replace(Optional.empty(), pending);
        assertEquals(Optional.of(pending), new HoeRepairFileStore(path).load());
        assertThrows(IOException.class, () -> store.replace(Optional.of(pending), intent()));
        store.replace(Optional.of(pending), pending.confirm());
        assertEquals(Optional.of(pending.confirm()), store.load());
    }

    @Test void staleExpectedGenerationCannotOverwriteAnotherWriter() throws IOException {
        var store = new HoeRepairFileStore(directory.resolve("hoe-repair.json"));
        store.replace(Optional.empty(), intent());
        assertThrows(IOException.class, () -> store.replace(Optional.empty(), intent()));
    }

    @Test void malformedUnknownOrOversizedJournalFailsClosed() throws IOException {
        Path path = directory.resolve("hoe-repair.json");
        var store = new HoeRepairFileStore(path);
        Files.writeString(path, "{}");
        assertThrows(IOException.class, store::load);
        Files.writeString(path, "x".repeat(4097));
        assertThrows(IOException.class, store::load);
        Files.delete(path);
        store.replace(Optional.empty(), intent());
        Files.writeString(path, Files.readString(path).replace("\"version\":1", "\"version\":1,\"extra\":true"));
        assertThrows(IOException.class, store::load);
    }

    private static HoeRepairSession.Journal intent() {
        return new HoeRepairSession.Journal(UUID.randomUUID().toString(),
                new RunContext("sha256:" + "a".repeat(64), "minecraft:overworld"),
                new HoeRepairSession.Tool(0, "minecraft:diamond_hoe", "b".repeat(64), 1, 1500, 1561, false),
                UUID.randomUUID().toString(), 10, false);
    }
}
