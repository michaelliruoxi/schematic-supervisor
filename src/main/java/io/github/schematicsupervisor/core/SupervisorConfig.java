package io.github.schematicsupervisor.core;

import java.time.Duration;
import java.util.List;
import java.util.Objects;

/**
 * @param transientRetryDelays the waits before each automatic retry of a recovery whose cause is
 *        transient (see {@link RecoveryClassifier}); its size is the retry limit per incident
 */
public record SupervisorConfig(
        Duration stallTimeout,
        Duration lagWait,
        Duration advisorTimeout,
        long minimumHoes,
        long minimumFood,
        List<Duration> transientRetryDelays
) {
    public static final List<Duration> DEFAULT_TRANSIENT_RETRY_DELAYS = List.of(
            Duration.ofSeconds(30), Duration.ofSeconds(90), Duration.ofSeconds(270));
    public static final Duration FLIGHT_RESTORE_TIMEOUT = Duration.ofSeconds(10);

    public SupervisorConfig {
        Objects.requireNonNull(stallTimeout, "stallTimeout");
        Objects.requireNonNull(lagWait, "lagWait");
        Objects.requireNonNull(advisorTimeout, "advisorTimeout");
        transientRetryDelays = List.copyOf(Objects.requireNonNull(transientRetryDelays, "transientRetryDelays"));
        if (stallTimeout.isZero() || stallTimeout.isNegative()) {
            throw new IllegalArgumentException("stall timeout must be positive");
        }
        if (lagWait.isZero() || lagWait.isNegative()) {
            throw new IllegalArgumentException("lag wait must be positive");
        }
        if (advisorTimeout.isZero() || advisorTimeout.isNegative()) {
            throw new IllegalArgumentException("advisor timeout must be positive");
        }
        if (minimumHoes < 0 || minimumFood < 0) {
            throw new IllegalArgumentException("minimum supply counts must be non-negative");
        }
        if (transientRetryDelays.size() > 10
                || transientRetryDelays.stream().anyMatch(delay -> delay.isZero() || delay.isNegative())) {
            throw new IllegalArgumentException("transient retry delays must be positive and at most ten");
        }
    }

    public SupervisorConfig(
            Duration stallTimeout,
            Duration lagWait,
            Duration advisorTimeout,
            long minimumHoes,
            long minimumFood
    ) {
        this(stallTimeout, lagWait, advisorTimeout, minimumHoes, minimumFood, DEFAULT_TRANSIENT_RETRY_DELAYS);
    }

    public static SupervisorConfig defaults() {
        return new SupervisorConfig(
                Duration.ofSeconds(15),
                Duration.ofSeconds(10),
                Duration.ofSeconds(15),
                1,
                1
        );
    }

    public SupervisorConfig withTransientRetryDelays(List<Duration> delays) {
        return new SupervisorConfig(stallTimeout, lagWait, advisorTimeout, minimumHoes, minimumFood, delays);
    }
}
