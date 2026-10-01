package io.github.schematicsupervisor.fabric;

import io.github.schematicsupervisor.core.DepotId;
import io.github.schematicsupervisor.core.DepotStock;
import io.github.schematicsupervisor.core.MaterialQuantities;
import java.util.Objects;

/**
 * Persisted identity plus session-local observations for one operator-approved chest.
 */
record RegisteredDepot(
        DepotId id,
        String worldIdentityHash,
        String dimension,
        int x,
        int y,
        int z,
        boolean scanned,
        MaterialQuantities cachedStock,
        String lastError
) {
    RegisteredDepot {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(cachedStock, "cachedStock");
        new RunContext(worldIdentityHash, dimension);
        lastError = lastError == null ? "" : lastError;
        if (!scanned && !cachedStock.isEmpty()) {
            throw new IllegalArgumentException("unscanned depot cannot expose cached stock");
        }
    }

    static RegisteredDepot unscanned(
            DepotId id,
            String worldIdentityHash,
            String dimension,
            int x,
            int y,
            int z
    ) {
        return new RegisteredDepot(
                id,
                worldIdentityHash,
                dimension,
                x,
                y,
                z,
                false,
                MaterialQuantities.empty(),
                ""
        );
    }

    RegisteredDepot withScan(MaterialQuantities stock) {
        return new RegisteredDepot(
                id,
                worldIdentityHash,
                dimension,
                x,
                y,
                z,
                true,
                stock,
                ""
        );
    }

    RegisteredDepot markUnscanned() {
        return unscanned(id, worldIdentityHash, dimension, x, y, z);
    }

    RegisteredDepot withError(String detail) {
        return new RegisteredDepot(
                id,
                worldIdentityHash,
                dimension,
                x,
                y,
                z,
                false,
                MaterialQuantities.empty(),
                Objects.requireNonNull(detail, "detail")
        );
    }

    DepotStock toStock() {
        if (!scanned) {
            throw new IllegalStateException("depot has not been scanned in this session");
        }
        return new DepotStock(id, cachedStock);
    }

    boolean matches(RunContext context) {
        return worldIdentityHash.equals(context.worldIdentityHash())
                && dimension.equals(context.dimension());
    }

    boolean sameLocation(
            RunContext context,
            int candidateX,
            int candidateY,
            int candidateZ
    ) {
        return matches(context)
                && x == candidateX
                && y == candidateY
                && z == candidateZ;
    }
}
