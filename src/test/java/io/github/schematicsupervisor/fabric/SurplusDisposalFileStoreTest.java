package io.github.schematicsupervisor.fabric;

import static org.junit.jupiter.api.Assertions.*;
import static io.github.schematicsupervisor.fabric.SurplusDisposalPolicyTest.*;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SurplusDisposalFileStoreTest {
    @TempDir Path temporary;

    @Test void intentAndConfirmedReceiptRoundTripAndCannotBeOverwrittenByAnotherPendingOperation() throws Exception {
        var store = new SurplusDisposalFileStore(temporary.resolve("surplus-disposal.json"));
        var proof = proof("minecraft:pumpkin_seeds");
        var intent = SurplusDisposalJournal.pending(proof, 2, SITE, PLAYER, NOW);
        store.replace(Optional.empty(), intent);
        assertEquals(intent, store.load().orElseThrow());
        var another = SurplusDisposalJournal.pending(proof, 2, SITE, PLAYER, NOW);
        assertThrows(IOException.class, () -> store.replace(Optional.of(intent), another));
        var confirmed = intent.confirm(receipt(proof));
        store.replace(Optional.of(intent), confirmed);
        assertEquals(confirmed, store.load().orElseThrow());
        assertThrows(IOException.class, () -> store.replace(Optional.of(confirmed), intent));
        assertThrows(IOException.class, () -> store.replace(Optional.empty(), another));
        assertEquals(confirmed, store.load().orElseThrow());
    }

    @Test void unsupportedAtomicReplacementKeepsPriorJournalAndCreatesNoInputPermission() throws Exception {
        Path path = temporary.resolve("surplus-disposal.json");
        var store = new SurplusDisposalFileStore(path, (from, to) -> {
            throw new AtomicMoveNotSupportedException(from.toString(), to.toString(), "unsupported");
        });
        var intent = SurplusDisposalJournal.pending(proof("minecraft:moss_block"), 2, SITE, PLAYER, NOW);
        assertThrows(IOException.class, () -> store.replace(Optional.empty(), intent));
        assertFalse(Files.exists(path));
        try (var files = Files.list(temporary)) { assertEquals(1, files.count(), "only the bounded lock file remains"); }
    }

    @Test void committedThenFailedWriteReloadsAsPendingAndCannotDispatchOnRestart() throws Exception {
        Path path = temporary.resolve("surplus-disposal.json");
        var store = new SurplusDisposalFileStore(path, (from, to) -> {
            Files.move(from, to, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            throw new IOException("acknowledgement lost after commit");
        });
        var proof = proof("minecraft:melon_seeds");
        var fake = new SurplusDisposalControllerTest.Port(new SurplusDisposalControllerTest.Store());
        var controller = new SurplusDisposalController(store, fake);
        assertThrows(IOException.class, () -> controller.begin(true, proof, SITE, PLAYER, NOW));
        assertEquals(0, fake.throwsSent);
        var reloaded = new SurplusDisposalController(new SurplusDisposalFileStore(path), fake);
        assertEquals(SurplusDisposalJournal.Stage.PENDING, reloaded.journal().orElseThrow().stage());
        assertThrows(IllegalStateException.class, () -> reloaded.begin(true, proof, SITE, PLAYER, NOW));
        assertEquals(0, fake.throwsSent);
    }

    @Test void corruptUnknownMissingAndOversizedJournalsRemainUnreadableAndUntouched() throws Exception {
        Path path = temporary.resolve("surplus-disposal.json");
        var store = new SurplusDisposalFileStore(path);
        var intent = SurplusDisposalJournal.pending(proof("minecraft:jack_o_lantern"), 2, SITE, PLAYER, NOW);
        store.replace(Optional.empty(), intent);
        String valid = Files.readString(path);
        for (String invalid : new String[] { "{", valid.replace("\"version\":2", "\"version\":99"),
                valid.replace("\"sourceMainIndex\":2", "\"sourceMainIndex\":36"),
                valid.replace("\"stage\":\"PENDING\"", "\"stage\":\"CONFIRMED\""),
                valid.replace("\"playerUuid\":\"" + PLAYER + "\"", "\"playerUuid\":null"),
                valid.replace("\"receipt\":null", "\"receipt\":null,\"unknown\":true"),
                " ".repeat(SurplusDisposalFileStore.MAXIMUM_BYTES + 1) }) {
            Files.writeString(path, invalid);
            assertThrows(IOException.class, store::load);
            assertEquals(invalid, Files.readString(path));
        }
    }

    @Test void journalConstructorRejectsMismatchedProofItemAndProtectedReceiptChanges() {
        var proof = proof("minecraft:moss_block");
        var intent = SurplusDisposalJournal.pending(proof, 2, SITE, PLAYER, NOW);
        var storage = new SurplusDisposalJournal.StorageProof(intent.storage().chests(), "minecraft:melon_seeds", 1);
        assertThrows(IllegalArgumentException.class, () -> new SurplusDisposalJournal(intent.operationId(), PLAYER, intent.before(),
                2, SITE, storage, SurplusDisposalJournal.Stage.PENDING, null, null));
        var after = receipt(proof);
        var changed = MossDepositControllerTest.replaceMain(after.slots(), 5, MossDepositControllerTest.empty());
        assertThrows(IllegalArgumentException.class, () -> intent.confirm(withSlots(after, changed)));
        assertThrows(IllegalArgumentException.class, () -> SurplusDisposalJournal.pending(proof, 2, SITE, "invalid", NOW));
        assertThrows(IllegalArgumentException.class, () -> SurplusDisposalJournal.pending(proof, 2, SITE, null, NOW));
        assertEquals(PLAYER, intent.confirm(after).playerUuid());
    }

    @Test void legacyIntentLoadsWithoutRewritingOrChangingItsStorageOnlyAuthorization() throws Exception {
        Path path = temporary.resolve("surplus-disposal.json");
        var store = new SurplusDisposalFileStore(path);
        var intent = SurplusDisposalJournal.pending(proof("minecraft:moss_block"), 2, SITE, PLAYER, NOW);
        store.replace(Optional.empty(), intent);
        var legacy = com.google.gson.JsonParser.parseString(Files.readString(path)).getAsJsonObject();
        legacy.addProperty("version", 1); legacy.getAsJsonObject("journal").getAsJsonObject("storage").remove("mode");
        Files.writeString(path, legacy.toString());
        assertEquals(intent, store.load().orElseThrow());
        assertEquals(legacy.toString(), Files.readString(path));
        store.replace(Optional.of(intent), intent.confirm(receipt(proof("minecraft:moss_block"))));
        assertEquals(SurplusDisposalJournal.Stage.CONFIRMED, store.load().orElseThrow().stage());
    }

    @Test void directIntentRoundTripsAndUnknownOrMissingModeCannotAuthorizeDisposal() throws Exception {
        Path path = temporary.resolve("surplus-disposal.json");
        var store = new SurplusDisposalFileStore(path);
        var observed = SurplusDisposalAuthorizationTest.availableStorage();
        var direct = SurplusDisposalAuthorization.direct(java.util.List.of(observed.context()), observed, NOW);
        var intent = SurplusDisposalJournal.pendingAuthorized(direct, 2, SITE, PLAYER, NOW);
        store.replace(Optional.empty(), intent);
        assertEquals(intent, store.load().orElseThrow());
        String valid = Files.readString(path);
        for (String value : new String[] {"null", "\"UNKNOWN\"", "true"}) {
            Files.writeString(path, valid.replace("\"mode\":\"DIRECT\"", "\"mode\":" + value));
            assertThrows(IOException.class, store::load);
        }
        Files.writeString(path, valid.replace(",\"mode\":\"DIRECT\"", ""));
        assertThrows(IOException.class, store::load);
    }
}
