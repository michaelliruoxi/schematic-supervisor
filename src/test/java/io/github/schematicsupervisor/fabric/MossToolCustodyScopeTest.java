package io.github.schematicsupervisor.fabric;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.network.packet.c2s.play.PlayerActionC2SPacket;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import org.junit.jupiter.api.Test;

/** Real packet-field predicates only; this does not simulate client lifetime or inventory receipts. */
class MossToolCustodyScopeTest {
    private static final BlockPos TARGET = new BlockPos(3, 8, 12);
    private static final BlockHitResult HIT = new BlockHitResult(new Vec3d(3.5, 9, 12.5), Direction.UP, TARGET, false);

    @Test void oneExactMiningPacketCannotAuthorizeASecondStart() {
        var scope = MossToolCustody.Scope.mining(TARGET);
        assertTrue(scope.acceptMining(PlayerActionC2SPacket.Action.START_DESTROY_BLOCK, TARGET));
        assertFalse(scope.acceptMining(PlayerActionC2SPacket.Action.START_DESTROY_BLOCK, TARGET));
    }

    @Test void onlyExactMiningTargetAndMiningActionsAreAuthorized() {
        for (var action : new PlayerActionC2SPacket.Action[] {
                PlayerActionC2SPacket.Action.START_DESTROY_BLOCK,
                PlayerActionC2SPacket.Action.STOP_DESTROY_BLOCK,
                PlayerActionC2SPacket.Action.ABORT_DESTROY_BLOCK}) {
            assertFalse(MossToolCustody.Scope.mining(TARGET).acceptMining(action, TARGET.up()));
            assertTrue(MossToolCustody.Scope.mining(TARGET).acceptMining(action, TARGET));
        }
        for (var action : new PlayerActionC2SPacket.Action[] {
                PlayerActionC2SPacket.Action.DROP_ITEM, PlayerActionC2SPacket.Action.DROP_ALL_ITEMS,
                PlayerActionC2SPacket.Action.SWAP_ITEM_WITH_OFFHAND, PlayerActionC2SPacket.Action.RELEASE_USE_ITEM}) {
            assertFalse(MossToolCustody.Scope.mining(TARGET).acceptMining(action, TARGET));
        }
    }

    @Test void clickMatchesExactWindowAndFieldsOnlyOnce() {
        Object handler = new Object();
        var scope = MossToolCustody.Scope.click(handler, 7, 13, 0, SlotActionType.SWAP);
        assertTrue(scope.acceptClick(handler, 7, 13, 0, SlotActionType.SWAP));
        assertFalse(scope.acceptClick(handler, 7, 13, 0, SlotActionType.SWAP));
        assertFalse(MossToolCustody.Scope.click(handler, 7, 13, 0, SlotActionType.SWAP)
                .acceptClick(new Object(), 7, 13, 0, SlotActionType.SWAP));
        assertFalse(MossToolCustody.Scope.click(handler, 7, 13, 0, SlotActionType.SWAP)
                .acceptClick(handler, 8, 13, 0, SlotActionType.SWAP));
        assertFalse(MossToolCustody.Scope.click(handler, 7, 13, 0, SlotActionType.SWAP)
                .acceptClick(handler, 7, 14, 0, SlotActionType.SWAP));
        assertFalse(MossToolCustody.Scope.click(handler, 7, 13, 0, SlotActionType.SWAP)
                .acceptClick(handler, 7, 13, 1, SlotActionType.SWAP));
        assertFalse(MossToolCustody.Scope.click(handler, 7, 13, 0, SlotActionType.SWAP)
                .acceptClick(handler, 7, 13, 0, SlotActionType.PICKUP));
    }

    @Test void interactionRejectsHandFacePositionAndTargetSubstitution() {
        var scope = MossToolCustody.Scope.interact(Hand.MAIN_HAND, HIT);
        assertTrue(scope.acceptInteraction(Hand.MAIN_HAND, HIT));
        assertFalse(scope.acceptInteraction(Hand.MAIN_HAND, HIT));
        assertFalse(MossToolCustody.Scope.interact(Hand.MAIN_HAND, HIT).acceptInteraction(Hand.OFF_HAND, HIT));
        assertFalse(MossToolCustody.Scope.interact(Hand.MAIN_HAND, HIT)
                .acceptInteraction(Hand.MAIN_HAND, HIT.withSide(Direction.DOWN)));
        assertFalse(MossToolCustody.Scope.interact(Hand.MAIN_HAND, HIT)
                .acceptInteraction(Hand.MAIN_HAND, HIT.withBlockPos(TARGET.up())));
        assertFalse(MossToolCustody.Scope.interact(Hand.MAIN_HAND, HIT).acceptInteraction(Hand.MAIN_HAND,
                new BlockHitResult(HIT.getPos().add(0.01, 0, 0), Direction.UP, TARGET, false)));
    }

    @Test void interactionRejectsInsideBorderAndMissedSubstitution() {
        assertFalse(MossToolCustody.Scope.interact(Hand.MAIN_HAND, HIT).acceptInteraction(Hand.MAIN_HAND,
                new BlockHitResult(HIT.getPos(), Direction.UP, TARGET, true)));
        assertFalse(MossToolCustody.Scope.interact(Hand.MAIN_HAND, HIT)
                .acceptInteraction(Hand.MAIN_HAND, HIT.againstWorldBorder()));
        assertFalse(MossToolCustody.Scope.interact(Hand.MAIN_HAND, HIT).acceptInteraction(Hand.MAIN_HAND,
                BlockHitResult.createMissed(HIT.getPos(), Direction.UP, TARGET)));
    }

    @Test void scopesDoNotAuthorizeAnotherPacketKind() {
        assertFalse(MossToolCustody.Scope.mining(TARGET).acceptInteraction(Hand.MAIN_HAND, HIT));
        assertFalse(MossToolCustody.Scope.interact(Hand.MAIN_HAND, HIT)
                .acceptMining(PlayerActionC2SPacket.Action.START_DESTROY_BLOCK, TARGET));
        Object handler = new Object();
        assertFalse(MossToolCustody.Scope.mining(TARGET).acceptClick(handler, 0, 13, 0, SlotActionType.SWAP));
    }
}
