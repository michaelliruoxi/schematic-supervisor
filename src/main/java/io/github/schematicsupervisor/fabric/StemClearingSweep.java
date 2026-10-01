package io.github.schematicsupervisor.fabric;

import io.github.schematicsupervisor.core.BlockPosition;
import io.github.schematicsupervisor.core.BlockState;
import io.github.schematicsupervisor.core.BuildVolume;
import io.github.schematicsupervisor.core.SchematicPlan;
import io.github.schematicsupervisor.core.WorkOrder;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.BooleanSupplier;

/** A bounded pre-order scan; it never changes the persisted layer schedule. */
final class StemClearingSweep {
    static final int CLEARING_BUDGET_TICKS = 100;
    enum Stage { APPROACH, INTERACTION }
    enum ApproachAction { INSPECT_TARGET, FOLLOW_PLANNED_ROUTE, RECEIVE_TARGET_CHUNK }
    private final BuildVolume bounds;
    private final Map<BlockPosition, BlockState> expected;
    private final int width;
    private final int depth;
    private final int size;

    record Target(BlockPosition position, BlockState observedState) {
        Target {
            Objects.requireNonNull(position, "position");
            Objects.requireNonNull(observedState, "observedState");
            if (!isStem(observedState.blockId())) {
                throw new IllegalArgumentException("The clearing target is not an allowed stem");
            }
        }

        boolean matches(BlockState actual) { return observedState.equals(actual); }
    }

    StemClearingSweep(SchematicPlan plan, WorkOrder order) {
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(order, "order");
        if (!plan.chunk(order.chunkIndex()).chunk().equals(order.chunk())) {
            throw new IllegalArgumentException("Stem sweep chunk does not match the selected plan");
        }
        var targets = switch (order) {
            case WorkOrder.OrdinaryBlocks ordinary -> ordinary.placements().stream()
                    .map(placement -> placement.position()).toList();
            case WorkOrder.Till till -> till.targets();
            case WorkOrder.Plant plant -> plant.targets();
        };
        BuildVolume volume = plan.buildVolume();
        for (BlockPosition target : targets) {
            if (!volume.contains(target) || !order.chunk().contains(target)) {
                throw new IllegalArgumentException("Stem sweep order is outside the selected volume or chunk");
            }
        }
        int maximumOrderY = targets.stream().mapToInt(BlockPosition::y).max().orElseThrow();
        int top = (int) Math.min(volume.maxY(), (long) maximumOrderY + 1);
        int chunkX = Math.multiplyExact(order.chunk().x(), 16);
        int chunkZ = Math.multiplyExact(order.chunk().z(), 16);
        bounds = new BuildVolume(Math.max(volume.minX(), chunkX), volume.minY(),
                Math.max(volume.minZ(), chunkZ), Math.min(volume.maxX(), Math.addExact(chunkX, 15)),
                top, Math.min(volume.maxZ(), Math.addExact(chunkZ, 15)));
        width = Math.addExact(Math.subtractExact(bounds.maxX(), bounds.minX()), 1);
        depth = Math.addExact(Math.subtractExact(bounds.maxZ(), bounds.minZ()), 1);
        size = Math.toIntExact(bounds.blockCount());
        Map<BlockPosition, BlockState> states = new HashMap<>();
        plan.chunk(order.chunkIndex()).expectedBlocks().forEach(target ->
                states.put(target.position(), target.state()));
        expected = Map.copyOf(states);
    }

    int size() { return size; }

    BlockPosition position(int cursor) {
        Objects.checkIndex(cursor, size);
        int plane = Math.multiplyExact(width, depth);
        int row = cursor % plane / depth;
        int offset = cursor % depth;
        int z = (row & 1) == 0 ? offset : depth - 1 - offset;
        return new BlockPosition(bounds.minX() + row,
                bounds.minY() + cursor / plane, bounds.minZ() + z);
    }

    boolean allows(BlockPosition position, BlockState actual) {
        return isStem(actual.blockId()) && allowsSourcePosition(position);
    }

    boolean allowsSourcePosition(BlockPosition position) {
        if (!bounds.contains(position)) { return false; }
        BlockState source = expected.getOrDefault(position, BlockState.AIR);
        return source.isAir() || source.blockId().equals("minecraft:wheat");
    }

    boolean matches(Target target, BlockState actual) {
        return target != null && allows(target.position(), actual) && target.matches(actual);
    }

    static ApproachAction approachAction(boolean targetReceived, boolean activeInteractionRoute) {
        if (targetReceived) { return ApproachAction.INSPECT_TARGET; }
        return activeInteractionRoute ? ApproachAction.FOLLOW_PLANNED_ROUTE
                : ApproachAction.RECEIVE_TARGET_CHUNK;
    }

    static String occupancyProblem(Stage stage, BooleanSupplier entityConflict) {
        Objects.requireNonNull(stage, "stage");
        Objects.requireNonNull(entityConflict, "entityConflict");
        // Approaching cannot mutate the target. Occupancy must be re-read at the actual attack pose.
        if (stage == Stage.APPROACH) { return ""; }
        return entityConflict.getAsBoolean()
                ? "An entity occupies or stands on the stem clearing target" : "";
    }

    static boolean isStem(String blockId) {
        if (blockId == null) { return false; }
        return switch (blockId) {
            case "minecraft:pumpkin_stem", "minecraft:melon_stem",
                    "minecraft:attached_pumpkin_stem", "minecraft:attached_melon_stem" -> true;
            default -> false;
        };
    }
}
