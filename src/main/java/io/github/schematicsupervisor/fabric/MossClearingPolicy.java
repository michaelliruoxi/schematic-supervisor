package io.github.schematicsupervisor.fabric;

import io.github.schematicsupervisor.core.BlockPosition;
import io.github.schematicsupervisor.core.BuildVolume;
import io.github.schematicsupervisor.core.ChunkCoordinate;
import io.github.schematicsupervisor.core.Material;
import io.github.schematicsupervisor.core.OrdinaryPlacement;
import io.github.schematicsupervisor.core.WorkOrder;
import java.util.Objects;

/** Exact replacement targets allow moss and stems, plus jack o'lanterns only for planned Glowstone. */
final class MossClearingPolicy {
    record Observation(BlockPosition target, String blockId, boolean received,
                       boolean blockEntity, boolean fluid, boolean entityConflict,
                       boolean replacementAvailable) { }

    private MossClearingPolicy() { }

    static boolean allowsReplacement(OrdinaryPlacement placement, String actualBlockId) {
        if (placement == null) { return false; }
        return "minecraft:moss_block".equals(actualBlockId)
                || StemClearingSweep.isStem(actualBlockId)
                || "minecraft:jack_o_lantern".equals(actualBlockId)
                && placement.material() == Material.GLOWSTONE
                && "minecraft:glowstone".equals(placement.state().blockId())
                && placement.state().properties().isEmpty();
    }

    static int clearingBudgetTicks(OrdinaryPlacement placement, String actualBlockId) {
        if (!allowsReplacement(placement, actualBlockId)) {
            throw new IllegalArgumentException("Unsupported planned clearing target");
        }
        // Airborne plain-hand jack o'lantern breaking needs about 150 ticks before acknowledgement.
        return "minecraft:jack_o_lantern".equals(actualBlockId) ? 240 : 100;
    }

    static String rejection(BuildVolume volume, WorkOrder order, Observation observed) {
        Objects.requireNonNull(volume, "volume");
        Objects.requireNonNull(observed, "observed");
        if (!(order instanceof WorkOrder.OrdinaryBlocks ordinary)
                || !volume.contains(observed.target())
                || !ordinary.chunk().equals(ChunkCoordinate.containing(observed.target()))
                || ordinary.placements().stream().noneMatch(placement ->
                        placement.position().equals(observed.target()))) {
            return "Clearing target is outside the exact current ordinary slice";
        }
        if (!observed.received()) { return "Clearing target chunk has not been received"; }
        if (ordinary.placements().stream().filter(placement -> placement.position().equals(observed.target()))
                .anyMatch(placement -> !allowsReplacement(placement, observed.blockId()))) {
            return "Only moss, permitted stems, or a jack o'lantern at a planned Glowstone target may be cleared";
        }
        if (observed.blockEntity() || observed.fluid()) {
            return "Clearing target contains a block entity or fluid";
        }
        if (observed.entityConflict()) { return "An entity occupies or stands on the clearing target"; }
        if (!observed.replacementAvailable()) { return "Acquire the planned replacement before clearing"; }
        return "";
    }
}
