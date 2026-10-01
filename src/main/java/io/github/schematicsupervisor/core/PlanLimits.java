package io.github.schematicsupervisor.core;

/** Bounds the source scan, target collections, and full-volume verification snapshot. */
public final class PlanLimits {
    public static final long MAX_VOLUME_CELLS = 2_000_000;
    public static final int MAX_TARGET_BLOCKS = 1_000_000;
    public static final int MAX_CHUNKS = 1_024;

    private PlanLimits() { }

    public static void requireVolume(BuildVolume volume) {
        if (volume.blockCount() > MAX_VOLUME_CELLS) {
            throw new IllegalArgumentException("Schematic volume contains " + volume.blockCount()
                    + " cells; the supported limit is " + MAX_VOLUME_CELLS + ".");
        }
    }

    public static void requireTargetCount(long count) {
        if (count < 0 || count > MAX_TARGET_BLOCKS) {
            throw new IllegalArgumentException("Schematic non-air block count " + count
                    + " exceeds the supported range 0.." + MAX_TARGET_BLOCKS + ".");
        }
    }

    public static void requireChunkCount(long count) {
        if (count < 1 || count > MAX_CHUNKS) {
            throw new IllegalArgumentException("Schematic chunk count " + count
                    + " exceeds the supported range 1.." + MAX_CHUNKS + ".");
        }
    }
}
