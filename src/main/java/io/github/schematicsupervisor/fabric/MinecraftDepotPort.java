package io.github.schematicsupervisor.fabric;

import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.pathing.goals.GoalNear;
import com.google.gson.JsonIOException;
import io.github.schematicsupervisor.core.DepotId;
import io.github.schematicsupervisor.core.DepotStock;
import io.github.schematicsupervisor.core.DepotWithdrawal;
import io.github.schematicsupervisor.core.Material;
import io.github.schematicsupervisor.core.MaterialQuantities;
import io.github.schematicsupervisor.core.RestockTransferSnapshot;
import io.github.schematicsupervisor.core.RestockTransferStatus;
import io.github.schematicsupervisor.core.SupervisorPorts;
import io.github.schematicsupervisor.fabric.DepotCancellationPolicy.Termination;
import io.github.schematicsupervisor.fabric.mixin.ClientWorldPendingUpdatesAccessor;
import io.github.schematicsupervisor.fabric.mixin.PendingBlockUpdatesAccessor;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.TreeMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import net.minecraft.block.ChestBlock;
import net.minecraft.block.Blocks;
import net.minecraft.block.enums.ChestType;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.ActionResult;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.world.RaycastContext;

/**
 * Client-thread, tick-driven registered-chest depot adapter.
 *
 * <p>The adapter never reads remote chest contents without opening the real container. A depot
 * loaded from disk remains absent from {@link #snapshot()} until it has been scanned in the
 * current session. Each inventory click is acknowledged from observed client state before another
 * click is sent.</p>
 */
final class MinecraftDepotPort implements SupervisorPorts.Depots {
    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger(MinecraftDepotPort.class);
    private static final int NAVIGATION_TIMEOUT_TICKS = 20 * 120;
    private static final int SCREEN_TIMEOUT_TICKS = 60;
    private static final int ACTION_TIMEOUT_TICKS = 20;

    private final MinecraftClient client;
    private final IBaritone baritone;
    private final MinecraftFlightNavigation flightNavigation;
    private java.util.function.Predicate<net.minecraft.util.math.Box> mossFlightConstraint = box -> true;
    private boolean mossFlightOnly;
    private final BaritoneCancellationGuard cancellationGuard;
    private final DepotRegistryStore store;
    private final DepotRegistryPruner registryPruner;
    private final int pathGoalRadius;
    private final Map<DepotId, RegisteredDepot> depots = new LinkedHashMap<>();
    private final Deque<DepotId> scanQueue = new ArrayDeque<>();
    private final TreeMap<Material, Long> unpolledMovement = new TreeMap<>();

    private OperationKind operation = OperationKind.NONE;
    private Stage stage = Stage.IDLE;
    private RegisteredDepot currentDepot;
    private List<DepotWithdrawal> withdrawalPlan = List.of();
    private int withdrawalIndex;
    private ExactTransferLedger transferLedger;
    private Material activeMaterial;
    private int heldSourceSlot = -1;
    private PendingAction pendingAction;
    private int stageTicks;
    private boolean withdrawalCapacityRejected;
    private RestockTransferStatus withdrawalStatus = RestockTransferStatus.IDLE;
    private String withdrawalDetail = "";
    private Termination termination = Termination.NONE;
    private BaritoneSettingLease navigationSettings;
    private boolean flightRoute;
    private boolean waitingForPage;
    private String pageBlocker = "";
    // The failing withdrawal found no route to its current depot before that chest was opened.
    private boolean withdrawalDepotUnreachable;
    private int openingPlayerHandlerSyncId = -1;
    private DepotContainerIdentity containerIdentity = DepotContainerIdentity.inactive();
    private CancellationOpenPolicy outstandingOpenPolicy;
    private DepotInitialInventoryGate<ItemStack> initialInventoryGate;
    private MinecraftExecutionPort mossWearExecution;
    private Runnable beforeMossOpening;
    private MossToolCustody mossCustody;
    private QueuedOpenQuarantinePolicy openQuarantine;
    private String maintenanceFailureDetail = "";
    private String registryPruneFailure = "";
    private RunContext autoScanContext;
    private boolean automaticScansPaused;
    private String mossChestFailure = "";
    private GenericContainerScreenHandler acceptedMossHandler;
    private Object acceptedMossWorld;
    private Object acceptedMossConnection;

    MinecraftDepotPort(
            MinecraftClient client,
            Path registryPath,
            int pathGoalRadius
    ) throws IOException {
        this.client = Objects.requireNonNull(client, "client");
        if (pathGoalRadius < 1 || pathGoalRadius > 8) {
            throw new IllegalArgumentException("pathGoalRadius must be between 1 and 8");
        }
        this.pathGoalRadius = pathGoalRadius;
        store = new DepotRegistryStore(Objects.requireNonNull(registryPath, "registryPath"));
        registryPruner = new DepotRegistryPruner(depots, scanQueue, store::save);
        baritone = BaritoneAPI.getProvider().getPrimaryBaritone();
        flightNavigation = new MinecraftFlightNavigation(client,
                box -> operation != OperationKind.MOSS_DEPOSIT || mossFlightConstraint.test(box));
        cancellationGuard = new BaritoneCancellationGuard(
                () -> {
                    flightNavigation.stop();
                    flightRoute = false;
                },
                () -> baritone.getPathingBehavior().cancelEverything(),
                () -> baritone.getPathingBehavior().forceCancel(),
                () -> baritone.getBuilderProcess().onLostControl(),
                () -> baritone.getCustomGoalProcess().onLostControl(),
                this::restoreNavigationSettings
        );
        for (RegisteredDepot depot : store.load()) {
            depots.put(depot.id(), depot);
        }
    }

    private boolean supplyInPlace;

    void configureSupplyInPlace(boolean enabled) {
        supplyInPlace = enabled;
        if (enabled) { automaticScansPaused = true; scanQueue.clear(); }
    }

    void configureMossCustody(MossToolCustody custody) { mossCustody = Objects.requireNonNull(custody); }

    void configureMossWearExecution(MinecraftExecutionPort execution) { mossWearExecution = execution; }

    void beforeMossDepositWearOpen() {
        if (mossWearExecution != null) { mossWearExecution.beforeMossWearDepotOpen(); }
    }

    void discardMossDepositWearOpen() {
        if (mossWearExecution != null) { mossWearExecution.discardMossWearDepotOpen(); }
    }

    void afterMossDepositWearReceipt(GenericContainerScreenHandler handler, ServerInventorySnapshotStamp stamp) {
        var packet = ServerInventorySnapshotObserver.latestMatching(client.world, client.getNetworkHandler(), handler)
                .filter(value -> value.stamp().equals(stamp)).orElse(null);
        if (mossWearExecution != null) {
            if (handler == mossChest() && packet != null) { mossWearExecution.afterMossWearDepotReceipt(handler, packet); }
            else { mossWearExecution.discardMossWearDepotOpen(); }
        }
    }

    /**
     * Advances navigation, container scanning, and at most one inventory interaction.
     */
    void tick() {
        if (cancellationGuard.blocked()) {
            return;
        }
        if (openQuarantine != null) {
            tickQuarantinedOpen();
            return;
        }
        if (client.player == null || client.world == null || client.interactionManager == null) {
            autoScanContext = null;
            scanQueue.clear();
            if (operation == OperationKind.WITHDRAWAL) {
                requestWithdrawalFailure("client world became unavailable");
            } else if (operation == OperationKind.SCAN || operation == OperationKind.MOSS_DEPOSIT) {
                failMaintenance("client world became unavailable");
            }
            if (!cancellationGuard.blocked() && outstandingOpenPolicy != null) {
                enterOpenQuarantine(
                        "queued depot screen response cannot be resolved because "
                                + "the client world is unavailable"
                );
            }
            return;
        }

        pruneMissingDepots();
        if (operation == OperationKind.NONE) {
            refreshAutoScanQueue();
            // Routes run behind an idle Inventory, Settings page or chat; only the chest access leaves the page.
            if (!MinecraftBackgroundBuildAccess.allowsWorldActions(client)) { return; }
            beginQueuedScan();
            return;
        }

        if (stage == Stage.NAVIGATING && matchesCurrentRunContext(currentDepot)
                && !MinecraftBackgroundBuildAccess.allowsWorldActions(client)) {
            flightNavigation.waitForScreen();
            return;
        }
        stageTicks = Math.addExact(stageTicks, 1);
        switch (stage) {
            case NAVIGATING -> tickNavigation();
            case WAITING_FOR_SCREEN -> tickWaitingForScreen();
            case CANCELLING_OPEN -> tickCancellingOpen();
            case CANCELLING_SCAN_OPEN -> tickCancellingScanOpen();
            case TRANSFERRING -> tickTransfer();
            case MOSS_CHEST_READY -> {
                if (!validDepotContext(true) || currentContainer() == null) {
                    failActive("Moss storage chest identity changed.");
                }
            }
            case CLEANING_UP -> tickCleanup();
            case IDLE -> {
                // The operation transition methods keep these states aligned.
            }
        }
    }

    private void pruneMissingDepots() {
        boolean settled = operation == OperationKind.NONE && stage == Stage.IDLE
                && pendingAction == null && outstandingOpenPolicy == null && !containerIdentity.active();
        try {
            List<DepotId> removed = registryPruner.tick(currentRunContextOrNull(), settled, depot -> {
                BlockPos position = blockPosition(depot);
                if (!ClientChunkAvailability.isLoaded(client.world, position)) {
                    return new DepotRegistryPruner.Presence(false, false, false);
                }
                var manager = ((ClientWorldPendingUpdatesAccessor) client.world).supervisor$getPendingUpdateManager();
                boolean predictionPending = ((PendingBlockUpdatesAccessor) manager)
                        .supervisor$getPendingBlockUpdates().containsKey(position.asLong());
                return new DepotRegistryPruner.Presence(true, predictionPending,
                        client.world.getBlockState(position).getBlock() instanceof ChestBlock);
            });
            if (!removed.isEmpty()) {
                registryPruneFailure = "";
                client.player.sendMessage(Text.literal("[Schematic Supervisor] Removed " + removed.size()
                        + " missing material chest(s) from the depot registry."), false);
            }
        } catch (IOException | JsonIOException exception) {
            String failure = "Could not save automatic depot cleanup: " + conciseMessage(exception);
            if (!failure.equals(registryPruneFailure)) { LOGGER.warn(failure); }
            registryPruneFailure = failure;
        }
    }

    String registerTargetedDepot() {
        if (automationBlocked()) {
            return automationBlockDetail();
        }
        if (client.player == null || client.world == null) {
            return "No client world is available.";
        }
        if (!(client.crosshairTarget instanceof BlockHitResult hit)
                || hit.getType() != HitResult.Type.BLOCK) {
            return "Look directly at a chest before registering a depot.";
        }
        BlockPos targeted = hit.getBlockPos();
        if (!(client.world.getBlockState(targeted).getBlock() instanceof ChestBlock)) {
            return "The targeted block is not a chest.";
        }
        BlockPos position = canonicalChestPosition(targeted);

        RunContext context = currentRunContextOrNull();
        if (context == null) {
            return "The current world identity is unavailable.";
        }
        for (RegisteredDepot depot : depots.values()) {
            if (depot.sameLocation(
                    context,
                    position.getX(),
                    position.getY(),
                    position.getZ()
            )) {
                enqueueScan(depot.id());
                return "Depot " + depot.id().value() + " is already registered; scan queued.";
            }
        }

        DepotId id = nextDepotId();
        RegisteredDepot registered = RegisteredDepot.unscanned(
                id,
                context.worldIdentityHash(),
                context.dimension(),
                position.getX(),
                position.getY(),
                position.getZ()
        );
        List<RegisteredDepot> candidate = new ArrayList<>(depots.values());
        candidate.add(registered);
        try {
            store.save(candidate);
        } catch (IOException | JsonIOException exception) {
            return "Could not save depot registry: " + conciseMessage(exception);
        }
        depots.put(id, registered);
        enqueueScan(id);
        return "Registered " + id.value() + "; real chest scan queued.";
    }

    String registerNearbyDepots(int radius) {
        if (radius < 1 || radius > 16) {
            throw new IllegalArgumentException("Depot search radius must be between 1 and 16 blocks.");
        }
        if (automationBlocked()) { return automationBlockDetail(); }
        if (client.player == null || client.world == null) { return "No client world is available."; }
        if (!maintenanceIdle()) { return "Wait for current depot maintenance before registering nearby chests."; }
        RunContext context = currentRunContextOrNull();
        if (context == null) { return "The current world identity is unavailable."; }
        BlockPos center = client.player.getBlockPos();
        Set<BlockPos> positions = new HashSet<>();
        for (BlockPos position : BlockPos.iterate(center.add(-radius, -radius, -radius),
                center.add(radius, radius, radius))) {
            if (!ClientChunkAvailability.isLoaded(client.world, position)
                    || !(client.world.getBlockState(position).getBlock() instanceof ChestBlock)) { continue; }
            BlockPos canonical = canonicalChestPosition(position).toImmutable();
            if (!ClientChunkAvailability.isLoaded(client.world, canonical)) { continue; }
            positions.add(canonical);
            if (positions.size() > 64) { return "More than 64 nearby chests found; use a smaller radius."; }
        }
        if (positions.isEmpty()) { return "No received chest blocks found within " + radius + " blocks."; }
        List<RegisteredDepot> candidate = new ArrayList<>(depots.values());
        List<DepotId> scans = new ArrayList<>();
        Set<DepotId> used = new HashSet<>(depots.keySet());
        int added = 0;
        int nextId = 1;
        for (BlockPos position : positions.stream().sorted(Comparator.comparingInt(BlockPos::getY)
                .thenComparingInt(BlockPos::getX).thenComparingInt(BlockPos::getZ)).toList()) {
            RegisteredDepot existing = candidate.stream().filter(depot -> depot.sameLocation(context,
                    position.getX(), position.getY(), position.getZ())).findFirst().orElse(null);
            if (existing != null) { scans.add(existing.id()); continue; }
            DepotId id;
            do { id = new DepotId(String.format(Locale.ROOT, "depot-%03d", nextId++)); } while (!used.add(id));
            candidate.add(RegisteredDepot.unscanned(id, context.worldIdentityHash(), context.dimension(),
                    position.getX(), position.getY(), position.getZ()));
            scans.add(id);
            added++;
        }
        try { store.save(candidate); }
        catch (IOException | JsonIOException exception) {
            return "Could not save depot registry: " + conciseMessage(exception);
        }
        for (RegisteredDepot depot : candidate) { depots.put(depot.id(), depot); }
        scans.forEach(this::enqueueScan);
        return "Registered " + added + " nearby depot(s); " + scans.size() + " real chest scan(s) queued.";
    }

    List<String> listDepots() {
        RunContext context = currentRunContextOrNull();
        if (context == null) {
            return List.of();
        }
        return depots.values().stream()
                .filter(depot -> depot.matches(context))
                .sorted(Comparator.comparing(RegisteredDepot::id))
                .map(MinecraftDepotPort::describe)
                .toList();
    }

    /** Reads session observations without scheduling scans, opening chests, or settling transfers. */
    DepotObservation observation() {
        RunContext context = currentRunContextOrNull();
        if (context == null) {
            return DepotObservation.unavailable("Current world context is unavailable.");
        }
        boolean operationMatches = currentDepot == null || currentDepot.matches(context);
        String detail = currentDepot == null ? automationBlocked() ? "Depot cleanup is blocked." : registryPruneFailure
                : !operationMatches ? "Depot operation belongs to another context."
                : automationBlocked() ? automationBlockDetail()
                : !maintenanceFailureDetail.isBlank() ? maintenanceFailureDetail
                : withdrawalStatus == RestockTransferStatus.FAILED
                        || withdrawalStatus == RestockTransferStatus.UNREACHABLE ? withdrawalDetail
                : waitingForPage && stage == Stage.NAVIGATING ? "At " + currentDepot.id().value()
                        + "; waiting to leave the open page for the chest"
                        + (pageBlocker.isBlank() ? "." : ": " + pageBlocker + ".")
                : flightStatus();
        return DepotObservation.capture(context, depots.values(),
                operationMatches && currentDepot != null ? currentDepot.id() : null,
                scanQueue, operation.name(), stage.name(), automaticScansPaused, automationBlocked(), detail);
    }

    /** Whether an operation runs or a scan waits; unlike {@link #maintenanceIdle()} it never queues scans. */
    boolean maintenanceActive() {
        return operation != OperationKind.NONE || !scanQueue.isEmpty();
    }

    boolean maintenanceIdle() {
        if (automationBlocked()) {
            return false;
        }
        if (operation == OperationKind.NONE) {
            refreshAutoScanQueue();
        }
        return DepotCancellationPolicy.maintenanceIdle(
                operation == OperationKind.NONE,
                scanQueue.isEmpty()
        );
    }

    List<RegisteredDepot> mossStorageCandidates() {
        RunContext context = currentRunContextOrNull();
        return context == null ? List.of() : depots.values().stream()
                .filter(depot -> depot.matches(context))
                .sorted(Comparator.comparing(RegisteredDepot::id)).toList();
    }

    String beginMossChest(DepotId id) {
        return beginMossChest(id, box -> true, false, null);
    }

    String beginMossChest(DepotId id, Runnable beforeOpening) {
        return beginMossChest(id, box -> true, false, Objects.requireNonNull(beforeOpening));
    }

    String beginMossChest(DepotId id, java.util.function.Predicate<net.minecraft.util.math.Box> movementAllowed) {
        return beginMossChest(id, movementAllowed, true, null);
    }

    private String beginMossChest(DepotId id,
            java.util.function.Predicate<net.minecraft.util.math.Box> movementAllowed, boolean flightOnly,
            Runnable beforeOpening) {
        if (!maintenanceIdle() || operation != OperationKind.NONE) {
            return "Depot maintenance must finish before moss storage.";
        }
        RegisteredDepot depot = depots.get(id);
        if (depot == null || !matchesCurrentRunContext(depot)) {
            return "Moss storage requires a registered chest in the current world.";
        }
        mossFlightConstraint = Objects.requireNonNull(movementAllowed, "movementAllowed");
        mossFlightOnly = flightOnly;
        beforeMossOpening = beforeOpening;
        mossChestFailure = "";
        currentDepot = depot.markUnscanned();
        depots.put(id, currentDepot);
        operation = OperationKind.MOSS_DEPOSIT;
        beginNavigation();
        return mossChestFailure;
    }

    GenericContainerScreenHandler mossChest() {
        return operation == OperationKind.MOSS_DEPOSIT && stage == Stage.MOSS_CHEST_READY
                && validDepotContext(true) ? currentContainer() : null;
    }

    boolean mossChestActive() { return operation == OperationKind.MOSS_DEPOSIT; }

    String mossChestFailure() { return mossChestFailure; }

    void tickCancelledMaintenance() {
        if (openQuarantine != null || stage == Stage.CANCELLING_OPEN
                || stage == Stage.CANCELLING_SCAN_OPEN || stage == Stage.CLEANING_UP) {
            tick();
        }
    }

    void resumeAutomaticScansAfterMoss() {
        if (supplyInPlace) { return; }
        if (automaticScansPaused && operation == OperationKind.NONE && !automationBlocked()) {
            automaticScansPaused = false;
            autoScanContext = null;
        }
    }

    void observeMossStock(GenericContainerScreenHandler expected,
                          ServerInventorySnapshotObserver.FullSnapshot packet) {
        if (expected != mossChest() || packet.stamp().syncId() != expected.syncId
                || packet.rows() != expected.getRows()) {
            throw new IllegalStateException("Moss stock observation no longer belongs to the accepted chest.");
        }
        var slots = packet.slots();
        int chestSlots = expected.getRows() * 9;
        if (slots.size() != chestSlots + 36) {
            throw new IllegalStateException("Incomplete server chest contents.");
        }
        var contents = new net.minecraft.inventory.SimpleInventory(
                slots.subList(0, chestSlots).toArray(net.minecraft.item.ItemStack[]::new));
        currentDepot = currentDepot.withScan(MinecraftMaterials.count(contents));
        depots.put(currentDepot.id(), currentDepot);
    }

    String closeMossChest() {
        if (operation != OperationKind.MOSS_DEPOSIT) { return mossChestFailure; }
        GenericContainerScreenHandler handler = mossChest();
        if (handler == null || !handler.getCursorStack().isEmpty()) {
            return "Moss storage can close only its accepted chest with an empty cursor.";
        }
        closeHandledScreen();
        finishMaintenance();
        return "";
    }

    boolean automationBlocked() {
        return cancellationGuard.blocked() || openQuarantine != null;
    }

    String automationBlockDetail() {
        if (cancellationGuard.blocked() && openQuarantine != null) {
            return cancellationGuard.blockedDetail() + " " + openQuarantine.detail();
        }
        if (cancellationGuard.blocked()) {
            return cancellationGuard.blockedDetail();
        }
        return openQuarantine == null ? "" : openQuarantine.detail();
    }

    String prepareForExplicitReset() {
        boolean releasedBlock = cancellationGuard.blocked();
        if (releasedBlock && !cancellationGuard.retryForReset()) {
            return automationBlockDetail();
        }
        if (openQuarantine != null) {
            return openQuarantine.detail();
        }

        if (operation == OperationKind.SCAN || operation == OperationKind.MOSS_DEPOSIT) {
            if (stage == Stage.CANCELLING_SCAN_OPEN) {
                return "Depot maintenance screen-close resumed; wait for it to settle, then reset again.";
            }
            if (releasedBlock) {
                finishMaintenance();
                return "";
            }
            return stopCurrentMaintenance(
                    "could not stop depot maintenance before explicit reset"
            ) ? "" : maintenanceStopDetail();
        }
        if (operation != OperationKind.WITHDRAWAL) {
            return "";
        }
        if (stage == Stage.CANCELLING_OPEN) {
            return "Depot screen-open cancellation resumed; wait for it to settle, then reset again.";
        }
        if (!releasedBlock) {
            return "Depot withdrawal cleanup is still active; wait for it to settle, then reset again.";
        }
        if (stage != Stage.CLEANING_UP) {
            termination = Termination.FAIL;
            if (withdrawalDetail.isBlank()) {
                withdrawalDetail =
                        "Blocked depot withdrawal entered explicit reset cleanup.";
            }
            stage = Stage.CLEANING_UP;
            stageTicks = 0;
        }
        if (pendingAction == null) {
            beginCursorReturnOrFinishTermination();
        }
        return operation == OperationKind.WITHDRAWAL
                ? "Depot withdrawal cleanup resumed; wait for it to settle, then reset again."
                : "";
    }

    MaterialQuantities totalScannedStock() {
        if (automationBlocked()) {
            return MaterialQuantities.empty();
        }
        RunContext context = currentRunContextOrNull();
        if (context == null) {
            return MaterialQuantities.empty();
        }
        MaterialQuantities total = MaterialQuantities.empty();
        for (RegisteredDepot depot : depots.values()) {
            if (depot.matches(context) && depot.scanned()) {
                total = total.plus(depot.cachedStock());
            }
        }
        return total;
    }

    String rescanDepots() {
        if (automationBlocked()) {
            return automationBlockDetail();
        }
        if (operation == OperationKind.WITHDRAWAL || operation == OperationKind.MOSS_DEPOSIT) {
            return "Cannot rescan depots during a withdrawal.";
        }
        RunContext context = currentRunContextOrNull();
        if (context == null) {
            return "The current world identity is unavailable.";
        }
        if (!stopCurrentMaintenance("could not stop depot maintenance before rescan")) {
            return maintenanceStopDetail();
        }
        automaticScansPaused = false;
        scanQueue.clear();
        for (Map.Entry<DepotId, RegisteredDepot> entry : depots.entrySet()) {
            if (entry.getValue().matches(context)) {
                entry.setValue(entry.getValue().markUnscanned());
                scanQueue.addLast(entry.getKey());
            }
        }
        return "Queued " + scanQueue.size() + " depot scan(s).";
    }

    /** Stops queued and active scans while allowing an outstanding chest-open response to settle. */
    String cancelMaintenance() {
        automaticScansPaused = true;
        scanQueue.clear();
        autoScanContext = currentRunContextOrNull();
        if (!stopCurrentMaintenance("could not stop depot maintenance after operator cancellation")) {
            return maintenanceStopDetail();
        }
        return "";
    }

    String flightStatus() {
        return flightRoute && flightNavigation.active() ? flightNavigation.detail() : "";
    }

    String clearDepots() {
        if (automationBlocked()) {
            return automationBlockDetail();
        }
        if (operation == OperationKind.WITHDRAWAL || operation == OperationKind.MOSS_DEPOSIT) {
            return "Cannot clear depots during a withdrawal.";
        }
        RunContext context = currentRunContextOrNull();
        if (context == null) {
            return "The current world identity is unavailable.";
        }
        List<RegisteredDepot> retained = depots.values().stream()
                .filter(depot -> !depot.matches(context))
                .toList();
        if (!stopCurrentMaintenance("could not stop depot maintenance before clear")) {
            return maintenanceStopDetail();
        }
        try {
            store.save(retained);
        } catch (IOException | JsonIOException exception) {
            return "Could not save the depot registry: " + conciseMessage(exception);
        }
        scanQueue.clear();
        int count = Math.subtractExact(depots.size(), retained.size());
        depots.entrySet().removeIf(entry -> entry.getValue().matches(context));
        return "Cleared " + count + " registered depot(s).";
    }

    @Override
    public List<DepotStock> snapshot() {
        if (supplyInPlace || automationBlocked()) {
            return List.of();
        }
        RunContext context = currentRunContextOrNull();
        if (context == null) {
            return List.of();
        }
        return depots.values().stream()
                .filter(depot -> depot.matches(context))
                .filter(RegisteredDepot::scanned)
                .sorted(Comparator.comparing(RegisteredDepot::id))
                .map(RegisteredDepot::toStock)
                .toList();
    }

    @Override
    public void beginWithdrawal(List<DepotWithdrawal> withdrawals) {
        Objects.requireNonNull(withdrawals, "withdrawals");
        if (supplyInPlace) { throw new IllegalStateException("Material supplies must be purchased at the current position"); }
        if (automationBlocked()) {
            withdrawalStatus = RestockTransferStatus.FAILED;
            withdrawalDetail = automationBlockDetail();
            return;
        }
        if (withdrawalStatus == RestockTransferStatus.RUNNING) {
            throw new IllegalStateException("a depot withdrawal is already running");
        }
        RunContext context = currentRunContextOrNull();
        if (context == null) {
            setImmediateWithdrawalFailure("the current world identity is unavailable");
            return;
        }

        if (!stopCurrentMaintenance("could not stop depot maintenance before withdrawal")) {
            withdrawalStatus = RestockTransferStatus.FAILED;
            withdrawalDetail = maintenanceStopDetail();
            return;
        }
        scanQueue.clear();
        resetWithdrawalFields();
        if (withdrawals.isEmpty()) {
            withdrawalStatus = RestockTransferStatus.SUCCEEDED;
            return;
        }

        Set<DepotId> seen = new HashSet<>();
        for (DepotWithdrawal withdrawal : withdrawals) {
            Objects.requireNonNull(withdrawal, "withdrawal");
            RegisteredDepot depot = depots.get(withdrawal.depot());
            if (depot == null) {
                setImmediateWithdrawalFailure(
                        "withdrawal references an unregistered depot: "
                                + withdrawal.depot().value()
                );
                return;
            }
            if (!depot.matches(context)) {
                setImmediateWithdrawalFailure(
                        "withdrawal references a depot from another world context: "
                                + withdrawal.depot().value()
                );
                return;
            }
            if (!depot.scanned()) {
                setImmediateWithdrawalFailure(
                        "depot has not been scanned in this session: "
                                + withdrawal.depot().value()
                );
                return;
            }
            if (!seen.add(withdrawal.depot())) {
                setImmediateWithdrawalFailure(
                        "withdrawal repeats depot: " + withdrawal.depot().value()
                );
                return;
            }
            MaterialQuantities missing = withdrawal.quantities().shortageFrom(depot.cachedStock());
            if (!missing.isEmpty()) {
                setImmediateWithdrawalFailure(
                        "cached stock no longer covers allocation at "
                                + withdrawal.depot().value()
                );
                return;
            }
        }

        withdrawalPlan = List.copyOf(withdrawals);
        withdrawalStatus = RestockTransferStatus.RUNNING;
        operation = OperationKind.WITHDRAWAL;
        withdrawalIndex = 0;
        startWithdrawalDepot();
    }

    @Override
    public RestockTransferSnapshot pollWithdrawal() {
        MaterialQuantities delta = MaterialQuantities.of(unpolledMovement);
        unpolledMovement.clear();
        return switch (withdrawalStatus) {
            case IDLE -> new RestockTransferSnapshot(
                    RestockTransferStatus.IDLE,
                    delta,
                    withdrawalDetail
            );
            case RUNNING -> RestockTransferSnapshot.running(delta);
            case SUCCEEDED -> RestockTransferSnapshot.succeeded(delta);
            case FAILED -> RestockTransferSnapshot.failed(delta, withdrawalDetail);
            case UNREACHABLE -> RestockTransferSnapshot.unreachable(delta, withdrawalDetail);
            case CAPACITY_BLOCKED -> new RestockTransferSnapshot(
                    RestockTransferStatus.CAPACITY_BLOCKED, delta, withdrawalDetail);
        };
    }

    @Override
    public void cancelWithdrawal() {
        DepotCancellationPolicy.State current = new DepotCancellationPolicy.State(
                withdrawalStatus,
                termination,
                withdrawalDetail,
                MaterialQuantities.of(unpolledMovement)
        );
        DepotCancellationPolicy.State requested =
                DepotCancellationPolicy.requestCancellation(
                        current,
                        operation == OperationKind.WITHDRAWAL
                );
        if (requested == current) {
            return;
        }
        withdrawalStatus = requested.status();
        termination = requested.termination();
        withdrawalDetail = requested.detail();
        if (withdrawalStatus != RestockTransferStatus.RUNNING) {
            return;
        }

        Stage interruptedStage = stage;
        if (currentDepot != null && depots.containsKey(currentDepot.id())) {
            DepotId interrupted = currentDepot.id();
            currentDepot = currentDepot.markUnscanned();
            depots.put(interrupted, currentDepot);
            enqueueScan(interrupted);
        }
        if (interruptedStage == Stage.WAITING_FOR_SCREEN) {
            if (outstandingOpenPolicy == null) {
                finishTermination(
                        "cancelled depot open had no outstanding screen identity"
                );
                return;
            }
            stage = Stage.CANCELLING_OPEN;
        } else {
            stage = Stage.CLEANING_UP;
            stageTicks = 0;
        }

        if (!stopBaritone("could not fully cancel depot navigation")) {
            DepotCancellationPolicy.State failed =
                    DepotCancellationPolicy.cancellationFailure(
                            new DepotCancellationPolicy.State(
                                    withdrawalStatus,
                                    termination,
                                    withdrawalDetail,
                                    MaterialQuantities.of(unpolledMovement)
                            ),
                            cancellationGuard.blockedDetail()
                    );
            termination = failed.termination();
            withdrawalDetail = failed.detail();
            if (DepotCancellationPolicy.mayFinishAfterStopFailure(
                    stage == Stage.CANCELLING_OPEN,
                    pendingAction != null
            )) {
                finishTermination("");
            }
            return;
        }

        if (stage == Stage.CLEANING_UP && pendingAction == null) {
            beginCursorReturnOrFinishTermination();
        }
    }

    private void beginQueuedScan() {
        RunContext context = currentRunContextOrNull();
        if (context == null) {
            scanQueue.clear();
            return;
        }
        while (!scanQueue.isEmpty()) {
            DepotId id = scanQueue.removeFirst();
            RegisteredDepot depot = depots.get(id);
            if (depot != null && depot.matches(context)) {
                currentDepot = depot.markUnscanned();
                depots.put(id, currentDepot);
                operation = OperationKind.SCAN;
                beginNavigation();
                return;
            }
        }
    }

    private void startWithdrawalDepot() {
        DepotWithdrawal withdrawal = withdrawalPlan.get(withdrawalIndex);
        currentDepot = Objects.requireNonNull(depots.get(withdrawal.depot()));
        if (!matchesCurrentRunContext(currentDepot)) {
            setImmediateWithdrawalFailure(
                    "depot world context changed before withdrawal navigation"
            );
            return;
        }
        currentDepot = currentDepot.markUnscanned();
        depots.put(currentDepot.id(), currentDepot);
        transferLedger = new ExactTransferLedger(withdrawal.quantities());
        activeMaterial = null;
        heldSourceSlot = -1;
        pendingAction = null;
        beginNavigation();
    }

    private void beginNavigation() {
        stage = Stage.NAVIGATING;
        stageTicks = 0;
        waitingForPage = false;
        resetContainerIdentity();
        outstandingOpenPolicy = null;
        if (client.player == null || client.world == null || client.interactionManager == null) {
            failActive("client world is unavailable");
            return;
        }
        if (!matchesCurrentRunContext(currentDepot)) {
            failActive(
                    "depot " + currentDepot.id().value()
                            + " belongs to another world context"
            );
            return;
        }
        if (client.player != null
                && client.player.currentScreenHandler != client.player.playerScreenHandler) {
            failActive("close the current container before depot automation");
            return;
        }
        try {
            if (operation == OperationKind.MOSS_DEPOSIT && mossFlightOnly
                    && (!client.player.getAbilities().allowFlying || !client.player.getAbilities().flying)) {
                failActive("This inventory receipt requires its constrained active-flight route.");
                return;
            }
            if (client.player.getAbilities().flying) {
                if (!stopBaritone("could not release ground navigation before depot flight")) {
                    failActive("Ground navigation could not be released before depot flight");
                    return;
                }
                BlockPos depotPosition = blockPosition(currentDepot);
                if (!validateReceivedFlightDepot(depotPosition)) { return; }
                flightRoute = true;
                double flightReach = Math.min(3.0, client.player.getBlockInteractionRange() - 0.25);
                if (flightReach <= 0) {
                    failActive("The player has no usable chest interaction range");
                    return;
                }
                flightNavigation.begin(blockPosition(currentDepot), flightReach);
                if (flightNavigation.failed()) {
                    failRoute(flightNavigation.detail(), flightNavigation.failedForLackOfRoute());
                }
                return;
            }
            navigationSettings = BaritoneSettingLease.forNonDestructiveRoute(
                    BaritoneAPI.getSettings()
            );
            baritone.getCustomGoalProcess().setGoalAndPath(
                    new GoalNear(blockPosition(currentDepot), pathGoalRadius)
            );
        } catch (RuntimeException exception) {
            failActive(
                    "could not start a non-destructive depot route: "
                            + conciseMessage(exception)
            );
        }
    }

    private void tickNavigation() {
        if (!matchesCurrentRunContext(currentDepot)) {
            failActive("player changed world context during depot navigation");
            return;
        }
        BlockPos position = blockPosition(currentDepot);
        if (flightRoute && !validateReceivedFlightDepot(position)) { return; }
        boolean reached;
        try {
            reached = canReach(position);
        } catch (RuntimeException exception) {
            failActive(
                    "could not inspect depot reachability: " + conciseMessage(exception)
            );
            return;
        }
        if (reached) {
            if (waitForPageBeforeChest()) {
                flightNavigation.waitForScreen();
                return;
            }
            if (!stopBaritone("could not stop depot navigation before chest access")) {
                failActive("Depot navigation could not be stopped before chest access");
                return;
            }
            try {
                if (!(client.world.getBlockState(position).getBlock() instanceof ChestBlock)) {
                    failActive("registered depot is no longer a chest");
                    return;
                }
                if (client.player.isSneaking()) {
                    failActive("stop sneaking before opening a registered depot");
                    return;
                }
                var interactionSlot = PlainInteractionItems.findDepotHotbarSlot(client.player.getInventory());
                if (interactionSlot.isEmpty()) {
                    failActive("depot access needs an empty or permitted plain item in the hotbar");
                    return;
                }
                client.player.getInventory().setSelectedSlot(interactionSlot.getAsInt());
                String handProblem = depotHandProblem(position);
                if (!handProblem.isEmpty()) { failActive(handProblem); return; }
                ItemStack selectedHand = client.player.getMainHandStack().copy();
                BlockHitResult hit = new BlockHitResult(
                        Vec3d.ofCenter(position),
                        Direction.UP,
                        position,
                        false
                );
                openingPlayerHandlerSyncId = client.player.currentScreenHandler.syncId;
                int expectedRows = expectedContainerRowsFor(currentDepot);
                outstandingOpenPolicy = new CancellationOpenPolicy(
                        openingPlayerHandlerSyncId,
                        expectedRows,
                        SCREEN_TIMEOUT_TICKS
                );
                initialInventoryGate = operation == OperationKind.MOSS_DEPOSIT ? null
                        : new DepotInitialInventoryGate<>(client.world, client.player, client.getNetworkHandler(),
                                openingPlayerHandlerSyncId, expectedRows, SCREEN_TIMEOUT_TICKS,
                                ItemStack::areEqual, ItemStack::isEmpty);
                stage = Stage.WAITING_FOR_SCREEN;
                stageTicks = 0;
                if (mossWearExecution != null && operation != OperationKind.MOSS_DEPOSIT) {
                    mossWearExecution.beforeMossWearDepotOpen();
                } else if (operation == OperationKind.MOSS_DEPOSIT && beforeMossOpening != null) {
                    Runnable beforeOpening = beforeMossOpening;
                    beforeMossOpening = null;
                    beforeOpening.run();
                }
                // Vanilla synchronizes the selected slot before sending this main-hand interaction.
                java.util.function.Supplier<ActionResult> open = () -> {
                    String problem = depotHandProblem(position);
                    if (!problem.isEmpty() || client.player.getInventory().getSelectedSlot() != interactionSlot.getAsInt()
                            || !ItemStack.areItemsAndComponentsEqual(selectedHand, client.player.getMainHandStack())) {
                        throw new IllegalStateException(problem.isEmpty() ? "The selected depot hand changed before chest access" : problem);
                    }
                    // Exact vanilla ChestBlock.onUse accepts before item use. Never fall back to offhand or item use.
                    return client.interactionManager.interactBlock(
                            Objects.requireNonNull(client.player), net.minecraft.util.Hand.MAIN_HAND, hit);
                };
                ActionResult opened = mossCustody == null ? open.get()
                        : mossCustody.interactBlock(net.minecraft.util.Hand.MAIN_HAND, hit, open);
                if (!opened.isAccepted()) {
                    outstandingOpenPolicy = null;
                    failActive("server rejected the registered-depot interaction");
                    return;
                }
            } catch (RuntimeException exception) {
                failActive(
                        "registered-depot screen open failed: " + conciseMessage(exception)
                );
                return;
            }
            return;
        }
        waitingForPage = false;
        int navigationTimeout = flightRoute
                ? MinecraftFlightNavigation.MAXIMUM_ACTIVE_TICKS + 20 * 31 : NAVIGATION_TIMEOUT_TICKS;
        if (stageTicks > navigationTimeout) {
            failActive("navigation to depot timed out" + (flightRoute ? ": " + flightNavigation.detail() : ""));
            return;
        }
        if (flightRoute) {
            try {
                flightNavigation.tick();
                if (flightNavigation.failed()) {
                    failRoute("Flight route to depot failed: " + flightNavigation.detail(),
                            flightNavigation.failedForLackOfRoute());
                } else if (flightNavigation.arrived() && !canReach(position)) {
                    failRoute("Flight route ended outside registered-depot interaction reach", true);
                }
            } catch (RuntimeException exception) {
                failActive("Flight route to depot failed: " + conciseMessage(exception));
            }
            return;
        }
        boolean navigationActive;
        try {
            navigationActive = baritone.getCustomGoalProcess().isActive()
                    || baritone.getPathingBehavior().isPathing();
        } catch (RuntimeException exception) {
            failActive(
                    "could not inspect depot navigation state: "
                            + conciseMessage(exception)
            );
            return;
        }
        if (!navigationActive) {
            failActive("Baritone could not reach the registered depot");
        }
    }

    /**
     * The chest screen replaces an idle Inventory or Settings page only through the guarded passive
     * transition, which reopens the page later; chat or an unsafe page waits without using route time.
     */
    private boolean waitForPageBeforeChest() {
        waitingForPage = client.currentScreen != null
                && MinecraftBackgroundBuildAccess.leavePassiveScreenForRestock(client, () -> validDepotContext(true))
                        != BackgroundBuildPolicy.RestockTransition.CLEARED
                && validDepotContext(true);
        if (waitingForPage) {
            stageTicks = Math.subtractExact(stageTicks, 1);
            pageBlocker = MinecraftBackgroundBuildAccess.pageLeaveBlocker(client);
        }
        return waitingForPage;
    }

    private void tickWaitingForScreen() {
        if (!validDepotContext(true)) {
            failActive("registered-depot context changed while opening the chest");
            return;
        }
        CancellationOpenPolicy openPolicy = outstandingOpenPolicy;
        if (openPolicy == null) {
            failActive("registered-depot open identity was unavailable");
            return;
        }
        if (client.player.currentScreenHandler instanceof GenericContainerScreenHandler handler) {
            if (handler.syncId == openPolicy.openingSyncId()) {
                failActive("registered-depot screen was not positively opened");
                return;
            }
            int expectedRows = openPolicy.expectedRows();
            if (handler.getRows() != expectedRows) {
                failActive(
                        "registered-depot screen shape did not match the targeted chest"
                );
                return;
            }
            if (operation == OperationKind.MOSS_DEPOSIT) {
                containerIdentity = DepotContainerIdentity.accepted(handler.syncId, expectedRows);
                outstandingOpenPolicy = null;
                acceptedMossHandler = handler;
                acceptedMossWorld = client.world;
                acceptedMossConnection = client.getNetworkHandler();
                stage = Stage.MOSS_CHEST_READY;
                stageTicks = 0;
                return;
            }
            if (initialInventoryGate == null) {
                failActive("registered-depot initial inventory identity was unavailable");
                return;
            }
            var packet = ServerInventorySnapshotObserver.latestMatching(client.world, client.getNetworkHandler(), handler)
                    .orElse(null);
            var window = new DepotInitialInventoryGate.Window<>(handler, handler.syncId, handler.getRows(),
                    handler.slots.stream().map(Slot::getStack).toList(), handler.getCursorStack());
            var receipt = packet == null ? null : new DepotInitialInventoryGate.Receipt<>(
                    packet.stamp(), packet.rows(), packet.slots(), packet.cursorStack());
            var decision = initialInventoryGate.observe(client.world, client.player, client.getNetworkHandler(),
                    window, receipt, stageTicks);
            if (decision == DepotInitialInventoryGate.Decision.WAIT) { return; }
            if (decision != DepotInitialInventoryGate.Decision.READY) {
                failActive(decision == DepotInitialInventoryGate.Decision.FAIL_TIMEOUT
                        ? "registered-depot initial server inventory did not arrive or match before timeout"
                        : "registered-depot initial server inventory identity changed: " + decision);
                return;
            }
            // The copied full receipt supplies stock; an empty newly created handler never does.
            if (mossWearExecution != null) { mossWearExecution.afterMossWearDepotReceipt(handler, packet); }
            var contents = new net.minecraft.inventory.SimpleInventory(
                    receipt.slots().subList(0, expectedRows * 9).toArray(ItemStack[]::new));
            MaterialQuantities stock = MinecraftMaterials.count(contents);
            containerIdentity = DepotContainerIdentity.accepted(handler.syncId, expectedRows);
            outstandingOpenPolicy = null;
            initialInventoryGate = null;
            currentDepot = currentDepot.withScan(stock);
            depots.put(currentDepot.id(), currentDepot);
            if (operation == OperationKind.SCAN) {
                closeHandledScreen();
                finishMaintenance();
                return;
            }

            DepotWithdrawal withdrawal = withdrawalPlan.get(withdrawalIndex);
            MaterialQuantities missing = withdrawal.quantities().shortageFrom(stock);
            if (!missing.isEmpty()) {
                requestWithdrawalFailure(
                        "real chest stock does not cover the allocation at "
                                + currentDepot.id().value()
                );
                return;
            }
            stage = Stage.TRANSFERRING;
            stageTicks = 0;
            return;
        }
        if (client.player.currentScreenHandler != client.player.playerScreenHandler) {
            failActive("an unexpected container opened during registered-depot access");
            return;
        }
        if (stageTicks > SCREEN_TIMEOUT_TICKS) {
            failActive("chest screen did not open");
        }
    }

    private void tickCancellingOpen() {
        CancellationOpenPolicy openPolicy = outstandingOpenPolicy;
        if (openPolicy == null) {
            finishTermination("cancelled depot open identity was unavailable");
            return;
        }
        CancellationOpenPolicy.ScreenObservation observation = currentScreenObservation();
        CancellationOpenPolicy.Decision decision = openPolicy.evaluate(
                validDepotContext(true),
                stageTicks,
                observation
        );
        switch (decision) {
            case WAIT -> {
                return;
            }
            case CLOSE_MATCHING -> closeCancelledOpen(openPolicy, observation.syncId());
            case FAIL_IDENTITY -> enterOpenQuarantine(
                    decision,
                    "cancelled depot open received an unexpected screen identity or shape"
            );
            case FAIL_CONTEXT -> enterOpenQuarantine(
                    decision,
                    "registered-depot context changed before cancellation could close the screen"
            );
            case FAIL_TIMEOUT -> enterOpenQuarantine(
                    decision,
                    "cancelled depot screen-open response timed out"
            );
        }
    }

    private CancellationOpenPolicy.ScreenObservation currentScreenObservation() {
        ScreenHandler handler = Objects.requireNonNull(client.player).currentScreenHandler;
        if (handler == client.player.playerScreenHandler) {
            return CancellationOpenPolicy.ScreenObservation.playerInventory();
        }
        if (handler instanceof GenericContainerScreenHandler container) {
            return CancellationOpenPolicy.ScreenObservation.genericContainer(
                    container.syncId,
                    container.getRows()
            );
        }
        return CancellationOpenPolicy.ScreenObservation.other();
    }

    private void closeCancelledOpen(
            CancellationOpenPolicy openPolicy,
            int observedSyncId
    ) {
        containerIdentity = DepotContainerIdentity.accepted(
                observedSyncId,
                openPolicy.expectedRows()
        );
        ScreenHandler handler = Objects.requireNonNull(client.player).currentScreenHandler;
        if (!(handler instanceof GenericContainerScreenHandler container)) {
            enterOpenQuarantine(
                    "matching cancelled depot screen disappeared before it could be closed"
            );
            retainCurrentQuarantineObservation();
            return;
        }
        if (container.syncId != observedSyncId
                || container.getRows() != openPolicy.expectedRows() || !ownsInitialInventoryWindow(container)) {
            enterOpenQuarantine(
                    "matching cancelled depot screen identity changed before it could be closed"
            );
            retainCurrentQuarantineObservation();
            return;
        }
        GenericContainerScreenHandler accepted = currentContainer();
        if (accepted == null) {
            enterOpenQuarantine(
                    "matching cancelled depot screen identity changed before it could be closed"
            );
            retainCurrentQuarantineObservation();
            return;
        }
        if (!accepted.getCursorStack().isEmpty()) {
            enterOpenQuarantine(
                    "matching cancelled depot screen had a non-empty cursor and was left open"
            );
            return;
        }
        closeHandledScreen();
        finishTermination("");
    }

    private void tickCancellingScanOpen() {
        CancellationOpenPolicy openPolicy = outstandingOpenPolicy;
        if (openPolicy == null) {
            finishCancelledMaintenance(
                    "cancelled depot scan identity was unavailable"
            );
            return;
        }
        CancellationOpenPolicy.ScreenObservation observation = currentScreenObservation();
        CancellationOpenPolicy.Decision decision = openPolicy.evaluate(
                validDepotContext(true),
                stageTicks,
                observation
        );
        switch (decision) {
            case WAIT -> {
                return;
            }
            case CLOSE_MATCHING -> closeCancelledMaintenanceOpen(
                    openPolicy,
                    observation.syncId()
            );
            case FAIL_IDENTITY -> enterOpenQuarantine(
                    decision,
                    "cancelled depot scan received an unexpected screen identity or shape"
            );
            case FAIL_CONTEXT -> enterOpenQuarantine(
                    decision,
                    "registered-depot context changed before scan cancellation settled"
            );
            case FAIL_TIMEOUT -> enterOpenQuarantine(
                    decision,
                    "cancelled depot scan screen-open response timed out"
            );
        }
    }

    private void closeCancelledMaintenanceOpen(
            CancellationOpenPolicy openPolicy,
            int observedSyncId
    ) {
        containerIdentity = DepotContainerIdentity.accepted(
                observedSyncId,
                openPolicy.expectedRows()
        );
        ScreenHandler handler = Objects.requireNonNull(client.player).currentScreenHandler;
        if (!(handler instanceof GenericContainerScreenHandler container)
                || container.syncId != observedSyncId
                || container.getRows() != openPolicy.expectedRows() || !ownsInitialInventoryWindow(container)) {
            enterOpenQuarantine(
                    "matching cancelled depot scan screen changed before it could be closed"
            );
            retainCurrentQuarantineObservation();
            return;
        }
        if (operation == OperationKind.MOSS_DEPOSIT) {
            acceptedMossHandler = container;
            acceptedMossWorld = client.world;
            acceptedMossConnection = client.getNetworkHandler();
        }
        GenericContainerScreenHandler accepted = currentContainer();
        if (accepted == null || !accepted.getCursorStack().isEmpty()) {
            enterOpenQuarantine(
                    "matching cancelled depot scan screen was left open for manual recovery"
            );
            return;
        }
        closeHandledScreen();
        finishCancelledMaintenance("");
    }

    private void enterOpenQuarantine(
            CancellationOpenPolicy.Decision failure,
            String incident
    ) {
        if (openQuarantine != null) {
            return;
        }
        CancellationOpenPolicy openPolicy = Objects.requireNonNull(
                outstandingOpenPolicy,
                "outstandingOpenPolicy"
        );
        openQuarantine = QueuedOpenQuarantinePolicy.fromFailure(
                openPolicy,
                failure,
                quarantineDetail(incident)
        );
        retainPreviouslyMatchedResponse();
    }

    private void enterOpenQuarantine(String incident) {
        if (openQuarantine != null) {
            return;
        }
        CancellationOpenPolicy openPolicy = Objects.requireNonNull(
                outstandingOpenPolicy,
                "outstandingOpenPolicy"
        );
        openQuarantine = QueuedOpenQuarantinePolicy.start(
                openPolicy,
                quarantineDetail(incident)
        );
        retainPreviouslyMatchedResponse();
    }

    private void retainPreviouslyMatchedResponse() {
        if (openQuarantine == null || !containerIdentity.active()) {
            return;
        }
        openQuarantine = openQuarantine.withMatchedResponse(
                containerIdentity.acceptedSyncId(),
                containerIdentity.expectedRows()
        );
    }

    private void retainCurrentQuarantineObservation() {
        if (openQuarantine == null || client.player == null) {
            return;
        }
        ScreenHandler handler = client.player.currentScreenHandler;
        boolean cursorEmpty =
                handler instanceof GenericContainerScreenHandler container
                        && container.getCursorStack().isEmpty();
        openQuarantine = openQuarantine.observe(
                currentScreenObservation(),
                cursorEmpty
        ).state();
    }

    private void tickQuarantinedOpen() {
        QueuedOpenQuarantinePolicy quarantine = openQuarantine;
        if (quarantine == null || client.player == null) {
            return;
        }
        CancellationOpenPolicy.ScreenObservation screen = currentScreenObservation();
        boolean matchingCursorEmpty =
                client.player.currentScreenHandler
                        instanceof GenericContainerScreenHandler container
                        && container.getCursorStack().isEmpty();
        QueuedOpenQuarantinePolicy.Observation observation =
                quarantine.observe(screen, matchingCursorEmpty);
        openQuarantine = observation.state();
        if (openQuarantine.matchedResponse()) {
            containerIdentity = DepotContainerIdentity.accepted(
                    openQuarantine.matchedResponseSyncId(),
                    openQuarantine.openRequest().expectedRows()
            );
        }
        switch (observation.decision()) {
            case RETAIN -> {
                return;
            }
            case CLOSE_MATCHING -> closeQuarantinedOpen();
            case RELEASE_MANUALLY_CLOSED -> completeOpenQuarantine();
        }
    }

    private void closeQuarantinedOpen() {
        QueuedOpenQuarantinePolicy quarantine = Objects.requireNonNull(openQuarantine);
        if (!quarantine.matchedResponse() || client.player == null) {
            return;
        }
        ScreenHandler handler = client.player.currentScreenHandler;
        if (!(handler instanceof GenericContainerScreenHandler container)
                || container.syncId != quarantine.matchedResponseSyncId()
                || container.getRows() != quarantine.openRequest().expectedRows()
                || !container.getCursorStack().isEmpty() || !ownsInitialInventoryWindow(container)) {
            return;
        }
        closeHandledScreen();
        if (client.player != null
                && client.player.currentScreenHandler == client.player.playerScreenHandler) {
            completeOpenQuarantine();
        } else {
            retainCurrentQuarantineObservation();
        }
    }

    private void completeOpenQuarantine() {
        QueuedOpenQuarantinePolicy quarantine = Objects.requireNonNull(openQuarantine);
        if (operation == OperationKind.WITHDRAWAL) {
            termination = Termination.FAIL;
            withdrawalDetail = quarantine.detail();
            finishTermination("");
        } else if (operation == OperationKind.SCAN || operation == OperationKind.MOSS_DEPOSIT) {
            maintenanceFailureDetail = "";
            finishCancelledMaintenance(quarantine.detail());
        }
    }

    private String quarantineDetail(String incident) {
        Objects.requireNonNull(incident, "incident");
        if (incident.isBlank()) {
            throw new IllegalArgumentException("quarantine incident must not be blank");
        }
        String prior = operation == OperationKind.WITHDRAWAL
                ? withdrawalDetail
                : maintenanceFailureDetail;
        String reason = prior.isBlank() || prior.equals(incident)
                ? incident
                : prior + "; " + incident;
        return "Queued depot screen response is quarantined: "
                + reason
                + ". Automation remains blocked until the exact matching empty-cursor "
                + "response is closed. Explicit Reset cannot clear this state; restart "
                + "the client if the response never arrives.";
    }

    private void finishCancelledMaintenance(String cleanupDetail) {
        String detail;
        if (maintenanceFailureDetail.isBlank()) {
            detail = cleanupDetail;
        } else if (cleanupDetail.isBlank()) {
            detail = maintenanceFailureDetail;
        } else {
            detail = maintenanceFailureDetail + "; " + cleanupDetail;
        }
        if (!detail.isBlank()
                && currentDepot != null
                && depots.containsKey(currentDepot.id())) {
            RegisteredDepot failed = currentDepot.withError(detail);
            depots.put(failed.id(), failed);
        }
        finishMaintenance();
    }

    private void tickTransfer() {
        if (!validDepotContext(true)) {
            requestWithdrawalFailure("registered-depot context changed during withdrawal");
            return;
        }
        GenericContainerScreenHandler handler = currentContainer();
        if (handler == null) {
            requestWithdrawalFailure("chest screen closed during withdrawal");
            return;
        }
        DepotTransferPolicy.Step next = DepotTransferPolicy.next(pendingAction != null,
                transferLedger.complete(), heldSourceSlot >= 0, handler.getCursorStack().isEmpty(),
                heldSourceSlot >= 0 && activeMaterial != null ? transferLedger.remaining(activeMaterial) : -1);
        switch (next) {
            case SETTLE_PENDING -> {
                observePendingAction(handler);
                return;
            }
            case RETURN_CURSOR -> {
                if (!MinecraftMaterials.matches(handler.getCursorStack(), Objects.requireNonNull(activeMaterial))) {
                    heldSourceSlot = -1;
                    requestWithdrawalFailure("cursor stack changed before returning the allocated chest remainder");
                } else {
                    issueCursorReturn(handler);
                }
                return;
            }
            case DEPOSIT_ONE -> {
                issueSingleDeposit(handler);
                return;
            }
            case REJECT_CURSOR -> {
                heldSourceSlot = -1;
                requestWithdrawalFailure("container cursor ownership changed; screen left open for manual recovery");
                return;
            }
            case COMPLETE, NEXT_SOURCE -> {
                // Only an empty cursor with no pending action or held source reaches either branch.
            }
        }
        if (next == DepotTransferPolicy.Step.COMPLETE) {
            currentDepot = currentDepot.withScan(MinecraftMaterials.count(handler.getInventory()));
            depots.put(currentDepot.id(), currentDepot);
            closeHandledScreen();
            withdrawalIndex++;
            if (withdrawalIndex >= withdrawalPlan.size()) {
                operation = OperationKind.NONE;
                stage = Stage.IDLE;
                currentDepot = null;
                resetContainerIdentity();
                withdrawalStatus = RestockTransferStatus.SUCCEEDED;
                return;
            }
            startWithdrawalDepot();
            return;
        }

        activeMaterial = nextRemainingMaterial();
        long remaining = transferLedger.remaining(activeMaterial);
        int sourceSlot = findSourceSlot(handler, activeMaterial);
        if (sourceSlot < 0) {
            requestWithdrawalFailure(
                    "allocated " + activeMaterial.jsonName() + " is absent from the open chest"
            );
            return;
        }
        ItemStack source = handler.getSlot(sourceSlot).getStack();
        if (source.getCount() <= remaining) {
            issueQuickMove(handler, sourceSlot, activeMaterial, source.getCount());
        } else {
            issueSourcePickup(handler, sourceSlot, activeMaterial, source.getCount());
        }
    }

    private void tickCleanup() {
        if (containerIdentity.active() && !validDepotContext(true)) {
            finishTermination(
                    "registered-depot context changed during cleanup; screen left open"
            );
            return;
        }
        GenericContainerScreenHandler handler = currentContainer();
        if (pendingAction != null) {
            if (handler == null) {
                finishTermination(
                        "container closed before the pending inventory action was confirmed"
                );
                return;
            }
            observePendingAction(handler);
            if (pendingAction == null) {
                beginCursorReturnOrFinishTermination();
            }
            return;
        }
        beginCursorReturnOrFinishTermination();
    }

    private void observePendingAction(GenericContainerScreenHandler handler) {
        PendingAction action = pendingAction;
        long playerCount = playerMaterialCount(action.material());
        long gain = playerCount - action.playerCountBefore();
        action = action.withWaitedTicks(action.waitedTicks() + 1);
        pendingAction = action;

        switch (action.type()) {
            case QUICK_MOVE -> {
                if (gain > 0) {
                    if (gain > action.maximumGain()) {
                        actionFailure("quick move exceeded the remaining allocation");
                        return;
                    }
                    recordMovement(action.material(), gain);
                    pendingAction = null;
                } else if (gain < 0) {
                    actionFailure("player inventory changed during a depot quick move");
                } else if (action.waitedTicks() > ACTION_TIMEOUT_TICKS) {
                    actionFailure("depot quick move was not acknowledged");
                }
            }
            case PICKUP_SOURCE -> {
                ItemStack cursor = handler.getCursorStack();
                if (MinecraftMaterials.matches(cursor, action.material())
                        && cursor.getCount() == action.cursorCountExpected()) {
                    heldSourceSlot = action.slot();
                    pendingAction = null;
                } else if (gain != 0) {
                    actionFailure("player inventory changed while picking up a chest stack");
                } else if (action.waitedTicks() > ACTION_TIMEOUT_TICKS) {
                    actionFailure("chest stack pickup was not acknowledged");
                }
            }
            case DEPOSIT_ONE -> {
                if (gain == 1
                        && handler.getCursorStack().getCount()
                        == action.cursorCountExpected()) {
                    recordMovement(action.material(), 1);
                    pendingAction = null;
                } else if (gain == 1) {
                    recordMovement(action.material(), 1);
                    actionFailure("cursor stack changed unexpectedly after a single-item deposit");
                } else if (gain != 0) {
                    actionFailure("single-item deposit changed inventory by " + gain);
                } else if (action.waitedTicks() > ACTION_TIMEOUT_TICKS) {
                    actionFailure("single-item deposit was not acknowledged");
                }
            }
            case RETURN_CURSOR -> {
                if (handler.getCursorStack().isEmpty()) {
                    heldSourceSlot = -1;
                    pendingAction = null;
                } else if (gain != 0) {
                    cursorReturnFailure(
                            "player inventory changed while returning the cursor stack"
                    );
                } else if (action.waitedTicks() > ACTION_TIMEOUT_TICKS) {
                    cursorReturnFailure(
                            "cursor stack could not be returned; container left open for manual recovery"
                    );
                }
            }
        }
    }

    private void issueQuickMove(
            GenericContainerScreenHandler handler,
            int sourceSlot,
            Material material,
            int sourceCount
    ) {
        long remaining = transferLedger.remaining(material);
        long maximumGain = Math.min(remaining, sourceCount);
        if (!admitWithdrawalSource(handler, sourceSlot, remaining, sourceCount)) { return; }
        long before = playerMaterialCount(material);
        click(handler, sourceSlot, 0, SlotActionType.QUICK_MOVE);
        pendingAction = new PendingAction(
                PendingActionType.QUICK_MOVE,
                material,
                sourceSlot,
                before,
                maximumGain,
                0,
                0
        );
    }

    private void issueSourcePickup(
            GenericContainerScreenHandler handler,
            int sourceSlot,
            Material material,
            int sourceCount
    ) {
        if (!admitWithdrawalSource(handler, sourceSlot, transferLedger.remaining(material), sourceCount)) { return; }
        long before = playerMaterialCount(material);
        click(handler, sourceSlot, 0, SlotActionType.PICKUP);
        pendingAction = new PendingAction(
                PendingActionType.PICKUP_SOURCE,
                material,
                sourceSlot,
                before,
                0,
                sourceCount,
                0
        );
    }

    private void issueSingleDeposit(GenericContainerScreenHandler handler) {
        ItemStack cursor = handler.getCursorStack();
        Material material = Objects.requireNonNull(activeMaterial);
        if (!MinecraftMaterials.matches(cursor, material)) {
            heldSourceSlot = -1;
            requestWithdrawalFailure("cursor stack changed during exact transfer");
            return;
        }
        int destination = findPlayerDestination(handler, cursor);
        if (destination < 0) {
            requestWithdrawalFailure("player inventory has no room for " + material.jsonName());
            return;
        }
        DepotTransferPolicy.SingleDeposit observed = DepotTransferPolicy.issueSingleDeposit(
                playerMaterialCount(material), cursor.getCount(),
                () -> click(handler, destination, 1, SlotActionType.PICKUP));
        pendingAction = new PendingAction(
                PendingActionType.DEPOSIT_ONE,
                material,
                destination,
                observed.playerCountBefore(),
                1,
                observed.cursorCountExpected(),
                0
        );
    }

    private void issueCursorReturn(GenericContainerScreenHandler handler) {
        Material material = Objects.requireNonNull(activeMaterial);
        long before = playerMaterialCount(material);
        click(handler, heldSourceSlot, 0, SlotActionType.PICKUP);
        pendingAction = new PendingAction(
                PendingActionType.RETURN_CURSOR,
                material,
                heldSourceSlot,
                before,
                0,
                0,
                0
        );
    }

    private void recordMovement(Material material, long gain) {
        try {
            transferLedger.recordGain(material, gain);
            unpolledMovement.merge(material, gain, Math::addExact);
        } catch (IllegalStateException | ArithmeticException exception) {
            requestWithdrawalFailure(conciseMessage(exception));
        }
    }

    private void actionFailure(String detail) {
        pendingAction = null;
        requestWithdrawalFailure(detail);
    }

    private void cursorReturnFailure(String detail) {
        pendingAction = null;
        if (termination == Termination.NONE) {
            termination = Termination.FAIL;
            withdrawalDetail = detail;
            finishTermination("");
        } else {
            finishTermination(detail);
        }
    }

    private void requestWithdrawalFailure(String detail) {
        requestWithdrawalFailure(detail, false);
    }

    private void requestWithdrawalFailure(String detail, boolean capacityRejected) {
        requestWithdrawalFailure(detail, capacityRejected, false);
    }

    private void requestWithdrawalFailure(String detail, boolean capacityRejected, boolean unreachable) {
        withdrawalCapacityRejected = capacityRejected;
        withdrawalDepotUnreachable = unreachable;
        withdrawalDetail = detail;
        termination = Termination.FAIL;
        boolean outstandingScreenResponse =
                (stage == Stage.WAITING_FOR_SCREEN || stage == Stage.CANCELLING_OPEN)
                        && outstandingOpenPolicy != null;
        if (currentDepot != null
                && depots.containsKey(currentDepot.id())
                && matchesCurrentRunContext(currentDepot)) {
            currentDepot = currentDepot.withError(detail);
            depots.put(currentDepot.id(), currentDepot);
        }
        if (outstandingScreenResponse) {
            stage = Stage.CANCELLING_OPEN;
        } else {
            stage = Stage.CLEANING_UP;
            stageTicks = 0;
        }
        if (!stopBaritone("could not fully cancel depot navigation")) {
            DepotCancellationPolicy.State failed =
                    DepotCancellationPolicy.cancellationFailure(
                            new DepotCancellationPolicy.State(
                                    withdrawalStatus,
                                    termination,
                                    withdrawalDetail,
                                    MaterialQuantities.of(unpolledMovement)
                            ),
                            cancellationGuard.blockedDetail()
                    );
            termination = failed.termination();
            withdrawalDetail = failed.detail();
            if (DepotCancellationPolicy.mayFinishAfterStopFailure(
                    outstandingScreenResponse,
                    pendingAction != null
            )) {
                finishTermination("");
            }
            return;
        }
        if (!outstandingScreenResponse && pendingAction == null) {
            beginCursorReturnOrFinishTermination();
        }
    }

    private void beginCursorReturnOrFinishTermination() {
        if (containerIdentity.active() && !validDepotContext(true)) {
            finishTermination(
                    "registered-depot context changed during cleanup; screen left open"
            );
            return;
        }
        GenericContainerScreenHandler handler = currentContainer();
        if (handler != null && !handler.getCursorStack().isEmpty()) {
            if (heldSourceSlot >= 0) {
                if (pendingAction == null) {
                    issueCursorReturn(handler);
                }
            } else {
                finishTermination(
                        "cursor origin is unknown; container left open for manual recovery"
                );
            }
            return;
        }
        if (handler == null && hasUnexpectedHandledScreen()) {
            finishTermination(
                    "container identity changed; the unexpected screen was left open"
            );
            return;
        }
        closeHandledScreen();
        finishTermination("");
    }

    private void finishTermination(String cleanupProblem) {
        // Read the facts before the reset below clears them.
        WithdrawalTermination.Outcome outcome = WithdrawalTermination.settle(new WithdrawalTermination.Facts(
                termination, withdrawalDetail, cleanupProblem, withdrawalCapacityRejected,
                withdrawalDepotUnreachable, automationBlocked(), pendingAction != null, heldSourceSlot >= 0));
        resetOperationFields();
        withdrawalStatus = outcome.status();
        withdrawalDetail = outcome.detail();
    }

    private void setImmediateWithdrawalFailure(String detail) {
        operation = OperationKind.NONE;
        stage = Stage.IDLE;
        resetContainerIdentity();
        withdrawalStatus = RestockTransferStatus.FAILED;
        withdrawalDetail = detail;
    }

    private void failActive(String detail) {
        if (operation == OperationKind.WITHDRAWAL) {
            requestWithdrawalFailure(detail);
        } else {
            failMaintenance(detail);
        }
    }

    /**
     * A route that found no way to this chest opened nothing, so a withdrawal reports it unreachable
     * and the supervisor may plan from other depots. Interference, such as movement input, lost flight
     * or a server correction, still fails the withdrawal and pauses.
     */
    private void failRoute(String detail, boolean noRoute) {
        if (operation == OperationKind.WITHDRAWAL) {
            boolean unreachable = noRoute && currentDepot != null;
            requestWithdrawalFailure(WithdrawalTermination.routeFailureDetail(
                    unreachable ? currentDepot.id().value() : "", detail, unreachable), false, unreachable);
        } else {
            failMaintenance(detail);
        }
    }

    private void failMaintenance(String detail) {
        if (operation == OperationKind.MOSS_DEPOSIT) { mossChestFailure = detail; }
        boolean outstandingScreenResponse =
                (stage == Stage.WAITING_FOR_SCREEN
                        || stage == Stage.CANCELLING_SCAN_OPEN)
                        && outstandingOpenPolicy != null;
        maintenanceFailureDetail = detail;
        if (outstandingScreenResponse) {
            stage = Stage.CANCELLING_SCAN_OPEN;
        }
        if (!stopBaritone("could not fully cancel depot maintenance navigation")) {
            String failure = detail + "; " + cancellationGuard.blockedDetail();
            if (currentDepot != null && depots.containsKey(currentDepot.id())) {
                RegisteredDepot failed = currentDepot.withError(failure);
                depots.put(failed.id(), failed);
            }
            return;
        }
        if (outstandingScreenResponse) {
            return;
        }
        GenericContainerScreenHandler handler = currentContainer();
        if (handler != null && handler.getCursorStack().isEmpty()) {
            closeHandledScreen();
        } else if (handler != null) {
            detail += "; container left open because its cursor is not empty";
        } else if (hasUnexpectedHandledScreen()) {
            detail += "; unexpected container left open for manual recovery";
        }
        if (currentDepot != null
                && depots.containsKey(currentDepot.id())
                && matchesCurrentRunContext(currentDepot)) {
            RegisteredDepot failed = currentDepot.withError(detail);
            depots.put(failed.id(), failed);
        }
        finishMaintenance();
    }

    private void finishMaintenance() {
        beforeMossOpening = null;
        operation = OperationKind.NONE;
        stage = Stage.IDLE;
        currentDepot = null;
        stageTicks = 0;
        resetContainerIdentity();
        outstandingOpenPolicy = null;
        openQuarantine = null;
        maintenanceFailureDetail = "";
    }

    private boolean stopCurrentMaintenance(String context) {
        if (operation != OperationKind.SCAN && operation != OperationKind.MOSS_DEPOSIT) {
            return !automationBlocked();
        }
        boolean outstandingScreenResponse =
                (stage == Stage.WAITING_FOR_SCREEN
                        || stage == Stage.CANCELLING_SCAN_OPEN)
                        && outstandingOpenPolicy != null;
        if (outstandingScreenResponse) {
            stage = Stage.CANCELLING_SCAN_OPEN;
        }
        boolean cancellationSucceeded = stopBaritone(context);
        if (!DepotCancellationPolicy.maintenanceStopSettled(
                cancellationSucceeded,
                outstandingScreenResponse
        )) {
            return false;
        }
        if (currentContainer() != null && !currentContainer().getCursorStack().isEmpty()) {
            if (operation == OperationKind.MOSS_DEPOSIT) {
                mossChestFailure = "Moss storage cursor is not empty; chest left open.";
            }
            return false;
        }
        if (currentContainer() != null) {
            closeHandledScreen();
        }
        finishMaintenance();
        return true;
    }

    private String maintenanceStopDetail() {
        return automationBlocked()
                ? automationBlockDetail()
                : "Depot maintenance screen-close is still settling; retry this action shortly.";
    }

    private void resetWithdrawalFields() {
        resetOperationFields();
        withdrawalStatus = RestockTransferStatus.IDLE;
        withdrawalDetail = "";
        unpolledMovement.clear();
    }

    private void resetOperationFields() {
        withdrawalCapacityRejected = false;
        withdrawalDepotUnreachable = false;
        operation = OperationKind.NONE;
        stage = Stage.IDLE;
        currentDepot = null;
        withdrawalPlan = List.of();
        withdrawalIndex = 0;
        transferLedger = null;
        activeMaterial = null;
        heldSourceSlot = -1;
        pendingAction = null;
        stageTicks = 0;
        termination = Termination.NONE;
        resetContainerIdentity();
        outstandingOpenPolicy = null;
        openQuarantine = null;
    }

    private Material nextRemainingMaterial() {
        for (Material material : transferLedger.requestedMaterials()) {
            if (transferLedger.remaining(material) > 0) {
                return material;
            }
        }
        throw new IllegalStateException("transfer ledger is incomplete without a remaining material");
    }

    private int findSourceSlot(GenericContainerScreenHandler handler, Material material) {
        int chestSlots = Math.multiplyExact(handler.getRows(), 9);
        for (int slot = 0; slot < chestSlots; slot++) {
            if (MinecraftMaterials.matches(handler.getSlot(slot).getStack(), material)) {
                return slot;
            }
        }
        return -1;
    }

    private boolean admitWithdrawalSource(GenericContainerScreenHandler handler, int sourceSlot,
                                          long remaining, int sourceCount) {
        ItemStack source = handler.getSlot(sourceSlot).getStack();
        long capacity = 0;
        for (Slot slot : handler.slots) {
            if (slot.inventory != client.player.getInventory() || slot.getIndex() < 0 || slot.getIndex() >= 36
                    || !slot.canInsert(source)) { continue; }
            ItemStack destination = slot.getStack();
            if (destination.isEmpty() || ItemStack.areItemsAndComponentsEqual(destination, source)) {
                capacity += Math.max(0, Math.min(source.getMaxCount(), slot.getMaxItemCount(source))
                        - (destination.isEmpty() ? 0 : destination.getCount()));
            }
        }
        if (WithdrawalCapacityPolicy.admitsSource(capacity, remaining, sourceCount)) { return true; }
        requestWithdrawalFailure("compatible inventory capacity changed before depot transfer; "
                + "no click sent; returning to inventory capacity recovery",
                pendingAction == null && heldSourceSlot < 0 && handler.getCursorStack().isEmpty());
        return false;
    }

    private int findPlayerDestination(
            GenericContainerScreenHandler handler,
            ItemStack cursor
    ) {
        PlayerInventory inventory = client.player.getInventory();
        for (Slot slot : handler.slots) {
            if (slot.inventory != inventory || !slot.canInsert(cursor)) {
                continue;
            }
            ItemStack destination = slot.getStack();
            if (destination.isEmpty()) {
                return slot.id;
            }
            if (ItemStack.areItemsAndComponentsEqual(destination, cursor)
                    && destination.getCount() < Math.min(
                            destination.getMaxCount(),
                            slot.getMaxItemCount(destination)
                    )) {
                return slot.id;
            }
        }
        return -1;
    }

    private void click(
            GenericContainerScreenHandler handler,
            int slot,
            int button,
            SlotActionType action
    ) {
        Runnable click = () -> client.interactionManager.clickSlot(
                handler.syncId,
                slot,
                button,
                action,
                Objects.requireNonNull(client.player)
        );
        if (mossCustody == null) { click.run(); }
        else { mossCustody.clickSlot(handler, slot, button, action, click); }
    }

    private GenericContainerScreenHandler currentContainer() {
        if (client.player != null
                && client.player.currentScreenHandler
                instanceof GenericContainerScreenHandler handler
                && containerIdentity.matches(handler.syncId, handler.getRows())
                && (operation != OperationKind.MOSS_DEPOSIT || handler == acceptedMossHandler
                    && client.world == acceptedMossWorld
                    && client.getNetworkHandler() == acceptedMossConnection)) {
            return handler;
        }
        return null;
    }

    private long playerMaterialCount(Material material) {
        return MinecraftMaterials.count(
                Objects.requireNonNull(client.player).getInventory()
        ).get(material);
    }

    private String depotHandProblem(BlockPos position) {
        ClientPlayerEntity player = client.player;
        if (player == null || client.world == null || client.interactionManager == null) {
            return "The client context became unavailable before depot hand validation";
        }
        boolean exactChest = currentDepot != null && position.equals(blockPosition(currentDepot))
                && validDepotContext(true)
                && (client.world.getBlockState(position).isOf(Blocks.CHEST)
                    || client.world.getBlockState(position).isOf(Blocks.TRAPPED_CHEST));
        return DepotInteractionHandPolicy.rejection(new DepotInteractionHandPolicy.Context(
                exactChest, true, player.isSneaking(), player.shouldCancelInteraction(),
                player.currentScreenHandler == player.playerScreenHandler,
                player.currentScreenHandler != null && player.currentScreenHandler.getCursorStack().isEmpty()),
                PlainInteractionItems.safeDepotHand(player.getMainHandStack()));
    }

    private boolean canReach(BlockPos position) {
        if (!ClientChunkAvailability.isLoaded(client.world, position)) { return false; }
        ClientPlayerEntity player = Objects.requireNonNull(client.player);
        double range = player.getBlockInteractionRange();
        Vec3d center = Vec3d.ofCenter(position);
        if (player.getEyePos().squaredDistanceTo(center) > range * range) {
            return false;
        }
        BlockHitResult hit = Objects.requireNonNull(client.world).raycast(new RaycastContext(
                player.getEyePos(), center, RaycastContext.ShapeType.COLLIDER,
                RaycastContext.FluidHandling.NONE, player));
        return hit.getType() == HitResult.Type.MISS || hit.getBlockPos().equals(position);
    }

    private boolean validateReceivedFlightDepot(BlockPos position) {
        if (!ClientChunkAvailability.isLoaded(client.world, position)) { return true; }
        if (client.world.getBlockState(position).getBlock() instanceof ChestBlock) { return true; }
        failActive("Registered depot flight target " + position + " is no longer a chest: "
                + client.world.getBlockState(position) + "; worldY="
                + client.world.getBottomY() + ".." + client.world.getTopYInclusive()
                + "; player=" + client.player.getPos());
        return false;
    }

    private boolean validDepotContext(boolean requireReach) {
        if (client.player == null || client.world == null || currentDepot == null) {
            return false;
        }
        if (!matchesCurrentRunContext(currentDepot)) {
            return false;
        }
        BlockPos position = blockPosition(currentDepot);
        if (!ClientChunkAvailability.isLoaded(client.world, position)) { return false; }
        if (!(client.world.getBlockState(position).getBlock() instanceof ChestBlock)) {
            return false;
        }
        if (containerIdentity.active()
                && expectedContainerRowsFor(currentDepot)
                != containerIdentity.expectedRows()) {
            return false;
        }
        return !requireReach || canReach(position);
    }

    private RunContext currentRunContextOrNull() {
        try {
            return MinecraftRunContext.capture(client);
        } catch (IllegalStateException exception) {
            return null;
        }
    }

    private boolean matchesCurrentRunContext(RegisteredDepot depot) {
        RunContext context = currentRunContextOrNull();
        return context != null && depot.matches(context);
    }

    private void refreshAutoScanQueue() {
        if (supplyInPlace || automaticScansPaused) {
            return;
        }
        RunContext context = currentRunContextOrNull();
        if (context == null) {
            autoScanContext = null;
            scanQueue.clear();
            return;
        }
        if (context.equals(autoScanContext)) {
            return;
        }

        autoScanContext = context;
        scanQueue.clear();
        for (Map.Entry<DepotId, RegisteredDepot> entry : depots.entrySet()) {
            if (entry.getValue().matches(context)) {
                entry.setValue(entry.getValue().markUnscanned());
                scanQueue.addLast(entry.getKey());
            }
        }
    }

    private int expectedContainerRowsFor(RegisteredDepot depot) {
        net.minecraft.block.BlockState state =
                Objects.requireNonNull(client.world).getBlockState(blockPosition(depot));
        return DepotContainerIdentity.expectedRows(
                state.get(ChestBlock.CHEST_TYPE) != ChestType.SINGLE
        );
    }

    private boolean hasUnexpectedHandledScreen() {
        return client.player != null
                && client.player.currentScreenHandler != client.player.playerScreenHandler;
    }

    private void resetContainerIdentity() {
        openingPlayerHandlerSyncId = -1;
        containerIdentity = containerIdentity.reset();
        acceptedMossHandler = null;
        acceptedMossWorld = null;
        acceptedMossConnection = null;
        initialInventoryGate = null;
    }

    private boolean ownsInitialInventoryWindow(GenericContainerScreenHandler handler) {
        return initialInventoryGate == null || initialInventoryGate.acceptWindow(client.world, client.player,
                client.getNetworkHandler(), handler, handler.syncId, handler.getRows());
    }

    private void closeHandledScreen() {
        if (client.player != null
                && client.player.currentScreenHandler != client.player.playerScreenHandler) {
            client.player.closeHandledScreen();
        }
    }

    private boolean stopBaritone(String context) {
        return cancellationGuard.stop(context);
    }

    private void restoreNavigationSettings() {
        baritone.getInputOverrideHandler().clearAllKeys();
        BaritoneSettingLease lease = navigationSettings;
        if (lease != null) {
            lease.close();
            navigationSettings = null;
        }
    }

    private DepotId nextDepotId() {
        int value = 1;
        while (depots.containsKey(new DepotId(
                "depot-" + String.format(Locale.ROOT, "%03d", value)
        ))) {
            value = Math.addExact(value, 1);
        }
        return new DepotId("depot-" + String.format(Locale.ROOT, "%03d", value));
    }

    private void enqueueScan(DepotId id) {
        RegisteredDepot depot = depots.get(id);
        RunContext context = currentRunContextOrNull();
        if (depot != null
                && context != null
                && depot.matches(context)
                && !scanQueue.contains(id)) {
            scanQueue.addLast(id);
        }
    }

    private static BlockPos blockPosition(RegisteredDepot depot) {
        return new BlockPos(depot.x(), depot.y(), depot.z());
    }

    private BlockPos canonicalChestPosition(BlockPos position) {
        net.minecraft.block.BlockState state =
                Objects.requireNonNull(client.world).getBlockState(position);
        ChestType type = state.get(ChestBlock.CHEST_TYPE);
        if (type == ChestType.SINGLE) {
            return position;
        }
        BlockPos other = position.offset(ChestBlock.getFacing(state));
        if (!(client.world.getBlockState(other).getBlock() instanceof ChestBlock)) {
            return position;
        }
        if (position.getX() != other.getX()) {
            return position.getX() < other.getX() ? position : other;
        }
        if (position.getZ() != other.getZ()) {
            return position.getZ() < other.getZ() ? position : other;
        }
        return position.getY() <= other.getY() ? position : other;
    }

    private static String describe(RegisteredDepot depot) {
        String observation;
        if (depot.scanned()) {
            observation = "stock=" + depot.cachedStock();
        } else if (!depot.lastError().isBlank()) {
            observation = "unscanned error=" + depot.lastError();
        } else {
            observation = "unscanned";
        }
        return depot.id().value()
                + " " + depot.dimension()
                + " (" + depot.x() + ", " + depot.y() + ", " + depot.z() + ") "
                + observation;
    }

    private static String conciseMessage(Throwable throwable) {
        String message = throwable.getMessage();
        return message == null || message.isBlank()
                ? throwable.getClass().getSimpleName()
                : message;
    }

    private enum OperationKind {
        NONE,
        SCAN,
        MOSS_DEPOSIT,
        WITHDRAWAL
    }

    private enum Stage {
        IDLE,
        NAVIGATING,
        WAITING_FOR_SCREEN,
        CANCELLING_OPEN,
        CANCELLING_SCAN_OPEN,
        MOSS_CHEST_READY,
        TRANSFERRING,
        CLEANING_UP
    }

    private enum PendingActionType {
        QUICK_MOVE,
        PICKUP_SOURCE,
        DEPOSIT_ONE,
        RETURN_CURSOR
    }

    private record PendingAction(
            PendingActionType type,
            Material material,
            int slot,
            long playerCountBefore,
            long maximumGain,
            int cursorCountExpected,
            int waitedTicks
    ) {
        private PendingAction {
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(material, "material");
            if (waitedTicks < 0) {
                throw new IllegalArgumentException("waitedTicks must be non-negative");
            }
        }

        private PendingAction withWaitedTicks(int replacement) {
            return new PendingAction(
                    type,
                    material,
                    slot,
                    playerCountBefore,
                    maximumGain,
                    cursorCountExpected,
                    replacement
            );
        }
    }
}
