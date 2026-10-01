package io.github.schematicsupervisor.fabric;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;

/** Ranks already available tools only; this policy never authorizes a clearing target or spends wear. */
final class ClearingToolSelection {
    enum Family { HOE, AXE, SHOVEL, OTHER }
    record Candidate(int slot, Family family, boolean eligible, double speed, double effectiveSpeed) {
        Candidate(int slot, Family family, boolean eligible, double speed) {
            this(slot, family, eligible, speed, speed);
        }
    }

    private ClearingToolSelection() { }

    static Family appropriateFamily(String blockId) {
        if ("minecraft:moss_block".equals(blockId)) { return Family.HOE; }
        if ("minecraft:dirt".equals(blockId) || "minecraft:grass_block".equals(blockId)) { return Family.SHOVEL; }
        return "minecraft:jack_o_lantern".equals(blockId) || "minecraft:pumpkin".equals(blockId)
                || "minecraft:carved_pumpkin".equals(blockId) || "minecraft:melon".equals(blockId)
                ? Family.AXE : Family.OTHER;
    }

    static boolean admitted(Family family, boolean comparable, int count, int damagePerBlock, int damage, int maximum) {
        return family != null && family != Family.OTHER && comparable && count == 1 && damagePerBlock == 1
                && maximum > 0 && damage >= 0 && damage <= maximum;
    }

    static boolean eligible(Family family, String blockId, boolean comparable, int count,
                            int damagePerBlock, int damage, int maximum, boolean unbreakable, double multiplier) {
        return admitted(family, comparable, count, damagePerBlock, damage, maximum)
                && family == appropriateFamily(blockId)
                && (unbreakable || damage < maximum) && Double.isFinite(multiplier) && multiplier > 1;
    }

    /** Common haste/fatigue/airborne factors cancel between candidates; candidate attributes do not. */
    static double score(double multiplier, double efficiency, double breakSpeed, boolean submerged, double waterSpeed) {
        if (!Double.isFinite(multiplier) || multiplier <= 1 || !Double.isFinite(efficiency) || efficiency < 0
                || !Double.isFinite(breakSpeed) || breakSpeed <= 0 || !Double.isFinite(waterSpeed) || waterSpeed < 0) {
            return Double.NaN;
        }
        double result = (multiplier + efficiency) * breakSpeed * (submerged ? waterSpeed : 1);
        return Double.isFinite(result) && result > 0 ? result : Double.NaN;
    }

    static List<Integer> ranked(String blockId, List<Candidate> candidates) {
        if (candidates == null || candidates.size() > 36) { throw new IllegalArgumentException("Unbounded tool candidates"); }
        var seen = new HashSet<Integer>();
        var eligible = new ArrayList<Candidate>();
        Family wanted = appropriateFamily(blockId);
        for (Candidate candidate : candidates) {
            if (candidate == null || candidate.slot() < 0 || candidate.slot() >= 36 || !seen.add(candidate.slot())) {
                throw new IllegalArgumentException("Invalid or duplicate tool slot");
            }
            if (wanted != Family.OTHER && candidate.family() == wanted && candidate.eligible()
                    && Double.isFinite(candidate.speed()) && candidate.speed() > 0) { eligible.add(candidate); }
        }
        // Never mix effective and base units. Stale attributes must not prevent selecting the correct family.
        boolean complete = eligible.stream().allMatch(candidate -> Double.isFinite(candidate.effectiveSpeed())
                && candidate.effectiveSpeed() > 0);
        eligible.sort(Comparator.comparingDouble((Candidate candidate) -> complete
                ? candidate.effectiveSpeed() : candidate.speed()).reversed().thenComparingInt(Candidate::slot));
        return eligible.stream().map(Candidate::slot).toList();
    }
}
