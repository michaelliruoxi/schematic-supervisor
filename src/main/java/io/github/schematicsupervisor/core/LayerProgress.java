package io.github.schematicsupervisor.core;

/** Progress through horizontal work stages, with an individual bounded chunk slice. */
public record LayerProgress(
        String order,
        String stage,
        int ordinal,
        int total,
        Integer y,
        int chunkOrdinal,
        int chunkTotal
) {
    public LayerProgress {
        if (order == null || stage == null || ordinal < 0 || total < ordinal
                || chunkOrdinal < 0 || chunkTotal < chunkOrdinal) {
            throw new IllegalArgumentException("invalid layer progress");
        }
    }
}
