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
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MossDepositFileStoreTest {
    @TempDir Path directory;

    @Test void originalIntentAndExactReceiptRoundTripWithoutInventingEquipmentAuthority() throws Exception {
        Path path = directory.resolve("moss-deposit.json");
        var store = new MossDepositFileStore(path);
        var intent = intent();
        assertTrue(store.load().isEmpty());
        store.replace(Optional.empty(), intent);
        assertEquals(intent, new MossDepositFileStore(path).load().orElseThrow());
        var confirmed = intent.confirm(MossDepositControllerTest.after());
        store.replace(Optional.of(intent), confirmed);
        assertEquals(confirmed, new MossDepositFileStore(path).load().orElseThrow());
        assertTrue(Files.size(path) < MossDepositFileStore.MAXIMUM_BYTES);
        assertTrue(Files.readString(path).contains("\"armor\""));
        assertEquals(4, confirmed.before().slots().armor().size());
    }

    @Test void aCrossEpochBarrierAlsoSurvivesRestart() throws Exception {
        var store = new MossDepositFileStore(directory.resolve("moss-deposit.json"));
        var intent = intent();
        store.replace(Optional.empty(), intent);
        var observed = MossDepositControllerTest.observed(MossDepositControllerTest.NEXT_EPOCH, 1, 1, 1,
                MossDepositControllerTest.deposited());
        var barrier = intent.withBarrier(observed);
        store.replace(Optional.of(intent), barrier);
        assertEquals(barrier, store.load().orElseThrow());
        var receipt = MossDepositControllerTest.observed(MossDepositControllerTest.NEXT_EPOCH, 1, 2, 2,
                MossDepositControllerTest.deposited());
        store.replace(Optional.of(barrier), barrier.confirm(receipt));
        assertEquals(receipt, store.load().orElseThrow().receipt());
    }

    @Test void staleExpectedJournalAndNewOperationCannotReplaceAnUnresolvedIntent() throws Exception {
        var store = new MossDepositFileStore(directory.resolve("moss-deposit.json"));
        var intent = intent();
        store.replace(Optional.empty(), intent);
        var other = intent();
        assertThrows(IOException.class, () -> store.replace(Optional.empty(), other));
        assertThrows(IOException.class, () -> store.replace(Optional.of(intent), other));
        assertEquals(intent, store.load().orElseThrow());
    }

    @Test void immutableBeforeEvidenceAndConfirmedReceiptCannotBeRewritten() throws Exception {
        var store = new MossDepositFileStore(directory.resolve("moss-deposit.json"));
        var intent = intent();
        store.replace(Optional.empty(), intent);
        var changedSlots = MossDepositControllerTest.replaceMain(intent.before().slots(), 2,
                MossDepositControllerTest.stack("minecraft:moss_block", 36, true));
        var changedBefore = new MossDepositJournal.Observation(intent.before().context(), intent.before().stamp(), changedSlots);
        var mutated = new MossDepositJournal(intent.operationId(), changedBefore,
                MossDepositPlanning.plan(changedSlots, 2).orElseThrow(), MossDepositJournal.Stage.PENDING, null, null);
        assertThrows(IOException.class, () -> store.replace(Optional.of(intent), mutated));
        assertEquals(intent, store.load().orElseThrow());
        var confirmed = intent.confirm(MossDepositControllerTest.after());
        store.replace(Optional.of(intent), confirmed);
        assertThrows(IOException.class, () -> store.replace(Optional.of(confirmed), intent));
        assertEquals(confirmed, store.load().orElseThrow());
    }

    @Test void unsupportedAtomicReplacementPreservesTheLastJournalWithoutFallback() throws Exception {
        Path path = directory.resolve("moss-deposit.json");
        var originalStore = new MossDepositFileStore(path);
        var intent = intent();
        originalStore.replace(Optional.empty(), intent);
        byte[] previous = Files.readAllBytes(path);
        var failingStore = new MossDepositFileStore(path, (temporary, destination) -> {
            assertTrue(Files.size(temporary) > 0);
            throw new AtomicMoveNotSupportedException(temporary.toString(), destination.toString(), "test filesystem");
        });
        assertThrows(AtomicMoveNotSupportedException.class,
                () -> failingStore.replace(Optional.of(intent), intent.confirm(MossDepositControllerTest.after())));
        assertArrayEquals(previous, Files.readAllBytes(path));
        assertEquals(intent, originalStore.load().orElseThrow());
        try (var paths = Files.list(directory)) {
            assertFalse(paths.anyMatch(file -> file.getFileName().toString().endsWith(".tmp")));
        }
    }

    @Test void simultaneousWritersCannotPassTheSameExpectedJournalCheck() throws Exception {
        Path path = directory.resolve("moss-deposit.json");
        Path lockPath = directory.resolve("moss-deposit.json.lock");
        try (FileChannel owner = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var held = owner.lock()) {
            assertTrue(held.isValid());
            var competingStore = new MossDepositFileStore(path);
            assertThrows(IOException.class, () -> competingStore.replace(Optional.empty(), intent()));
            assertFalse(Files.exists(path));
        }
        var availableStore = new MossDepositFileStore(path);
        availableStore.replace(Optional.empty(), intent());
        assertTrue(availableStore.load().isPresent());
    }

    @Test void corruptOversizedOrUnsupportedJournalsArePreservedAndRejected() throws Exception {
        Path path = directory.resolve("moss-deposit.json");
        var store = new MossDepositFileStore(path);
        for (byte[] invalid : new byte[][] {
                "{broken".getBytes(StandardCharsets.UTF_8),
                "{\"version\":2,\"journal\":null}".getBytes(StandardCharsets.UTF_8),
                "{\"version\":1.0,\"journal\":null}".getBytes(StandardCharsets.UTF_8),
                "{\"version\":1,\"journal\":null}".getBytes(StandardCharsets.UTF_8),
                "{\"version\":1,\"journal\":{},\"other\":1}".getBytes(StandardCharsets.UTF_8),
                new byte[] {(byte) 0xff}, new byte[MossDepositFileStore.MAXIMUM_BYTES + 1] }) {
            Files.write(path, invalid);
            assertThrows(IOException.class, store::load);
            assertArrayEquals(invalid, Files.readAllBytes(path));
            assertThrows(IOException.class, () -> store.replace(Optional.empty(), intent()));
            assertArrayEquals(invalid, Files.readAllBytes(path));
        }
    }

    @Test void journalRestoreRevalidatesTheCompleteSlotPlanAndReceipt() throws Exception {
        Path path = directory.resolve("moss-deposit.json");
        var store = new MossDepositFileStore(path);
        var intent = intent();
        store.replace(Optional.empty(), intent);
        String original = Files.readString(path);
        String invalid = original.replace("\"quantity\":37", "\"quantity\":36");
        assertNotEquals(original, invalid);
        Files.writeString(path, invalid);
        assertThrows(IOException.class, store::load);
        Files.writeString(path, original.replace("\"stage\":\"PENDING\"", "\"stage\":\"CONFIRMED\""));
        assertThrows(IOException.class, store::load);
    }

    @Test void versionOnePendingMossRetainsOriginalIntentAndUpgradesOnlyOnDurableConfirmation() throws Exception {
        Path path = directory.resolve("moss-deposit.json");
        var store = new MossDepositFileStore(path);
        var intent = intent();
        store.replace(Optional.empty(), intent);
        String legacy = legacyJson(Files.readString(path));
        Files.writeString(path, legacy);
        assertEquals(intent, store.load().orElseThrow());
        assertEquals(legacy, Files.readString(path), "reading never rewrites or restamps pending evidence");
        var before = store.load().orElseThrow();
        assertEquals(intent.operationId(), before.operationId());
        assertEquals(intent.before().stamp(), before.before().stamp());
        assertEquals(java.util.Set.of(MossDepositFacts.MOSS), before.before().slots().chest().getFirst().insertion().keySet());
        var confirmed = before.confirm(MossDepositControllerTest.after());
        store.replace(Optional.of(before), confirmed);
        assertEquals(2, JsonParser.parseString(Files.readString(path)).getAsJsonObject().get("version").getAsInt());
        assertEquals(confirmed, store.load().orElseThrow());
    }

    @Test void versionOneBarrierAndConfirmedReceiptRemainExactAcrossUpgrade() throws Exception {
        Path path = directory.resolve("moss-deposit.json");
        var store = new MossDepositFileStore(path);
        var initial = intent();
        var observed = MossDepositControllerTest.observed(MossDepositControllerTest.NEXT_EPOCH, 1, 1, 1,
                MossDepositControllerTest.deposited());
        var barrier = initial.withBarrier(observed);
        store.replace(Optional.empty(), barrier);
        Files.writeString(path, legacyJson(Files.readString(path)));
        assertEquals(barrier, store.load().orElseThrow());
        var receipt = MossDepositControllerTest.observed(MossDepositControllerTest.NEXT_EPOCH, 1, 2, 2,
                MossDepositControllerTest.deposited());
        var confirmed = barrier.confirm(receipt);
        store.replace(Optional.of(barrier), confirmed);
        Files.writeString(path, legacyJson(Files.readString(path)));
        assertEquals(confirmed, store.load().orElseThrow());
    }

    @Test void legacyFlagsCannotInventNewPickupAuthority() throws Exception {
        Path path = directory.resolve("moss-deposit.json");
        var store = new MossDepositFileStore(path);
        store.replace(Optional.empty(), intent());
        String legacy = legacyJson(Files.readString(path)).replace("minecraft:moss_block", "minecraft:pumpkin_seeds");
        Files.writeString(path, legacy);
        assertThrows(IOException.class, store::load);
        assertEquals(legacy, Files.readString(path));
    }

    @Test void newPickupPendingAndExactReceiptRoundTripWithItemSpecificInsertionFacts() throws Exception {
        Path path = directory.resolve("moss-deposit.json");
        var store = new MossDepositFileStore(path);
        String pickup = "minecraft:pumpkin_seeds";
        var beforeBase = MossDepositControllerTest.before();
        var afterBase = MossDepositControllerTest.after();
        var before = new MossDepositJournal.Observation(beforeBase.context(), beforeBase.stamp(),
                replaceMossKind(beforeBase.slots(), pickup));
        var after = new MossDepositJournal.Observation(afterBase.context(), afterBase.stamp(),
                replaceMossKind(afterBase.slots(), pickup));
        var pending = MossDepositJournal.pending(before, MossDepositPlanning.plan(before.slots()).orElseThrow());
        store.replace(Optional.empty(), pending);
        assertEquals(pending, store.load().orElseThrow());
        var confirmed = pending.confirm(after);
        store.replace(Optional.of(pending), confirmed);
        assertEquals(confirmed, store.load().orElseThrow());
        assertEquals(pickup, confirmed.before().slots().main().get(2).stack().itemId());
    }

    private static MossDepositFacts.Snapshot replaceMossKind(MossDepositFacts.Snapshot slots, String itemId) {
        java.util.function.Function<MossDepositFacts.SlotFacts, MossDepositFacts.SlotFacts> replace = slot -> {
            var stack = slot.stack().plainPickup()
                    ? MossDepositControllerTest.stack(itemId, slot.stack().count(), true) : slot.stack();
            return new MossDepositFacts.SlotFacts(slot.handlerSlot(), slot.inventoryIndex(), stack, slot.canTake(),
                    java.util.Map.of(itemId, new MossDepositFacts.Insertion(true, 64)));
        };
        return new MossDepositFacts.Snapshot(slots.chest().stream().map(replace).toList(),
                slots.main().stream().map(replace).toList(), slots.cursor(), slots.offhand(), slots.armor());
    }

    private static String legacyJson(String modern) {
        JsonObject root = JsonParser.parseString(modern).getAsJsonObject();
        root.addProperty("version", 1);
        var journal = root.getAsJsonObject("journal");
        legacyObservation(journal.getAsJsonObject("before"));
        if (!journal.get("receipt").isJsonNull()) { legacyObservation(journal.getAsJsonObject("receipt")); }
        return root.toString();
    }

    private static void legacyObservation(JsonObject observation) {
        var slots = observation.getAsJsonObject("slots");
        for (String group : java.util.List.of("chest", "main")) {
            for (var element : slots.getAsJsonArray(group)) {
                var slot = element.getAsJsonObject();
                var moss = slot.remove("insertion").getAsJsonObject().getAsJsonObject(MossDepositFacts.MOSS);
                slot.add("canInsertMoss", moss.get("allowed"));
                slot.add("mossSlotLimit", moss.get("limit"));
                legacyStack(slot.getAsJsonObject("stack"));
            }
        }
        legacyStack(slots.getAsJsonObject("cursor"));
        legacyStack(slots.getAsJsonObject("offhand"));
        for (var armor : slots.getAsJsonArray("armor")) { legacyStack(armor.getAsJsonObject()); }
    }

    private static void legacyStack(JsonObject stack) { stack.add("plainMoss", stack.remove("plainPickup")); }

    private static MossDepositJournal intent() {
        var before = MossDepositControllerTest.before();
        return MossDepositJournal.pending(before, MossDepositPlanning.plan(before.slots(), 2).orElseThrow());
    }
}
