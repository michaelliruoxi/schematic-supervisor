package io.github.schematicsupervisor.fabric;

import io.github.schematicsupervisor.core.BlockPosition;
import java.lang.ref.WeakReference;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** One historical selection decision. Reading it never re-evaluates tool eligibility. */
record MossToolSelection(Instant capturedAt, BlockPosition target, Boolean currentContextMatches,
                         String outcome, Integer selectedSlot, String selectedItem,
                         int trackedIdentitiesBefore, int trackedIdentitiesAfter, int identityCap,
                         boolean repairEnabled, List<Candidate> candidates, boolean truncated, String error) {
    static final int MAX_CANDIDATES = 9;

    MossToolSelection {
        Objects.requireNonNull(capturedAt);
        Objects.requireNonNull(target);
        outcome = token(outcome);
        error = token(error);
        selectedItem = selectedItem == null ? null : registryId(selectedItem);
        if (selectedSlot != null && (selectedSlot < 0 || selectedSlot >= 9)
                || trackedIdentitiesBefore < 0 || trackedIdentitiesAfter < 0 || identityCap < 1
                || trackedIdentitiesBefore > identityCap || trackedIdentitiesAfter > identityCap) {
            throw new IllegalArgumentException("Invalid moss selection diagnostic");
        }
        truncated |= candidates.size() > MAX_CANDIDATES;
        candidates = List.copyOf(candidates.subList(0, Math.min(candidates.size(), MAX_CANDIDATES)));
    }

    MossToolSelection withCurrentContextMatches(Boolean matches) {
        return new MossToolSelection(capturedAt, target, matches, outcome, selectedSlot, selectedItem,
                trackedIdentitiesBefore, trackedIdentitiesAfter, identityCap, repairEnabled, candidates, truncated, error);
    }

    record Candidate(int slot, String itemId, int count, Integer damage, Integer maximum,
                     Boolean unbreakable, Boolean usable, Integer damagePerBlock, Float miningSpeed,
                     MossMiningToolGuard.Diagnostic guard, MinecraftMossToolFingerprint.Fingerprint fingerprint,
                     boolean evaluated, String error, MetadataEvidence metadataEvidence) {
        Candidate(int slot, String itemId, int count, Integer damage, Integer maximum,
                  Boolean unbreakable, Boolean usable, Integer damagePerBlock, Float miningSpeed,
                  MossMiningToolGuard.Diagnostic guard, MinecraftMossToolFingerprint.Fingerprint fingerprint,
                  boolean evaluated, String error) {
            this(slot, itemId, count, damage, maximum, unbreakable, usable, damagePerBlock, miningSpeed,
                    guard, fingerprint, evaluated, error, null);
        }

        Candidate {
            itemId = registryId(itemId);
            error = token(error);
            if (slot < 0 || slot >= MAX_CANDIDATES || count < 0
                    || miningSpeed != null && !Float.isFinite(miningSpeed)) {
                throw new IllegalArgumentException("Invalid moss candidate diagnostic");
            }
        }

        Candidate withEvaluation(boolean value, MossMiningToolGuard.Diagnostic evaluatedGuard) {
            return new Candidate(slot, itemId, count, damage, maximum, unbreakable, usable,
                    damagePerBlock, miningSpeed, value && Boolean.TRUE.equals(usable) ? evaluatedGuard : guard,
                    fingerprint, value, error, metadataEvidence);
        }

        Candidate withMetadataEvidence(MetadataEvidence evidence) {
            return new Candidate(slot, itemId, count, damage, maximum, unbreakable, usable,
                    damagePerBlock, miningSpeed, guard, fingerprint, evaluated, error, evidence);
        }
    }

    record MetadataEvidence(MossToolMetadataProbe.Snapshot current,
                            AppliedMetadata latestAppliedSlotReceipt, String error) {
        MetadataEvidence { error = token(error); }
    }

    /** The stamp proves past packet application; exactCurrentMatch separately compares the live stack. */
    record AppliedMetadata(String epoch, long sequence, int slot, Integer damage, boolean exactCurrentMatch,
                           MossToolMetadataProbe.Snapshot customData, MossToolReceiptHistory.Transition previousReceipt) {
        AppliedMetadata(String epoch, long sequence, int slot, Integer damage, boolean exactCurrentMatch,
                        MossToolMetadataProbe.Snapshot customData) {
            this(epoch, sequence, slot, damage, exactCurrentMatch, customData, null);
        }

        AppliedMetadata {
            UUID.fromString(epoch);
            if (sequence < 1 || slot < 0 || slot >= 36 || damage != null && damage < 0) {
                throw new IllegalArgumentException("Invalid applied metadata evidence");
            }
            Objects.requireNonNull(customData);
        }
    }

    static final class History {
        private MossToolSelection last;
        private WeakReference<Object> world = new WeakReference<>(null);
        private WeakReference<Object> player = new WeakReference<>(null);

        void capture(MossToolSelection selection, Object sourceWorld, Object sourcePlayer) {
            last = Objects.requireNonNull(selection);
            world = new WeakReference<>(sourceWorld);
            player = new WeakReference<>(sourcePlayer);
        }

        MossToolSelection observation(Object currentWorld, Object currentPlayer) {
            if (last == null) { return null; }
            Boolean matches = currentWorld == null || currentPlayer == null ? null
                    : currentWorld == world.get() && currentPlayer == player.get();
            return last.withCurrentContextMatches(matches);
        }
    }

    private static String registryId(String value) {
        if (value == null || value.length() > 128 || !value.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) {
            throw new IllegalArgumentException("Invalid moss diagnostic registry identifier");
        }
        return value;
    }

    private static String token(String value) {
        if (value == null || value.length() > 64 || !value.matches("[A-Z_]*")) {
            throw new IllegalArgumentException("Invalid moss diagnostic status");
        }
        return value;
    }
}
