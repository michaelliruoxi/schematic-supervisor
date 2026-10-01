package io.github.schematicsupervisor.fabric;

/**
 * What to do with the next till target before moving to it, from what the client sees there. A
 * prediction still settling at the target or above it waits; tilled soil is confirmed without a click;
 * missing soil hands the slice back for its planned dirt; anything else that is not plain dirt with
 * room above fails.
 */
final class TillTargetCheck {
    static final String NOT_DIRT = "Till target is neither planned dirt nor confirmed farmland";
    static final String OCCUPIED_ABOVE = "Till target has an occupied block above it";

    enum Next { APPROACH_CHUNK, WAIT_FOR_PREDICTION, ALREADY_TILLED, NEEDS_DIRT, FAIL, PROCEED }

    record Facts(boolean chunkReceived, boolean predictionPending, boolean abovePredictionPending,
                 boolean farmland, boolean air, boolean dirt, boolean aboveAir) {
        /** An unreceived chunk has no block facts; reading one would see air. */
        static Facts unreceived() {
            return new Facts(false, false, false, false, false, false, false);
        }
    }

    record Decision(Next next, String failure) { }

    private TillTargetCheck() {
    }

    static Decision decide(Facts facts) {
        if (!facts.chunkReceived()) { return new Decision(Next.APPROACH_CHUNK, ""); }
        if (facts.predictionPending() || facts.abovePredictionPending()) {
            return new Decision(Next.WAIT_FOR_PREDICTION, "");
        }
        if (facts.farmland()) { return new Decision(Next.ALREADY_TILLED, ""); }
        if (facts.air()) { return new Decision(Next.NEEDS_DIRT, ""); }
        if (!facts.dirt()) { return new Decision(Next.FAIL, NOT_DIRT); }
        if (!facts.aboveAir()) { return new Decision(Next.FAIL, OCCUPIED_ABOVE); }
        return new Decision(Next.PROCEED, "");
    }
}
