package io.github.schematicsupervisor.core;

/** Bounded planned lookahead for one Glowstone stack, without changing work or accounting. */
public final class LightingRestockReserve {
    public static final int STACK_SIZE = 64;

    private LightingRestockReserve() { }

    /** Refreshes only the one-unit unknown-stock sentinel; an enlarged request cannot add lookahead twice. */
    public static long refreshLimit(SchematicPlan plan, LayerBuildSchedule schedule,
                                    SupervisorCheckpoint saved, MaterialQuantities inventory) {
        if (plan == null || schedule == null || saved == null || inventory == null
                || !plan.planId().equals(saved.planId()) || !schedule.id().equals(saved.scheduleId())
                || saved.chunkCount() != plan.chunkCount() || saved.plantingDeferred() != plan.plantingDeferred()
                || saved.scheduleCursor() < 0 || saved.scheduleCursor() >= schedule.size()
                || saved.repairChunkIndex() >= 0 || saved.phase() != BuildPhase.ORDINARY_BLOCKS
                || saved.recoveryStage() != RecoveryStage.NONE || saved.withdrawalInFlight()
                || saved.reconciliationRequired()
                || !(saved.state() == SupervisorState.RESTOCKING
                    || saved.state() == SupervisorState.PAUSED && saved.resumeState() == SupervisorState.RESTOCKING)
                || inventory.get(Material.GLOWSTONE) != 0) { return 0; }
        LayerBuildSchedule.Entry entry = schedule.entry(saved.scheduleCursor());
        LayerProgress progress = entry.progress();
        if (entry.order().chunkIndex() != saved.currentChunkIndex() || !"LIGHTING".equals(progress.stage())
                || progress.y() == null || !plainLightingOrder(entry.order(), progress.y())) { return 0; }
        long current = saved.restockRequirement().get(Material.GLOWSTONE);
        long remaining = Math.max(0, plan.plannedMaterials().get(Material.GLOWSTONE)
                - saved.consumedMaterials().get(Material.GLOWSTONE));
        if (current != 1 || saved.missingMaterials().get(Material.GLOWSTONE) != current
                || remaining < current) { return 0; }
        long future = additionalPlannedDemand(schedule, saved.scheduleCursor(), entry.order(), saved.checkedPieces());
        return Math.min(remaining, current + Math.min(future, STACK_SIZE - current));
    }

    public static long additionalPlannedDemand(
            LayerBuildSchedule schedule, int cursor, WorkOrder currentOrder
    ) {
        return additionalPlannedDemand(schedule, cursor, currentOrder, CompletedPieces.none());
    }

    /**
     * Returns at most one stack of future cells in the current authoritative lighting stage.
     * Current-slice missing cells are supplied separately by its received-world scan. Future
     * scheduled cells are a plan bound, not a claim that their world blocks are missing. Pieces the
     * start build check found finished are skipped by the builder and need no Glowstone.
     */
    public static long additionalPlannedDemand(
            LayerBuildSchedule schedule, int cursor, WorkOrder currentOrder, CompletedPieces checked
    ) {
        if (schedule == null || currentOrder == null || checked == null
                || cursor < 0 || cursor >= schedule.size()) {
            return 0;
        }
        LayerBuildSchedule.Entry current = schedule.entry(cursor);
        LayerProgress progress = current.progress();
        if (!current.order().equals(currentOrder) || !"LIGHTING".equals(progress.stage())
                || progress.y() == null || !plainLightingOrder(currentOrder, progress.y())) {
            return 0;
        }
        long upcoming = 0;
        for (int index = cursor + 1; index < schedule.size() && upcoming < STACK_SIZE; index++) {
            LayerBuildSchedule.Entry next = schedule.entry(index);
            if (next.progress().ordinal() != progress.ordinal()
                    || !next.progress().stage().equals(progress.stage())
                    || !progress.y().equals(next.progress().y())
                    || !plainLightingOrder(next.order(), progress.y())) {
                break;
            }
            if (checked.contains(index)) {
                continue;
            }
            int count = ((WorkOrder.OrdinaryBlocks) next.order()).placements().size();
            upcoming += Math.min(STACK_SIZE - upcoming, count);
        }
        return upcoming;
    }

    private static boolean plainLightingOrder(WorkOrder order, int y) {
        return order instanceof WorkOrder.OrdinaryBlocks ordinary
                && ordinary.placements().stream().allMatch(placement ->
                        placement.material() == Material.GLOWSTONE
                                && placement.position().y() == y
                                && ordinary.chunk().contains(placement.position())
                                && placement.state().blockId().equals("minecraft:glowstone")
                                && placement.state().properties().isEmpty());
    }
}
