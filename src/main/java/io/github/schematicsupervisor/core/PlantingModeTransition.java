package io.github.schematicsupervisor.core;

import java.util.BitSet;
import java.util.HashSet;
import java.util.Set;

/** Maps settled planting and lighting schedules without discarding construction or its ledger. */
public final class PlantingModeTransition {
    private PlantingModeTransition() { }

    public static SupervisorCheckpoint apply(SchematicPlan targetPlan, SupervisorCheckpoint original) {
        SupervisorCheckpoint saved = LayerBuildSchedule.rowMajor(original.scheduleId())
                ? toChunkTour(targetPlan, original) : original;
        if (LayerBuildSchedule.id(targetPlan.plantingDeferred(), targetPlan.glowstoneAfterStructure())
                .equals(saved.scheduleId())) { return saved; }
        if (!targetPlan.planId().equals(saved.planId()) || targetPlan.chunkCount() != saved.chunkCount()) {
            throw new IllegalArgumentException("Build schedule can change only for the same source plan and layout");
        }
        if (saved.withdrawalInFlight() || saved.reconciliationRequired()) {
            throw new IllegalArgumentException("Settle the pending material transfer before changing the build schedule; "
                    + "the saved checkpoint has not been changed");
        }
        if (saved.state() != SupervisorState.PAUSED && saved.state() != SupervisorState.STOPPED
                && saved.state() != SupervisorState.DONE) {
            throw new IllegalArgumentException("Pause and save the current build before changing the build schedule; "
                    + "the saved checkpoint has not been changed");
        }
        if (saved.repairChunkIndex() >= 0) {
            throw new IllegalArgumentException("Finish the active chunk repair before changing the build schedule; "
                    + "the saved checkpoint has not been changed");
        }
        LayerBuildSchedule source = LayerBuildSchedule.forId(targetPlan, saved.scheduleId());
        LayerBuildSchedule target = new LayerBuildSchedule(targetPlan);
        validateCursor(saved, source);
        // Advance only over orders confirmed before the saved cursor. Reordered work beyond
        // the first unfinished slice is inspected again when reached, without new credit for
        // blocks already present. A partially completed slice is never assumed complete.
        Set<WorkOrder> completed = new HashSet<>();
        for (int cursor = 0; cursor < saved.scheduleCursor(); cursor++) {
            completed.add(source.entry(cursor).order());
        }
        int targetCursor = 0;
        while (targetCursor < target.size() && completed.contains(target.entry(targetCursor).order())) {
            targetCursor++;
        }
        WorkOrder next = targetCursor < target.size() ? target.entry(targetCursor).order() : null;
        boolean sameSlice = next != null && saved.scheduleCursor() < source.size()
                && next.equals(source.entry(saved.scheduleCursor()).order());
        BuildPhase phase = next == null ? BuildPhase.VERIFY : next.phase();
        SupervisorState resume = phase == BuildPhase.VERIFY ? SupervisorState.VERIFYING : SupervisorState.BUILDING;
        return new SupervisorCheckpoint(saved.version(), saved.planId(), SupervisorState.PAUSED, resume, resume,
                next == null ? 0 : next.chunkIndex(), phase, VerificationStage.CHUNK, RecoveryStage.NONE,
                0, "", saved.consumedMaterials(), saved.withdrawnMaterials(), MaterialQuantities.empty(),
                MaterialQuantities.empty(), saved.lastError(), sameSlice ? saved.verificationRetries() : 0,
                sameSlice && saved.repathAttempted(), sameSlice && saved.safeReturnAttempted(),
                sameSlice && saved.advisorAttempted(), false, false, "",
                target.id(), targetCursor, -1, saved.chunkCount(), targetPlan.plantingDeferred(),
                saved.lastAppliedPlannedCredit());
    }

    /**
     * Maps a checkpoint from a version-one row-major schedule to the chunk tour with the same options.
     * Only the order of chunks inside each stage changes, so the state, ledger and settlement are kept
     * and every finished piece stays finished, whatever state the checkpoint was saved in.
     */
    static SupervisorCheckpoint toChunkTour(SchematicPlan targetPlan, SupervisorCheckpoint saved) {
        if (!targetPlan.planId().equals(saved.planId()) || targetPlan.chunkCount() != saved.chunkCount()) {
            throw new IllegalArgumentException("Build schedule can change only for the same source plan and layout");
        }
        LayerBuildSchedule source = LayerBuildSchedule.forId(targetPlan, saved.scheduleId());
        LayerBuildSchedule target = LayerBuildSchedule.forId(targetPlan,
                LayerBuildSchedule.id(saved.plantingDeferred(), LayerBuildSchedule.glowstoneAfterStructure(saved.scheduleId())));
        validateCursor(saved, source);
        if (saved.checkedPieces().limit() > source.size()) {
            throw new IllegalArgumentException("Saved checked pieces do not match the layer schedule");
        }
        int cursor = saved.scheduleCursor();
        int targetCursor;
        BitSet checked = new BitSet();
        if (cursor >= source.size()) {
            targetCursor = target.size();
        } else if (saved.repairChunkIndex() >= 0) {
            // A repair walks one chunk's pieces in stage order, which the tour keeps; continue at the same piece.
            WorkOrder current = source.entry(cursor).order();
            targetCursor = 0;
            while (!target.entry(targetCursor).order().equals(current)) { targetCursor++; }
        } else {
            // The saved piece itself is unfinished, so the tour always finds a first piece to resume.
            Set<WorkOrder> finished = new HashSet<>();
            for (int index = 0; index < source.size(); index++) {
                if (index < cursor || index > cursor && saved.checkedPieces().contains(index)) {
                    finished.add(source.entry(index).order());
                }
            }
            targetCursor = 0;
            while (targetCursor < target.size() && finished.contains(target.entry(targetCursor).order())) {
                targetCursor++;
            }
            for (int index = targetCursor + 1; index < target.size(); index++) {
                if (finished.contains(target.entry(index).order())) { checked.set(index); }
            }
        }
        boolean verification = cursor >= source.size();
        // Recovery attempts and restock needs belong to the saved piece; a different first piece starts fresh.
        boolean samePiece = verification
                || target.entry(targetCursor).order().equals(source.entry(cursor).order());
        WorkOrder next = verification ? null : target.entry(targetCursor).order();
        return new SupervisorCheckpoint(saved.version(), saved.planId(), saved.state(), saved.resumeState(),
                saved.restockResumeState(), next == null ? saved.currentChunkIndex() : next.chunkIndex(),
                next == null ? saved.phase() : next.phase(), saved.verificationStage(),
                samePiece ? saved.recoveryStage() : RecoveryStage.NONE, saved.stableVerificationPasses(),
                saved.lastVerificationFingerprint(), saved.consumedMaterials(), saved.withdrawnMaterials(),
                samePiece ? saved.missingMaterials() : MaterialQuantities.empty(),
                samePiece ? saved.restockRequirement() : MaterialQuantities.empty(), saved.lastError(),
                samePiece ? saved.verificationRetries() : 0, samePiece && saved.repathAttempted(),
                samePiece && saved.safeReturnAttempted(), samePiece && saved.advisorAttempted(),
                saved.withdrawalInFlight(), saved.reconciliationRequired(), saved.reconciliationDetail(),
                target.id(), targetCursor, saved.repairChunkIndex(), saved.chunkCount(), saved.plantingDeferred(),
                saved.lastAppliedPlannedCredit(), CompletedPieces.of(checked));
    }

    private static void validateCursor(SupervisorCheckpoint saved, LayerBuildSchedule source) {
        int cursor = saved.scheduleCursor();
        if (saved.currentChunkIndex() == saved.chunkCount()
                && saved.verificationStage() != VerificationStage.FINAL) {
            throw new IllegalArgumentException("Saved completed chunk cursor has no final verification stage");
        }
        if (saved.phase() == BuildPhase.VERIFY) {
            if (cursor != source.size()) { throw new IllegalArgumentException("Saved verification cursor is incomplete"); }
        } else if (cursor >= source.size() || source.entry(cursor).order().phase() != saved.phase()
                || source.entry(cursor).order().chunkIndex() != saved.currentChunkIndex()) {
            if (!(saved.state() == SupervisorState.STOPPED && source.size() == 0 && cursor == 0)) {
                throw new IllegalArgumentException("Saved cursor does not identify its original layer slice");
            }
        }
    }
}
