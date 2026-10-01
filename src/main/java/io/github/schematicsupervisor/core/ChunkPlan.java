package io.github.schematicsupervisor.core;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public record ChunkPlan(
        ChunkCoordinate chunk,
        List<TargetBlock> expectedBlocks,
        List<OrdinaryPlacement> ordinaryPlacements,
        List<BlockPosition> tillTargets,
        List<BlockPosition> plantTargets
) {
    public ChunkPlan {
        Objects.requireNonNull(chunk, "chunk");
        expectedBlocks = List.copyOf(Objects.requireNonNull(expectedBlocks, "expectedBlocks"));
        ordinaryPlacements = List.copyOf(Objects.requireNonNull(ordinaryPlacements, "ordinaryPlacements"));
        tillTargets = List.copyOf(Objects.requireNonNull(tillTargets, "tillTargets"));
        plantTargets = List.copyOf(Objects.requireNonNull(plantTargets, "plantTargets"));

        Set<BlockPosition> expectedPositions = new HashSet<>();
        for (TargetBlock target : expectedBlocks) {
            requireInChunk(chunk, target.position(), "expected block");
            if (!expectedPositions.add(target.position())) {
                throw new IllegalArgumentException("duplicate expected position " + target.position());
            }
        }
        for (OrdinaryPlacement placement : ordinaryPlacements) {
            requireInChunk(chunk, placement.position(), "ordinary placement");
        }
        for (BlockPosition target : tillTargets) {
            requireInChunk(chunk, target, "till target");
        }
        for (BlockPosition target : plantTargets) {
            requireInChunk(chunk, target, "plant target");
        }
    }

    public MaterialQuantities ordinaryMaterials() {
        return countPlacementMaterials(ordinaryPlacements);
    }

    public MaterialQuantities plantMaterials() {
        return MaterialQuantities.of(Material.WHEAT_SEEDS, plantTargets.size());
    }

    public MaterialQuantities plannedConsumables() {
        return ordinaryMaterials().plus(plantMaterials());
    }

    public boolean hasWork(BuildPhase phase) {
        return switch (phase) {
            case ORDINARY_BLOCKS -> !ordinaryPlacements.isEmpty();
            case TILL -> !tillTargets.isEmpty();
            case PLANT -> !plantTargets.isEmpty();
            case VERIFY -> true;
        };
    }

    private static MaterialQuantities countPlacementMaterials(List<OrdinaryPlacement> placements) {
        java.util.TreeMap<Material, Long> counts = new java.util.TreeMap<>();
        for (OrdinaryPlacement placement : placements) {
            counts.merge(placement.material(), 1L, Math::addExact);
        }
        return MaterialQuantities.of(counts);
    }

    private static void requireInChunk(ChunkCoordinate chunk, BlockPosition position, String description) {
        if (!chunk.contains(position)) {
            throw new IllegalArgumentException(description + " " + position + " is outside " + chunk);
        }
    }
}
