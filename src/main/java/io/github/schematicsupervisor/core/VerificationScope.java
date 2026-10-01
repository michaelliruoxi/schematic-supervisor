package io.github.schematicsupervisor.core;

public record VerificationScope(Kind kind, int chunkIndex) {
    public enum Kind {
        CHUNK,
        FULL_PLAN
    }

    public VerificationScope {
        if (kind == null) {
            throw new NullPointerException("kind");
        }
        if (kind == Kind.CHUNK && chunkIndex < 0) {
            throw new IllegalArgumentException("chunk verification index must be non-negative and bound to its plan");
        }
        if (kind == Kind.FULL_PLAN && chunkIndex != -1) {
            throw new IllegalArgumentException("full-plan verification uses chunk index -1");
        }
    }

    public static VerificationScope chunk(int index) {
        return new VerificationScope(Kind.CHUNK, index);
    }

    public static VerificationScope fullPlan() {
        return new VerificationScope(Kind.FULL_PLAN, -1);
    }
}
