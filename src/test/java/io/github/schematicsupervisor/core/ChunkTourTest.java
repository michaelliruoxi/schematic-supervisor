package io.github.schematicsupervisor.core;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ChunkTourTest {
    @Test
    void farmTourCoversTheFirstRowSnakesTheColumnsAndEndsBesideItsStart() {
        int[] expected = {
                0, 1, 2, 3, 4, 5, 6,
                13, 20, 27, 34, 41, 48,
                47, 40, 33, 26, 19, 12,
                11, 18, 25, 32, 39, 46,
                45, 38, 31, 24, 17, 10,
                9, 16, 23, 30, 37, 44,
                43, 42, 35, 36, 29, 28, 21, 22, 15, 14, 7, 8};
        assertArrayEquals(expected, ChunkTour.order(new ChunkLayout(new ChunkCoordinate(513, -1686), 7, 7)));
    }

    @Test
    void everyLayoutVisitsEachChunkOnceWithEdgeAdjacentStepsAndEndsNearItsStart() {
        for (int columns = 1; columns <= 9; columns++) {
            for (int rows = 1; rows <= 9; rows++) {
                int[] tour = ChunkTour.order(columns, rows);
                assertEquals(columns * rows, tour.length);
                boolean[] seen = new boolean[tour.length];
                for (int index : tour) {
                    assertTrue(!seen[index], "revisited " + index);
                    seen[index] = true;
                }
                assertEquals(0, tour[0], "every stage starts at the layout's first chunk");
                for (int step = 1; step < tour.length; step++) {
                    int dx = Math.abs(tour[step] % columns - tour[step - 1] % columns);
                    int dz = Math.abs(tour[step] / columns - tour[step - 1] / columns);
                    assertEquals(1, dx + dz, columns + "x" + rows + " step " + step);
                }
                if (columns > 1 && rows > 1) {
                    int last = tour[tour.length - 1];
                    int reach = Math.max(last % columns, last / columns);
                    assertEquals(1, reach, columns + "x" + rows + " ends " + last);
                    if (columns % 2 == 0 || rows % 2 == 0) {
                        assertEquals(1, last % columns + last / columns, "an even side closes the loop");
                    }
                }
            }
        }
    }

    @Test
    void singleRowsAndColumnsRunStraightAndInvalidSizesAreRejected() {
        assertArrayEquals(new int[]{0, 1, 2, 3}, ChunkTour.order(4, 1));
        assertArrayEquals(new int[]{0, 1, 2}, ChunkTour.order(1, 3));
        assertArrayEquals(new int[]{0}, ChunkTour.order(1, 1));
        assertThrows(IllegalArgumentException.class, () -> ChunkTour.order(0, 3));
        assertThrows(IllegalArgumentException.class, () -> ChunkTour.order(3, -1));
    }
}
