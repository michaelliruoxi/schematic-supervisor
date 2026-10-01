package io.github.schematicsupervisor.fabric;

import io.github.schematicsupervisor.core.BlockObservation;
import io.github.schematicsupervisor.core.BlockPosition;
import io.github.schematicsupervisor.core.BlockState;
import io.github.schematicsupervisor.core.BuildPhase;
import io.github.schematicsupervisor.core.ChunkCoordinate;
import io.github.schematicsupervisor.core.OrdinaryPlacement;
import io.github.schematicsupervisor.core.SchematicPlan;
import io.github.schematicsupervisor.core.WorkOrder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Plans exactly two temporary dirt cells below one seed in the active ordinary slice. */
final class TemporarySupportPlanner {
    private TemporarySupportPlanner() { }

    static Optional<Column> find(SchematicPlan plan, WorkOrder.OrdinaryBlocks order,
                                 BlockObservation receivedWorld) {
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(order, "order");
        Objects.requireNonNull(receivedWorld, "receivedWorld");
        if (!plan.chunk(order.chunkIndex()).chunk().equals(order.chunk())) {
            throw new IllegalArgumentException("support slice chunk does not match plan");
        }
        List<OrdinaryPlacement> seeds = order.placements().stream()
                .sorted(java.util.Comparator.comparing(OrdinaryPlacement::position)).toList();
        int layerY = seeds.getFirst().position().y();
        if (seeds.stream().anyMatch(seed -> seed.position().y() != layerY)) {
            throw new IllegalArgumentException("support planning requires one horizontal slice");
        }
        Map<BlockPosition, BlockState> expected = new HashMap<>();
        Map<BlockPosition, OrdinaryPlacement> plannedPlacements = new HashMap<>();
        plan.chunk(order.chunkIndex()).ordinaryPlacements().forEach(placement ->
                plannedPlacements.put(placement.position(), placement));
        plan.chunk(order.chunkIndex()).expectedBlocks().forEach(target ->
                expected.put(target.position(), target.state()));
        for (OrdinaryPlacement seed : seeds) {
            BlockPosition target = seed.position();
            if (!ChunkCoordinate.containing(target).equals(order.chunk())
                    || !plan.buildVolume().contains(target)
                    || !seed.equals(plannedPlacements.get(target))
                    || !matchesStructuralState(expected.get(target), seed.state())) {
                continue;
            }
            if (target.y() < Integer.MIN_VALUE + 3) { continue; }
            BlockPosition anchor = new BlockPosition(target.x(), target.y() - 3, target.z());
            BlockPosition bottom = new BlockPosition(target.x(), target.y() - 2, target.z());
            BlockPosition top = new BlockPosition(target.x(), target.y() - 1, target.z());
            if (!plan.buildVolume().contains(anchor)
                    || !BlockState.AIR.equals(expected.getOrDefault(bottom, BlockState.AIR))
                    || !BlockState.AIR.equals(expected.getOrDefault(top, BlockState.AIR))) {
                continue;
            }
            if (!receivedWorld.isChunkLoaded(order.chunk())) { continue; }
            if (!BlockState.AIR.equals(receivedWorld.blockState(target))
                    || !BlockState.AIR.equals(receivedWorld.blockState(bottom))
                    || !BlockState.AIR.equals(receivedWorld.blockState(top))) {
                continue;
            }
            BlockState anchorState = receivedWorld.blockState(anchor);
            if (!matchesStructuralState(expected.get(anchor), anchorState)) { continue; }
            return Optional.of(new Column(plan.planId(), sliceId(order), order.chunkIndex(),
                    layerY, seed, anchor, List.of(bottom, top)));
        }
        return Optional.empty();
    }

    static String sliceId(WorkOrder.OrdinaryBlocks order) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update((BuildPhase.ORDINARY_BLOCKS + ":" + order.chunkIndex() + ":"
                    + order.chunk().x() + ":" + order.chunk().z() + "\n").getBytes(StandardCharsets.UTF_8));
            order.placements().stream().sorted(java.util.Comparator.comparing(OrdinaryPlacement::position))
                    .forEach(placement -> digest.update((placement.position() + ":" + placement.state()
                            + ":" + placement.material() + "\n").getBytes(StandardCharsets.UTF_8)));
            return "sha256:" + HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static boolean matchesStructuralState(BlockState planned, BlockState actual) {
        if (planned == null || actual == null || !actual.properties().isEmpty()) { return false; }
        return switch (actual.blockId()) {
            case "minecraft:dirt" -> planned.blockId().equals("minecraft:dirt")
                    || planned.blockId().equals("minecraft:farmland");
            case "minecraft:birch_planks", "minecraft:glowstone" -> planned.equals(actual);
            default -> false;
        };
    }

    record Column(String planId, String sliceId, int chunkIndex, int layerY,
                  OrdinaryPlacement seed, BlockPosition anchor, List<BlockPosition> supports) {
        Column {
            Objects.requireNonNull(planId, "planId");
            Objects.requireNonNull(sliceId, "sliceId");
            Objects.requireNonNull(seed, "seed");
            Objects.requireNonNull(anchor, "anchor");
            supports = List.copyOf(supports);
            if (planId.isBlank() || planId.length() > 256 || !sliceId.matches("sha256:[0-9a-f]{64}")
                    || chunkIndex < 0
                    || seed.position().y() != layerY || supports.size() != 2) {
                throw new IllegalArgumentException("invalid support column binding");
            }
            if (layerY < Integer.MIN_VALUE + 3
                    || !anchor.equals(new BlockPosition(seed.position().x(), layerY - 3, seed.position().z()))
                    || !supports.get(0).equals(new BlockPosition(seed.position().x(), layerY - 2, seed.position().z()))
                    || !supports.get(1).equals(new BlockPosition(seed.position().x(), layerY - 1, seed.position().z()))) {
                throw new IllegalArgumentException("supports must be exactly two vertical cells below the seed");
            }
        }
    }
}
