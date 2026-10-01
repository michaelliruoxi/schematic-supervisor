package io.github.schematicsupervisor.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.TreeMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.function.Function;

/** Deterministic fakes for the supervisor ports, shared by the core supervisor tests. */
final class SupervisorFakes {
    private SupervisorFakes() {
    }

    static final class Harness {
        final MutableClock clock = new MutableClock();
        final MutableInventory inventory = new MutableInventory();
        final FakeExecution execution = new FakeExecution(inventory);
        final FakeDepots depots = new FakeDepots(inventory);
        final FakeVerification verification = new FakeVerification();
        final FakeHealth health = new FakeHealth();
        final FakeAdvisor advisor = new FakeAdvisor();
        final MemoryCheckpoints checkpoints = new MemoryCheckpoints();
        final FakeNotifications notifications = new FakeNotifications();
        SchematicSupervisor supervisor;

        SupervisorPorts ports() {
            return new SupervisorPorts(
                    execution,
                    inventory,
                    depots,
                    verification,
                    health,
                    advisor,
                    checkpoints,
                    notifications,
                    clock
            );
        }
    }

    static final class MutableClock extends Clock {
        Instant current = Instant.parse("2026-07-22T12:00:00Z");

        void advance(Duration duration) {
            current = current.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            assertNotNull(zone);
            return this;
        }

        @Override
        public Instant instant() {
            return current;
        }
    }

    static final class MutableInventory implements SupervisorPorts.Inventory {
        MaterialQuantities quantities = MaterialQuantities.empty();

        void set(MaterialQuantities newQuantities) {
            quantities = newQuantities;
        }

        void add(MaterialQuantities added) {
            quantities = quantities.plus(added);
        }

        void remove(MaterialQuantities removed) {
            assertTrue(removed.shortageFrom(quantities).isEmpty(), "fake inventory underflow");
            quantities = quantities.minusFloorZero(removed);
        }

        @Override
        public MaterialQuantities snapshot() {
            return quantities;
        }
    }

    static final class FakeExecution implements SupervisorPorts.Execution {
        final MutableInventory inventory;
        final List<WorkOrder> started = new ArrayList<>();
        final Queue<ExecutionSnapshot> scripted = new ArrayDeque<>();
        final Queue<ExecutionSettlementSnapshot> settlements = new ArrayDeque<>();
        RuntimeException settlementFailure;
        PlannedConsumptionCredit plannedCredit;
        PlannedConsumptionCredit creditOnPoll;
        RuntimeException creditAckFailure;
        int creditAcknowledgements;
        Runnable beforeCreditAck = () -> { };
        WorkOrder current;
        boolean autoComplete = true;
        int stopCount;
        RuntimeException stopFailure;
        int cancelCount;
        int safeReturnCount;
        int cancelSafeReturnCount;
        int restartCallCount;
        long marker;
        final Queue<Boolean> restartResults = new ArrayDeque<>();
        final Queue<SafeReturnSnapshot> safeReturnResults = new ArrayDeque<>();
        // Flight restore: restorable while flight is off and the server allows it.
        boolean flightRestorable;
        int flightRestoreCount;
        int cancelFlightRestoreCount;
        RuntimeException flightRestoreFailure;
        final Queue<FlightRestoreSnapshot> flightRestoreResults = new ArrayDeque<>();

        FakeExecution(MutableInventory inventory) {
            this.inventory = inventory;
        }

        @Override
        public void start(WorkOrder order) {
            current = order;
            started.add(order);
        }

        @Override
        public ExecutionSnapshot poll() {
            if (creditOnPoll != null) {
                plannedCredit = creditOnPoll;
                creditOnPoll = null;
            }
            if (!scripted.isEmpty()) {
                ExecutionSnapshot result = scripted.remove();
                if (!result.consumedDelta().isEmpty()) {
                    inventory.remove(result.consumedDelta());
                }
                return result;
            }
            if (!autoComplete) {
                return ExecutionSnapshot.running(marker);
            }
            MaterialQuantities consumed = consumption(current);
            inventory.remove(consumed);
            return ExecutionSnapshot.succeeded(++marker, consumed);
        }

        @Override
        public ExecutionSettlementSnapshot pollSettlement() {
            if (settlementFailure != null) { throw settlementFailure; }
            return settlements.isEmpty() ? ExecutionSettlementSnapshot.settled() : settlements.remove();
        }

        @Override
        public Optional<PlannedConsumptionCredit> pendingPlannedCredit() {
            return Optional.ofNullable(plannedCredit);
        }

        @Override
        public void acknowledgePlannedCredit(String id) {
            creditAcknowledgements++;
            beforeCreditAck.run();
            if (creditAckFailure != null) { throw creditAckFailure; }
            assertNotNull(plannedCredit);
            assertEquals(plannedCredit.id(), id);
            plannedCredit = null;
        }

        @Override
        public void stopMovement() {
            stopCount++;
            if (stopFailure != null) {
                throw stopFailure;
            }
        }

        @Override
        public void cancelCurrentPath() {
            cancelCount++;
        }

        @Override
        public boolean restartCurrentPath() {
            restartCallCount++;
            return restartResults.isEmpty() || restartResults.remove();
        }

        @Override
        public void beginReturnToLastSafePosition() {
            safeReturnCount++;
        }

        @Override
        public SafeReturnSnapshot pollSafeReturn() {
            return safeReturnResults.isEmpty()
                    ? SafeReturnSnapshot.succeeded()
                    : safeReturnResults.remove();
        }

        @Override
        public void cancelSafeReturn() {
            cancelSafeReturnCount++;
        }

        @Override
        public boolean flightRestorable() {
            return flightRestorable;
        }

        @Override
        public void beginFlightRestore() {
            flightRestoreCount++;
            if (flightRestoreFailure != null) {
                throw flightRestoreFailure;
            }
        }

        @Override
        public FlightRestoreSnapshot pollFlightRestore() {
            FlightRestoreSnapshot result = flightRestoreResults.isEmpty()
                    ? FlightRestoreSnapshot.succeeded()
                    : flightRestoreResults.remove();
            if (result.status() == FlightRestoreSnapshot.Status.SUCCEEDED) {
                flightRestorable = false;
            }
            return result;
        }

        @Override
        public void cancelFlightRestore() {
            cancelFlightRestoreCount++;
        }

        static MaterialQuantities consumption(WorkOrder order) {
            if (order instanceof WorkOrder.OrdinaryBlocks ordinary) {
                TreeMap<Material, Long> counts = new TreeMap<>();
                ordinary.placements().forEach(
                        placement -> counts.merge(placement.material(), 1L, Math::addExact)
                );
                return MaterialQuantities.of(counts);
            }
            if (order instanceof WorkOrder.Plant plant) {
                return MaterialQuantities.of(Material.WHEAT_SEEDS, plant.targets().size());
            }
            return MaterialQuantities.empty();
        }
    }

    static final class FakeDepots implements SupervisorPorts.Depots {
        final MutableInventory inventory;
        final Map<DepotId, MaterialQuantities> stock = new LinkedHashMap<>();
        List<DepotWithdrawal> active = List.of();
        int beginCount;
        Runnable onBegin = () -> {};
        RestockTransferSnapshot terminalOverride;

        FakeDepots(MutableInventory inventory) {
            this.inventory = inventory;
        }

        void put(DepotId id, MaterialQuantities quantities) {
            stock.put(id, quantities);
        }

        @Override
        public List<DepotStock> snapshot() {
            return stock.entrySet().stream()
                    .map(entry -> new DepotStock(entry.getKey(), entry.getValue()))
                    .toList();
        }

        @Override
        public void beginWithdrawal(List<DepotWithdrawal> withdrawals) {
            onBegin.run();
            assertTrue(active.isEmpty(), "fake depot already has an active withdrawal");
            active = List.copyOf(withdrawals);
            beginCount++;
        }

        @Override
        public RestockTransferSnapshot pollWithdrawal() {
            assertFalse(active.isEmpty(), "fake depot has no active withdrawal");
            if (terminalOverride != null) {
                var result = terminalOverride;
                terminalOverride = null;
                active = List.of();
                inventory.add(result.movedDelta());
                return result;
            }
            MaterialQuantities total = MaterialQuantities.empty();
            for (DepotWithdrawal withdrawal : active) {
                MaterialQuantities available = stock.getOrDefault(
                        withdrawal.depot(),
                        MaterialQuantities.empty()
                );
                assertTrue(
                        withdrawal.quantities().shortageFrom(available).isEmpty(),
                        "fake depot underflow"
                );
                stock.put(
                        withdrawal.depot(),
                        available.minusFloorZero(withdrawal.quantities())
                );
                total = total.plus(withdrawal.quantities());
            }
            inventory.add(total);
            active = List.of();
            return RestockTransferSnapshot.succeeded(total);
        }

        @Override
        public void cancelWithdrawal() {
            active = List.of();
        }
    }

    static final class PartialDepots implements SupervisorPorts.Depots {
        final MutableInventory inventory;
        long remaining = 63;
        boolean active;
        int beginCount;
        int pollCount;

        PartialDepots(MutableInventory inventory) {
            this.inventory = inventory;
        }

        @Override
        public List<DepotStock> snapshot() {
            return List.of(new DepotStock(
                    new DepotId("partial"),
                    MaterialQuantities.of(Material.DIRT, remaining)
            ));
        }

        @Override
        public void beginWithdrawal(List<DepotWithdrawal> withdrawals) {
            assertFalse(active);
            assertEquals(MaterialQuantities.of(Material.DIRT, 63), withdrawals.getFirst().quantities());
            active = true;
            beginCount++;
        }

        @Override
        public RestockTransferSnapshot pollWithdrawal() {
            assertTrue(active);
            pollCount++;
            long moved = pollCount == 1 ? 20 : 43;
            remaining -= moved;
            MaterialQuantities delta = MaterialQuantities.of(Material.DIRT, moved);
            inventory.add(delta);
            if (remaining == 0) {
                active = false;
                return RestockTransferSnapshot.succeeded(delta);
            }
            return RestockTransferSnapshot.running(delta);
        }

        @Override
        public void cancelWithdrawal() {
            active = false;
        }
    }

    static final class SettlingDepots implements SupervisorPorts.Depots {
        final MutableInventory inventory;
        final Queue<RestockTransferSnapshot> cancellationResults;
        boolean active;
        boolean cancelling;
        int beginCount;
        int pollCount;

        SettlingDepots(
                MutableInventory inventory,
                RestockTransferSnapshot... cancellationResults
        ) {
            this.inventory = inventory;
            this.cancellationResults = new ArrayDeque<>(List.of(cancellationResults));
        }

        @Override
        public List<DepotStock> snapshot() {
            return List.of(new DepotStock(
                    new DepotId("settling"),
                    MaterialQuantities.of(Material.DIRT, 63)
            ));
        }

        @Override
        public void beginWithdrawal(List<DepotWithdrawal> withdrawals) {
            assertFalse(active);
            assertFalse(cancelling);
            assertEquals(
                    MaterialQuantities.of(Material.DIRT, 63),
                    withdrawals.getFirst().quantities()
            );
            active = true;
            beginCount++;
        }

        @Override
        public RestockTransferSnapshot pollWithdrawal() {
            assertTrue(active);
            assertTrue(cancelling);
            assertFalse(cancellationResults.isEmpty());
            pollCount++;
            RestockTransferSnapshot result = cancellationResults.remove();
            inventory.add(result.movedDelta());
            if (result.status() != RestockTransferStatus.RUNNING) {
                active = false;
                cancelling = false;
            }
            return result;
        }

        @Override
        public void cancelWithdrawal() {
            assertTrue(active);
            cancelling = true;
        }
    }

    static final class OverTransferDepots implements SupervisorPorts.Depots {
        final MutableInventory inventory;
        boolean cancellationPolled;
        int cancelCount;

        OverTransferDepots(MutableInventory inventory) {
            this.inventory = inventory;
        }

        @Override
        public List<DepotStock> snapshot() {
            return List.of(new DepotStock(
                    new DepotId("over-transfer"),
                    MaterialQuantities.of(Material.DIRT, 63)
            ));
        }

        @Override
        public void beginWithdrawal(List<DepotWithdrawal> withdrawals) {
            assertEquals(
                    MaterialQuantities.of(Material.DIRT, 63),
                    withdrawals.getFirst().quantities()
            );
        }

        @Override
        public RestockTransferSnapshot pollWithdrawal() {
            if (cancelCount == 0) {
                MaterialQuantities moved = MaterialQuantities.of(Material.DIRT, 64);
                inventory.add(moved);
                return RestockTransferSnapshot.succeeded(moved);
            }
            assertFalse(cancellationPolled);
            cancellationPolled = true;
            return new RestockTransferSnapshot(
                    RestockTransferStatus.IDLE,
                    MaterialQuantities.empty(),
                    ""
            );
        }

        @Override
        public void cancelWithdrawal() {
            cancelCount++;
        }
    }

    static final class FakeVerification implements SupervisorPorts.Verification {
        final List<VerificationScope> scopes = new ArrayList<>();
        final Queue<VerificationTaskSnapshot> scripted = new ArrayDeque<>();
        Function<VerificationScope, VerificationResult> result = scope ->
                VerificationResult.clean(
                        scope.kind() == VerificationScope.Kind.FULL_PLAN
                                ? "stable-full-plan"
                                : "chunk-" + scope.chunkIndex()
                );
        VerificationScope active;

        @Override
        public void beginVerification(SchematicPlan plan, VerificationScope scope) {
            assertEquals(null, active, "fake verification already active");
            scopes.add(scope);
            active = scope;
        }

        @Override
        public VerificationTaskSnapshot pollVerification() {
            assertNotNull(active, "fake verification is not active");
            if (!scripted.isEmpty()) {
                VerificationTaskSnapshot next = scripted.remove();
                if (next.status() != VerificationTaskStatus.RUNNING) {
                    active = null;
                }
                return next;
            }
            VerificationResult completed = result.apply(active);
            active = null;
            return VerificationTaskSnapshot.succeeded(completed);
        }

        @Override
        public void cancelVerification() {
            active = null;
        }
    }

    static final class FakeHealth implements SupervisorPorts.ServerHealth {
        boolean laggy;

        @Override
        public boolean appearsLaggy() {
            return laggy;
        }
    }

    static final class FakeAdvisor implements SupervisorPorts.Advisor {
        final Queue<AdviceSnapshot> responses = new ArrayDeque<>();
        final List<RecoveryIncident> incidents = new ArrayList<>();
        int beginCount;
        int pollCount;
        int cancelCount;
        boolean returnNull;

        @Override
        public void beginAdvice(RecoveryIncident incident) {
            incidents.add(incident);
            beginCount++;
        }

        @Override
        public AdviceSnapshot pollAdvice() {
            pollCount++;
            if (returnNull) {
                return null;
            }
            return responses.isEmpty() ? AdviceSnapshot.pending() : responses.remove();
        }

        @Override
        public void cancelAdvice() {
            cancelCount++;
        }
    }

    static final class MemoryCheckpoints implements SupervisorPorts.Checkpoints {
        SupervisorCheckpoint saved;
        RuntimeException saveFailure;

        @Override
        public Optional<SupervisorCheckpoint> load() {
            return Optional.ofNullable(saved);
        }

        @Override
        public void save(SupervisorCheckpoint checkpoint) {
            if (saveFailure != null) {
                throw saveFailure;
            }
            saved = checkpoint;
        }

        @Override
        public void clear() {
            saved = null;
        }
    }

    static final class FakeNotifications implements SupervisorPorts.Notifications {
        final List<String> messages = new ArrayList<>();

        @Override
        public void alert(String message, MaterialQuantities missingMaterials) {
            messages.add(message + " " + missingMaterials);
        }
    }
}
