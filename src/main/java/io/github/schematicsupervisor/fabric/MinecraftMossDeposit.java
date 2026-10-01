package io.github.schematicsupervisor.fabric;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.client.MinecraftClient;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.slot.SlotActionType;

/** Bounded restock maintenance using registered chest access and full server inventory receipts. */
final class MinecraftMossDeposit implements MossDepositController.Port {
    private static final int FULL_PACKET_TIMEOUT_TICKS = 100;
    private final MinecraftClient client;
    private MossToolCustody mossCustody;
    private final MinecraftDepotPort depots;
    private final MossDepositFileStore store;
    private final Deque<RegisteredDepot> candidates = new ArrayDeque<>();
    private MossDepositController controller;
    private RegisteredDepot destination;
    private String planId;
    private boolean active;
    private boolean awaitingReceipt;
    private int fullPacketTicks;
    private int confirmedStacks;
    private int confirmedItems;
    private int confirmedPickupItems;
    private int inspectedFullChests;
    private int unavailableChests;
    private String capacityDetail = "";
    private List<MossDepositJournal.Context> registeredAtStart = List.of();
    private final Map<MossDepositJournal.Context, MossDepositJournal.Observation> inspectedChests = new LinkedHashMap<>();
    private MossDepositJournal.Observation lastStorageObservation;
    private long oldestStorageObservationNanos;
    private SurplusStorageExhaustion exhaustion;
    private String detail = "Pickup storage has not started.";
    private String loadFailure = "";
    private boolean failed;
    private final MossDepositWearRefresh wearRefresh = new MossDepositWearRefresh();

    MinecraftMossDeposit(MinecraftClient client, MinecraftDepotPort depots, Path journalPath) {
        this.client = client;
        this.depots = depots;
        store = new MossDepositFileStore(journalPath);
        reloadController();
    }

    void configureMossCustody(MossToolCustody custody) { mossCustody = java.util.Objects.requireNonNull(custody); }

    boolean active() { return active; }
    boolean failed() { return failed; }
    boolean unavailable() { return !loadFailure.isBlank(); }
    String detail() { return detail; }
    int confirmedItems() { return confirmedItems; }
    String capacityDetail() { return capacityDetail; }
    Optional<SurplusStorageExhaustion> storageExhaustion() {
        if (active || pending() || failed || unavailable() || exhaustion == null
                || !registeredAtStart.equals(depots.mossStorageCandidates().stream().map(this::context).toList())
                || !contextMatches(exhaustion.finalInventory().context())) { return Optional.empty(); }
        return Optional.of(exhaustion);
    }
    Optional<SurplusStorageExhaustion> takeStorageExhaustion() {
        Optional<SurplusStorageExhaustion> result = storageExhaustion();
        exhaustion = null;
        return result;
    }
    MossDepositObservation observation() {
        return unavailable() ? MossDepositObservation.unavailable(active, loadFailure, confirmedItems)
                : MossDepositObservation.capture(active, failed, detail, confirmedItems, confirmedPickupItems, journal());
    }
    Optional<MossDepositJournal> journal() {
        return controller == null ? Optional.empty() : controller.currentJournal();
    }
    boolean pending() {
        return journal().filter(value -> value.stage() == MossDepositJournal.Stage.PENDING).isPresent();
    }
    String stateProblem() {
        if (!loadFailure.isBlank()) { return loadFailure; }
        return pending() ? "A pickup deposit needs its original build and registered chest receipt; "
                + "restore that build before reset or unload." : "";
    }
    String resumeProblem(String expectedPlanId) {
        if (!loadFailure.isBlank()) { return loadFailure; }
        if (pending() && (!journal().orElseThrow().before().context().planId().equals(expectedPlanId)
                || !contextMatches(journal().orElseThrow().before().context()))) {
            return stateProblem();
        }
        return "";
    }

    boolean shouldStart(InventoryObservation inventory) {
        return pending() || inventory.available() && inventory.mainSlots().stream()
                .anyMatch(slot -> SurplusPickupPolicy.allowed(slot.itemId())
                        && Boolean.TRUE.equals(slot.plainDefaultComponents()) && slot.count() > 0);
    }

    void begin(String expectedPlanId) {
        if (active) { throw new IllegalStateException("pickup storage is already active"); }
        discardWearRefresh();
        reloadController();
        failed = false;
        confirmedStacks = 0;
        confirmedItems = 0;
        confirmedPickupItems = 0;
        inspectedFullChests = 0;
        unavailableChests = 0;
        capacityDetail = "";
        registeredAtStart = List.of();
        inspectedChests.clear();
        lastStorageObservation = null;
        oldestStorageObservationNanos = 0;
        exhaustion = null;
        fullPacketTicks = 0;
        planId = expectedPlanId;
        candidates.clear();
        String problem = resumeProblem(expectedPlanId);
        if (!problem.isBlank()) { fail(problem); return; }
        if (!depots.maintenanceIdle()) { fail("Depot maintenance has not settled."); return; }
        List<RegisteredDepot> registered = depots.mossStorageCandidates();
        registeredAtStart = registered.stream().map(this::context).toList();
        for (RegisteredDepot depot : registered) {
            if (pending() && !context(depot).equals(journal().orElseThrow().before().context())) { continue; }
            candidates.addLast(depot);
        }
        active = true;
        awaitingReceipt = pending();
        openNext();
    }

    void tick() {
        if (!active) { return; }
        try {
            if (destination == null || !contextMatches(context(destination))) {
                fail("Pickup storage world or registered depot changed.");
                return;
            }
            depots.tick();
            if (depots.automationBlocked()) { fail(depots.automationBlockDetail()); return; }
            GenericContainerScreenHandler handler = depots.mossChest();
            if (handler == null) {
                if (!depots.mossChestActive()) {
                    if (pending()) { fail("Pickup receipt chest could not open: " + depots.mossChestFailure()); }
                    else { unavailableChests++; openNext(); }
                }
                return;
            }
            if (++fullPacketTicks > FULL_PACKET_TIMEOUT_TICKS) {
                fail("Timed out waiting for a full server inventory receipt; no transfer will be replayed.");
                return;
            }
            Optional<MossDepositJournal.Observation> observed = capture(handler);
            if (observed.isEmpty()) { return; }
            boolean justConfirmed = false;
            if (awaitingReceipt) {
                MossDepositController.Result result = controller.reconcile(observed.orElseThrow());
                detail = result.detail();
                switch (result.status()) {
                    case WAITING -> { return; }
                    case REOPEN_REQUIRED -> { controller.requestReceiptReopen(); return; }
                    case UNCERTAIN, CONTEXT_MISMATCH -> { fail(result.detail()); return; }
                    case CONFIRMED -> {
                        confirmedStacks++;
                        MossDepositJournal confirmed = journal().orElseThrow();
                        int quantity = confirmed.plan().quantity();
                        confirmedPickupItems += quantity;
                        if (MossDepositFacts.MOSS.equals(confirmed.before().slots().main()
                                .get(confirmed.plan().sourceMainIndex()).stack().itemId())) { confirmedItems += quantity; }
                        awaitingReceipt = false;
                        justConfirmed = true;
                        detail = "Stored " + confirmedPickupItems + " plain pickup items with server-confirmed receipts.";
                    }
                    case NO_PENDING -> { fail("The deposit receipt intent was unavailable."); return; }
                }
            }
            boolean liveBaselineMatches = MinecraftMossDepositCapture.matchesLiveBaseline(
                    client, handler, observed.orElseThrow().slots());
            if (justConfirmed) {
                if (wearRefresh.takeConfirmed(journal().orElseThrow(), observed.orElseThrow(), liveBaselineMatches)) {
                    depots.afterMossDepositWearReceipt(handler, observed.orElseThrow().stamp());
                } else { depots.discardMossDepositWearOpen(); }
            }
            if (!liveBaselineMatches) { return; }
            Optional<MossDepositFacts.Plan> plan = MossDepositPlanning.plan(observed.orElseThrow().slots());
            lastStorageObservation = observed.orElseThrow();
            boolean pickupRemains = observed.orElseThrow().slots().main().stream()
                    .anyMatch(slot -> slot.stack().plainPickup());
            switch (SurplusPickupPolicy.next(confirmedStacks, pickupRemains, plan.isPresent())) {
                case FINISH -> { finish(); return; }
                case NEXT_CHEST -> {
                    inspectedFullChests++;
                    if (inspectedChests.isEmpty()) { oldestStorageObservationNanos = System.nanoTime(); }
                    if (inspectedChests.size() < SurplusStorageExhaustion.MAXIMUM_CHESTS) {
                        inspectedChests.put(lastStorageObservation.context(), lastStorageObservation);
                    }
                    String closeProblem = depots.closeMossChest();
                    if (!closeProblem.isBlank()) { fail(closeProblem); }
                    else { openNext(); }
                    return;
                }
                case TRANSFER -> { }
            }
            wearRefresh.submitted(controller.begin(observed.orElseThrow(), plan.orElseThrow().sourceMainIndex()));
            awaitingReceipt = true;
            controller.requestReceiptReopen();
        } catch (IOException | RuntimeException problem) {
            fail("Pickup storage stopped: " + concise(problem));
        }
    }

    void cancel(String reason) {
        discardWearRefresh();
        exhaustion = null;
        if (controller != null) { controller.cancel(); }
        if (active) {
            active = false;
            failed = pending();
            detail = reason + (pending() ? " The existing deposit intent remains for observation only." : "");
            depots.cancelMaintenance();
        }
    }

    @Override
    public boolean matchesForDispatch(MossDepositJournal.Observation before, MossDepositFacts.Plan plan) {
        GenericContainerScreenHandler handler = depots.mossChest();
        if (!active || awaitingReceipt || handler == null || !contextMatches(before.context())) { return false; }
        return capture(handler).filter(before::equals).isPresent()
                && MinecraftMossDepositCapture.matchesLiveBaseline(client, handler, before.slots());
    }

    @Override
    public void quickMoveOnce(MossDepositJournal intent) throws IOException {
        if (!matchesForDispatch(intent.before(), intent.plan())) {
            throw new IOException("The accepted physical chest or exact inventory changed after intent persistence.");
        }
        GenericContainerScreenHandler handler = depots.mossChest();
        Runnable click = () -> client.interactionManager.clickSlot(handler.syncId, intent.plan().sourceHandlerSlot(),
                0, SlotActionType.QUICK_MOVE, client.player);
        if (mossCustody == null) { click.run(); }
        else { mossCustody.clickSlot(handler, intent.plan().sourceHandlerSlot(), 0, SlotActionType.QUICK_MOVE, click); }
    }

    @Override
    public void requestReceiptReopen(MossDepositJournal.Context expected) throws IOException {
        if (!active || destination == null || !context(destination).equals(expected) || !contextMatches(expected)) {
            throw new IOException("Pickup receipt reopen no longer matches the approved physical chest.");
        }
        String problem = depots.closeMossChest();
        if (!problem.isBlank()) { throw new IOException(problem); }
        fullPacketTicks = 0;
        problem = depots.beginMossChest(destination.id());
        if (!problem.isBlank()) { throw new IOException(problem); }
        detail = "Reopening " + destination.id().value() + " for a full server inventory receipt.";
    }

    private Optional<MossDepositJournal.Observation> capture(GenericContainerScreenHandler handler) {
        return ServerInventorySnapshotObserver.latestMatching(client.world, client.getNetworkHandler(), handler)
                .flatMap(packet -> MinecraftMossDepositCapture.capture(client, handler, packet)
                        .map(slots -> {
                            depots.observeMossStock(handler, packet);
                            return new MossDepositJournal.Observation(context(destination), packet.stamp(), slots);
                        }));
    }

    private MossDepositJournal.Context context(RegisteredDepot depot) {
        return new MossDepositJournal.Context(depot.worldIdentityHash(), depot.dimension(), planId,
                depot.id().value(), depot.x(), depot.y(), depot.z());
    }

    private boolean contextMatches(MossDepositJournal.Context expected) {
        try {
            RunContext world = MinecraftRunContext.capture(client);
            return world.worldIdentityHash().equals(expected.worldIdentityHash())
                    && world.dimension().equals(expected.dimension())
                    && depots.mossStorageCandidates().stream().anyMatch(depot ->
                    depot.id().value().equals(expected.depotId()) && depot.x() == expected.depotX()
                            && depot.y() == expected.depotY() && depot.z() == expected.depotZ());
        } catch (RuntimeException unavailable) { return false; }
    }

    private void openNext() {
        discardWearRefresh();
        destination = candidates.pollFirst();
        if (destination == null) {
            if (pending()) {
                fail("The pending deposit's original registered chest is unavailable.");
            } else {
                active = false;
                failed = false;
                capacityDetail = "Pickup storage capacity blocked: " + inspectedFullChests
                        + " registered chests inspected without another eligible pickup transfer; "
                        + unavailableChests + " chest openings unavailable. Items were retained.";
                if (unavailableChests == 0 && registeredAtStart.equals(depots.mossStorageCandidates().stream()
                        .map(this::context).toList())) {
                    exhaustion = SurplusStorageExhaustion.capture(registeredAtStart,
                            List.copyOf(inspectedChests.values()), lastStorageObservation,
                            oldestStorageObservationNanos, System.nanoTime()).orElse(null);
                }
                detail = capacityDetail;
            }
            return;
        }
        fullPacketTicks = 0;
        wearRefresh.arm(pending());
        String problem = depots.beginMossChest(destination.id(), () -> {
            if (wearRefresh.captureBeforeOpen()) { depots.beforeMossDepositWearOpen(); }
        });
        if (!problem.isBlank()) { fail(problem); }
        else { detail = "Opening " + destination.id().value() + " to inspect actual pickup storage capacity."; }
    }

    private void finish() {
        discardWearRefresh();
        String problem = depots.closeMossChest();
        if (!problem.isBlank()) { fail(problem); return; }
        active = false;
        failed = false;
        depots.resumeAutomaticScansAfterMoss();
        if (confirmedPickupItems == 0) { detail = "No plain clearing pickups need storage."; }
    }

    private void fail(String reason) {
        discardWearRefresh();
        exhaustion = null;
        active = false;
        failed = true;
        detail = reason;
        // A failed atomic replacement may already have committed its intent on disk.
        reloadController();
        if (controller != null) { controller.cancel(); }
        depots.cancelMaintenance();
    }

    private void discardWearRefresh() {
        wearRefresh.cancel();
        depots.discardMossDepositWearOpen();
    }

    private void reloadController() {
        try {
            controller = new MossDepositController(store, this);
            loadFailure = "";
        } catch (IOException | RuntimeException failure) {
            controller = null;
            loadFailure = "Pickup storage journal could not be read: " + concise(failure);
        }
    }

    private static String concise(Throwable problem) {
        String message = problem.getMessage();
        return message == null ? problem.getClass().getSimpleName()
                : message.substring(0, Math.min(350, message.length()));
    }
}
