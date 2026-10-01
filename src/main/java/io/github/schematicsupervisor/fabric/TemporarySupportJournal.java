package io.github.schematicsupervisor.fabric;

import io.github.schematicsupervisor.core.BlockPosition;
import io.github.schematicsupervisor.core.BlockState;
import io.github.schematicsupervisor.core.PlannedConsumptionCredit;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Immutable ownership and acknowledgement state for one bounded support column. */
record TemporarySupportJournal(TemporarySupportPlanner.Column column, RunContext context,
                               List<Cell> cells, SeedStage seedStage, boolean cleanupRequested,
                               long revision, String columnId, Boolean starterCreative,
                               PlannedConsumptionCredit plannedCredit, boolean creditAcknowledged) {
    static final BlockState DIRT = new BlockState("minecraft:dirt");

    TemporarySupportJournal {
        Objects.requireNonNull(column, "column");
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(seedStage, "seedStage");
        Objects.requireNonNull(columnId, "columnId");
        if (!UUID.fromString(columnId).toString().equals(columnId)) {
            throw new IllegalArgumentException("support column id must be a canonical UUID");
        }
        cells = List.copyOf(cells);
        if (cells.size() != 2 || revision < 0) {
            throw new IllegalArgumentException("support journal requires two cells and a valid revision");
        }
        for (int index = 0; index < cells.size(); index++) {
            if (!cells.get(index).position().equals(column.supports().get(index))) {
                throw new IllegalArgumentException("journal cell does not match bound support column");
            }
        }
        if (seedStage == SeedStage.CONFIRMED && !cleanupRequested) {
            throw new IllegalArgumentException("confirmed seed requires immediate support cleanup");
        }
        if (seedStage == SeedStage.CONFIRMED) {
            Objects.requireNonNull(starterCreative, "confirmed starter requires its receipt mode");
            if (starterCreative != (plannedCredit == null)) {
                throw new IllegalArgumentException("survival starter requires exactly one durable material credit");
            }
        } else if (starterCreative != null || plannedCredit != null || creditAcknowledged) {
            throw new IllegalArgumentException("unconfirmed starter cannot carry material credit");
        }
        if (plannedCredit != null && (!plannedCredit.id().equals(columnId)
                || !plannedCredit.planId().equals(column.planId())
                || plannedCredit.material() != column.seed().material() || plannedCredit.quantity() != 1)) {
            throw new IllegalArgumentException("planned credit must match the confirmed structural starter");
        }
        if (creditAcknowledged && plannedCredit == null) {
            throw new IllegalArgumentException("only a persisted material credit can be acknowledged");
        }
        if (seedStage == SeedStage.PLACE_INTENT && (cleanupRequested
                || cells.stream().anyMatch(cell -> cell.stage() != CellStage.PLACED))) {
            throw new IllegalArgumentException("seed placement requires both confirmed supports");
        }
        if (!cleanupRequested && cells.stream().anyMatch(cell ->
                cell.stage() == CellStage.REMOVE_INTENT || cell.stage() == CellStage.REMOVED)) {
            throw new IllegalArgumentException("support removal requires cleanup mode");
        }
        if (!cleanupRequested && cells.get(1).stage() != CellStage.PLANNED
                && cells.get(0).stage() != CellStage.PLACED) {
            throw new IllegalArgumentException("upper support requires a confirmed lower support");
        }
        if (cells.stream().filter(cell -> cell.stage() == CellStage.PLACE_INTENT
                || cell.stage() == CellStage.REMOVE_INTENT).count() > 1) {
            throw new IllegalArgumentException("only one support interaction may be unsettled");
        }
    }

    static TemporarySupportJournal begin(TemporarySupportPlanner.Column column, RunContext context) {
        return new TemporarySupportJournal(column, context,
                column.supports().stream().map(position -> new Cell(position, BlockState.AIR,
                        DIRT, CellStage.PLANNED)).toList(), SeedStage.PLANNED, false, 0,
                UUID.randomUUID().toString(), null, null, false);
    }

    boolean initial() {
        return revision == 0 && seedStage == SeedStage.PLANNED && !cleanupRequested
                && cells.stream().allMatch(cell -> cell.stage() == CellStage.PLANNED);
    }

    boolean requiresReconciliation() {
        return seedStage == SeedStage.PLACE_INTENT || cells.stream().anyMatch(cell ->
                cell.stage() == CellStage.PLACE_INTENT || cell.stage() == CellStage.REMOVE_INTENT);
    }

    boolean complete() {
        return cleanupComplete() && pendingPlannedCredit().isEmpty();
    }

    boolean cleanupComplete() {
        return cleanupRequested && seedStage != SeedStage.PLACE_INTENT && cells.stream().allMatch(cell ->
                cell.stage() == CellStage.PLANNED || cell.stage() == CellStage.REMOVED);
    }

    Optional<PlannedConsumptionCredit> pendingPlannedCredit() {
        return creditAcknowledged ? Optional.empty() : Optional.ofNullable(plannedCredit);
    }

    TemporarySupportJournal confirmStarter(boolean creative) {
        require(seedStage == SeedStage.PLACE_INTENT, "starter has no persisted placement intent");
        PlannedConsumptionCredit credit = creative ? null
                : new PlannedConsumptionCredit(columnId, column.planId(), column.seed().material(), 1);
        return new TemporarySupportJournal(column, context, cells, SeedStage.CONFIRMED, true,
                Math.incrementExact(revision), columnId, creative, credit, false);
    }

    TemporarySupportJournal acknowledgePlannedCredit(String id) {
        require(plannedCredit != null && plannedCredit.id().equals(id), "credit acknowledgement does not match this column");
        if (creditAcknowledged) { return this; }
        return new TemporarySupportJournal(column, context, cells, seedStage, cleanupRequested,
                Math.incrementExact(revision), columnId, starterCreative, plannedCredit, true);
    }

    List<BlockPosition> outstandingSupports() {
        return cells.stream().filter(cell -> cell.stage() != CellStage.PLANNED
                && cell.stage() != CellStage.REMOVED).map(Cell::position).toList();
    }

    boolean matches(String planId, RunContext runContext, String sliceId) {
        return column.planId().equals(planId) && context.equals(runContext) && column.sliceId().equals(sliceId);
    }

    TemporarySupportJournal transition(Action action, int cellIndex) {
        Objects.requireNonNull(action, "action");
        if (action == Action.SEED_INTENT || action == Action.SEED_CONFIRMED
                || action == Action.SEED_REJECTED || action == Action.BEGIN_CLEANUP) {
            if (cellIndex != -1) { throw new IllegalArgumentException("seed and cleanup actions use cell index -1"); }
            return transitionSeed(action);
        }
        if (cellIndex < 0 || cellIndex >= cells.size()) {
            throw new IllegalArgumentException("support cell index must be zero or one");
        }
        Cell cell = cells.get(cellIndex);
        CellStage nextStage;
        switch (action) {
            case PLACE_INTENT -> {
                require(!cleanupRequested && seedStage == SeedStage.PLANNED && !requiresReconciliation(),
                        "support placement is not available");
                require(cell.stage() == CellStage.PLANNED
                        && (cellIndex == 0 || cells.get(0).stage() == CellStage.PLACED),
                        "supports must be placed bottom first");
                nextStage = CellStage.PLACE_INTENT;
            }
            case PLACE_CONFIRMED -> {
                require(cell.stage() == CellStage.PLACE_INTENT, "support placement has no persisted intent");
                nextStage = CellStage.PLACED;
            }
            case PLACE_REJECTED -> {
                require(cell.stage() == CellStage.PLACE_INTENT, "support placement has no persisted intent");
                nextStage = CellStage.PLANNED;
            }
            case REMOVE_INTENT -> {
                require(cleanupRequested && !requiresReconciliation() && cell.stage() == CellStage.PLACED,
                        "support removal requires confirmed ownership and cleanup mode");
                require(cellIndex == 1 || cells.get(1).stage() == CellStage.REMOVED
                        || cells.get(1).stage() == CellStage.PLANNED, "supports must be removed top first");
                nextStage = CellStage.REMOVE_INTENT;
            }
            case REMOVE_CONFIRMED -> {
                require(cell.stage() == CellStage.REMOVE_INTENT, "support removal has no persisted intent");
                nextStage = CellStage.REMOVED;
            }
            case REMOVE_REJECTED -> {
                require(cell.stage() == CellStage.REMOVE_INTENT, "support removal has no persisted intent");
                nextStage = CellStage.PLACED;
            }
            default -> throw new IllegalArgumentException("unsupported support action");
        }
        ArrayList<Cell> updated = new ArrayList<>(cells);
        updated.set(cellIndex, new Cell(cell.position(), cell.originalState(), cell.expectedState(), nextStage));
        return new TemporarySupportJournal(column, context, updated, seedStage, cleanupRequested,
                Math.incrementExact(revision), columnId, starterCreative, plannedCredit, creditAcknowledged);
    }

    private TemporarySupportJournal transitionSeed(Action action) {
        SeedStage nextSeed = seedStage;
        boolean nextCleanup = cleanupRequested;
        switch (action) {
            case SEED_INTENT -> {
                require(!cleanupRequested && seedStage == SeedStage.PLANNED
                        && cells.stream().allMatch(cell -> cell.stage() == CellStage.PLACED),
                        "seed requires two confirmed supports");
                nextSeed = SeedStage.PLACE_INTENT;
            }
            case SEED_CONFIRMED -> {
                throw new IllegalStateException("starter confirmation requires its live creative/material receipt");
            }
            case SEED_REJECTED -> {
                require(seedStage == SeedStage.PLACE_INTENT, "seed has no persisted placement intent");
                nextSeed = SeedStage.PLANNED;
            }
            case BEGIN_CLEANUP -> {
                require(!requiresReconciliation(), "unsettled support or seed interaction needs reconciliation");
                require(!cleanupRequested, "support cleanup is already active");
                nextCleanup = true;
            }
            default -> throw new IllegalArgumentException("unsupported seed action");
        }
        return new TemporarySupportJournal(column, context, cells, nextSeed, nextCleanup,
                Math.incrementExact(revision), columnId, starterCreative, plannedCredit, creditAcknowledged);
    }

    private static void require(boolean allowed, String detail) {
        if (!allowed) { throw new IllegalStateException(detail); }
    }

    enum CellStage { PLANNED, PLACE_INTENT, PLACED, REMOVE_INTENT, REMOVED }
    enum SeedStage { PLANNED, PLACE_INTENT, CONFIRMED }
    enum Action {
        PLACE_INTENT, PLACE_CONFIRMED, PLACE_REJECTED,
        SEED_INTENT, SEED_CONFIRMED, SEED_REJECTED,
        BEGIN_CLEANUP, REMOVE_INTENT, REMOVE_CONFIRMED, REMOVE_REJECTED
    }

    record Cell(BlockPosition position, BlockState originalState, BlockState expectedState, CellStage stage) {
        Cell {
            Objects.requireNonNull(position, "position");
            Objects.requireNonNull(stage, "stage");
            if (!BlockState.AIR.equals(originalState) || !DIRT.equals(expectedState)) {
                throw new IllegalArgumentException("temporary support ownership requires original AIR and expected DIRT");
            }
        }
    }
}
