package io.github.schematicsupervisor.fabric;

import static io.github.schematicsupervisor.fabric.MaterialPurchaseControllerTest.*;
import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class MaterialShopReturnTest {
    private static MaterialPurchaseFacts.Snapshot baseline() {
        return replaceMain(initial(), 0, empty());
    }

    private static MaterialPurchaseFacts.Snapshot delivered(int count) {
        return replaceMain(baseline(), 4, stack("minecraft:glowstone", count, true));
    }

    private static MaterialPurchaseJournal.Observation observation(long open, long sequence, int sync,
                                                                   MaterialPurchaseFacts.Snapshot slots) {
        return new MaterialPurchaseJournal.Observation(CONTEXT,
                new ServerInventorySnapshotStamp(EPOCH, 1, open, sequence, sync, 1), slots);
    }

    private static MaterialPurchaseJournal intent() {
        return MaterialPurchaseJournal.pending(observation(172, 283, 72, baseline()), quote());
    }

    private static MaterialPurchaseJournal.Observation returned() {
        return observation(173, 284, 73, delivered(64));
    }

    private static MaterialShopPolicy.Menu menu() {
        var entries = new ArrayList<MaterialShopPolicy.Entry>();
        for (int slot = 0; slot < 54; slot++) {
            entries.add(new MaterialShopPolicy.Entry(slot, "minecraft:white_stained_glass_pane", "", List.of(), 1));
        }
        entries.set(11, new MaterialShopPolicy.Entry(11, "minecraft:dirt", "Dirt", List.of("Buy price: $800"), 1));
        entries.set(45, new MaterialShopPolicy.Entry(45, "minecraft:birch_door", "Main Menu", List.of("← Click to go back"), 1));
        entries.set(50, new MaterialShopPolicy.Entry(50, "minecraft:paper", "Next page →", List.of(), 1));
        return new MaterialShopPolicy.Menu(153, 73, "Blocks (Page 1/5)", entries, true);
    }

    @Test void sync72To73AllowsCleanupButLeavesDurableReceiptPendingWithoutReplay() throws Exception {
        var store = new FakeStore();
        store.value = Optional.of(intent());
        var port = new FakePort(store);
        var controller = new MaterialPurchaseController(store, port);
        assertTrue(MaterialShopPolicy.isPostPurchaseReturn(menu(), store.value.orElseThrow(), returned()));
        assertEquals(MaterialPurchaseJournal.Stage.PENDING, store.value.orElseThrow().stage());
        assertEquals(0, store.saves);
        assertThrows(IllegalStateException.class, () -> controller.begin(intent().before(), quote()));
        controller.requestReceiptReopen();
        assertEquals(MaterialPurchaseController.Status.CONFIRMED,
                controller.reconcile(observation(174, 285, 74, delivered(64))).status());
        assertEquals(0, port.clicks);
        assertEquals(1, port.reopens);
        assertEquals(1, store.saves);
    }

    @Test void missingOrStaleServerEvidenceCannotAdoptTransition() {
        assertFalse(MaterialShopPolicy.isPostPurchaseReturn(menu(), intent(), null));
        for (var candidate : List.of(observation(172, 284, 73, delivered(64)),
                observation(174, 285, 73, delivered(64)),
                observation(173, 283, 73, delivered(64)), observation(173, 284, 74, delivered(64)),
                observed(NEXT_EPOCH, 1, 173, 284, delivered(64)),
                observed(EPOCH, 2, 173, 284, delivered(64)))) {
            assertFalse(MaterialShopPolicy.isPostPurchaseReturn(menu(), intent(), candidate));
        }
        assertFalse(MaterialShopPolicy.isPostPurchaseReturn(menu(), intent().confirm(returned()), returned()));
        assertFalse(MaterialShopPolicy.isPostPurchaseReturn(menu(), null, returned()));
        var other = new MaterialPurchaseJournal.Context(CONTEXT.worldIdentityHash(), CONTEXT.dimension(), "other-plan");
        assertFalse(MaterialShopPolicy.isPostPurchaseReturn(menu(), intent(),
                new MaterialPurchaseJournal.Observation(other, returned().stamp(), delivered(64))));
    }

    @Test void exactQuantityPlainItemsProtectedSlotsEquipmentAndCursorRemainRequired() {
        var good = delivered(64);
        for (var slots : List.of(baseline(), delivered(63),
                replaceMain(good, 5, stack("minecraft:glowstone", 1, true)),
                replaceMain(good, 4, stack("minecraft:glowstone", 64, false)),
                replaceMain(good, 35, empty()),
                new MaterialPurchaseFacts.Snapshot(good.main(), stack("minecraft:glowstone", 1, true), good.offhand(), good.armor()),
                new MaterialPurchaseFacts.Snapshot(good.main(), good.cursor(), empty(), good.armor()),
                new MaterialPurchaseFacts.Snapshot(good.main(), good.cursor(), good.offhand(),
                        List.of(stack("minecraft:diamond_boots", 1, false), empty(), empty(), empty())))) {
            assertFalse(MaterialShopPolicy.isPostPurchaseReturn(menu(), intent(), observation(173, 284, 73, slots)));
        }
    }

    @Test void titleAlonePartialMenuWrongPageAndAmbiguousEntriesCannotGrantOwnership() {
        var good = menu();
        assertFalse(MaterialShopPolicy.isPostPurchaseReturn(null, intent(), returned()));
        for (String title : List.of("Chest", "Blocks (Page 2/5)", "Shop | Economy")) {
            assertFalse(MaterialShopPolicy.isPostPurchaseReturn(
                    new MaterialShopPolicy.Menu(153, 73, title, good.entries(), true), intent(), returned()));
        }
        assertFalse(MaterialShopPolicy.isPostPurchaseReturn(
                new MaterialShopPolicy.Menu(153, 73, good.title(), good.entries(), false), intent(), returned()));
        assertFalse(MaterialShopPolicy.isPostPurchaseReturn(
                new MaterialShopPolicy.Menu(153, 73, good.title(), good.entries().subList(0, 53), true), intent(), returned()));
        for (var replacement : List.of(
                new MaterialShopPolicy.Entry(45, "minecraft:stone", "Main Menu", List.of(), 1),
                new MaterialShopPolicy.Entry(12, "minecraft:dirt", "Dirt", List.of("Buy price: $800"), 1),
                new MaterialShopPolicy.Entry(11, "minecraft:dirt", "Dirt", List.of("Buy price: $invalid"), 1))) {
            var entries = new ArrayList<>(good.entries());
            entries.set(replacement.slot(), replacement);
            assertFalse(MaterialShopPolicy.isPostPurchaseReturn(
                    new MaterialShopPolicy.Menu(153, 73, good.title(), entries, true), intent(), returned()));
        }
    }
}
