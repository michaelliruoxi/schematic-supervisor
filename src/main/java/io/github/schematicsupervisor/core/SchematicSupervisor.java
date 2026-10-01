package io.github.schematicsupervisor.core;

import java.time.Duration;
import java.time.Instant;
import java.util.TreeMap;
import java.util.Objects;

/**
 * Tick-driven deterministic supervisor. All game and transport behavior is behind ports.
 */
public final class SchematicSupervisor {
    // Registered depots whose route failed may be skipped this often per restock before it pauses.
    static final int MAXIMUM_UNREACHABLE_DEPOT_SKIPS = 3;
    private final SchematicPlan plan;
    private final MaterialQuantities plannedMaterials;
    private final SupervisorConfig config;
    private final SupervisorPorts ports;
    private final LayerBuildSchedule schedule;
    private int scheduleCursor;
    private int repairChunkIndex = -1;
    // Pieces after the cursor that the start build check found finished; the cursor skips them.
    private CompletedPieces checkedPieces = CompletedPieces.none();
    // Transient: restoring the unchanged till cursor rechecks soil before any navigation.
    private WorkOrder.OrdinaryBlocks tillStructureRepair;

    private SupervisorState state = SupervisorState.STOPPED;
    private SupervisorState resumeState = SupervisorState.BUILDING;
    private SupervisorState restockResumeState = SupervisorState.BUILDING;
    private int currentChunkIndex;
    private BuildPhase phase = BuildPhase.ORDINARY_BLOCKS;
    private VerificationStage verificationStage = VerificationStage.CHUNK;
    private RecoveryStage recoveryStage = RecoveryStage.NONE;
    private int stableVerificationPasses;
    private String lastVerificationFingerprint = "";
    private MaterialQuantities consumedMaterials = MaterialQuantities.empty();
    private PlannedConsumptionCredit lastAppliedPlannedCredit;
    private MaterialQuantities withdrawnMaterials = MaterialQuantities.empty();
    private MaterialQuantities missingMaterials = MaterialQuantities.empty();
    private MaterialQuantities restockRequirement = MaterialQuantities.empty();
    private String lastError = "";
    private int verificationRetries;
    private boolean repathAttempted;
    private boolean safeReturnAttempted;
    private boolean advisorAttempted;
    // Not saved: a restored checkpoint pauses, and a later incident starts with fresh attempts.
    private boolean flightRestoreAttempted;
    private boolean recoveryTransient;
    private int transientRetries;

    private boolean commandStarted;
    private long lastProgressMarker;
    private Instant lastProgressAt;
    private Instant recoveryStartedAt;
    private Instant waitUntil;
    private Instant executionWaitAt;
    private Instant safeReturnScreenWaitAt;
    private boolean withdrawalStarted;
    private MaterialQuantities activeWithdrawalRemaining = MaterialQuantities.empty();
    private int unreachableDepotSkips;
    private boolean cancellationSettlementPending;
    private boolean cancellationSettlementFailed;
    private MaterialQuantities cancellationRemaining = MaterialQuantities.empty();
    private boolean executionSettlementPending;
    private boolean reconciliationRequired;
    private String reconciliationDetail = "";
    private boolean verificationStarted;

    private ScheduleProgress.Index progressIndex;
    private Instant lastConfirmedProgressAt;
    private boolean progressBaselinePending;

    public SchematicSupervisor(
            SchematicPlan plan,
            SupervisorConfig config,
            SupervisorPorts ports
    ) {
        this.plan = Objects.requireNonNull(plan, "plan");
        this.plannedMaterials = plan.plannedMaterials();
        this.config = Objects.requireNonNull(config, "config");
        this.ports = Objects.requireNonNull(ports, "ports");
        this.schedule = new LayerBuildSchedule(plan);
        if (schedule.size() > 0) {
            WorkOrder first = schedule.entry(0).order();
            currentChunkIndex = first.chunkIndex();
            phase = first.phase();
        }
        this.lastProgressAt = ports.clock().instant();
        this.recoveryStartedAt = lastProgressAt;
        this.waitUntil = lastProgressAt;
    }

    public static SchematicSupervisor loadOrCreate(
            SchematicPlan plan,
            SupervisorConfig config,
            SupervisorPorts ports
    ) {
        SchematicSupervisor supervisor = new SchematicSupervisor(plan, config, ports);
        ports.checkpoints().load().ifPresent(saved -> {
            SupervisorCheckpoint checkpoint = PlantingModeTransition.apply(plan, saved);
            supervisor.restore(checkpoint);
            if (checkpoint != saved) { supervisor.persist(); }
        });
        return supervisor;
    }

    /** Starts at the first piece and re-checks every piece in schedule order. */
    public synchronized void start() {
        beginRun(CompletedPieces.none());
    }

    /** Why a start would be refused now, or blank; lets a caller check before a longer preparation. */
    public synchronized String startProblem() {
        if (state != SupervisorState.STOPPED) {
            return "start is allowed only from STOPPED";
        }
        if (ports.execution().pendingPlannedCredit().isPresent()) {
            return "Settle the saved planned credit before starting a new ledger";
        }
        return requiresReconciliation() ? "start is blocked until reconciliation: " + settlementDetail() : "";
    }

    /**
     * Starts at the first piece the build check found unfinished. Pieces it found finished are
     * skipped; final verification still checks every chunk.
     */
    public synchronized void start(BuildCheck.Result check) {
        Objects.requireNonNull(check, "check");
        if (!plan.planId().equals(check.planId()) || !schedule.id().equals(check.scheduleId())
                || check.pieceCount() != schedule.size()) {
            throw new IllegalArgumentException("The build check belongs to a different plan or schedule");
        }
        beginRun(check.completePieces());
    }

    private void beginRun(CompletedPieces finished) {
        if (state != SupervisorState.STOPPED) {
            throw new IllegalStateException("start is allowed only from STOPPED");
        }
        if (ports.execution().pendingPlannedCredit().isPresent()) {
            throw new IllegalStateException("Settle the saved planned credit before starting a new ledger");
        }
        requireReconciled("start");
        currentChunkIndex = 0;
        checkedPieces = finished;
        scheduleCursor = finished.nextIncomplete(0, schedule.size());
        repairChunkIndex = -1;
        tillStructureRepair = null;
        phase = BuildPhase.ORDINARY_BLOCKS;
        verificationStage = VerificationStage.CHUNK;
        recoveryStage = RecoveryStage.NONE;
        stableVerificationPasses = 0;
        lastVerificationFingerprint = "";
        consumedMaterials = MaterialQuantities.empty();
        lastAppliedPlannedCredit = null;
        withdrawnMaterials = MaterialQuantities.empty();
        missingMaterials = MaterialQuantities.empty();
        restockRequirement = MaterialQuantities.empty();
        lastError = "";
        verificationRetries = 0;
        resetRecoveryAttempts();
        commandStarted = false;
        lastProgressMarker = 0;
        lastProgressAt = ports.clock().instant();
        state = SupervisorState.BUILDING;
        resumeState = SupervisorState.BUILDING;
        restockResumeState = SupervisorState.BUILDING;
        skipEmptyWorkPhases();
        persist();
    }

    public synchronized void pause() {
        if (state == SupervisorState.PAUSED
                || state == SupervisorState.STOPPED
                || state == SupervisorState.DONE) {
            pollExecutionSettlement(true);
            return;
        }
        cancelAsyncOperations();
        resumeState = state == SupervisorState.STUCK ? activeStateForPhase() : state;
        stopMovementForTransition("pausing");
        state = SupervisorState.PAUSED;
        recoveryStage = RecoveryStage.NONE;
        commandStarted = false;
        persist();
    }

    public synchronized void resume() {
        if (state != SupervisorState.PAUSED) {
            throw new IllegalStateException("resume is allowed only from PAUSED");
        }
        requireReconciled("resume");
        state = resumeState == SupervisorState.PAUSED ? activeStateForPhase() : resumeState;
        if (state == SupervisorState.RESTOCKING) {
            missingMaterials = restockRequirement.shortageFrom(ports.inventory().snapshot());
            unreachableDepotSkips = 0;
        }
        commandStarted = false;
        lastProgressAt = ports.clock().instant();
        lastProgressMarker = 0;
        persist();
    }

    /** A settled executor may yield for inventory cleanup, then restart the same slice without progress credit. */
    public synchronized boolean requestInventoryMaintenance() {
        if (state != SupervisorState.BUILDING || withdrawalStarted || cancellationSettlementPending
                || cancellationSettlementFailed || executionSettlementPending || requiresReconciliation()
                || ports.execution().pendingPlannedCredit().isPresent()) { return false; }
        stopMovementForTransition("inventory maintenance");
        if (executionSettlementPending || cancellationSettlementFailed || requiresReconciliation()) {
            resumeState = SupervisorState.BUILDING; state = SupervisorState.PAUSED; commandStarted = false;
            persist();
            return false;
        }
        commandStarted = false;
        enterRestocking(MaterialQuantities.empty(), SupervisorState.BUILDING);
        return true;
    }

    /** Accepts a smaller observed Dirt batch when the adapter reports that another refill cannot fit. */
    public synchronized boolean acceptAvailableDirtBatch() {
        boolean restocking = state == SupervisorState.RESTOCKING
                || (state == SupervisorState.PAUSED && resumeState == SupervisorState.RESTOCKING);
        if (!restocking || withdrawalStarted || cancellationSettlementPending || requiresReconciliation()) {
            return false;
        }
        MaterialQuantities available = ports.inventory().snapshot();
        long dirt = available.get(Material.DIRT);
        if (dirt < 1 || dirt >= restockRequirement.get(Material.DIRT)) { return false; }
        TreeMap<Material, Long> reduced = new TreeMap<>();
        reduced.putAll(restockRequirement.asMap());
        reduced.put(Material.DIRT, dirt);
        restockRequirement = MaterialQuantities.of(reduced);
        missingMaterials = restockRequirement.shortageFrom(available);
        // Restart the same slice after restocking so the executor cannot keep waiting for
        // the old batch. Its fresh world scan retains completed cells and requests future shortages.
        commandStarted = false;
        persist();
        return true;
    }

    /** Refreshes only a settled, checkpoint-bound Glowstone request before its first withdrawal. */
    public synchronized boolean refreshGlowstoneRestockBatch(SupervisorCheckpoint expected, long targetAvailable) {
        if (expected == null || !expected.equals(checkpoint()) || withdrawalStarted
                || cancellationSettlementPending || cancellationSettlementFailed || executionSettlementPending
                || requiresReconciliation()
                || ports.execution().pendingPlannedCredit().isPresent()) { return false; }
        MaterialQuantities available = ports.inventory().snapshot();
        long limit = LightingRestockReserve.refreshLimit(plan, schedule, expected, available);
        long previous = restockRequirement.get(Material.GLOWSTONE);
        if (targetAvailable <= previous || targetAvailable > limit) { return false; }
        long observedStock = 0;
        for (DepotStock depot : ports.depots().snapshot()) {
            observedStock = Math.min(LightingRestockReserve.STACK_SIZE,
                    observedStock + Math.min(LightingRestockReserve.STACK_SIZE, depot.available().get(Material.GLOWSTONE)));
        }
        if (targetAvailable > observedStock) { return false; }
        TreeMap<Material, Long> refreshed = new TreeMap<>();
        refreshed.putAll(restockRequirement.asMap());
        refreshed.put(Material.GLOWSTONE, targetAvailable);
        MaterialQuantities beforeRequirement = restockRequirement;
        MaterialQuantities beforeMissing = missingMaterials;
        restockRequirement = MaterialQuantities.of(refreshed);
        missingMaterials = restockRequirement.shortageFrom(available);
        try { persist(); }
        catch (RuntimeException failure) {
            restockRequirement = beforeRequirement;
            missingMaterials = beforeMissing;
            throw failure;
        }
        return true;
    }

    public synchronized void stop() {
        cancelAsyncOperations();
        if (state != SupervisorState.STOPPED) {
            stopMovementForTransition("stopping");
        } else {
            pollExecutionSettlement(false);
        }
        state = SupervisorState.STOPPED;
        recoveryStage = RecoveryStage.NONE;
        commandStarted = false;
        persist();
    }

    public synchronized void tick() {
        settlePlannedCredit();
        if (executionSettlementPending) {
            pollExecutionSettlement(true);
        }
        if (cancellationSettlementPending) {
            pollCancellationSettlement(true);
            return;
        }
        if (requiresReconciliation()) {
            return;
        }
        switch (state) {
            case BUILDING -> tickBuilding();
            case RESTOCKING -> tickRestocking();
            case VERIFYING -> tickVerifying();
            case STUCK -> tickRecovery();
            case STOPPED, PAUSED, DONE -> {
                // Intentionally inert.
            }
        }
    }

    public synchronized SupervisorStatus status() {
        ChunkCoordinate currentChunk = currentChunkIndex < plan.chunkCount()
                ? plan.chunk(currentChunkIndex).chunk()
                : null;
        int ordinal = currentChunk == null ? 0 : currentChunkIndex + 1;
        MaterialLedgerSnapshot ledger = MaterialLedgerSnapshot.calculate(
                plannedMaterials,
                consumedMaterials,
                withdrawnMaterials
        );
        return new SupervisorStatus(
                state,
                phase,
                verificationStage,
                ordinal,
                currentChunk,
                recoveryStage,
                stableVerificationPasses,
                ledger,
                ports.inventory().snapshot(),
                missingMaterials,
                statusError(),
                scheduleCursor < schedule.size()
                        ? schedule.entry(scheduleCursor).progress()
                        : schedule.completedProgress(state == SupervisorState.DONE),
                plan.chunkCount(),
                plan.plantingDeferred(),
                plan.deferredSeedCells(),
                plan.glowstoneAfterStructure()
        );
    }

    public synchronized ScheduleProgress.Key progressKey() {
        return new ScheduleProgress.Key(plan.planId(), schedule.id(), scheduleCursor, repairChunkIndex,
                checkedPieces);
    }

    public synchronized ScheduleProgress progress() {
        if (progressIndex == null) {
            progressIndex = ScheduleProgress.index(plan, schedule);
        }
        return ScheduleProgress.of(progressIndex, scheduleCursor, repairChunkIndex, checkedPieces);
    }

    /** Wall-clock time of the last confirmed work; pause, resume, and restore leave it unchanged. */
    public synchronized Instant lastConfirmedProgressAt() {
        return lastConfirmedProgressAt;
    }

    public synchronized boolean requiresReconciliation() {
        return reconciliationRequired || executionSettlementPending;
    }

    /** Persists unresolved interactions before the client loses their acknowledgement context. */
    public synchronized void markClientStopping() {
        SupervisorCheckpoint before = checkpoint();
        pollExecutionSettlement(false);
        if (executionSettlementPending) {
            executionSettlementPending = false;
            failExecutionSettlement(
                    "The client stopped before a build interaction settled"
            );
        }
        if (cancellationSettlementPending) {
            cancellationSettlementPending = false;
            cancellationSettlementFailed = true;
            cancellationRemaining = MaterialQuantities.empty();
            String previous = reconciliationRequired ? reconciliationDetail : "";
            reconciliationRequired = true;
            reconciliationDetail =
                    "The client stopped before a cancelled depot transfer settled. "
                            + "Reset is required before starting or resuming."
                            + (previous.isBlank() ? "" : " Existing reconciliation issue: " + previous);
        }
        // The final poll can drain consumption or discover an uncertainty even while already paused.
        if (!before.equals(checkpoint())) {
            persist();
        }
    }

    public synchronized SupervisorCheckpoint checkpoint() {
        return new SupervisorCheckpoint(
                SupervisorCheckpoint.CURRENT_VERSION,
                plan.planId(),
                state,
                resumeState,
                restockResumeState,
                currentChunkIndex,
                phase,
                verificationStage,
                recoveryStage.persisted(),
                stableVerificationPasses,
                lastVerificationFingerprint,
                consumedMaterials,
                withdrawnMaterials,
                missingMaterials,
                restockRequirement,
                lastError,
                verificationRetries,
                repathAttempted,
                safeReturnAttempted,
                advisorAttempted,
                withdrawalStarted || cancellationSettlementPending,
                requiresReconciliation(),
                settlementDetail(),
                schedule.id(),
                scheduleCursor,
                repairChunkIndex,
                plan.chunkCount(),
                plan.plantingDeferred(),
                lastAppliedPlannedCredit,
                checkedPieces
        );
    }

    private void tickBuilding() {
        skipEmptyWorkPhases();
        if (state != SupervisorState.BUILDING) {
            return;
        }

        WorkOrder order = currentWorkOrder();
        if (!commandStarted) {
            MaterialQuantities startRequirement = startRequirement(order);
            RestockDecision decision = RestockPlanner.plan(
                    startRequirement,
                    ports.inventory().snapshot(),
                    ports.depots().snapshot()
            );
            if (!decision.ready()) {
                enterRestocking(startRequirement, SupervisorState.BUILDING);
                return;
            }
            ports.execution().start(order);
            commandStarted = true;
            lastProgressMarker = 0;
            progressBaselinePending = false;
            lastProgressAt = ports.clock().instant();
            executionWaitAt = null;
            persist();
            return;
        }

        ExecutionSnapshot execution = ports.execution().poll();
        Instant now = ports.clock().instant();
        if (executionWaitAt != null && !now.isBefore(executionWaitAt)) {
            lastProgressAt = lastProgressAt.plus(Duration.between(executionWaitAt, now));
        }
        executionWaitAt = execution.status() == ExecutionStatus.WAITING_FOR_SCREEN
                || execution.status() == ExecutionStatus.WAITING_FOR_MAINTENANCE ? now : null;
        if (!execution.consumedDelta().isEmpty()) {
            consumedMaterials = consumedMaterials.plus(execution.consumedDelta());
            renewRecoveryAfterConfirmedConsumption();
        }
        boolean markerMoved = execution.progressMarker() != lastProgressMarker;
        if (markerMoved || !execution.consumedDelta().isEmpty()) {
            lastProgressMarker = execution.progressMarker();
            lastProgressAt = ports.clock().instant();
        }
        if (!execution.consumedDelta().isEmpty() || (markerMoved && !progressBaselinePending)) {
            lastConfirmedProgressAt = ports.clock().instant();
        }
        progressBaselinePending = false;
        // poll() has already drained ordinary receipts. Retain their delta in memory even
        // when a newly exposed durable credit cannot yet be saved or acknowledged.
        settlePlannedCredit();

        switch (execution.status()) {
            case WAITING_FOR_SCREEN, WAITING_FOR_MAINTENANCE -> {
                // Screen and maintenance waits are neither failed construction nor new progress.
                // Preserve the remaining stall budget and retain receipts settled while waiting.
                if (!execution.consumedDelta().isEmpty()) { persist(); }
            }
            case RUNNING -> {
                if (Duration.between(lastProgressAt, ports.clock().instant())
                        .compareTo(config.stallTimeout()) >= 0) {
                    beginRecovery("No build progress for " + config.stallTimeout().toSeconds() + " seconds");
                } else if (!execution.consumedDelta().isEmpty()) {
                    persist();
                }
            }
            case NEEDS_MATERIALS -> {
                if (execution.requestedAvailable().isEmpty()) {
                    beginRecovery("Executor requested restocking without an exact material requirement");
                } else {
                    enterRestocking(execution.requestedAvailable(), SupervisorState.BUILDING);
                }
            }
            case SUCCEEDED -> {
                commandStarted = false;
                if (tillStructureRepair != null) {
                    tillStructureRepair = null;
                    persist();
                } else {
                    advanceWorkPhase();
                }
            }
            case NEEDS_PREREQUISITES -> {
                if (!(order instanceof WorkOrder.Till till)) {
                    beginRecovery("Prerequisite repair requested outside a till order");
                    break;
                }
                var targets = new java.util.HashSet<>(till.targets());
                var placements = plan.chunk(till.chunkIndex()).ordinaryPlacements().stream()
                        .filter(placement -> targets.contains(placement.position())
                                && placement.material() == Material.DIRT).toList();
                if (placements.size() != targets.size()) {
                    beginRecovery("Till prerequisite repair has no complete planned dirt coverage");
                    break;
                }
                ports.execution().stopMovement();
                tillStructureRepair = new WorkOrder.OrdinaryBlocks(till.chunkIndex(), till.chunk(), placements);
                commandStarted = false;
                lastError = execution.detail();
                persist();
            }
            case FAILED -> beginRecovery(execution.detail().isBlank()
                    ? "Build executor reported failure"
                    : execution.detail());
            case IDLE -> beginRecovery("Build executor became idle before completing its order");
        }
    }

    private void tickRestocking() {
        if (withdrawalStarted) {
            RestockTransferSnapshot transfer = Objects.requireNonNull(
                    ports.depots().pollWithdrawal(),
                    "depot port returned null transfer status"
            );
            MaterialQuantities excess = transfer.movedDelta().minusFloorZero(activeWithdrawalRemaining);
            activeWithdrawalRemaining = activeWithdrawalRemaining.minusFloorZero(
                    transfer.movedDelta()
            );
            withdrawnMaterials = withdrawnMaterials.plus(transfer.movedDelta());
            if (!excess.isEmpty()) {
                requireReset(
                        "Depot adapter moved more than the active withdrawal allowed: "
                                + excess
                );
                safePause(
                        "Depot adapter moved more than the active withdrawal allowed",
                        missingMaterials,
                        SupervisorState.RESTOCKING
                );
                return;
            }
            switch (transfer.status()) {
                case RUNNING -> {
                    if (!transfer.movedDelta().isEmpty()) {
                        persist();
                    }
                }
                case SUCCEEDED, CAPACITY_BLOCKED -> {
                    withdrawalStarted = false;
                    activeWithdrawalRemaining = MaterialQuantities.empty();
                    persist();
                }
                case FAILED -> {
                    withdrawalStarted = false;
                    activeWithdrawalRemaining = MaterialQuantities.empty();
                    safePause(
                            transfer.detail().isBlank()
                                    ? "Depot withdrawal failed"
                                    : "Depot withdrawal failed: " + transfer.detail(),
                            missingMaterials,
                            SupervisorState.RESTOCKING
                    );
                }
                case UNREACHABLE -> {
                    withdrawalStarted = false;
                    activeWithdrawalRemaining = MaterialQuantities.empty();
                    if (unreachableDepotSkips >= MAXIMUM_UNREACHABLE_DEPOT_SKIPS) {
                        safePause("Depot withdrawal failed: " + transfer.detail(), missingMaterials,
                                SupervisorState.RESTOCKING);
                        return;
                    }
                    // Nothing moved at that depot and its stock no longer counts, so the next tick
                    // plans the remaining shortage from the other scanned depots.
                    unreachableDepotSkips++;
                    lastError = "Skipped an unreachable registered depot: " + transfer.detail();
                    persist();
                }
                case IDLE -> safePause(
                        "Depot withdrawal became idle before completion",
                        missingMaterials,
                        SupervisorState.RESTOCKING
                );
            }
            return;
        }

        RestockDecision decision = RestockPlanner.plan(
                restockRequirement,
                ports.inventory().snapshot(),
                ports.depots().snapshot()
        );
        missingMaterials = decision.missing();
        if (decision.ready()) {
            missingMaterials = MaterialQuantities.empty();
            restockRequirement = MaterialQuantities.empty();
            unreachableDepotSkips = 0;
            state = restockResumeState;
            lastProgressAt = ports.clock().instant();
            persist();
            return;
        }
        if (decision.canWithdraw()) {
            withdrawalStarted = true;
            activeWithdrawalRemaining = decision.totalWithdrawal();
            // Persist the uncertainty marker before an adapter can start moving items.
            // A crash during beginWithdrawal must never restore as a settled transfer.
            try {
                persist();
            } catch (RuntimeException exception) {
                withdrawalStarted = false;
                activeWithdrawalRemaining = MaterialQuantities.empty();
                throw exception;
            }
            ports.depots().beginWithdrawal(decision.withdrawals());
            return;
        }
        safePause(
                "Registered depots cannot satisfy the exact material shortage: " + decision.missing(),
                decision.missing(),
                SupervisorState.RESTOCKING
        );
    }

    private void tickVerifying() {
        VerificationScope scope = verificationStage == VerificationStage.CHUNK
                ? VerificationScope.chunk(currentChunkIndex)
                : VerificationScope.fullPlan();
        if (!verificationStarted) {
            ports.verification().beginVerification(plan, scope);
            verificationStarted = true;
            persist();
            return;
        }
        VerificationTaskSnapshot task = Objects.requireNonNull(
                ports.verification().pollVerification(),
                "verification port returned null status"
        );
        if (task.status() == VerificationTaskStatus.RUNNING) {
            return;
        }
        if (task.status() == VerificationTaskStatus.FAILED) {
            verificationStarted = false;
            safePause(
                    task.detail().isBlank()
                            ? "Verification task failed"
                            : "Verification task failed: " + task.detail(),
                    MaterialQuantities.empty(),
                    SupervisorState.VERIFYING
            );
            return;
        }
        if (task.status() == VerificationTaskStatus.IDLE) {
            verificationStarted = false;
            safePause(
                    "Verification task became idle before completion",
                    MaterialQuantities.empty(),
                    SupervisorState.VERIFYING
            );
            return;
        }
        VerificationResult result = task.result().orElseThrow();
        verificationStarted = false;

        if (verificationStage == VerificationStage.CHUNK) {
            if (result.clean()) {
                advanceChunk();
                return;
            }
            lastError = "Chunk verification failed: " + result.conciseSummary();
            if (verificationRetries == 0) {
                verificationRetries = 1;
                beginChunkRepair();
            } else {
                beginRecovery("Chunk verification failed after deterministic retry: "
                        + result.conciseSummary());
            }
            return;
        }

        if (!result.clean()) {
            safePause(
                    "Final verification failed; completion was not recorded: " + result.conciseSummary(),
                    MaterialQuantities.empty(),
                    SupervisorState.VERIFYING
            );
            return;
        }

        if (result.normalizedFingerprint().equals(lastVerificationFingerprint)) {
            stableVerificationPasses++;
        } else {
            lastVerificationFingerprint = result.normalizedFingerprint();
            stableVerificationPasses = 1;
        }
        if (stableVerificationPasses >= 2) {
            stableVerificationPasses = 2;
            state = SupervisorState.DONE;
            recoveryStage = RecoveryStage.NONE;
            commandStarted = false;
        }
        persist();
    }

    private void tickRecovery() {
        switch (recoveryStage) {
            case STOP_MOVEMENT -> {
                ports.execution().stopMovement();
                // Before any lag wait: a player whose flight dropped mid-air is still falling.
                if (!flightRestoreAttempted && ports.execution().flightRestorable()) {
                    recoveryStage = RecoveryStage.RESTORE_FLIGHT;
                } else {
                    continueAfterMovementStopped();
                }
                persist();
            }
            case RESTORE_FLIGHT -> beginFlightRestore();
            case WAIT_FOR_FLIGHT -> pollFlightRestore();
            case RETRY_WAIT -> {
                if (!ports.clock().instant().isBefore(waitUntil)) {
                    retryAfterTransientFailure();
                }
            }
            case WAIT_FOR_LAG -> {
                if (!ports.clock().instant().isBefore(waitUntil)) {
                    recoveryStage = RecoveryStage.CHECK_MATERIALS;
                    persist();
                }
            }
            case CHECK_MATERIALS -> {
                MaterialQuantities required = currentRecoveryRequirement();
                RestockDecision decision = RestockPlanner.plan(
                        required,
                        ports.inventory().snapshot(),
                        ports.depots().snapshot()
                );
                if (!decision.ready()) {
                    enterRestocking(required, SupervisorState.STUCK);
                } else if (!repathAttempted) {
                    recoveryStage = RecoveryStage.REPATH;
                    persist();
                } else if (!safeReturnAttempted) {
                    recoveryStage = RecoveryStage.RETURN_TO_SAFE_POSITION;
                    persist();
                } else {
                    recoveryStage = RecoveryStage.ASK_ADVISOR;
                    persist();
                }
            }
            case REPATH -> {
                ports.execution().cancelCurrentPath();
                repathAttempted = true;
                if (ports.execution().restartCurrentPath()) {
                    resumeFromRecovery(true);
                } else {
                    recoveryStage = RecoveryStage.RETURN_TO_SAFE_POSITION;
                    persist();
                }
            }
            case RETURN_TO_SAFE_POSITION -> {
                safeReturnAttempted = true;
                ports.execution().beginReturnToLastSafePosition();
                waitUntil = ports.clock().instant().plus(config.stallTimeout());
                safeReturnScreenWaitAt = null;
                recoveryStage = RecoveryStage.WAIT_FOR_SAFE_POSITION;
                persist();
            }
            case WAIT_FOR_SAFE_POSITION -> pollSafeReturn();
            case ASK_ADVISOR -> askAdvisor();
            case WAIT_FOR_ADVISOR -> pollAdvisor();
            case ADVISOR_WAIT -> {
                if (!ports.clock().instant().isBefore(waitUntil)) {
                    if (phase == BuildPhase.VERIFY) {
                        resumeFromRecovery(false);
                    } else if (ports.execution().restartCurrentPath()) {
                        resumeFromRecovery(true);
                    } else {
                        commandStarted = false;
                        resumeFromRecovery(false);
                    }
                }
            }
            case NONE -> safePause(
                    "Recovery entered STUCK without a recovery stage",
                    MaterialQuantities.empty(),
                    activeStateForPhase()
            );
        }
    }

    private void continueAfterMovementStopped() {
        if (ports.serverHealth().appearsLaggy()) {
            waitUntil = ports.clock().instant().plus(config.lagWait());
            recoveryStage = RecoveryStage.WAIT_FOR_LAG;
        } else {
            recoveryStage = RecoveryStage.CHECK_MATERIALS;
        }
    }

    private void beginFlightRestore() {
        // One takeoff per incident: confirmed placement or planting renews it, a second landing does not.
        flightRestoreAttempted = true;
        try {
            ports.execution().beginFlightRestore();
        } catch (RuntimeException exception) {
            lastError = recoveryError("Flight could not be restored: " + safeMessage(exception));
            continueAfterMovementStopped();
            persist();
            return;
        }
        waitUntil = ports.clock().instant().plus(SupervisorConfig.FLIGHT_RESTORE_TIMEOUT);
        recoveryStage = RecoveryStage.WAIT_FOR_FLIGHT;
        persist();
    }

    private void pollFlightRestore() {
        FlightRestoreSnapshot result = Objects.requireNonNull(
                ports.execution().pollFlightRestore(),
                "execution port returned null flight-restore status"
        );
        switch (result.status()) {
            case RUNNING -> {
                if (ports.clock().instant().isBefore(waitUntil)) {
                    return;
                }
                ports.execution().cancelFlightRestore();
                lastError = recoveryError("Flight restore timed out");
                continueAfterMovementStopped();
            }
            case SUCCEEDED -> {
                lastError = recoveryError("Flight was restored automatically");
                // A failed order cannot restart its own route, so the same slice starts again from here.
                resumeFromRecovery(false);
                return;
            }
            case FAILED, IDLE -> {
                lastError = recoveryError(result.detail().isBlank()
                        ? "Flight could not be restored"
                        : "Flight could not be restored: " + result.detail());
                continueAfterMovementStopped();
            }
        }
        persist();
    }

    /**
     * Pauses at the end of recovery unless its cause is transient and has automatic retries left. The
     * retry waits, then starts the same slice again with fresh deterministic attempts.
     */
    private void pauseAfterRecovery(String error) {
        int limit = config.transientRetryDelays().size();
        if (!recoveryTransient || transientRetries >= limit) {
            safePause(error, missingMaterials, activeStateForPhase());
            return;
        }
        Duration delay = config.transientRetryDelays().get(transientRetries);
        transientRetries++;
        lastError = recoveryError(error + "; retrying automatically in " + delay.toSeconds() + " s ("
                + transientRetries + " of " + limit + ")");
        waitUntil = ports.clock().instant().plus(delay);
        recoveryStage = RecoveryStage.RETRY_WAIT;
        persist();
    }

    private void retryAfterTransientFailure() {
        repathAttempted = false;
        safeReturnAttempted = false;
        advisorAttempted = false;
        if (phase != BuildPhase.VERIFY && ports.execution().restartCurrentPath()) {
            resumeFromRecovery(true);
        } else {
            resumeFromRecovery(false);
        }
    }

    private void askAdvisor() {
        if (advisorAttempted) {
            pauseAfterRecovery("Deterministic recovery and the constrained advisor were exhausted");
            return;
        }
        long stalledSeconds = Math.max(
                0,
                Duration.between(recoveryStartedAt, ports.clock().instant()).toSeconds()
        );
        RecoveryIncident incident = new RecoveryIncident(
                plan.planId(),
                Math.min(currentChunkIndex + 1, plan.chunkCount()),
                phase,
                stalledSeconds,
                ports.serverHealth().appearsLaggy(),
                missingMaterials,
                lastError,
                repathAttempted,
                safeReturnAttempted,
                plan.chunkCount()
        );
        try {
            ports.advisor().beginAdvice(incident);
        } catch (RuntimeException exception) {
            pauseAfterRecovery("Advisor unavailable after deterministic recovery: " + safeMessage(exception));
            return;
        }
        advisorAttempted = true;
        waitUntil = ports.clock().instant().plus(config.advisorTimeout());
        recoveryStage = RecoveryStage.WAIT_FOR_ADVISOR;
        persist();
    }

    private void pollAdvisor() {
        if (!ports.clock().instant().isBefore(waitUntil)) {
            ports.advisor().cancelAdvice();
            pauseAfterRecovery("Advisor timed out after " + config.advisorTimeout().toSeconds() + " seconds");
            return;
        }
        AdviceSnapshot result;
        try {
            result = Objects.requireNonNull(ports.advisor().pollAdvice(), "advisor returned null status");
        } catch (RuntimeException exception) {
            pauseAfterRecovery("Advisor returned an invalid result: " + safeMessage(exception));
            return;
        }
        switch (result.status()) {
            case PENDING -> {
                // Poll again on a later client tick.
            }
            case SUCCEEDED -> applyAdvice(result.advice().orElseThrow());
            case UNAVAILABLE -> pauseAfterRecovery(result.detail().isBlank()
                    ? "Advisor unavailable after deterministic recovery"
                    : "Advisor unavailable after deterministic recovery: " + result.detail());
            case FAILED -> pauseAfterRecovery(result.detail().isBlank()
                    ? "Advisor failed after deterministic recovery"
                    : "Advisor failed after deterministic recovery: " + result.detail());
            case IDLE -> pauseAfterRecovery("Advisor became idle before returning advice");
        }
    }

    private void pollSafeReturn() {
        SafeReturnSnapshot result = Objects.requireNonNull(
                ports.execution().pollSafeReturn(),
                "execution port returned null safe-return status"
        );
        Instant now = ports.clock().instant();
        if (safeReturnScreenWaitAt != null && !now.isBefore(safeReturnScreenWaitAt)) {
            waitUntil = waitUntil.plus(Duration.between(safeReturnScreenWaitAt, now));
        }
        safeReturnScreenWaitAt = result.status() == SafeReturnStatus.WAITING_FOR_SCREEN ? now : null;
        if (result.status() == SafeReturnStatus.WAITING_FOR_SCREEN) { return; }
        if (!now.isBefore(waitUntil)) {
            ports.execution().cancelSafeReturn();
            recoveryStage = RecoveryStage.ASK_ADVISOR;
            lastError = recoveryError("Return to the last safe position timed out");
            persist();
            return;
        }
        switch (result.status()) {
            case RUNNING -> {
                // Poll again on a later client tick.
            }
            case SUCCEEDED -> {
                if (ports.execution().restartCurrentPath()) {
                    resumeFromRecovery(true);
                } else {
                    recoveryStage = RecoveryStage.ASK_ADVISOR;
                    persist();
                }
            }
            case FAILED -> {
                if (!result.detail().isBlank()) {
                    lastError = recoveryError("Return to safe position failed: " + result.detail());
                }
                recoveryStage = RecoveryStage.ASK_ADVISOR;
                persist();
            }
            case IDLE -> {
                lastError = recoveryError("Return to safe position became idle before arrival");
                recoveryStage = RecoveryStage.ASK_ADVISOR;
                persist();
            }
        }
    }

    private void applyAdvice(RecoveryAdvice advice) {
        switch (advice) {
            case WAIT -> {
                waitUntil = ports.clock().instant().plus(config.lagWait());
                recoveryStage = RecoveryStage.ADVISOR_WAIT;
                persist();
            }
            case REPATH -> {
                ports.execution().cancelCurrentPath();
                if (ports.execution().restartCurrentPath()) {
                    resumeFromRecovery(true);
                } else {
                    safePause("Advisor-requested repath failed", missingMaterials, activeStateForPhase());
                }
            }
            case RESTOCK -> {
                recoveryStage = RecoveryStage.NONE;
                enterRestocking(currentRecoveryRequirement(), activeStateForPhase());
            }
            case RETRY_CHUNK -> {
                ports.execution().cancelCurrentPath();
                commandStarted = false;
                recoveryStage = RecoveryStage.NONE;
                repathAttempted = false;
                safeReturnAttempted = false;
                if (phase == BuildPhase.VERIFY) {
                    if (verificationStage == VerificationStage.CHUNK) {
                        beginChunkRepair();
                    } else {
                        safePause("Final verification requires inspection before a build retry",
                                missingMaterials, SupervisorState.VERIFYING);
                    }
                } else {
                    // Retry exactly this layer/chunk slice; earlier layers stay complete.
                    state = SupervisorState.BUILDING;
                    persist();
                }
            }
            case RETURN_TO_SAFE_POSITION -> {
                ports.execution().beginReturnToLastSafePosition();
                safeReturnAttempted = true;
                waitUntil = ports.clock().instant().plus(config.stallTimeout());
                safeReturnScreenWaitAt = null;
                recoveryStage = RecoveryStage.WAIT_FOR_SAFE_POSITION;
                persist();
            }
            case PAUSE_AND_ALERT -> safePause(
                    "Advisor requested a safe pause after deterministic recovery failed",
                    missingMaterials,
                    activeStateForPhase()
            );
        }
    }

    private void enterRestocking(
            MaterialQuantities requiredAvailable,
            SupervisorState resumeAfterRestock
    ) {
        restockRequirement = enabledReserveRequirement(requiredAvailable);
        restockResumeState = resumeAfterRestock;
        missingMaterials = restockRequirement.shortageFrom(ports.inventory().snapshot());
        withdrawalStarted = false;
        activeWithdrawalRemaining = MaterialQuantities.empty();
        unreachableDepotSkips = 0;
        state = SupervisorState.RESTOCKING;
        persist();
    }

    private MaterialQuantities enabledReserveRequirement(MaterialQuantities requirement) {
        boolean omitFood = config.minimumFood() == 0 && requirement.get(Material.FOOD) > 0;
        boolean omitSeeds = plan.plantingDeferred() && requirement.get(Material.WHEAT_SEEDS) > 0;
        if (!omitFood && !omitSeeds) {
            return requirement;
        }
        // Saved shortages and executor requests must obey the active construction mode too.
        TreeMap<Material, Long> enabled = new TreeMap<>();
        enabled.putAll(requirement.asMap());
        if (omitFood) { enabled.remove(Material.FOOD); }
        if (omitSeeds) { enabled.remove(Material.WHEAT_SEEDS); }
        return MaterialQuantities.of(enabled);
    }

    private void beginRecovery(String error) {
        executionWaitAt = null;
        lastError = error;
        recoveryTransient = RecoveryClassifier.transientFailure(error);
        state = SupervisorState.STUCK;
        recoveryStage = RecoveryStage.STOP_MOVEMENT;
        recoveryStartedAt = ports.clock().instant();
        restockResumeState = SupervisorState.STUCK;
        persist();
    }

    private void resumeFromRecovery(boolean existingCommandRunning) {
        executionWaitAt = null;
        safeReturnScreenWaitAt = null;
        state = activeStateForPhase();
        recoveryStage = RecoveryStage.NONE;
        commandStarted = existingCommandRunning && phase != BuildPhase.VERIFY;
        lastProgressMarker = 0;
        // The executor keeps its own marker for a command that continues; re-read it before counting.
        progressBaselinePending = commandStarted;
        lastProgressAt = ports.clock().instant();
        persist();
    }

    private void advanceWorkPhase() {
        resetRecoveryAttempts();
        scheduleCursor = repairChunkIndex >= 0
                ? schedule.nextForChunk(scheduleCursor + 1, repairChunkIndex)
                : checkedPieces.nextIncomplete(scheduleCursor + 1, schedule.size());
        skipEmptyWorkPhases();
        persist();
    }

    private void advanceChunk() {
        currentChunkIndex++;
        verificationRetries = 0;
        repairChunkIndex = -1;
        resetRecoveryAttempts();
        commandStarted = false;
        verificationStarted = false;
        phase = BuildPhase.VERIFY;
        state = SupervisorState.VERIFYING;
        if (currentChunkIndex == plan.chunkCount()) {
            verificationStage = VerificationStage.FINAL;
            stableVerificationPasses = 0;
            lastVerificationFingerprint = "";
        }
        persist();
    }

    private void beginChunkRepair() {
        tillStructureRepair = null;
        repairChunkIndex = currentChunkIndex;
        scheduleCursor = schedule.nextForChunk(0, repairChunkIndex);
        if (scheduleCursor == schedule.size()) {
            safePause("The dirty chunk has no scheduled build targets; inspect it before resuming",
                    MaterialQuantities.empty(), SupervisorState.VERIFYING);
            return;
        }
        state = SupervisorState.BUILDING;
        commandStarted = false;
        verificationStarted = false;
        resetRecoveryAttempts();
        skipEmptyWorkPhases();
        persist();
    }

    private void skipEmptyWorkPhases() {
        if (state != SupervisorState.BUILDING) { return; }
        if (scheduleCursor < schedule.size()) {
            WorkOrder order = schedule.entry(scheduleCursor).order();
            currentChunkIndex = order.chunkIndex();
            phase = order.phase();
            return;
        }
        // Full-height chunk scans are meaningful only after every layer is built.
        currentChunkIndex = repairChunkIndex >= 0 ? repairChunkIndex : 0;
        // Every piece now counts as done; a later chunk repair must re-walk its chunk in full.
        checkedPieces = CompletedPieces.none();
        phase = BuildPhase.VERIFY;
        state = SupervisorState.VERIFYING;
        verificationStage = VerificationStage.CHUNK;
        verificationStarted = false;
    }

    private WorkOrder currentWorkOrder() {
        if (phase == BuildPhase.VERIFY || scheduleCursor >= schedule.size()) {
            throw new IllegalStateException("verification has no execution work order");
        }
        return tillStructureRepair != null ? tillStructureRepair : schedule.entry(scheduleCursor).order();
    }

    private MaterialQuantities startRequirement(WorkOrder order) {
        TreeMap<Material, Long> requirement = new TreeMap<>();
        switch (order) {
            case WorkOrder.OrdinaryBlocks ignored -> {
                // The execution adapter requests a capacity-aware block batch after its
                // baseline scan. Preloading one item here would cause a separate depot trip.
            }
            case WorkOrder.Till ignored -> {
                if (config.minimumHoes() > 0) {
                    requirement.put(Material.HOE, config.minimumHoes());
                }
            }
            case WorkOrder.Plant ignored -> {
                // Seed batches are requested by the executor after already-planted targets
                // have been removed from the remaining order.
            }
        }
        if (config.minimumFood() > 0) {
            requirement.put(Material.FOOD, config.minimumFood());
        }
        return MaterialQuantities.of(requirement);
    }

    private MaterialQuantities currentRecoveryRequirement() {
        if (!restockRequirement.isEmpty()) {
            return restockRequirement;
        }
        if (phase == BuildPhase.VERIFY || currentChunkIndex >= plan.chunkCount()) {
            return MaterialQuantities.empty();
        }
        return startRequirement(currentWorkOrder());
    }

    private SupervisorState activeStateForPhase() {
        return phase == BuildPhase.VERIFY ? SupervisorState.VERIFYING : SupervisorState.BUILDING;
    }

    private void stopMovementForTransition(String action) {
        try {
            ports.execution().stopMovement();
        } catch (RuntimeException exception) {
            cancellationSettlementFailed = true;
            String previousDetail = reconciliationRequired ? reconciliationDetail : "";
            reconciliationRequired = true;
            reconciliationDetail =
                    "Movement could not be stopped while " + action + ": "
                            + safeMessage(exception)
                            + (previousDetail.isBlank()
                                    ? ""
                                    : ". Existing reconciliation issue: " + previousDetail)
                            + ". Reset is required before starting or resuming.";
        } finally {
            pollExecutionSettlement(false);
        }
    }

    private void pollExecutionSettlement(boolean persistChanges) {
        boolean previouslyPending = executionSettlementPending;
        boolean changed = false;
        try {
            ExecutionSettlementSnapshot settlement = Objects.requireNonNull(
                    ports.execution().pollSettlement(),
                    "execution port returned null settlement status"
            );
            if (!settlement.consumedDelta().isEmpty()) {
                consumedMaterials = consumedMaterials.plus(settlement.consumedDelta());
                renewRecoveryAfterConfirmedConsumption();
                changed = true;
            }
            executionSettlementPending = settlement.pending();
            if (!settlement.detail().isBlank()) {
                failExecutionSettlement("Build interaction settlement failed: " + settlement.detail());
                changed = true;
            }
        } catch (RuntimeException exception) {
            failExecutionSettlement("Build interaction settlement failed: " + safeMessage(exception));
            changed = true;
        }
        // Credit persistence/acknowledgement failures are retryable from the durable outbox;
        // they must not be converted into an unrecoverable unknown-interaction accounting error.
        settlePlannedCredit();
        if (persistChanges && (changed || previouslyPending != executionSettlementPending)) {
            persist();
        }
    }

    private void failExecutionSettlement(String detail) {
        executionSettlementPending = false;
        // A later successful depot cancellation must not clear an execution accounting failure.
        cancellationSettlementFailed = true;
        String previous = reconciliationRequired ? reconciliationDetail : "";
        reconciliationRequired = true;
        String failure = detail + ". Reset is required before starting or resuming.";
        reconciliationDetail = previous.contains(failure) ? previous : failure
                + (previous.isBlank() ? "" : " Existing reconciliation issue: " + previous);
    }

    private String settlementDetail() {
        String waiting = executionSettlementPending
                ? "A build interaction is still settling; start and resume are blocked."
                : "";
        return reconciliationDetail.isBlank() ? waiting : reconciliationDetail
                + (waiting.isBlank() ? "" : " " + waiting);
    }

    private void safePause(
            String error,
            MaterialQuantities missing,
            SupervisorState resumeAfterPause
    ) {
        String pauseError = state == SupervisorState.STUCK ? recoveryError(error) : error;
        cancelAsyncOperations();
        stopMovementForTransition("pausing after supervision failure");
        lastError = pauseError;
        missingMaterials = missing;
        resumeState = resumeAfterPause == SupervisorState.STUCK
                ? activeStateForPhase()
                : resumeAfterPause;
        state = SupervisorState.PAUSED;
        recoveryStage = RecoveryStage.NONE;
        commandStarted = false;
        withdrawalStarted = false;
        activeWithdrawalRemaining = MaterialQuantities.empty();
        verificationStarted = false;
        ports.notifications().alert(pauseError, missing);
        persist();
    }

    private String recoveryError(String detail) {
        // Recovery failures supplement the execution cause, including in saved checkpoints.
        return lastError.isBlank() || lastError.equals(detail)
                ? detail
                : lastError + "; " + detail;
    }

    private void renewRecoveryAfterConfirmedConsumption() {
        // Only drained execution receipts renew attempts; movement and supply do not.
        repathAttempted = false;
        safeReturnAttempted = false;
        advisorAttempted = false;
        flightRestoreAttempted = false;
        transientRetries = 0;
    }

    private void settlePlannedCredit() {
        PlannedConsumptionCredit credit = Objects.requireNonNull(ports.execution().pendingPlannedCredit(),
                "execution port returned null planned credit option").orElse(null);
        if (credit == null) { return; }
        if (!plan.planId().equals(credit.planId())) {
            throw new IllegalStateException("Pending planned credit belongs to a different plan");
        }
        if (lastAppliedPlannedCredit != null && lastAppliedPlannedCredit.id().equals(credit.id())) {
            if (!lastAppliedPlannedCredit.equals(credit)) {
                throw new IllegalStateException("Pending planned credit payload differs from its saved identity");
            }
        } else {
            MaterialQuantities beforeTotal = consumedMaterials;
            PlannedConsumptionCredit beforeCredit = lastAppliedPlannedCredit;
            boolean beforeRepath = repathAttempted;
            boolean beforeSafeReturn = safeReturnAttempted;
            boolean beforeAdvisor = advisorAttempted;
            boolean beforeFlightRestore = flightRestoreAttempted;
            int beforeTransientRetries = transientRetries;
            Instant beforeProgress = lastProgressAt;
            try {
                consumedMaterials = consumedMaterials.plus(MaterialQuantities.of(credit.material(), credit.quantity()));
                lastAppliedPlannedCredit = credit;
                renewRecoveryAfterConfirmedConsumption();
                lastProgressAt = ports.clock().instant();
                persist();
            } catch (RuntimeException exception) {
                consumedMaterials = beforeTotal;
                lastAppliedPlannedCredit = beforeCredit;
                repathAttempted = beforeRepath;
                safeReturnAttempted = beforeSafeReturn;
                advisorAttempted = beforeAdvisor;
                flightRestoreAttempted = beforeFlightRestore;
                transientRetries = beforeTransientRetries;
                lastProgressAt = beforeProgress;
                throw exception;
            }
        }
        // An acknowledgement failure leaves the committed total/identity intact. The same
        // outbox record retries this step on the next poll without another credit or renewal.
        ports.execution().acknowledgePlannedCredit(credit.id());
    }

    private void resetRecoveryAttempts() {
        repathAttempted = false;
        safeReturnAttempted = false;
        advisorAttempted = false;
        flightRestoreAttempted = false;
        transientRetries = 0;
        recoveryStage = RecoveryStage.NONE;
        restockRequirement = MaterialQuantities.empty();
        missingMaterials = MaterialQuantities.empty();
    }

    private void restore(SupervisorCheckpoint checkpoint) {
        if (!plan.planId().equals(checkpoint.planId())) {
            throw new IllegalArgumentException(
                    "checkpoint belongs to a different plan: " + checkpoint.planId()
            );
        }
        if (checkpoint.chunkCount() != plan.chunkCount()
                || checkpoint.currentChunkIndex() > plan.chunkCount()
                || checkpoint.repairChunkIndex() >= plan.chunkCount()) {
            throw new IllegalArgumentException("checkpoint chunk layout does not match the loaded plan");
        }
        if (checkpoint.currentChunkIndex() == plan.chunkCount()
                && checkpoint.verificationStage() != VerificationStage.FINAL) {
            throw new IllegalArgumentException("completed chunk cursor requires final verification stage");
        }
        if (!schedule.id().equals(checkpoint.scheduleId())
                || checkpoint.scheduleCursor() > schedule.size()) {
            throw new IllegalArgumentException("checkpoint layer schedule does not match; reset is required");
        }
        scheduleCursor = checkpoint.scheduleCursor();
        repairChunkIndex = checkpoint.repairChunkIndex();
        if (checkpoint.checkedPieces().limit() > schedule.size()) {
            throw new IllegalArgumentException("checkpoint checked pieces do not match the layer schedule; reset is required");
        }
        // Verification already counts every piece as done.
        checkedPieces = checkpoint.phase() == BuildPhase.VERIFY ? CompletedPieces.none() : checkpoint.checkedPieces();
        if (checkpoint.phase() == BuildPhase.VERIFY) {
            if (scheduleCursor != schedule.size()) {
                throw new IllegalArgumentException("verification checkpoint has unfinished layer work");
            }
        } else if (scheduleCursor >= schedule.size()
                || schedule.entry(scheduleCursor).order().chunkIndex() != checkpoint.currentChunkIndex()
                || schedule.entry(scheduleCursor).order().phase() != checkpoint.phase()
                || (repairChunkIndex >= 0 && repairChunkIndex != checkpoint.currentChunkIndex())) {
            // STOPPED with an empty plan has no execution slice yet.
            if (!(checkpoint.state() == SupervisorState.STOPPED && schedule.size() == 0)) {
                throw new IllegalArgumentException("checkpoint cursor does not identify its layer slice");
            }
        }
        currentChunkIndex = checkpoint.currentChunkIndex();
        phase = checkpoint.phase();
        verificationStage = checkpoint.verificationStage();
        recoveryStage = checkpoint.recoveryStage();
        stableVerificationPasses = checkpoint.stableVerificationPasses();
        lastVerificationFingerprint = checkpoint.lastVerificationFingerprint();
        consumedMaterials = checkpoint.consumedMaterials();
        lastAppliedPlannedCredit = checkpoint.lastAppliedPlannedCredit();
        withdrawnMaterials = checkpoint.withdrawnMaterials();
        missingMaterials = enabledReserveRequirement(checkpoint.missingMaterials());
        restockRequirement = enabledReserveRequirement(checkpoint.restockRequirement());
        lastError = checkpoint.lastError();
        String shortageErrorPrefix = "Registered depots cannot satisfy the exact material shortage: ";
        if (!missingMaterials.equals(checkpoint.missingMaterials())
                && lastError.equals(shortageErrorPrefix + checkpoint.missingMaterials())) {
            lastError = missingMaterials.isEmpty() ? "" : shortageErrorPrefix + missingMaterials;
        }
        verificationRetries = checkpoint.verificationRetries();
        repathAttempted = checkpoint.repathAttempted();
        safeReturnAttempted = checkpoint.safeReturnAttempted();
        advisorAttempted = checkpoint.advisorAttempted();
        restockResumeState = checkpoint.restockResumeState();
        reconciliationRequired = checkpoint.reconciliationRequired();
        reconciliationDetail = checkpoint.reconciliationDetail();
        if (checkpoint.withdrawalInFlight()) {
            reconciliationRequired = true;
            reconciliationDetail =
                    "The previous session ended with a depot transfer in flight. "
                            + "Reset is required before starting or resuming.";
        }

        if (checkpoint.state() == SupervisorState.DONE
                || checkpoint.state() == SupervisorState.STOPPED) {
            state = checkpoint.state();
            resumeState = checkpoint.resumeState();
        } else {
            state = SupervisorState.PAUSED;
            resumeState = checkpoint.state() == SupervisorState.PAUSED
                    ? checkpoint.resumeState()
                    : checkpoint.state();
            if (resumeState == SupervisorState.STUCK) {
                resumeState = activeStateForPhase();
                recoveryStage = RecoveryStage.NONE;
            }
        }
        invalidateFinalVerification();
        commandStarted = false;
        withdrawalStarted = false;
        activeWithdrawalRemaining = MaterialQuantities.empty();
        cancellationSettlementPending = false;
        cancellationSettlementFailed = false;
        cancellationRemaining = MaterialQuantities.empty();
        verificationStarted = false;
        lastProgressMarker = 0;
        lastProgressAt = ports.clock().instant();
        recoveryStartedAt = lastProgressAt;
        waitUntil = lastProgressAt;
    }

    private void persist() {
        ports.checkpoints().save(checkpoint());
    }

    private void cancelAsyncOperations() {
        executionWaitAt = null;
        safeReturnScreenWaitAt = null;
        invalidateFinalVerification();
        if (withdrawalStarted) {
            beginCancellationSettlement();
        }
        if (recoveryStage == RecoveryStage.WAIT_FOR_ADVISOR) {
            try {
                ports.advisor().cancelAdvice();
            } catch (RuntimeException ignored) {
                // Continue the safety transition.
            }
        }
        if (recoveryStage == RecoveryStage.WAIT_FOR_SAFE_POSITION) {
            try {
                ports.execution().cancelSafeReturn();
            } catch (RuntimeException ignored) {
                // Continue the safety transition.
            }
        }
        if (recoveryStage == RecoveryStage.WAIT_FOR_FLIGHT) {
            try {
                ports.execution().cancelFlightRestore();
            } catch (RuntimeException ignored) {
                // Continue the safety transition; the takeoff releases its jump input on its own deadline.
            }
        }
        if (verificationStarted) {
            try {
                ports.verification().cancelVerification();
            } catch (RuntimeException ignored) {
                // Continue the safety transition.
            }
            verificationStarted = false;
        }
    }

    private void invalidateFinalVerification() {
        if (verificationStage == VerificationStage.FINAL && state != SupervisorState.DONE) {
            stableVerificationPasses = 0;
            lastVerificationFingerprint = "";
        }
    }

    private void beginCancellationSettlement() {
        boolean alreadyRequiresReset = reconciliationRequired;
        cancellationRemaining = activeWithdrawalRemaining;
        withdrawalStarted = false;
        activeWithdrawalRemaining = MaterialQuantities.empty();
        cancellationSettlementPending = true;
        cancellationSettlementFailed = alreadyRequiresReset;
        reconciliationRequired = true;
        if (!alreadyRequiresReset) {
            reconciliationDetail =
                    "A cancelled depot transfer is still settling; start and resume are blocked.";
        }
        try {
            ports.depots().cancelWithdrawal();
            pollCancellationSettlement(false);
        } catch (RuntimeException exception) {
            cancellationSettlementPending = false;
            cancellationSettlementFailed = true;
            cancellationRemaining = MaterialQuantities.empty();
            reconciliationRequired = true;
            reconciliationDetail =
                    "Cancelled depot transfer settlement failed: " + safeMessage(exception)
                            + ". Reset is required before starting or resuming.";
        }
    }

    private void pollCancellationSettlement(boolean persistChanges) {
        boolean changed = false;
        RestockTransferSnapshot transfer;
        try {
            transfer = Objects.requireNonNull(
                    ports.depots().pollWithdrawal(),
                    "depot port returned null cancellation status"
            );
        } catch (RuntimeException exception) {
            cancellationSettlementPending = false;
            cancellationSettlementFailed = true;
            cancellationRemaining = MaterialQuantities.empty();
            reconciliationRequired = true;
            reconciliationDetail =
                    "Cancelled depot transfer settlement failed: " + safeMessage(exception)
                            + ". Reset is required before starting or resuming.";
            if (persistChanges) {
                persist();
            }
            return;
        }

        if (!transfer.movedDelta().isEmpty()) {
            MaterialQuantities excess = transfer.movedDelta().minusFloorZero(
                    cancellationRemaining
            );
            withdrawnMaterials = withdrawnMaterials.plus(transfer.movedDelta());
            cancellationRemaining = cancellationRemaining.minusFloorZero(
                    transfer.movedDelta()
            );
            changed = true;
            if (!excess.isEmpty()) {
                cancellationSettlementFailed = true;
                reconciliationDetail =
                        "A cancelled depot transfer moved more than its remaining allocation: "
                                + excess
                                + ". Reset is required before starting or resuming.";
            }
        }

        if (transfer.status() == RestockTransferStatus.RUNNING) {
            if (persistChanges && changed) {
                persist();
            }
            return;
        }

        cancellationSettlementPending = false;
        cancellationRemaining = MaterialQuantities.empty();
        // An unreachable depot was never opened and left no screen, so its report settles cleanly.
        if (transfer.status() == RestockTransferStatus.FAILED
                || !transfer.detail().isBlank() && transfer.status() != RestockTransferStatus.UNREACHABLE) {
            cancellationSettlementFailed = true;
            String detail = transfer.detail().isBlank()
                    ? "the depot adapter reported failure"
                    : transfer.detail();
            reconciliationDetail =
                    "Cancelled depot transfer cleanup was not confirmed: " + detail
                            + ". Reset is required before starting or resuming.";
        }
        if (!cancellationSettlementFailed) {
            reconciliationRequired = false;
            reconciliationDetail = "";
        }
        if (persistChanges) {
            persist();
        }
    }

    private void requireReconciled(String action) {
        settlePlannedCredit();
        if (!requiresReconciliation()) {
            return;
        }
        throw new IllegalStateException(
                action + " is blocked until reconciliation: " + settlementDetail()
        );
    }

    private void requireReset(String detail) {
        reconciliationRequired = true;
        reconciliationDetail = detail + ". Reset is required before starting or resuming.";
    }

    private String statusError() {
        if (!requiresReconciliation()) {
            return lastError;
        }
        if (lastError.isBlank()) {
            return settlementDetail();
        }
        return settlementDetail() + " Previous error: " + lastError;
    }

    private static String safeMessage(RuntimeException exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank()
                ? exception.getClass().getSimpleName()
                : message;
    }
}
