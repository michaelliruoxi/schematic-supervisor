package io.github.schematicsupervisor.fabric;

import io.github.schematicsupervisor.core.LayerBuildSchedule;
import io.github.schematicsupervisor.core.SchematicPlan;
import io.github.schematicsupervisor.core.WorkOrder;
import java.io.IOException;
import java.util.Objects;
import java.util.Optional;

/** Holds the original complete slice and its profile-wide ownership journal across executor restarts. */
final class TemporarySupportBinding {
    final SchematicPlan plan;
    final RunContext context;
    final TemporarySupportStore store;
    TemporarySupportController controller;
    WorkOrder.OrdinaryBlocks slice;

    TemporarySupportBinding(SchematicPlan plan, RunContext context, TemporarySupportStore store) throws IOException {
        this.plan = Objects.requireNonNull(plan, "plan");
        this.context = Objects.requireNonNull(context, "context");
        this.store = Objects.requireNonNull(store, "store");
        Optional<TemporarySupportJournal> saved = requirePlanContext(store, plan, context);
        if (saved.isPresent() && !saved.orElseThrow().complete()) {
            TemporarySupportJournal journal = saved.orElseThrow();
            slice = new LayerBuildSchedule(plan).entries().stream().map(LayerBuildSchedule.Entry::order)
                    .filter(WorkOrder.OrdinaryBlocks.class::isInstance).map(WorkOrder.OrdinaryBlocks.class::cast)
                    .filter(order -> TemporarySupportPlanner.sliceId(order).equals(journal.column().sliceId()))
                    .findFirst().orElseThrow(() -> new IOException("Saved supports have no matching original layer slice"));
            controller = TemporarySupportController.restore(plan, slice, context, store).orElseThrow();
            controller.pause();
        }
    }

    static Optional<TemporarySupportJournal> requirePlanContext(TemporarySupportStore store,
            SchematicPlan plan, RunContext context) throws IOException {
        Optional<TemporarySupportJournal> saved = store.load();
        if (saved.isPresent() && !saved.orElseThrow().complete()) {
            TemporarySupportJournal journal = saved.orElseThrow();
            if (!journal.column().planId().equals(plan.planId()) || !journal.context().equals(context)) {
                throw new IOException("Unfinished temporary supports belong to another build or world; "
                        + "restore that build before switching");
            }
        }
        return saved;
    }

    boolean outstanding() { return controller != null && !controller.complete(); }

    void requireOrder(WorkOrder order) {
        if (outstanding() && !Objects.equals(slice, order)) {
            throw new IllegalStateException("Temporary support ownership must settle in its original layer slice "
                    + "before another work order can start");
        }
        if (plan.plantingDeferred() && order instanceof WorkOrder.Plant) {
            throw new IllegalStateException("Planting is deferred for this build");
        }
    }
}
