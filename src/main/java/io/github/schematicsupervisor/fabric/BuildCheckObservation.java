package io.github.schematicsupervisor.fabric;

import io.github.schematicsupervisor.core.BlockState;
import io.github.schematicsupervisor.core.BuildCheck;
import io.github.schematicsupervisor.core.LayerProgress;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;

/** The start build check as reported in chat and to local clients. */
record BuildCheckObservation(
        String status,
        double progress,
        Instant startedAt,
        Instant finishedAt,
        long durationMillis,
        String summary,
        BuildCheck.Result result
) {
    static final String RUNNING = "RUNNING";
    static final String COMPLETE = "COMPLETE";
    static final String FAILED = "FAILED";

    BuildCheckObservation {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(startedAt, "startedAt");
        summary = summary == null ? "" : summary;
        progress = Math.max(0.0, Math.min(1.0, progress));
        durationMillis = Math.max(0, durationMillis);
    }

    static BuildCheckObservation running(Instant startedAt, double progress, long elapsedMillis) {
        return new BuildCheckObservation(RUNNING, progress, startedAt, null, elapsedMillis,
                "Checking the build before starting.", null);
    }

    static BuildCheckObservation complete(Instant startedAt, Instant finishedAt, long durationMillis,
                                          BuildCheck.Result result, LayerProgress start) {
        return new BuildCheckObservation(COMPLETE, 1.0, startedAt, finishedAt, durationMillis,
                summarize(result, durationMillis, start), Objects.requireNonNull(result, "result"));
    }

    static BuildCheckObservation failed(Instant startedAt, Instant finishedAt, long durationMillis, String detail) {
        return new BuildCheckObservation(FAILED, 1.0, startedAt, finishedAt, durationMillis,
                "The build check failed (" + detail + "); construction started from the first stage and "
                        + "re-checks each piece instead.", null);
    }

    static String summarize(BuildCheck.Result result, long durationMillis, LayerProgress start) {
        StringBuilder text = new StringBuilder("Build check (")
                .append(String.format(Locale.ROOT, "%.1f", durationMillis / 1000.0)).append(" s): ")
                .append(result.chunksChecked()).append(" of ").append(result.chunkCount()).append(" chunks checked");
        int unchecked = result.chunkCount() - result.chunksChecked();
        if (unchecked > 0) {
            text.append(" (").append(unchecked).append(unchecked == 1 ? " was" : " were")
                    .append(" out of range and will be checked when the build reaches ")
                    .append(unchecked == 1 ? "it" : "them").append(")");
        }
        long percent = result.totalActions() == 0 ? 100 : result.doneActions() * 100 / result.totalActions();
        text.append("; ").append(percent).append("% built. ");
        if (result.workLeft() == 0 && result.uncheckedActions() == 0) {
            text.append("Nothing is left to place, till, or plant. ");
        } else {
            text.append("Left: ").append(count(result.placementsLeft())).append(" to place, ")
                    .append(count(result.tillingLeft())).append(" to till, ")
                    .append(count(result.plantingLeft())).append(" to plant. ");
        }
        long attention = result.attentionBlocks();
        long wrong = result.wrongBlocks() - result.wrongBlocksCleared();
        long extra = result.extraBlocks() - result.extraBlocksCleared();
        if (attention == 0) {
            text.append("No wrong or extra blocks need attention");
        } else {
            text.append(count(wrong)).append(wrong == 1 ? " wrong block and " : " wrong blocks and ")
                    .append(count(extra)).append(extra == 1 ? " extra block need" : " extra blocks need")
                    .append(" attention");
            if (!result.problems().isEmpty()) {
                text.append(" (first: ").append(describe(result.problems().getFirst())).append(")");
            }
        }
        long cleared = result.wrongBlocksCleared() + result.extraBlocksCleared();
        if (cleared > 0) {
            text.append("; the builder clears ").append(count(cleared))
                    .append(cleared == 1 ? " moss or stem block" : " moss or stem blocks").append(" itself");
        }
        text.append(". ");
        if (start == null || start.y() == null || "VERIFY".equals(start.stage()) || "DONE".equals(start.stage())) {
            text.append("Starting final verification.");
        } else {
            text.append("Starting at stage ").append(start.ordinal()).append(" of ").append(start.total())
                    .append(" (").append(kind(start.stage())).append(", Y ").append(start.y()).append(").");
        }
        return text.toString();
    }

    static String describe(BuildCheck.Problem problem) {
        String where = problem.position().x() + " " + problem.position().y() + " " + problem.position().z();
        return problem.kind() == BuildCheck.ProblemKind.EXTRA
                ? "extra " + name(problem.actual()) + " at " + where
                : name(problem.actual()) + " instead of " + name(problem.expected()) + " at " + where;
    }

    static String name(BlockState state) {
        String id = state.blockId();
        int colon = id.indexOf(':');
        return (colon >= 0 ? id.substring(colon + 1) : id).replace('_', ' ');
    }

    private static String kind(String stage) {
        return switch (stage) {
            case "STRUCTURE" -> "structure";
            case "LIGHTING" -> "lights";
            case "TILL" -> "till";
            case "PLANT" -> "plant";
            default -> stage.toLowerCase(Locale.ROOT);
        };
    }

    private static String count(long value) {
        return String.format(Locale.ROOT, "%,d", value);
    }
}
