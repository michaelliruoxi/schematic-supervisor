package io.github.schematicsupervisor.fabric;

import io.github.schematicsupervisor.core.LayerBuildSchedule;
import io.github.schematicsupervisor.core.LightingRestockReserve;
import io.github.schematicsupervisor.core.Material;
import io.github.schematicsupervisor.core.SchematicPlan;
import io.github.schematicsupervisor.core.SupervisorCheckpoint;

/** Optional request refresh over complete idle observations; this policy never starts a transfer. */
final class GlowstoneRestockRefreshPolicy {
    private GlowstoneRestockRefreshPolicy() { }

    static long target(SchematicPlan plan, LayerBuildSchedule schedule, SupervisorCheckpoint saved,
                       InventoryObservation inventory, DepotObservation depots) {
        if (inventory == null || !inventory.available() || inventory.normalMaterialCapacity() == null
                || inventory.menu().cursor().count() != 0
                || !(inventory.menu().kind().equals("none") || inventory.menu().kind().equals("inventory"))
                || depots == null || !depots.available() || depots.truncated() || depots.blocked()
                || !"NONE".equals(depots.operation()) || !"IDLE".equals(depots.stage())
                || depots.activeDepotId() != null || !depots.queuedDepotIds().isEmpty()
                || depots.registeredCount() < 1 || depots.entries().size() != depots.registeredCount()
                || depots.entries().stream().anyMatch(entry -> !entry.scanned() || entry.active()
                    || entry.queued() || !entry.lastError().isBlank())) { return 0; }
        long limit = LightingRestockReserve.refreshLimit(plan, schedule, saved, inventory.mainMaterialTotals());
        if (limit < 1 || limit <= saved.restockRequirement().get(Material.GLOWSTONE)) { return 0; }
        long stock = 0;
        for (var depot : depots.entries()) {
            stock = Math.min(LightingRestockReserve.STACK_SIZE,
                    stock + Math.min(LightingRestockReserve.STACK_SIZE, depot.observedStock().get(Material.GLOWSTONE)));
        }
        long current = saved.restockRequirement().get(Material.GLOWSTONE);
        long upcoming = LightingRestockReserve.additionalPlannedDemand(schedule, saved.scheduleCursor(),
                schedule.entry(saved.scheduleCursor()).order(), saved.checkedPieces());
        long remaining = Math.max(0, plan.plannedMaterials().get(Material.GLOWSTONE)
                - saved.consumedMaterials().get(Material.GLOWSTONE));
        int reserved = MaterialPurchaseBudget.reservedSlotsForOtherShortages(Material.GLOWSTONE,
                saved.restockRequirement(), inventory.mainMaterialTotals());
        long target = RestockBatchPolicy.glowstoneTargetAvailable(current, upcoming, remaining, stock,
                inventory.normalMaterialCapacity().get(Material.GLOWSTONE), reserved);
        return target > current && target <= limit ? target : 0;
    }
}
