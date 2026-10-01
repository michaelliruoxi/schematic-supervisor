package io.github.schematicsupervisor.fabric;

import io.github.schematicsupervisor.core.BlockPosition;

/**
 * Test model of the bundled wheat farm's solid blocks, placed with its minimum X/Z corner at a
 * chunk-aligned origin: 112 × 112 blocks, a dirt plane every three blocks from Y -63 to Y 9, a
 * glowstone plane two blocks above each dirt plane in every eighth column, a birch roof at Y 12, and a
 * row of depot chests (Y 13 to 16) on the roof two blocks inside its north edge. Positions outside
 * {@link #loadedArea} count as unreceived, so routes cannot use them.
 */
final class WheatFarmModel {
    static final int SIZE = 112;
    static final int BOTTOM_Y = -63;
    static final int TOP_PLANE_Y = 9;
    static final int ROOF_Y = 12;
    private static final int MARGIN = 44;

    private final int originX;
    private final int originZ;

    WheatFarmModel(int originX, int originZ) {
        if (Math.floorMod(originX, 16) != 0 || Math.floorMod(originZ, 16) != 0) {
            throw new IllegalArgumentException("the farm's corner is chunk-aligned");
        }
        this.originX = originX;
        this.originZ = originZ;
    }

    /** A position relative to the farm's minimum corner. */
    BlockPosition at(int dx, int y, int dz) {
        return new BlockPosition(originX + dx, y, originZ + dz);
    }

    boolean loadedArea(BlockPosition point) {
        return point.x() >= originX - MARGIN && point.x() <= originX + SIZE + MARGIN
                && point.z() >= originZ - MARGIN && point.z() <= originZ + SIZE + MARGIN
                && point.y() >= -64 && point.y() <= 60;
    }

    boolean solid(BlockPosition point) {
        int dx = point.x() - originX;
        int dz = point.z() - originZ;
        boolean insideFarm = dx >= 0 && dx < SIZE && dz >= 0 && dz < SIZE;
        if (insideFarm) {
            int layer = Math.floorMod(point.y() - BOTTOM_Y, 3);
            if (point.y() <= TOP_PLANE_Y && layer == 0 || point.y() == ROOF_Y) { return true; }
            boolean glowstoneColumn = Math.floorMod(dx, 8) == 2 && Math.floorMod(dz, 8) == 2;
            if (point.y() <= ROOF_Y - 1 && layer == 2 && glowstoneColumn) { return true; }
        }
        return dz == 2 && dx >= 42 && dx <= 54 && point.y() >= ROOF_Y + 1 && point.y() <= ROOF_Y + 4;
    }

    /** Received air the player's feet may occupy: both body cells clear. */
    boolean clearForBody(BlockPosition feet) {
        BlockPosition head = new BlockPosition(feet.x(), feet.y() + 1, feet.z());
        return loadedArea(feet) && loadedArea(head) && !solid(feet) && !solid(head);
    }
}
