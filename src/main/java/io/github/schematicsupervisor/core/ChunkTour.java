package io.github.schematicsupervisor.core;

import java.util.Objects;

/**
 * The order in which every layer stage visits its chunks. Each step moves to an edge-adjacent chunk,
 * and the tour ends next to (or, when both sides are odd, diagonally beside) the chunk where it began,
 * so the next stage starts close by. Row-major order crossed the whole layout at the end of every row
 * and at every stage change.
 */
public final class ChunkTour {
    private ChunkTour() { }

    /** Chunk indexes ({@code x + z * columns}) in visiting order; every index appears once. */
    public static int[] order(ChunkLayout layout) {
        Objects.requireNonNull(layout, "layout");
        return order(layout.columns(), layout.rows());
    }

    static int[] order(int columns, int rows) {
        if (columns < 1 || rows < 1) {
            throw new IllegalArgumentException("chunk tour dimensions must be positive");
        }
        Path path = new Path(columns, rows);
        if (columns == 1 || rows == 1) {
            for (int index = 0; index < columns * rows; index++) { path.visit(index % columns, index / columns); }
        } else if (rows % 2 == 0) {
            // Along the first row, snake the other rows back over columns 1.., then return up column 0.
            for (int x = 0; x < columns; x++) { path.visit(x, 0); }
            for (int z = 1; z < rows; z++) {
                for (int step = 0; step < columns - 1; step++) {
                    path.visit(z % 2 == 1 ? columns - 1 - step : 1 + step, z);
                }
            }
            for (int z = rows - 1; z >= 1; z--) { path.visit(0, z); }
        } else if (columns % 2 == 0) {
            // The same loop turned sideways: down column 0, snake the columns, return along row 0.
            for (int z = 0; z < rows; z++) { path.visit(0, z); }
            for (int x = 1; x < columns; x++) {
                for (int step = 0; step < rows - 1; step++) {
                    path.visit(x, x % 2 == 1 ? rows - 1 - step : 1 + step);
                }
            }
            for (int x = columns - 1; x >= 1; x--) { path.visit(x, 0); }
        } else {
            // Both sides odd: no closed loop exists. Snake columns from the far side toward column 2,
            // then zigzag the two-column strip back up, ending diagonally beside the start.
            for (int x = 0; x < columns; x++) { path.visit(x, 0); }
            for (int x = columns - 1, turn = 0; x >= 2; x--, turn++) {
                for (int step = 0; step < rows - 1; step++) {
                    path.visit(x, turn % 2 == 0 ? 1 + step : rows - 1 - step);
                }
            }
            for (int z = rows - 1, turn = 0; z >= 1; z--, turn++) {
                path.visit(turn % 2 == 0 ? 1 : 0, z);
                path.visit(turn % 2 == 0 ? 0 : 1, z);
            }
        }
        return path.finish();
    }

    private static final class Path {
        private final int columns;
        private final int[] order;
        private final boolean[] seen;
        private int size;

        private Path(int columns, int rows) {
            this.columns = columns;
            order = new int[Math.multiplyExact(columns, rows)];
            seen = new boolean[order.length];
        }

        private void visit(int x, int z) {
            int index = Math.addExact(x, Math.multiplyExact(z, columns));
            if (seen[index]) { throw new IllegalStateException("chunk tour revisited chunk " + index); }
            seen[index] = true;
            order[size++] = index;
        }

        private int[] finish() {
            if (size != order.length) { throw new IllegalStateException("chunk tour missed a chunk"); }
            return order;
        }
    }
}
