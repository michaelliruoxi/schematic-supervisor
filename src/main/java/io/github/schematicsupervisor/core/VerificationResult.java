package io.github.schematicsupervisor.core;

import java.util.List;
import java.util.Objects;

public record VerificationResult(
        boolean allRequestedChunksLoaded,
        List<BlockMismatch> mismatches,
        List<BlockPosition> temporaryScaffolding,
        String normalizedFingerprint
) {
    public VerificationResult {
        mismatches = List.copyOf(Objects.requireNonNull(mismatches, "mismatches"));
        temporaryScaffolding = List.copyOf(Objects.requireNonNull(temporaryScaffolding, "temporaryScaffolding"));
        Objects.requireNonNull(normalizedFingerprint, "normalizedFingerprint");
        if (normalizedFingerprint.isBlank()) {
            throw new IllegalArgumentException("normalized fingerprint must not be blank");
        }
    }

    public boolean clean() {
        return allRequestedChunksLoaded && mismatches.isEmpty() && temporaryScaffolding.isEmpty();
    }

    public long missingCount() {
        return mismatches.stream().filter(mismatch -> mismatch.type() == MismatchType.MISSING).count();
    }

    public long incorrectCount() {
        return mismatches.stream().filter(mismatch -> mismatch.type() == MismatchType.INCORRECT).count();
    }

    public long extraneousCount() {
        return mismatches.stream().filter(mismatch -> mismatch.type() == MismatchType.EXTRANEOUS).count();
    }

    public String conciseSummary() {
        return "loaded=" + allRequestedChunksLoaded
                + ", missing=" + missingCount()
                + ", incorrect=" + incorrectCount()
                + ", extraneous=" + extraneousCount()
                + ", scaffolding=" + temporaryScaffolding.size();
    }

    public static VerificationResult clean(String normalizedFingerprint) {
        return new VerificationResult(true, List.of(), List.of(), normalizedFingerprint);
    }
}
