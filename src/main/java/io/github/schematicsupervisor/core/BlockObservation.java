package io.github.schematicsupervisor.core;

import java.util.List;

/**
 * Read-only world access used by the dependency-free verifier.
 */
public interface BlockObservation {
    boolean isChunkLoaded(ChunkCoordinate chunk);

    BlockState blockState(BlockPosition position);

    default List<BlockPosition> temporaryScaffolding(VerificationScope scope) {
        return List.of();
    }
}
