package io.github.schematicsupervisor.core;

import java.util.List;
import java.util.Objects;

/**
 * Deterministic work orders. These are created by the supervisor, never by an advisor.
 */
public sealed interface WorkOrder
        permits WorkOrder.OrdinaryBlocks, WorkOrder.Till, WorkOrder.Plant {
    int chunkIndex();

    ChunkCoordinate chunk();

    BuildPhase phase();

    record OrdinaryBlocks(
            int chunkIndex,
            ChunkCoordinate chunk,
            List<OrdinaryPlacement> placements
    ) implements WorkOrder {
        public OrdinaryBlocks {
            requireIndex(chunkIndex);
            Objects.requireNonNull(chunk, "chunk");
            placements = List.copyOf(Objects.requireNonNull(placements, "placements"));
            if (placements.isEmpty()) {
                throw new IllegalArgumentException("ordinary work order must not be empty");
            }
        }

        @Override
        public BuildPhase phase() {
            return BuildPhase.ORDINARY_BLOCKS;
        }
    }

    record Till(
            int chunkIndex,
            ChunkCoordinate chunk,
            List<BlockPosition> targets
    ) implements WorkOrder {
        public Till {
            requireIndex(chunkIndex);
            Objects.requireNonNull(chunk, "chunk");
            targets = List.copyOf(Objects.requireNonNull(targets, "targets"));
            if (targets.isEmpty()) {
                throw new IllegalArgumentException("till work order must not be empty");
            }
        }

        @Override
        public BuildPhase phase() {
            return BuildPhase.TILL;
        }
    }

    record Plant(
            int chunkIndex,
            ChunkCoordinate chunk,
            List<BlockPosition> targets
    ) implements WorkOrder {
        public Plant {
            requireIndex(chunkIndex);
            Objects.requireNonNull(chunk, "chunk");
            targets = List.copyOf(Objects.requireNonNull(targets, "targets"));
            if (targets.isEmpty()) {
                throw new IllegalArgumentException("plant work order must not be empty");
            }
        }

        @Override
        public BuildPhase phase() {
            return BuildPhase.PLANT;
        }
    }

    private static void requireIndex(int chunkIndex) {
        if (chunkIndex < 0) {
            throw new IllegalArgumentException("chunk index must be non-negative and bound to its plan");
        }
    }
}
