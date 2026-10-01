package io.github.schematicsupervisor.fabric;

import static org.junit.jupiter.api.Assertions.*;
import static io.github.schematicsupervisor.fabric.MaterialPurchaseControllerTest.*;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class MaterialPurchaseFactsTest {
    @Test void exactPlainGainMayMergePartialStacksOrUseOtherEligibleSlots() {
        assertTrue(MaterialPurchaseFacts.receiptProblem(initial(), quote(), purchased()).isEmpty());
        var relocated = replaceMain(replaceMain(purchased(), 0, empty()), 12, stack("minecraft:glowstone", 64, true));
        assertTrue(MaterialPurchaseFacts.receiptProblem(initial(), quote(), relocated).isEmpty());
    }

    @Test void partialExcessWrongProductAndModifiedGainsAreUnresolved() {
        for (var after : List.of(initial(), replaceMain(purchased(), 1, stack("minecraft:glowstone", 31, true)),
                replaceMain(purchased(), 1, stack("minecraft:glowstone", 33, true)),
                replaceMain(purchased(), 1, stack("minecraft:dirt", 32, true)),
                replaceMain(purchased(), 1, stack("minecraft:glowstone", 32, false)))) {
            assertTrue(MaterialPurchaseFacts.receiptProblem(initial(), quote(), after).isPresent());
        }
    }

    @Test void allThirtySixSlotsAreCheckedIncludingLateProtectedItemAndNewWrongItem() {
        assertTrue(MaterialPurchaseFacts.receiptProblem(initial(), quote(),
                replaceMain(purchased(), 35, stack("minecraft:tripwire_hook", 2, false))).isPresent());
        assertTrue(MaterialPurchaseFacts.receiptProblem(initial(), quote(),
                replaceMain(purchased(), 34, stack("minecraft:dirt", 1, false))).isPresent());
    }

    @Test void modifiedExistingTargetIsProtectedAndCannotBeConvertedIntoReceiptStock() {
        var modifiedBefore = replaceMain(initial(), 10, stack("minecraft:glowstone", 64, false));
        var unchangedModified = replaceMain(purchased(), 10, stack("minecraft:glowstone", 64, false));
        assertTrue(MaterialPurchaseFacts.receiptProblem(modifiedBefore, quote(), unchangedModified).isEmpty());
        assertTrue(MaterialPurchaseFacts.receiptProblem(modifiedBefore, quote(),
                replaceMain(unchangedModified, 10, stack("minecraft:glowstone", 64, true))).isPresent());
    }

    @Test void cursorAndEquipmentChangesCannotBeHiddenByCorrectMainGain() {
        var success = purchased();
        var armor = new ArrayList<>(success.armor());
        armor.set(3, stack("minecraft:tripwire_hook", 1, false));
        for (var invalid : List.of(new MaterialPurchaseFacts.Snapshot(success.main(), stack("minecraft:glowstone", 1, true), success.offhand(), success.armor()),
                new MaterialPurchaseFacts.Snapshot(success.main(), success.cursor(), empty(), success.armor()),
                new MaterialPurchaseFacts.Snapshot(success.main(), success.cursor(), success.offhand(), armor))) {
            assertTrue(MaterialPurchaseFacts.receiptProblem(initial(), quote(), invalid).isPresent());
        }
    }

    @Test void exactThreeProductAllowlistAndCurrentQuotedQuantityAreBounded() {
        for (var product : MaterialPurchaseFacts.Product.values()) {
            var valid = new MaterialPurchaseFacts.Quote(product, 9, 1, "a".repeat(64),
                    "Buying stacks of " + product.displayName(), 8);
            assertEquals(576, valid.quantity());
            assertEquals(product, MaterialPurchaseFacts.Product.fromItemId(product.itemId()));
        }
        assertThrows(IllegalArgumentException.class, () -> MaterialPurchaseFacts.Product.fromItemId("minecraft:diamond"));
        assertThrows(IllegalArgumentException.class, () -> new MaterialPurchaseFacts.Quote(quote().product(), 10, 1, "a".repeat(64), quote().menuTitle(), 9));
        assertThrows(IllegalArgumentException.class, () -> new MaterialPurchaseFacts.Quote(quote().product(), 1, 0, "a".repeat(64), quote().menuTitle(), 0));
        assertThrows(IllegalArgumentException.class, () -> new MaterialPurchaseFacts.Quote(quote().product(), 1, 1, "a".repeat(64), quote().menuTitle(), 1));
        assertThrows(IllegalArgumentException.class, () -> new MaterialPurchaseFacts.Quote(quote().product(), 1, 1, "a".repeat(64), "Selling stacks of Glowstone", 0));
        assertThrows(IllegalArgumentException.class, () -> new MaterialPurchaseFacts.Quote(quote().product(), 1, 1, "a".repeat(64), "Buying stacks of Dirt", 0));
    }

    @Test void baselineCapacityIncludesOnlyCompatiblePlainPartialRoomAndRequiresCompleteInventories() {
        var full = new ArrayList<MaterialPurchaseFacts.StackFacts>();
        for (int slot = 0; slot < 36; slot++) { full.add(stack("minecraft:glowstone", 64, true)); }
        var noEmptySlot = new MaterialPurchaseFacts.Snapshot(full, empty(), empty(), initial().armor());
        assertTrue(MaterialPurchaseFacts.baselineProblem(noEmptySlot, quote()).isPresent());
        var partialRoom = replaceMain(replaceMain(noEmptySlot, 0, stack("minecraft:glowstone", 32, true)),
                1, stack("minecraft:glowstone", 32, true));
        assertTrue(MaterialPurchaseFacts.baselineProblem(partialRoom, quote()).isEmpty());
        assertTrue(MaterialPurchaseFacts.receiptProblem(partialRoom, quote(), noEmptySlot).isEmpty());
        assertTrue(MaterialPurchaseFacts.baselineProblem(replaceMain(partialRoom, 0,
                stack("minecraft:glowstone", 32, false)), quote()).isPresent());
        assertThrows(IllegalArgumentException.class, () -> new MaterialPurchaseFacts.Snapshot(full.subList(0, 35), empty(), empty(), initial().armor()));
        assertThrows(IllegalArgumentException.class, () -> new MaterialPurchaseFacts.Snapshot(full, empty(), empty(), List.of()));
    }

    @Test void boundedOpaqueFingerprintsAndDefaultStackLimitsAreRequired() {
        assertThrows(IllegalArgumentException.class, () -> new MaterialPurchaseFacts.StackFacts("raw-components", "minecraft:glowstone", 64, 64, false, true));
        assertThrows(IllegalArgumentException.class, () -> new MaterialPurchaseFacts.StackFacts("a".repeat(64), "minecraft:diamond", 64, 64, false, true));
        assertThrows(IllegalArgumentException.class, () -> new MaterialPurchaseFacts.StackFacts("a".repeat(64), "minecraft:glowstone", 16, 16, false, true));
    }
}
