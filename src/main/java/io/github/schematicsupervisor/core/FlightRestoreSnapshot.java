package io.github.schematicsupervisor.core;

import java.util.Objects;

/** A poll result for a bounded takeoff that re-activates flight the server already allows. */
public record FlightRestoreSnapshot(Status status, String detail) {
    public enum Status {
        IDLE,
        RUNNING,
        SUCCEEDED,
        FAILED
    }

    public FlightRestoreSnapshot {
        Objects.requireNonNull(status, "status");
        detail = detail == null ? "" : detail;
    }

    public static FlightRestoreSnapshot idle() {
        return new FlightRestoreSnapshot(Status.IDLE, "");
    }

    public static FlightRestoreSnapshot running() {
        return new FlightRestoreSnapshot(Status.RUNNING, "");
    }

    public static FlightRestoreSnapshot succeeded() {
        return new FlightRestoreSnapshot(Status.SUCCEEDED, "");
    }

    public static FlightRestoreSnapshot failed(String detail) {
        return new FlightRestoreSnapshot(Status.FAILED, detail);
    }
}
