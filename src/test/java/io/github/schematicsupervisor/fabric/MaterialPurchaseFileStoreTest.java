package io.github.schematicsupervisor.fabric;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MaterialPurchaseFileStoreTest {
    @TempDir Path directory;

    @Test void originalIntentAndExactReceiptRoundTripWithoutInventingEquipmentAuthority() throws Exception {
        Path path = directory.resolve("material-purchase.json");
        var store = new MaterialPurchaseFileStore(path);
        var intent = intent();
        assertTrue(store.load().isEmpty());
        store.replace(Optional.empty(), intent);
        assertEquals(intent, new MaterialPurchaseFileStore(path).load().orElseThrow());
        var confirmed = intent.confirm(MaterialPurchaseControllerTest.after());
        store.replace(Optional.of(intent), confirmed);
        assertEquals(confirmed, new MaterialPurchaseFileStore(path).load().orElseThrow());
        assertTrue(Files.size(path) < MaterialPurchaseFileStore.MAXIMUM_BYTES);
        assertTrue(Files.readString(path).contains("\"armor\""));
        assertEquals(4, confirmed.before().slots().armor().size());
    }

    @Test void aCrossEpochBarrierAlsoSurvivesRestart() throws Exception {
        var store = new MaterialPurchaseFileStore(directory.resolve("material-purchase.json"));
        var intent = intent();
        store.replace(Optional.empty(), intent);
        var observed = MaterialPurchaseControllerTest.observed(MaterialPurchaseControllerTest.NEXT_EPOCH, 1, 1, 1,
                MaterialPurchaseControllerTest.purchased());
        var barrier = intent.withBarrier(observed);
        store.replace(Optional.of(intent), barrier);
        assertEquals(barrier, store.load().orElseThrow());
        var receipt = MaterialPurchaseControllerTest.observed(MaterialPurchaseControllerTest.NEXT_EPOCH, 1, 2, 2,
                MaterialPurchaseControllerTest.purchased());
        store.replace(Optional.of(barrier), barrier.confirm(receipt));
        assertEquals(receipt, store.load().orElseThrow().receipt());
    }

    @Test void staleExpectedJournalAndNewOperationCannotReplaceAnUnresolvedIntent() throws Exception {
        var store = new MaterialPurchaseFileStore(directory.resolve("material-purchase.json"));
        var intent = intent();
        store.replace(Optional.empty(), intent);
        var other = intent();
        assertThrows(IOException.class, () -> store.replace(Optional.empty(), other));
        assertThrows(IOException.class, () -> store.replace(Optional.of(intent), other));
        assertEquals(intent, store.load().orElseThrow());
    }

    @Test void immutableBeforeEvidenceAndConfirmedReceiptCannotBeRewritten() throws Exception {
        var store = new MaterialPurchaseFileStore(directory.resolve("material-purchase.json"));
        var intent = intent();
        store.replace(Optional.empty(), intent);
        var changedSlots = MaterialPurchaseControllerTest.replaceMain(intent.before().slots(), 2,
                MaterialPurchaseControllerTest.stack("minecraft:glowstone", 36, true));
        var changedBefore = new MaterialPurchaseJournal.Observation(intent.before().context(), intent.before().stamp(), changedSlots);
        var mutated = new MaterialPurchaseJournal(intent.operationId(), changedBefore,
                intent.quote(), MaterialPurchaseJournal.Stage.PENDING, null, null);
        assertThrows(IOException.class, () -> store.replace(Optional.of(intent), mutated));
        assertEquals(intent, store.load().orElseThrow());
        var confirmed = intent.confirm(MaterialPurchaseControllerTest.after());
        store.replace(Optional.of(intent), confirmed);
        assertThrows(IOException.class, () -> store.replace(Optional.of(confirmed), intent));
        assertEquals(confirmed, store.load().orElseThrow());
    }

    @Test void unsupportedAtomicReplacementPreservesTheLastJournalWithoutFallback() throws Exception {
        Path path = directory.resolve("material-purchase.json");
        var originalStore = new MaterialPurchaseFileStore(path);
        var intent = intent();
        originalStore.replace(Optional.empty(), intent);
        byte[] previous = Files.readAllBytes(path);
        var failingStore = new MaterialPurchaseFileStore(path, (temporary, destination) -> {
            assertTrue(Files.size(temporary) > 0);
            throw new AtomicMoveNotSupportedException(temporary.toString(), destination.toString(), "test filesystem");
        });
        assertThrows(AtomicMoveNotSupportedException.class,
                () -> failingStore.replace(Optional.of(intent), intent.confirm(MaterialPurchaseControllerTest.after())));
        assertArrayEquals(previous, Files.readAllBytes(path));
        assertEquals(intent, originalStore.load().orElseThrow());
        try (var paths = Files.list(directory)) {
            assertFalse(paths.anyMatch(file -> file.getFileName().toString().endsWith(".tmp")));
        }
    }

    @Test void simultaneousWritersCannotPassTheSameExpectedJournalCheck() throws Exception {
        Path path = directory.resolve("material-purchase.json");
        Path lockPath = directory.resolve("material-purchase.json.lock");
        try (FileChannel owner = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var held = owner.lock()) {
            assertTrue(held.isValid());
            var competingStore = new MaterialPurchaseFileStore(path);
            assertThrows(IOException.class, () -> competingStore.replace(Optional.empty(), intent()));
            assertFalse(Files.exists(path));
        }
        var availableStore = new MaterialPurchaseFileStore(path);
        availableStore.replace(Optional.empty(), intent());
        assertTrue(availableStore.load().isPresent());
    }

    @Test void corruptOversizedOrUnsupportedJournalsArePreservedAndRejected() throws Exception {
        Path path = directory.resolve("material-purchase.json");
        var store = new MaterialPurchaseFileStore(path);
        for (byte[] invalid : new byte[][] {
                "{broken".getBytes(StandardCharsets.UTF_8),
                "{\"version\":2,\"journal\":null}".getBytes(StandardCharsets.UTF_8),
                "{\"version\":1.0,\"journal\":null}".getBytes(StandardCharsets.UTF_8),
                "{\"version\":1,\"journal\":null}".getBytes(StandardCharsets.UTF_8),
                "{\"version\":1,\"journal\":{},\"other\":1}".getBytes(StandardCharsets.UTF_8),
                new byte[] {(byte) 0xff}, new byte[MaterialPurchaseFileStore.MAXIMUM_BYTES + 1] }) {
            Files.write(path, invalid);
            assertThrows(IOException.class, store::load);
            assertArrayEquals(invalid, Files.readAllBytes(path));
            assertThrows(IOException.class, () -> store.replace(Optional.empty(), intent()));
            assertArrayEquals(invalid, Files.readAllBytes(path));
        }
    }

    @Test void journalRestoreRevalidatesTheCompleteSlotPlanAndReceipt() throws Exception {
        Path path = directory.resolve("material-purchase.json");
        var store = new MaterialPurchaseFileStore(path);
        var intent = intent();
        store.replace(Optional.empty(), intent);
        String original = Files.readString(path);
        String invalid = original.replace("\"stacks\":1", "\"stacks\":10");
        assertNotEquals(original, invalid);
        Files.writeString(path, invalid);
        assertThrows(IOException.class, store::load);
        Files.writeString(path, original.replace("\"stage\":\"PENDING\"", "\"stage\":\"CONFIRMED\""));
        assertThrows(IOException.class, store::load);
    }

    @Test void unknownNestedFieldsAndMissingFieldsCannotBeSilentlyDiscardedOnRestore() throws Exception {
        Path path = directory.resolve("material-purchase.json");
        var store = new MaterialPurchaseFileStore(path);
        store.replace(Optional.empty(), intent());
        String original = Files.readString(path);
        for (String invalid : java.util.List.of(original.replace("\"stacks\":1", "\"stacks\":1,\"other\":1"),
                original.replace("\"reconciliationBarrier\":null,", ""))) {
            assertNotEquals(original, invalid);
            Files.writeString(path, invalid);
            assertThrows(IOException.class, store::load);
        }
    }

    private static MaterialPurchaseJournal intent() {
        var before = MaterialPurchaseControllerTest.before();
        return MaterialPurchaseJournal.pending(before, MaterialPurchaseControllerTest.quote());
    }
}
