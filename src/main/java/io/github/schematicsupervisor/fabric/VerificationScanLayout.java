package io.github.schematicsupervisor.fabric;

import io.github.schematicsupervisor.core.BuildVolume;
import io.github.schematicsupervisor.core.ChunkCoordinate;
import io.github.schematicsupervisor.core.ChunkPlan;
import io.github.schematicsupervisor.core.SchematicPlan;
import io.github.schematicsupervisor.core.VerificationScope;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

final class VerificationScanLayout {
    private VerificationScanLayout() {
    }

    static List<ChunkScan> forPlan(SchematicPlan plan, VerificationScope scope) {
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(scope, "scope");
        List<ChunkPlan> requested = scope.kind() == VerificationScope.Kind.FULL_PLAN
                ? plan.chunks()
                : List.of(plan.chunk(scope.chunkIndex()));
        ArrayList<ChunkScan> result = new ArrayList<>(requested.size());
        for (ChunkPlan chunk : requested) {
            result.add(intersection(plan.buildVolume(), chunk.chunk()));
        }
        return List.copyOf(result);
    }

    private static ChunkScan intersection(BuildVolume volume, ChunkCoordinate chunk) {
        int chunkX = Math.multiplyExact(chunk.x(), 16);
        int chunkZ = Math.multiplyExact(chunk.z(), 16);
        int minX = Math.max(volume.minX(), chunkX);
        int maxX = Math.min(volume.maxX(), Math.addExact(chunkX, 15));
        int minZ = Math.max(volume.minZ(), chunkZ);
        int maxZ = Math.min(volume.maxZ(), Math.addExact(chunkZ, 15));
        return new ChunkScan(
                chunk,
                minX,
                volume.minY(),
                minZ,
                maxX,
                volume.maxY(),
                maxZ
        );
    }

    record ChunkScan(
            ChunkCoordinate chunk,
            int minX,
            int minY,
            int minZ,
            int maxX,
            int maxY,
            int maxZ
    ) {
        ChunkScan {
            Objects.requireNonNull(chunk, "chunk");
            if (minY > maxY) {
                throw new IllegalArgumentException("minimum Y must not exceed maximum Y");
            }
        }

        boolean intersectsVolume() {
            return minX <= maxX && minZ <= maxZ;
        }

        long blockCount() {
            if (!intersectsVolume()) {
                return 0;
            }
            return Math.multiplyExact(
                    Math.multiplyExact((long) maxX - minX + 1, (long) maxY - minY + 1),
                    (long) maxZ - minZ + 1
            );
        }
    }
}
