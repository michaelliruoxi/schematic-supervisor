package io.github.schematicsupervisor.fabric;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.schematicsupervisor.core.BlockPosition;
import io.github.schematicsupervisor.core.BlockState;
import io.github.schematicsupervisor.core.BuildVolume;
import io.github.schematicsupervisor.core.ChunkCoordinate;
import io.github.schematicsupervisor.core.ChunkPlan;
import io.github.schematicsupervisor.core.SchematicPlan;
import io.github.schematicsupervisor.core.SchematicCompiler;
import io.github.schematicsupervisor.core.TargetBlock;
import io.github.schematicsupervisor.core.VerificationScope;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

final class VerificationScanLayoutTest {
    @Test
    void fullLayoutCoversTheBoundedVolumeExactlyOnce() {
        SchematicPlan plan = plan(new BuildVolume(3, 40, 4, 111, 41, 111));

        List<VerificationScanLayout.ChunkScan> scans =
                VerificationScanLayout.forPlan(plan, VerificationScope.fullPlan());

        assertEquals(SchematicPlan.CHUNK_COUNT, scans.size());
        assertEquals(plan.buildVolume().blockCount(),
                scans.stream().mapToLong(VerificationScanLayout.ChunkScan::blockCount).sum());
        assertEquals(new ChunkCoordinate(0, 0), scans.getFirst().chunk());
        assertEquals(new ChunkCoordinate(6, 6), scans.getLast().chunk());
        assertEquals(3, scans.getFirst().minX());
        assertEquals(15, scans.getFirst().maxX());
        assertEquals(96, scans.getLast().minX());
        assertEquals(111, scans.getLast().maxX());
    }

    @Test
    void chunkScopeContainsOnlyTheRequestedChunkIntersection() {
        SchematicPlan plan = plan(new BuildVolume(3, 40, 4, 111, 41, 111));

        VerificationScanLayout.ChunkScan scan =
                VerificationScanLayout.forPlan(plan, VerificationScope.chunk(8)).getFirst();

        assertEquals(new ChunkCoordinate(1, 1), scan.chunk());
        assertTrue(scan.intersectsVolume());
        assertEquals(16L * 2L * 16L, scan.blockCount());
    }

    @Test
    void layoutRetainsRequestedChunksThatDoNotIntersectTheBuildVolume() {
        SchematicPlan plan = plan(new BuildVolume(0, 40, 0, 15, 40, 15));

        VerificationScanLayout.ChunkScan scan =
                VerificationScanLayout.forPlan(plan, VerificationScope.chunk(48)).getFirst();

        assertFalse(scan.intersectsVolume());
        assertEquals(0, scan.blockCount());
    }

    @Test
    void unalignedRectangularLayoutCoversEveryVolumeCellExactlyOnce() {
        SchematicPlan plan = SchematicCompiler.compile(new BuildVolume(-17, 4, -1, 128, 5, 97), List.of());
        List<VerificationScanLayout.ChunkScan> scans = VerificationScanLayout.forPlan(plan, VerificationScope.fullPlan());
        assertEquals(88, plan.chunkCount());
        assertEquals(plan.chunkCount(), scans.size());
        java.util.Set<BlockPosition> seen = new java.util.HashSet<>();
        for (VerificationScanLayout.ChunkScan scan : scans) {
            assertTrue(scan.intersectsVolume());
            for (int x = scan.minX(); x <= scan.maxX(); x++) {
                for (int y = scan.minY(); y <= scan.maxY(); y++) {
                    for (int z = scan.minZ(); z <= scan.maxZ(); z++) {
                        assertTrue(seen.add(new BlockPosition(x, y, z)));
                    }
                }
            }
        }
        assertEquals(plan.buildVolume().blockCount(), seen.size());
        assertEquals(1, VerificationScanLayout.forPlan(plan, VerificationScope.chunk(87)).size());
        org.junit.jupiter.api.Assertions.assertThrows(IndexOutOfBoundsException.class,
                () -> VerificationScanLayout.forPlan(plan, VerificationScope.chunk(88)));
    }

    private static SchematicPlan plan(BuildVolume volume) {
        ArrayList<ChunkPlan> chunks = new ArrayList<>();
        for (int z = 0; z < SchematicPlan.GRID_SIZE; z++) {
            for (int x = 0; x < SchematicPlan.GRID_SIZE; x++) {
                ChunkCoordinate coordinate = new ChunkCoordinate(x, z);
                List<TargetBlock> targets = coordinate.equals(new ChunkCoordinate(0, 0))
                        ? List.of(new TargetBlock(
                                new BlockPosition(volume.minX(), volume.minY(), volume.minZ()),
                                new BlockState("minecraft:dirt")
                        ))
                        : List.of();
                chunks.add(new ChunkPlan(coordinate, targets, List.of(), List.of(), List.of()));
            }
        }
        return new SchematicPlan("layout-test", new ChunkCoordinate(0, 0), volume, chunks);
    }
}
