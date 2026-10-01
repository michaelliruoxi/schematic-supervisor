package io.github.schematicsupervisor.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class BlockPositionTest {
    @Test
    void denseFarmCoordinatesAvoidPathologicalImmutableSetProbeClusters() {
        // Set.copyOf uses a table twice the element count. Bound its probe work directly,
        // so a regression fails promptly instead of freezing the test on a huge set copy.
        int targetCount = 112 * 112 * 25;
        for (int origin : new int[] {0, -40_000, 30_000_000 - 112}) {
            boolean[] occupied = new boolean[targetCount * 2];
            long probes = 0;
            long maximumProbes = targetCount * 4L;
            for (int floor = 0; floor < 25; floor++) {
                for (int x = 0; x < 112; x++) {
                    for (int z = 0; z < 112; z++) {
                        BlockPosition position = new BlockPosition(origin + x, -64 + floor * 3,
                                origin + z);
                        int slot = Math.floorMod(position.hashCode(), occupied.length);
                        while (occupied[slot]) {
                            probes++;
                            assertTrue(probes < maximumProbes,
                                    "Dense coordinate hashing must have bounded linear-probe work");
                            slot = (slot + 1) % occupied.length;
                        }
                        occupied[slot] = true;
                        probes++;
                    }
                }
            }
            assertTrue(probes < maximumProbes);
        }
    }

    @Test
    void mixedHashPreservesCoordinateEqualityAndSortOrder() {
        BlockPosition first = new BlockPosition(-5, 7, 20);
        BlockPosition same = new BlockPosition(-5, 7, 20);
        assertEquals(first, same);
        assertEquals(first.hashCode(), same.hashCode());
        assertEquals(0, first.compareTo(same));
        assertTrue(first.compareTo(new BlockPosition(-6, 8, 20)) < 0);
        assertTrue(first.compareTo(new BlockPosition(-4, 7, 19)) < 0);
        assertTrue(first.compareTo(new BlockPosition(-5, 7, 21)) < 0);
    }
}
