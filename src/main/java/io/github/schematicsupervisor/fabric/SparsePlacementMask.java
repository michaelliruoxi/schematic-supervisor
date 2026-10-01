package io.github.schematicsupervisor.fabric;

import io.github.schematicsupervisor.core.BlockPosition;
import io.github.schematicsupervisor.core.Material;
import io.github.schematicsupervisor.core.OrdinaryPlacement;
import io.github.schematicsupervisor.core.WorkOrder;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Local coordinates for only the unconfirmed placements in one deterministic work order.
 */
final class SparsePlacementMask {
    private final int originX;
    private final int originY;
    private final int originZ;
    private final int height;
    private final Map<Long, Material> desired;

    SparsePlacementMask(
            WorkOrder.OrdinaryBlocks order,
            List<OrdinaryPlacement> remainingPlacements,
            int minimumAllowedY,
            int maximumAllowedY
    ) {
        Objects.requireNonNull(order, "order");
        Objects.requireNonNull(remainingPlacements, "remainingPlacements");
        if (remainingPlacements.isEmpty()) {
            throw new IllegalArgumentException("remaining placements must not be empty");
        }
        if (minimumAllowedY > maximumAllowedY) {
            throw new IllegalArgumentException("allowed height range is inverted");
        }
        originX = Math.multiplyExact(order.chunk().x(), 16);
        originZ = Math.multiplyExact(order.chunk().z(), 16);
        Set<OrdinaryPlacement> allowed = new HashSet<>(order.placements());
        int minimumY = Integer.MAX_VALUE;
        int maximumY = Integer.MIN_VALUE;
        for (OrdinaryPlacement placement : remainingPlacements) {
            Objects.requireNonNull(placement, "placement");
            BlockPosition position = placement.position();
            if (!allowed.contains(placement)) {
                throw new IllegalArgumentException("placement is outside the current work order");
            }
            if (Math.floorDiv(position.x(), 16) != order.chunk().x()
                    || Math.floorDiv(position.z(), 16) != order.chunk().z()) {
                throw new IllegalArgumentException("placement is outside the current chunk");
            }
            if (position.y() < minimumAllowedY || position.y() > maximumAllowedY) {
                throw new IllegalArgumentException("placement is outside the build height range");
            }
            minimumY = Math.min(minimumY, position.y());
            maximumY = Math.max(maximumY, position.y());
        }
        originY = minimumY;
        height = Math.addExact(Math.subtractExact(maximumY, minimumY), 1);
        desired = new HashMap<>(remainingPlacements.size() * 2);
        for (OrdinaryPlacement placement : remainingPlacements) {
            BlockPosition position = placement.position();
            long key = key(position.x() - originX, position.y() - originY, position.z() - originZ);
            if (desired.putIfAbsent(key, placement.material()) != null) {
                throw new IllegalArgumentException("duplicate remaining placement position");
            }
        }
    }

    int originX() {
        return originX;
    }

    int originY() {
        return originY;
    }

    int originZ() {
        return originZ;
    }

    int widthX() {
        return 16;
    }

    int heightY() {
        return height;
    }

    int lengthZ() {
        return 16;
    }

    Material materialAt(int x, int y, int z) {
        if (x < 0 || x >= widthX() || y < 0 || y >= heightY() || z < 0 || z >= lengthZ()) {
            return null;
        }
        return desired.get(key(x, y, z));
    }

    private static long key(int x, int y, int z) {
        return ((long) y << 8) | ((long) x << 4) | z;
    }
}
