package io.github.schematicsupervisor.fabric;

import io.github.schematicsupervisor.core.BlockObservation;
import io.github.schematicsupervisor.core.BlockPosition;
import io.github.schematicsupervisor.core.BlockState;
import io.github.schematicsupervisor.core.Material;
import io.github.schematicsupervisor.core.OrdinaryPlacement;
import io.github.schematicsupervisor.core.PlannedConsumptionCredit;
import io.github.schematicsupervisor.core.SchematicPlan;
import io.github.schematicsupervisor.core.WorkOrder;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Issues bounded support operations only after their intent is durably recorded. */
final class TemporarySupportController {
    private final TemporarySupportStore store;
    private final BlockState anchorState;
    private TemporarySupportJournal journal;
    private Operation pending;
    private boolean restored;
    private boolean paused;
    private boolean cleanupAfterSettlement;
    private String uncertainty;
    private List<Maintenance> inspectedMaintenance;
    private long inspectedRevision;

    private TemporarySupportController(TemporarySupportStore store, TemporarySupportJournal journal,
            BlockState anchorState, boolean restored) {
        this.store = store;
        this.journal = journal;
        this.anchorState = anchorState;
        this.restored = restored;
    }

    static Optional<TemporarySupportController> begin(SchematicPlan plan, WorkOrder.OrdinaryBlocks fullSlice,
            RunContext context, BlockObservation receivedWorld, TemporarySupportStore store) throws IOException {
        if (store.load().filter(existing -> !existing.complete()).isPresent()) {
            throw new IOException("an unfinished support journal must be restored before planning another column");
        }
        Optional<TemporarySupportPlanner.Column> planned = TemporarySupportPlanner.find(plan, fullSlice, receivedWorld);
        if (planned.isEmpty()) { return Optional.empty(); }
        TemporarySupportPlanner.Column column = planned.orElseThrow();
        BlockState anchor = validateColumn(plan, fullSlice, column);
        return Optional.of(new TemporarySupportController(store, store.create(column, context), anchor, false));
    }

    static Optional<TemporarySupportController> restore(SchematicPlan plan, WorkOrder.OrdinaryBlocks fullSlice,
            RunContext context, TemporarySupportStore store) throws IOException {
        Optional<TemporarySupportJournal> loaded = store.loadFor(plan.planId(), context,
                TemporarySupportPlanner.sliceId(fullSlice));
        if (loaded.isEmpty()) { return Optional.empty(); }
        TemporarySupportJournal journal = loaded.orElseThrow();
        try {
            return Optional.of(new TemporarySupportController(store, journal,
                    validateColumn(plan, fullSlice, journal.column()), true));
        } catch (IllegalArgumentException invalid) {
            throw new IOException("support column does not match the current immutable plan slice", invalid);
        }
    }

    /** The caller must release its controls on pause; this controller never operates input devices. */
    void pause() { paused = true; }

    void resume() { paused = false; }

    TemporarySupportJournal journal() { return journal; }

    boolean complete() { return journal.complete(); }

    List<BlockPosition> outstandingSupports() { return journal.outstandingSupports(); }

    Optional<PlannedConsumptionCredit> pendingPlannedCredit() { return journal.pendingPlannedCredit(); }

    void acknowledgePlannedCredit(String id) throws IOException {
        journal = store.acknowledgePlannedCredit(journal, id);
    }

    /** Cleanup waits for any current receipt. It never treats an unresolved placement as owned. */
    void requestCleanup() throws IOException {
        cleanupAfterSettlement = true;
        if (pending == null && !journal.requiresReconciliation()) { beginCleanup(); }
    }

    /** Records uncertainty locally; the persisted intent remains unsettled across process restarts. */
    void uncertain(Operation operation, String reason) {
        requirePending(operation);
        Objects.requireNonNull(reason, "reason");
        uncertainty = reason.isBlank() ? "Support interaction outcome is uncertain"
                : reason.substring(0, Math.min(256, reason.length()));
    }

    /** Read-only preview: navigation occurs before an intent or inventory baseline is recorded. */
    Decision preview(Snapshot snapshot) throws IOException {
        Inspection inspection = inspect(snapshot);
        return inspection.maintenance().isEmpty() ? inspection.decision()
                : decision(Status.MAINTENANCE, "Received ownership states require journal settlement before another operation");
    }

    /** Persists only cleanup flags and received absence of owned cells; it never issues input. */
    void settleObservedState(Snapshot snapshot) throws IOException {
        Inspection inspection = inspect(snapshot);
        for (Maintenance change : inspection.maintenance()) {
            transition(change.action(), change.cellIndex());
        }
    }

    /** The caller invokes this only after validating its final ray, body, item and interaction guards. */
    Operation issue(Candidate candidate, Snapshot snapshot) throws IOException {
        Objects.requireNonNull(candidate, "candidate");
        Decision available = preview(snapshot);
        if (available.status() != Status.ACTION || !candidate.equals(available.candidate())) {
            throw new IllegalStateException("support candidate changed before its interaction intent was issued");
        }
        Long inventoryBefore = candidate.material() == null ? 0L : snapshot.inventory().get(candidate.material());
        if (inventoryBefore == null) { throw new IllegalStateException("support inventory baseline is unavailable"); }
        transition(candidate.kind().intent(), candidate.kind().cellIndex());
        pending = new Operation(candidate.kind(), journal.revision(), candidate.target(), candidate.against(),
                candidate.original(), candidate.expected(), candidate.material(), inventoryBefore, snapshot.creative());
        restored = false;
        return pending;
    }

    private Inspection inspect(Snapshot snapshot) throws IOException {
        TemporarySupportController inspector = new TemporarySupportController(store, journal, anchorState, restored);
        inspector.paused = paused;
        inspector.pending = pending;
        inspector.cleanupAfterSettlement = cleanupAfterSettlement;
        inspector.uncertainty = uncertainty;
        inspector.inspectedMaintenance = new ArrayList<>();
        inspector.inspectedRevision = journal.revision();
        Decision decision = inspector.nextCandidate(snapshot);
        return new Inspection(decision, List.copyOf(inspector.inspectedMaintenance));
    }

    private Decision nextCandidate(Snapshot snapshot) throws IOException {
        requireContext(snapshot);
        if (paused) { return decision(Status.PAUSED, "Support controls are paused"); }
        if (pending != null) {
            return decision(uncertainty == null ? Status.WAITING : Status.UNCERTAIN,
                    uncertainty == null ? "Waiting for the current support receipt" : uncertainty);
        }
        if (journal.seedStage() == TemporarySupportJournal.SeedStage.PLACE_INTENT
                || journal.cells().stream().anyMatch(cell ->
                cell.stage() == TemporarySupportJournal.CellStage.PLACE_INTENT)) {
            return decision(Status.UNCERTAIN,
                    "A persisted placement intent has no surviving receipt; observed blocks cannot prove ownership");
        }
        if (journal.complete()) { return decision(Status.COMPLETE, "Temporary support cleanup is complete"); }
        if ((restored && !journal.initial()) || cleanupAfterSettlement) { beginCleanup(); }
        restored = false;
        if (journal.cleanupRequested()) { return nextCleanup(snapshot); }

        for (BlockPosition position : allPositions()) {
            if (!authoritative(snapshot.read(position))) {
                return decision(Status.WAITING, "Waiting for received column states without pending predictions");
            }
        }
        if (!anchorState.equals(snapshot.read(journal.column().anchor()).state())) {
            return decision(Status.BLOCKED, "The planned lower anchor changed");
        }
        if (!BlockState.AIR.equals(snapshot.read(journal.column().seed().position()).state())) {
            return decision(Status.BLOCKED, "The planned seed cell is occupied without a confirmed receipt");
        }
        for (TemporarySupportJournal.Cell cell : journal.cells()) {
            BlockState expected = cell.stage() == TemporarySupportJournal.CellStage.PLANNED
                    ? BlockState.AIR : TemporarySupportJournal.DIRT;
            if (!expected.equals(snapshot.read(cell.position()).state())) {
                return decision(Status.BLOCKED, "A support cell changed outside its confirmed journal state");
            }
        }
        int remainingSupports = (int) journal.cells().stream().filter(cell ->
                cell.stage() == TemporarySupportJournal.CellStage.PLANNED).count();
        Material seedMaterial = journal.column().seed().material();
        if (!snapshot.creative()) {
            long dirtNeeded = remainingSupports + (seedMaterial == Material.DIRT ? 1 : 0);
            if (!hasMaterial(snapshot, Material.DIRT, dirtNeeded)
                    || (seedMaterial != Material.DIRT && !hasMaterial(snapshot, seedMaterial, 1))) {
                return decision(Status.WAITING, "Waiting for all remaining support and seed materials");
            }
        }
        if (journal.cells().get(0).stage() == TemporarySupportJournal.CellStage.PLANNED) {
            return candidate(Kind.PLACE_BOTTOM, snapshot);
        }
        if (journal.cells().get(1).stage() == TemporarySupportJournal.CellStage.PLANNED) {
            return candidate(Kind.PLACE_TOP, snapshot);
        }
        return candidate(Kind.PLACE_SEED, snapshot);
    }

    /** Only a live, matching receipt may establish placement ownership or planned consumption. */
    Settlement acknowledge(Operation operation, Snapshot snapshot) throws IOException {
        requirePending(operation);
        requireContext(snapshot);
        if (uncertainty != null) { return new Settlement(Outcome.UNCERTAIN); }
        CellRead target = snapshot.read(operation.target());
        if (!authoritative(target)) { return new Settlement(Outcome.WAITING); }
        Long now = operation.material() == null ? null : snapshot.inventory().get(operation.material());
        boolean receiptMatches = operation.material() == null || (now != null
                && operation.creative() == snapshot.creative()
                && operation.inventoryBefore() - now == (operation.creative() ? 0 : 1));
        if (!operation.expected().equals(target.state()) || !receiptMatches) {
            uncertain(operation, "Received target state and material receipt do not jointly confirm the operation");
            return new Settlement(Outcome.UNCERTAIN);
        }
        if (operation.kind() == Kind.PLACE_SEED) {
            journal = store.confirmStarter(journal, operation.creative());
        } else {
            transition(operation.kind().confirmation(), operation.kind().cellIndex());
        }
        pending = null;
        uncertainty = null;
        return new Settlement(Outcome.CONFIRMED);
    }

    /** Use only after the caller's original bounded receipt has conclusively rejected the action. */
    Settlement reject(Operation operation, Snapshot snapshot) throws IOException {
        requirePending(operation);
        requireContext(snapshot);
        if (uncertainty != null) { return new Settlement(Outcome.UNCERTAIN); }
        CellRead target = snapshot.read(operation.target());
        Long now = operation.material() == null ? null : snapshot.inventory().get(operation.material());
        if (!authoritative(target) || !operation.original().equals(target.state())
                || (operation.material() != null && (now == null
                || operation.creative() != snapshot.creative() || operation.inventoryBefore() != now))) {
            uncertain(operation, "Rejection lacks a received original state and unchanged material receipt");
            return new Settlement(Outcome.UNCERTAIN);
        }
        transition(operation.kind().rejection(), operation.kind().cellIndex());
        pending = null;
        uncertainty = null;
        return new Settlement(Outcome.REJECTED);
    }

    private Decision nextCleanup(Snapshot snapshot) throws IOException {
        // Check both cells before removing either one, including cells that were never ours.
        for (TemporarySupportJournal.Cell cell : journal.cells()) {
            CellRead read = snapshot.read(cell.position());
            if (!authoritative(read)) {
                return decision(Status.WAITING, "Waiting for received support states before cleanup");
            }
            boolean owned = cell.stage() == TemporarySupportJournal.CellStage.PLACED
                    || cell.stage() == TemporarySupportJournal.CellStage.REMOVE_INTENT;
            if (!BlockState.AIR.equals(read.state()) && !(owned && TemporarySupportJournal.DIRT.equals(read.state()))) {
                return decision(Status.BLOCKED, "Cleanup cannot touch an unowned or changed support cell");
            }
        }
        for (int index = 1; index >= 0; index--) {
            TemporarySupportJournal.Cell cell = journal.cells().get(index);
            if (cell.stage() == TemporarySupportJournal.CellStage.PLANNED
                    || cell.stage() == TemporarySupportJournal.CellStage.REMOVED) { continue; }
            if (BlockState.AIR.equals(snapshot.read(cell.position()).state())) {
                if (cell.stage() == TemporarySupportJournal.CellStage.PLACED) {
                    transition(TemporarySupportJournal.Action.REMOVE_INTENT, index);
                }
                transition(TemporarySupportJournal.Action.REMOVE_CONFIRMED, index);
                // Absence settles cleanup only. It never claims a dropped item was picked up.
                continue;
            }
            if (cell.stage() == TemporarySupportJournal.CellStage.REMOVE_INTENT) {
                // A restarted removal may be retried because placement ownership was already durable.
                transition(TemporarySupportJournal.Action.REMOVE_REJECTED, index);
            }
            return candidate(index == 1 ? Kind.REMOVE_TOP : Kind.REMOVE_BOTTOM, snapshot);
        }
        return journal.pendingPlannedCredit().isEmpty()
                ? decision(Status.COMPLETE, "Temporary support cleanup and planned credit are complete")
                : decision(Status.WAITING, "Waiting for durable acknowledgement of the planned starter credit");
    }

    private Decision candidate(Kind kind, Snapshot snapshot) {
        BlockPosition target = kind == Kind.PLACE_SEED ? journal.column().seed().position()
                : journal.cells().get(kind.cellIndex()).position();
        Material material = kind.removes() ? null
                : kind == Kind.PLACE_SEED ? journal.column().seed().material() : Material.DIRT;
        Long inventoryBefore = material == null ? 0L : snapshot.inventory().get(material);
        if (inventoryBefore == null) { return decision(Status.WAITING, "Waiting for an exact material inventory count"); }
        BlockPosition against = kind.removes() ? null : kind == Kind.PLACE_BOTTOM ? journal.column().anchor()
                : journal.cells().get(kind == Kind.PLACE_TOP ? 0 : 1).position();
        BlockState original = kind.removes() ? TemporarySupportJournal.DIRT : BlockState.AIR;
        BlockState expected = kind.removes() ? BlockState.AIR
                : kind == Kind.PLACE_SEED ? journal.column().seed().state() : TemporarySupportJournal.DIRT;
        return new Decision(Status.ACTION, new Candidate(kind, target, against, original, expected,
                material, journal.columnId(), inspectedRevision), "Support candidate is ready for exact approach validation");
    }

    private void beginCleanup() throws IOException {
        if (!journal.cleanupRequested()) { transition(TemporarySupportJournal.Action.BEGIN_CLEANUP, -1); }
    }

    private void transition(TemporarySupportJournal.Action action, int cellIndex) throws IOException {
        if (inspectedMaintenance == null) {
            journal = store.transition(journal, action, cellIndex);
        } else {
            journal = journal.transition(action, cellIndex);
            inspectedMaintenance.add(new Maintenance(action, cellIndex));
        }
    }

    private void requirePending(Operation operation) {
        // Identity binds evidence to this controller instance, not merely a replayable journal revision.
        if (operation == null || pending != operation) {
            throw new IllegalArgumentException("operation is not this controller's live receipt");
        }
    }

    private void requireContext(Snapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        if (!journal.context().equals(snapshot.context())) {
            throw new IllegalArgumentException("support observation belongs to another world or dimension");
        }
    }

    private java.util.List<BlockPosition> allPositions() {
        return java.util.List.of(journal.column().anchor(), journal.cells().get(0).position(),
                journal.cells().get(1).position(), journal.column().seed().position());
    }

    private static boolean authoritative(CellRead read) {
        return read != null && read.received() && !read.predictionPending() && read.state() != null;
    }

    private static boolean hasMaterial(Snapshot snapshot, Material material, long needed) {
        if (needed == 0) { return true; }
        Long actual = snapshot.inventory().get(material);
        return actual != null && actual >= needed;
    }

    private static Decision decision(Status status, String detail) { return new Decision(status, null, detail); }

    private static BlockState validateColumn(SchematicPlan plan, WorkOrder.OrdinaryBlocks order,
            TemporarySupportPlanner.Column column) {
        if (!column.planId().equals(plan.planId()) || !column.sliceId().equals(TemporarySupportPlanner.sliceId(order))
                || column.chunkIndex() != order.chunkIndex() || !plan.chunk(order.chunkIndex()).chunk().equals(order.chunk())
                || order.placements().stream().anyMatch(placement -> placement.position().y() != column.layerY())
                || !order.placements().contains(column.seed())
                || !plan.chunk(order.chunkIndex()).ordinaryPlacements().contains(column.seed())
                || !io.github.schematicsupervisor.core.ChunkCoordinate.containing(column.seed().position()).equals(order.chunk())
                || !plan.buildVolume().contains(column.seed().position()) || !plan.buildVolume().contains(column.anchor())) {
            throw new IllegalArgumentException("support column is outside the immutable plan slice");
        }
        for (BlockPosition support : column.supports()) {
            if (!plan.buildVolume().contains(support) || plan.chunk(order.chunkIndex()).expectedBlocks().stream()
                    .anyMatch(target -> target.position().equals(support) && !BlockState.AIR.equals(target.state()))) {
                throw new IllegalArgumentException("temporary supports require final schematic AIR cells");
            }
        }
        OrdinaryPlacement anchor = plan.chunk(order.chunkIndex()).ordinaryPlacements().stream()
                .filter(placement -> placement.position().equals(column.anchor())).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("support anchor is not a planned structural block"));
        return anchor.state();
    }

    enum Status { ACTION, MAINTENANCE, WAITING, PAUSED, UNCERTAIN, BLOCKED, COMPLETE }
    enum Outcome { WAITING, CONFIRMED, REJECTED, UNCERTAIN }

    enum Kind {
        PLACE_BOTTOM(0), PLACE_TOP(1), PLACE_SEED(-1), REMOVE_TOP(1), REMOVE_BOTTOM(0);
        private final int cellIndex;
        Kind(int cellIndex) { this.cellIndex = cellIndex; }
        int cellIndex() { return cellIndex; }
        boolean removes() { return this == REMOVE_TOP || this == REMOVE_BOTTOM; }
        TemporarySupportJournal.Action intent() {
            return this == PLACE_SEED ? TemporarySupportJournal.Action.SEED_INTENT
                    : removes() ? TemporarySupportJournal.Action.REMOVE_INTENT : TemporarySupportJournal.Action.PLACE_INTENT;
        }
        TemporarySupportJournal.Action confirmation() {
            return this == PLACE_SEED ? TemporarySupportJournal.Action.SEED_CONFIRMED
                    : removes() ? TemporarySupportJournal.Action.REMOVE_CONFIRMED : TemporarySupportJournal.Action.PLACE_CONFIRMED;
        }
        TemporarySupportJournal.Action rejection() {
            return this == PLACE_SEED ? TemporarySupportJournal.Action.SEED_REJECTED
                    : removes() ? TemporarySupportJournal.Action.REMOVE_REJECTED : TemporarySupportJournal.Action.PLACE_REJECTED;
        }
    }

    record Operation(Kind kind, long intentRevision, BlockPosition target, BlockPosition against,
                     BlockState original, BlockState expected, Material material,
                     long inventoryBefore, boolean creative) { }

    record Candidate(Kind kind, BlockPosition target, BlockPosition against, BlockState original,
                     BlockState expected, Material material, String columnId, long journalRevision) { }

    record Decision(Status status, Candidate candidate, String detail) { }

    /** Planned consumption is exposed exclusively through the durable credit outbox. */
    record Settlement(Outcome outcome) { }

    private record Maintenance(TemporarySupportJournal.Action action, int cellIndex) { }
    private record Inspection(Decision decision, List<Maintenance> maintenance) { }

    record CellRead(boolean received, boolean predictionPending, BlockState state) {
        CellRead { if (!received) { state = null; } }
    }

    /** Four bound positions and exact material totals; missing values mean unavailable, never AIR or zero. */
    record Snapshot(RunContext context, Map<BlockPosition, CellRead> cells,
                    Map<Material, Long> inventory, boolean creative) {
        Snapshot {
            Objects.requireNonNull(context, "context");
            cells = Map.copyOf(cells);
            inventory = Map.copyOf(inventory);
            if (cells.size() > 4 || inventory.values().stream().anyMatch(count -> count < 0)) {
                throw new IllegalArgumentException("support snapshot must be bounded with nonnegative inventory counts");
            }
        }
        CellRead read(BlockPosition position) { return cells.get(position); }
    }
}
