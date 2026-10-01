package io.github.schematicsupervisor.fabric;

import io.github.schematicsupervisor.core.ScheduleProgress;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Client-thread cache of the /v1/progress body; rebuilt only when its inputs change. */
final class ProgressSnapshotCache {
    private final Consumer<RuntimeException> failures;
    private Object key;
    private Object failedKey;
    private long revision;
    private volatile byte[] body;

    ProgressSnapshotCache(Consumer<RuntimeException> failures) {
        this.failures = Objects.requireNonNull(failures, "failures");
    }

    void updateAvailable(ScheduleProgress.Key nextKey, Supplier<ScheduleProgress> progress) {
        Objects.requireNonNull(nextKey, "nextKey");
        Objects.requireNonNull(progress, "progress");
        if (nextKey.equals(key) || nextKey.equals(failedKey)) {
            return;
        }
        try {
            byte[] encoded = SupervisorProtocolJson.encodeProgress(progress.get(), revision + 1);
            revision++;
            key = nextKey;
            failedKey = null;
            body = encoded;
        } catch (RuntimeException failure) {
            // Keep the previous body and revision; retry only when the inputs change again.
            failedKey = nextKey;
            failures.accept(failure);
        }
    }

    void updateUnavailable(String reason) {
        Objects.requireNonNull(reason, "reason");
        if (reason.equals(key)) {
            return;
        }
        revision++;
        key = reason;
        failedKey = null;
        body = SupervisorProtocolJson.encodeProgressUnavailable(reason, revision);
    }

    long revision() {
        return revision;
    }

    byte[] snapshot() {
        byte[] current = body;
        return current == null ? null : current.clone();
    }
}
