package io.github.schematicsupervisor.fabric;

import io.github.schematicsupervisor.core.BlockPosition;
import io.github.schematicsupervisor.core.BuildVolume;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.function.Predicate;

/** One approved plain pickup stack, with explicit admission and exact inventory receipts; no build credit. */
final class SurplusDisposalPolicy {
    static final long MAXIMUM_PROOF_AGE_NANOS = 300_000_000_000L;
    static final int PRISM_RADIUS = 18;
    static final int MAXIMUM_HEIGHT = 90;
    static final int SCAN_BUDGET = 1024;
    private SurplusDisposalPolicy() { }

    record Site(BlockPosition feet, int bottomY) {
        Site {
            if (feet == null || Math.abs((long) feet.x()) > 29_999_950
                    || Math.abs((long) feet.z()) > 29_999_950
                    || bottomY < -4096 || bottomY > 4096
                    || feet.y() - bottomY < 12 || feet.y() - bottomY > MAXIMUM_HEIGHT) {
                throw new IllegalArgumentException("Disposal requires a bounded flight site above the world bottom");
            }
        }
        int cells() { return 37 * 37 * (feet.y() + 7 - bottomY); }
        BlockPosition cell(int index) {
            java.util.Objects.checkIndex(index, cells());
            return new BlockPosition(feet.x() - PRISM_RADIUS + index % 37,
                    bottomY + index / (37 * 37), feet.z() - PRISM_RADIUS + index / 37 % 37);
        }
        boolean clear(Predicate<BlockPosition> receivedUnpredictedAir) {
            for (int index = 0; index < cells(); index++) {
                if (!receivedUnpredictedAir.test(cell(index))) { return false; }
            }
            return true;
        }
    }

    static boolean fresh(SurplusStorageExhaustion proof, long nowNanos) {
        return proof != null && nowNanos - proof.capturedAtNanos() >= 0
                && nowNanos - proof.oldestObservedAtNanos() >= 0
                && nowNanos - proof.oldestObservedAtNanos() <= MAXIMUM_PROOF_AGE_NANOS;
    }

    static OptionalInt source(SurplusStorageExhaustion proof, boolean enabled, long nowNanos) {
        if (!enabled || !fresh(proof, nowNanos)) { return OptionalInt.empty(); }
        for (int index = 0; index < 36; index++) {
            var slot = proof.finalInventory().slots().main().get(index);
            if (slot.canTake() && slot.stack().plainPickup()
                    && SurplusPickupPolicy.allowed(slot.stack().itemId())) { return OptionalInt.of(index); }
        }
        return OptionalInt.empty();
    }

    static boolean freshAuthorization(SurplusDisposalAuthorization proof, long nowNanos) {
        return proof != null && nowNanos - proof.capturedAtNanos() >= 0
                && nowNanos - proof.oldestObservedAtNanos() >= 0
                && nowNanos - proof.oldestObservedAtNanos() <= MAXIMUM_PROOF_AGE_NANOS;
    }

    static OptionalInt sourceForAuthorization(SurplusDisposalAuthorization proof, boolean enabled, long nowNanos) {
        if (!enabled || !freshAuthorization(proof, nowNanos)) { return OptionalInt.empty(); }
        int selected = -1, largest = 0;
        for (int index = 0; index < 36; index++) {
            var slot = proof.finalInventory().slots().main().get(index);
            if (!slot.canTake() || !slot.stack().plainPickup() || !SurplusPickupPolicy.allowed(slot.stack().itemId())) { continue; }
            if (proof.mode() == SurplusDisposalAuthorization.Mode.STORAGE_FULL) { return OptionalInt.of(index); }
            if (slot.stack().count() > largest) { selected = index; largest = slot.stack().count(); }
        }
        return selected < 0 ? OptionalInt.empty() : OptionalInt.of(selected);
    }

    static boolean separated(Site site, BuildVolume build, List<MossDepositJournal.Context> depots) {
        int x = site.feet().x(), z = site.feet().z();
        if (!(x + 24 < build.minX() || x - 24 > build.maxX()
                || z + 24 < build.minZ() || z - 24 > build.maxZ())) { return false; }
        for (var depot : depots) {
            long dx = (long) x - depot.depotX(), dz = (long) z - depot.depotZ();
            if (dx * dx + dz * dz < 40L * 40) { return false; }
        }
        return true;
    }

    /** Pending receipt travel cannot descend into the item's entire conservative falling corridor. */
    static boolean permitsTravel(Site site, net.minecraft.util.math.Box body) {
        if (site == null || body == null) { return false; }
        int x = site.feet().x(), z = site.feet().z();
        boolean overlaps = body.maxX > x - PRISM_RADIUS && body.minX < x + PRISM_RADIUS + 1
                && body.maxZ > z - PRISM_RADIUS && body.minZ < z + PRISM_RADIUS + 1;
        return !overlaps || body.minY >= site.feet().y() - 0.3;
    }

    static List<Site> candidates(BuildVolume build, List<MossDepositJournal.Context> depots,
                                 BlockPosition current, int bottomY, int topY) {
        int y = Math.max(bottomY + 12, current.y());
        if (y - bottomY > MAXIMUM_HEIGHT || y + 6 > topY) { return List.of(); }
        int x = Math.max(build.minX(), Math.min(build.maxX(), current.x()));
        int z = Math.max(build.minZ(), Math.min(build.maxZ(), current.z()));
        List<Site> result = new ArrayList<>();
        for (int offset : List.of(40, 64)) {
            for (BlockPosition feet : List.of(new BlockPosition(build.minX() - offset, y, z),
                    new BlockPosition(build.maxX() + offset, y, z),
                    new BlockPosition(x, y, build.minZ() - offset),
                    new BlockPosition(x, y, build.maxZ() + offset))) {
                try {
                    Site site = new Site(feet, bottomY);
                    if (separated(site, build, depots) && squared(feet, current) <= 360L * 360) { result.add(site); }
                } catch (IllegalArgumentException outsideBounds) { /* No candidate is preferable to unsafe coordinates. */ }
            }
        }
        result.sort(Comparator.comparingLong(site -> squared(site.feet(), current)));
        return List.copyOf(result);
    }

    static Optional<String> receiptProblem(MossDepositFacts.Snapshot before, int source,
                                           MossDepositFacts.Snapshot after) {
        if (source < 0 || source >= 36 || !before.main().get(source).stack().plainPickup()) {
            return Optional.of("Disposal source is not an approved plain pickup.");
        }
        if (!after.main().get(source).stack().empty()) {
            return Optional.of("The exact disposal source is not empty in the server receipt.");
        }
        for (int slot = 0; slot < 36; slot++) {
            var prior = before.main().get(slot).stack();
            var next = after.main().get(slot).stack();
            // Unrelated default pickups may arrive while acquiring the receipt. Never hide
            // a loss, a protected component change, or recollection of the discarded item.
            boolean pickupIncrease = next.plainPickup()
                    && !next.itemId().equals(before.main().get(source).stack().itemId())
                    && (prior.empty() || prior.plainPickup() && prior.itemId().equals(next.itemId())
                        && next.count() > prior.count());
            if (slot != source && !prior.samePhysicalStack(next) && !pickupIncrease) {
                return Optional.of("A protected inventory slot changed during disposal.");
            }
        }
        if (!before.cursor().empty() || !after.cursor().empty()
                || !before.offhand().samePhysicalStack(after.offhand())) {
            return Optional.of("Disposal cursor or protected offhand changed.");
        }
        for (int index = 0; index < 4; index++) {
            if (!before.armor().get(index).samePhysicalStack(after.armor().get(index))) {
                return Optional.of("Protected equipment changed during disposal.");
            }
        }
        return Optional.empty();
    }

    private static long squared(BlockPosition a, BlockPosition b) {
        long x = (long) a.x() - b.x(), y = (long) a.y() - b.y(), z = (long) a.z() - b.z();
        return x * x + y * y + z * z;
    }
}
