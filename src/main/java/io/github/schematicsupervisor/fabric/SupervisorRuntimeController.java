package io.github.schematicsupervisor.fabric;

import baritone.api.BaritoneAPI;
import baritone.api.utils.input.Input;
import io.github.schematicsupervisor.core.BuildCheck;
import io.github.schematicsupervisor.core.JsonFileCheckpointStore;
import io.github.schematicsupervisor.core.Material;
import io.github.schematicsupervisor.core.MaterialQuantities;
import io.github.schematicsupervisor.core.SafeReturnStatus;
import io.github.schematicsupervisor.core.LayerProgress;
import io.github.schematicsupervisor.core.SchematicPlan;
import io.github.schematicsupervisor.core.SchematicSupervisor;
import io.github.schematicsupervisor.core.SupervisorPorts;
import io.github.schematicsupervisor.core.SupervisorState;
import io.github.schematicsupervisor.core.SupervisorStatus;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import net.minecraft.client.MinecraftClient;
import org.slf4j.Logger;

/**
 * Client-thread owner for the incremental loader, adapters, and deterministic core.
 */
final class SupervisorRuntimeController implements AutoCloseable {
    private static final int STATUS_PUBLICATION_INTERVAL_TICKS = 20;

    private final MinecraftClient client;
    private final SupervisorSettings settings;
    private final Logger logger;
    private final BuildStateRepository buildStates;
    private final TemporarySupportStore supportStore;
    private final Path hoeRepairPath;
    private Path checkpointPath;
    private JsonFileCheckpointStore checkpointStore;
    private RunContextStore contextStore;
    private ResetTeardownCoordinator resetTeardown;
    private BuildStateRepository.Handle activeBuildState;
    private final BuildUnloadCoordinator unloadTeardown = new BuildUnloadCoordinator();
    private final AutomationSafetyGuard safetyGuard = new AutomationSafetyGuard();
    private final ClientTickHealth health = new ClientTickHealth();
    private final MinecraftSoilWatchpoints soilWatchpoints = new MinecraftSoilWatchpoints();
    private final MinecraftInventoryPort inventory;
    private final MinecraftNotifications notifications;
    private final MinecraftDepotPort depots;
    private final MossMiningToolGuard<net.minecraft.item.ItemStack> mossTools =
            new MossMiningToolGuard<>(net.minecraft.item.ItemStack::areItemsAndComponentsEqual);
    private final MossToolCustody mossCustody;
    private final MinecraftMossDeposit mossDeposit;
    private final MinecraftSurplusDisposal surplusDisposal;
    private final DirtShopPurchase dirtShop;
    private final MinecraftMaterialShop materialShop;
    private final FlightTakeoffSession takeoff;
    // A Start or Resume pressed while standing; it runs when its takeoff completes.
    private final ActionAfterTakeoff actionAfterTakeoff = new ActionAfterTakeoff();
    private final CompanionGateway companion;
    private final PlacementReconciliationProbe placementReconciliationProbe;
    private final ClearingReconciliationProbe clearingReconciliationProbe;
    private final boolean controlTokenConfigured;

    private PlacementPlanLoadSession placementLoader;
    // A Start first checks the received build; the supervisor starts when the check finishes.
    private BuildCheckSession buildCheck;
    private MinecraftBuildCheckReader buildCheckReader;
    private Instant buildCheckStartedAt;
    private BuildCheckObservation lastBuildCheck;
    private SchematicPlan plan;
    private io.github.schematicsupervisor.core.LayerBuildSchedule lightingSchedule;
    private SchematicSupervisor supervisor;
    private MinecraftExecutionPort execution;
    private MinecraftVerificationPort verification;
    private RunContext activeContext;
    private String lastRuntimeError = "";
    private String lastMessage = "Ready.";
    private int statusPublicationTicks;
    private boolean closed;
    private String runId = UUID.randomUUID().toString();
    private long controlSequence;
    private String lastControlAction;
    private String lastControlRequestId;
    private volatile byte[] observationSnapshot;
    private final ProgressSnapshotCache progressCache;
    private RunContext observedIdleContext;
    private Object observedIdleSelection;
    private RunContext shopContext;
    private boolean automaticShop;
    private boolean shopHandledThisRestock;
    private boolean mossHandledThisRestock;
    private int withdrawalCapacityRecoveries;
    private boolean materialReceiptHeldScans;
    private boolean repairReceiptHeldScans;
    private int inventoryCleanupTicks;
    private int cleanupBatchStacks;
    private boolean cleanupBatchActive;
    private String takeoffRuntimeError = "";
    private PausedApproachSession pausedApproach;

    SupervisorRuntimeController(
            MinecraftClient client,
            SupervisorSettings settings,
            Path stateDirectory,
            Logger logger
    ) throws IOException {
        this.client = client;
        mossCustody = new MossToolCustody(client, mossTools);
        this.settings = settings;
        this.logger = logger;
        progressCache = new ProgressSnapshotCache(failure ->
                logger.warn("Progress snapshot failed; keeping the previous progress", failure));
        controlTokenConfigured = !settings.token().isBlank();
        buildStates = new BuildStateRepository(stateDirectory);
        supportStore = new TemporarySupportStore(stateDirectory.resolve("temporary-support.json"));
        hoeRepairPath = stateDirectory.resolve("hoe-repair.json");
        checkpointPath = stateDirectory.resolve("checkpoint.json");
        checkpointStore = new JsonFileCheckpointStore(checkpointPath);
        contextStore = new RunContextStore(stateDirectory.resolve("run-context.json"));
        resetTeardown = new ResetTeardownCoordinator(
                checkpointStore::clear,
                contextStore::clear
        );
        inventory = new MinecraftInventoryPort(client);
        notifications = new MinecraftNotifications(client, logger);
        depots = new MinecraftDepotPort(
                client,
                stateDirectory.resolve("depots.json"),
                settings.pathGoalRadius()
        );
        depots.configureMossCustody(mossCustody);
        depots.configureSupplyInPlace(buyInPlace());
        MinecraftDirtShopPort dirtShopPort = new MinecraftDirtShopPort(client);
        dirtShopPort.configureMossCustody(mossCustody);
        dirtShop = new DirtShopPurchase(dirtShopPort);
        mossDeposit = new MinecraftMossDeposit(client, depots, stateDirectory.resolve("moss-deposit.json"));
        mossDeposit.configureMossCustody(mossCustody);
        surplusDisposal = new MinecraftSurplusDisposal(client, depots, stateDirectory.resolve("surplus-disposal.json"));
        surplusDisposal.configureMossCustody(mossCustody);
        materialShop = new MinecraftMaterialShop(client, stateDirectory.resolve("material-purchase.json"));
        materialShop.configureMossCustody(mossCustody);
        materialReceiptHeldScans = materialShop.pending() || materialShop.unavailable();
        try {
            repairReceiptHeldScans = new HoeRepairFileStore(hoeRepairPath).load()
                    .filter(journal -> !journal.confirmed()).isPresent();
        } catch (IOException | RuntimeException failure) {
            repairReceiptHeldScans = true;
        }
        if (repairReceiptHeldScans || mossDeposit.pending() || mossDeposit.unavailable() || surplusUnsettled()
                || materialShop.pending() || materialShop.unavailable()) { depots.cancelMaintenance(); }
        takeoff = new FlightTakeoffSession(new MinecraftFlightTakeoffPort(client, this::takeoffEnvironmentProblem));
        companion = new CompanionGateway(settings);
        placementReconciliationProbe = PlacementReconciliationProbe.open(stateDirectory);
        clearingReconciliationProbe = ClearingReconciliationProbe.open(stateDirectory);
    }

    OperatorResult applyControl(ControlHttpServer.ControlRequest request) {
        requireClientThread();
        refreshIdleRunIdentity();
        if (!request.matches(runId, stateName(), controlSequence)) {
            return rejected("Control precondition changed; observe the current run before deciding again.");
        }
        return dispatchControl(request.action(), request.requestId());
    }

    OperatorResult start() {
        return dispatchControl(ControlHttpServer.ControlAction.START, null);
    }

    OperatorResult pause() {
        return dispatchControl(ControlHttpServer.ControlAction.PAUSE, null);
    }

    OperatorResult resume() {
        return dispatchControl(ControlHttpServer.ControlAction.RESUME, null);
    }

    OperatorResult stop() {
        return dispatchControl(ControlHttpServer.ControlAction.STOP, null);
    }

    OperatorResult rescanDepots() {
        return dispatchControl(ControlHttpServer.ControlAction.SCAN_DEPOTS, null);
    }

    OperatorResult takeoff() {
        return runOperator("TAKEOFF", null, this::takeoffImpl);
    }

    OperatorResult approach(int x, int y, int z) {
        return runOperator("APPROACH", null, () -> {
            String problem = approachEnvironmentProblem();
            if (!problem.isBlank()) { return rejected(problem); }
            if (!plan.buildVolume().contains(new io.github.schematicsupervisor.core.BlockPosition(x, y, z))) {
                return rejected("The approach target must be inside the loaded schematic volume.");
            }
            pausedApproach = new PausedApproachSession(new MinecraftPausedApproachPort(client,
                    new net.minecraft.util.math.BlockPos(x, y, z), this::approachEnvironmentProblem));
            pausedApproach.start();
            lastMessage = pausedApproach.detail();
            return pausedApproach.failed() ? rejected(lastMessage) : accepted(lastMessage);
        });
    }

    private boolean approachActive() { return pausedApproach != null && pausedApproach.active(); }

    private String approachEnvironmentProblem() {
        if (supervisor == null || plan == null || supervisor.status().state() != SupervisorState.PAUSED) {
            return "Load and pause the saved build before approaching an obstruction.";
        }
        String common = takeoffEnvironmentProblem();
        if (!common.isBlank()) { return common; }
        if (takeoff.active() || repairReceiptHeldScans || mossDeposit.active() || mossDeposit.pending()
                || mossDeposit.unavailable() || dirtShop.snapshot().pendingStacks() > 0
                || materialShop.unavailable() || !materialShop.stateProblem().isBlank()
                || !mossDeposit.stateProblem().isBlank()) {
            return "Settle all maintenance and inventory receipts before approaching.";
        }
        if (execution == null) { return "The paused execution adapter is unavailable."; }
        ExecutionObservation facts = execution.observation();
        if (!facts.available() || facts.receipt() != null || Boolean.TRUE.equals(facts.ownedMining())
                || Boolean.TRUE.equals(facts.managerBreaking())
                || !execution.hoeRepairBlockDetail().isBlank()
                || !execution.temporarySupportBlockDetail().isBlank()) {
            return "Settle pending construction, mining, tool repair, and support receipts before approaching.";
        }
        SafeReturnStatus returning = execution.pollSafeReturn().status();
        if (returning == SafeReturnStatus.RUNNING || returning == SafeReturnStatus.WAITING_FOR_SCREEN) {
            return "Wait for the current return route to finish before approaching.";
        }
        return "";
    }

    private void cancelApproach(String reason) {
        if (approachActive()) {
            pausedApproach.cancel(reason);
            lastMessage = pausedApproach.detail();
        }
    }

    private OperatorResult takeoffImpl() {
        if (takeoff.active()) { return accepted(takeoff.snapshot().detail()); }
        String problem = takeoffEnvironmentProblem();
        if (!problem.isBlank()) { return rejected(problem); }
        if (!MinecraftBackgroundBuildAccess.allowsWorldActions(client)) {
            return rejected(FlightTakeoffSession.BLOCKED_SCREEN);
        }
        takeoff.start();
        updateTakeoffStatus();
        return "FAILED".equals(takeoff.snapshot().state()) ? rejected(lastMessage) : accepted(lastMessage);
    }

    private String takeoffEnvironmentProblem() {
        boolean connected = client.world != null && client.player != null;
        return TakeoffEnvironment.problem(new TakeoffEnvironment.Facts(
                closed,
                runtimeWorkAllowed() ? "" : orElse(runtimeWorkBlockDetail(), "Runtime work is blocked."),
                connected,
                automationBlocked() ? orElse(automationBlockDetail(), "Automation is blocked.") : "",
                placementLoader != null || buildCheck != null || dirtShop.active() || materialShop.active()
                        || materialShop.pending() || surplusUnsettled() || !depots.maintenanceIdle(),
                supervisor == null ? null : supervisor.status().state(),
                supervisor != null && (supervisor.requiresReconciliation()
                        || supervisor.checkpoint().withdrawalInFlight()),
                () -> TakeoffCheckpointGuard.inspect(checkpointStore::load, contextStore::load,
                        () -> MinecraftRunContext.capture(client)),
                connected && activeContext != null && !contextMatchesCurrentWorld(),
                SupervisorRuntimeController::baritoneTakeoffProblem));
    }

    private static String baritoneTakeoffProblem() {
        var baritone = BaritoneAPI.getProvider().getPrimaryBaritone();
        if (baritone.getBuilderProcess().isActive() || baritone.getCustomGoalProcess().isActive()
                || baritone.getPathingBehavior().isPathing()) {
            return "Stop current Baritone work before takeoff.";
        }
        for (Input input : Input.values()) {
            if (baritone.getInputOverrideHandler().isInputForcedDown(input)) {
                return "Baritone still holds forced input " + input + "; stop its input override before takeoff.";
            }
        }
        return "";
    }

    private void updateTakeoffStatus() {
        FlightTakeoffSession.Snapshot snapshot = takeoff.snapshot();
        lastMessage = snapshot.detail();
        if ("FAILED".equals(snapshot.state())) {
            takeoffRuntimeError = snapshot.detail();
            lastRuntimeError = takeoffRuntimeError;
        } else if ("COMPLETE".equals(snapshot.state()) && lastRuntimeError.equals(takeoffRuntimeError)) {
            lastRuntimeError = "";
            takeoffRuntimeError = "";
        }
    }

    private boolean cancelTakeoff(String reason) {
        actionAfterTakeoff.clear();
        boolean active = takeoff.active();
        if (active) {
            takeoff.cancel(reason);
            updateTakeoffStatus();
        }
        return active;
    }

    /** The player stands but the server allows flight: take off, then run the same Start or Resume. */
    private OperatorResult takeOffBefore(ControlHttpServer.ControlAction action) {
        String requestId = lastControlRequestId;
        OperatorResult takeoffResult = takeoffImpl();
        if (!takeoffResult.accepted()) {
            return takeoffResult;
        }
        if (!takeoff.active()) {
            if (!"COMPLETE".equals(takeoff.snapshot().state()) || !client.player.getAbilities().flying) {
                return rejected(takeoff.snapshot().detail());
            }
            // Flight was already active by the time the takeoff started; nothing is left to wait for.
            return action == ControlHttpServer.ControlAction.START ? startImpl() : resumeImpl();
        }
        actionAfterTakeoff.hold(action, requestId);
        lastMessage = action == ControlHttpServer.ControlAction.START
                ? "Taking off before starting." : "Taking off before resuming.";
        return accepted(lastMessage);
    }

    private void runActionAfterTakeoff() {
        actionAfterTakeoff.takeAfter(takeoff.snapshot().state()).ifPresent(held -> {
            String verb = held.action() == ControlHttpServer.ControlAction.START ? "Start" : "Resume";
            if (!held.run()) {
                lastMessage = verb + " did not run because the takeoff did not finish: " + takeoff.snapshot().detail();
                return;
            }
            OperatorResult result = dispatchControl(held.action(), held.requestId());
            if (!result.accepted()) {
                lastRuntimeError = verb + " after takeoff was refused: " + result.message();
                lastMessage = lastRuntimeError;
            }
        });
    }

    private OperatorResult dispatchControl(ControlHttpServer.ControlAction action, String requestId) {
        return runOperator(action.name(), requestId, () -> switch (action) {
            case START -> startImpl();
            case PAUSE -> pauseImpl();
            case RESUME -> resumeImpl();
            case STOP -> stopImpl();
            case SCAN_DEPOTS -> rescanDepotsImpl();
        });
    }

    private OperatorResult runOperator(String action, String requestId, Supplier<OperatorResult> operation) {
        requireClientThread();
        if (closed) {
            return rejected("The supervisor runtime is closed.");
        }
        controlSequence++;
        lastControlAction = action;
        lastControlRequestId = requestId;
        try {
            if (approachActive() && !List.of("PAUSE", "STOP").contains(action)) {
                return rejected("Wait for the paused approach to finish, or use Pause or Stop.");
            }
            if (unloadTeardown.cleanupRequired() && !List.of("UNLOAD", "PAUSE", "STOP").contains(action)) {
                return rejected(unloadTeardown.detail());
            }
            return operation.get();
        } finally {
            publishObservation();
        }
    }

    private OperatorResult startImpl() {
        requireClientThread();
        if (materialShop.active()) { return rejected("Wait for material shopping to settle, or use Pause or Stop."); }
        if (takeoff.active()) { return rejected("Wait for takeoff to finish, or use Pause or Stop."); }
        if (dirtShop.active()) {
            return rejected("Wait for dirt shopping to finish, or use Pause or Stop.");
        }
        if (closed) {
            return rejected("The supervisor runtime is closed.");
        }
        if (!runtimeWorkAllowed()) {
            return rejected(runtimeWorkBlockDetail());
        }
        if (client.world == null || client.player == null) {
            return rejected("Join the target world before starting.");
        }
        BuildAccessPreflight.Decision access = BuildAccessPreflight.forFloatingBuild(
                client.player.getAbilities().flying, client.player.getAbilities().allowFlying);
        if (access.takeoffFirst()) {
            return takeOffBefore(ControlHttpServer.ControlAction.START);
        }
        if (!access.allowed()) {
            return rejected(access.detail());
        }
        if (placementLoader != null) {
            return rejected("The selected placement is already being loaded.");
        }
        if (buildCheck != null) {
            return rejected("The build check is already running.");
        }
        if (automationBlocked()) {
            return rejected(automationBlockDetail());
        }
        if (!depots.maintenanceIdle()) {
            return rejected("Wait for registered-depot scans to finish before starting.");
        }
        RunContext currentContext;
        try {
            currentContext = MinecraftRunContext.capture(client);
        } catch (RuntimeException exception) {
            return runtimeFailure("Unable to identify the target world", exception);
        }
        if (supervisor != null) {
            if (activeContext == null || !activeContext.equals(currentContext)) {
                return rejected("The loaded plan belongs to a different server, save, or dimension.");
            }
            SupervisorState state = supervisor.status().state();
            if (state == SupervisorState.STOPPED) {
                if (mossDeposit.pending() || materialShop.pending() || surplusDisposal.pending()) {
                    return rejected("A pending inventory receipt prevents restarting the saved construction ledger.");
                }
                String startProblem = supervisor.startProblem();
                if (!startProblem.isBlank()) {
                    return runtimeFailure("Unable to start", new IllegalStateException(startProblem));
                }
                try {
                    safetyGuard.activate();
                    beginBuildCheck();
                    shopHandledThisRestock = false;
                    lastMessage = "Checking the build before starting.";
                    lastRuntimeError = "";
                    return accepted(lastMessage);
                } catch (RuntimeException exception) {
                    cancelBuildCheck();
                    deactivateSafetyGuardWhenSafe();
                    return runtimeFailure("Unable to start", exception);
                }
            }
            if (state == SupervisorState.PAUSED) {
                return rejected("A checkpoint is paused; use Resume.");
            }
            if (state == SupervisorState.DONE) {
                return rejected("The loaded plan is complete; use Unload before selecting another build.");
            }
            return rejected("The supervisor is already active.");
        }
        try {
            activeContext = currentContext;
            shopHandledThisRestock = false;
            safetyGuard.activate();
            placementLoader = new PlacementPlanLoadSession();
            runId = UUID.randomUUID().toString();
            lastMessage = "Loading selected placement: " + placementLoader.placementName();
            lastRuntimeError = "";
            return accepted(lastMessage);
        } catch (RuntimeException exception) {
            placementLoader = null;
            activeContext = null;
            deactivateSafetyGuardWhenSafe();
            return runtimeFailure("Unable to load the selected placement", exception);
        }
    }

    private OperatorResult pauseImpl() {
        requireClientThread();
        cancelApproach("Approach paused; owned movement released.");
        materialShop.cancel("Material shopping paused.");
        mossDeposit.cancel("Moss storage paused.");
        surplusDisposal.cancel("Surplus disposal paused.");
        if (!surplusDisposal.pending() && !surplusDisposal.unavailable()) { mossHandledThisRestock = false; }
        boolean cancelledTakeoff = cancelTakeoff("Takeoff paused; jump input released.");
        boolean cancelledShop = cancelDirtShopping("Dirt shopping paused.");
        boolean cancelledLoading = placementLoader != null;
        boolean cancelledCheck = cancelBuildCheck();
        boolean cancelledMaintenance = !depots.maintenanceIdle();
        String maintenanceDetail;
        try {
            if (cancelledLoading) {
                placementLoader = null;
            } else if (supervisor != null) {
                supervisor.pause();
            }
        } finally {
            maintenanceDetail = depots.cancelMaintenance();
        }
        deactivateSafetyGuardWhenSafe();
        if (automationBlocked()) {
            lastRuntimeError = automationBlockDetail();
            return rejected(lastRuntimeError);
        }
        if (cancelledLoading) {
            lastMessage = "Placement loading paused and discarded.";
        } else if (cancelledCheck) {
            lastMessage = "Build check cancelled; construction was not started.";
        } else if (supervisor == null) {
            if (!cancelledShop && !cancelledTakeoff && !cancelledMaintenance) {
                return rejected("No plan is loaded.");
            }
            if (cancelledMaintenance) { lastMessage = "Depot maintenance paused."; }
        } else {
            lastMessage = "Paused.";
        }
        if (!maintenanceDetail.isBlank()) { lastMessage += " " + maintenanceDetail; }
        return accepted(lastMessage);
    }

    private OperatorResult resumeImpl() {
        requireClientThread();
        if (materialShop.active()) { return rejected("Wait for material shopping to settle, or use Pause or Stop."); }
        String purchaseProblem = materialShop.resumeProblem(plan == null ? "" : plan.planId());
        if (!purchaseProblem.isBlank()) { return rejected(purchaseProblem); }
        if (mossDeposit.active()) { return rejected("Wait for moss storage to settle, or use Pause or Stop."); }
        String storageProblem = mossDeposit.resumeProblem(plan == null ? "" : plan.planId());
        if (!storageProblem.isBlank()) { return rejected(storageProblem); }
        if (surplusDisposal.active()) { return rejected("Wait for surplus disposal to settle, or use Pause or Stop."); }
        String disposalProblem = surplusDisposal.resumeProblem(plan == null ? "" : plan.planId());
        if (!disposalProblem.isBlank()) { return rejected(disposalProblem); }
        if (takeoff.active()) { return rejected("Wait for takeoff to finish, or use Pause or Stop."); }
        if (dirtShop.active()) {
            return rejected("Wait for dirt shopping to finish, or use Pause or Stop.");
        }
        if (!runtimeWorkAllowed()) {
            return rejected(runtimeWorkBlockDetail());
        }
        if (supervisor == null) {
            return rejected("No paused checkpoint is loaded.");
        }
        if (supervisor.status().state() != SupervisorState.PAUSED) {
            return rejected("Resume is allowed only from PAUSED.");
        }
        if (client.world == null || client.player == null) {
            return rejected("Join the target world before resuming.");
        }
        BuildAccessPreflight.Decision access = BuildAccessPreflight.forFloatingBuild(
                client.player.getAbilities().flying, client.player.getAbilities().allowFlying);
        if (access.takeoffFirst()) {
            return takeOffBefore(ControlHttpServer.ControlAction.RESUME);
        }
        if (!access.allowed()) {
            return rejected(access.detail());
        }
        if (automationBlocked()) {
            return rejected(automationBlockDetail());
        }
        if (!depots.maintenanceIdle()) {
            return rejected("Wait for registered-depot scans to finish before resuming.");
        }
        if (!contextMatchesCurrentWorld()) {
            return rejected("The checkpoint belongs to a different server, save, or dimension.");
        }
        DirtRestockCapacityPolicy.Decision capacity = dirtRestockCapacity();
        if (capacity.blocked()) {
            return rejected(capacity.detail());
        }
        try {
            safetyGuard.activate();
            if (capacity.route() == DirtRestockCapacityPolicy.Route.USE_AVAILABLE) {
                supervisor.acceptAvailableDirtBatch();
            }
            supervisor.resume();
            shopHandledThisRestock = false;
            mossHandledThisRestock = false;
            lastMessage = "Resumed.";
            lastRuntimeError = "";
            return accepted(lastMessage);
        } catch (RuntimeException exception) {
            deactivateSafetyGuardWhenSafe();
            return runtimeFailure("Unable to resume", exception);
        }
    }

    private OperatorResult stopImpl() {
        requireClientThread();
        cancelApproach("Approach stopped; owned movement released.");
        materialShop.cancel("Material shopping stopped.");
        mossDeposit.cancel("Moss storage stopped.");
        surplusDisposal.cancel("Surplus disposal stopped.");
        cancelTakeoff("Takeoff stopped; jump input released.");
        cancelDirtShopping("Dirt shopping stopped.");
        placementLoader = null;
        cancelBuildCheck();
        String maintenanceDetail;
        try {
            if (supervisor != null) {
                if (mossDeposit.pending() || materialShop.pending() || surplusDisposal.pending()) { supervisor.pause(); }
                else { supervisor.stop(); }
            }
        } finally {
            maintenanceDetail = depots.cancelMaintenance();
        }
        deactivateSafetyGuardWhenSafe();
        if (automationBlocked()) {
            lastRuntimeError = automationBlockDetail();
            return rejected(lastRuntimeError);
        }
        lastMessage = (mossDeposit.pending() || materialShop.pending() || surplusDisposal.pending()
                ? "Stopped; the original restock checkpoint is paused for its pending inventory receipt."
                : "Stopped.") + (maintenanceDetail.isBlank() ? "" : " " + maintenanceDetail);
        return accepted(lastMessage);
    }

    OperatorResult reset() {
        return runOperator("RESET", null, this::resetImpl);
    }

    OperatorResult unload() {
        return runOperator("UNLOAD", null, this::unloadImpl);
    }

    private OperatorResult unloadImpl() {
        if (!resetTeardown.runtimeWorkAllowed()) { return rejected(resetTeardown.detail()); }
        if (cancelBuildCheck()) { deactivateSafetyGuardWhenSafe(); }
        try {
            boolean cleanupRetry = unloadTeardown.cleanupRequired();
            if (!cleanupRetry) {
                String activity = BuildUnloadPolicy.activityProblem(unloadSnapshot());
                if (!activity.isBlank()) { return rejected(activity); }
            }
            // Even an already paused supervisor polls and records late interaction receipts here.
            supervisor.pause();
            String supportProblem = temporarySupportStateProblem();
            if (!supportProblem.isBlank()) { return rejected(supportProblem); }
            String unsettled = cleanupRetry ? BuildUnloadPolicy.receiptProblem(unloadSnapshot())
                    : BuildUnloadPolicy.settledProblem(unloadSnapshot());
            if (!unsettled.isBlank()) { return rejected(unsettled); }
            String nextRunId = UUID.randomUUID().toString();
            boolean complete = unloadTeardown.attempt(() -> {
                if (execution != null && execution.automationBlocked()) {
                    // This retries control release only; no checkpoint or context file is cleared.
                    String release = execution.prepareForExplicitReset();
                    if (!release.isBlank()) { throw new IllegalStateException(release); }
                }
                closePlanAdapters();
            }, () -> {
                deactivateSafetyGuardWhenSafe();
                if (safetyGuard.active()) {
                    throw new IllegalStateException("automation safety settings could not be restored");
                }
            }, () -> {
                supervisor = null;
                plan = null;
                activeContext = null;
                placementLoader = null;
                lastBuildCheck = null;
                shopHandledThisRestock = false;
                automaticShop = false;
                shopContext = null;
                runId = nextRunId;
                observedIdleContext = null;
                observedIdleSelection = null;
            });
            if (!complete) {
                lastRuntimeError = unloadTeardown.detail();
                lastMessage = lastRuntimeError;
                logger.warn(lastRuntimeError);
                return rejected(lastRuntimeError);
            }
            lastRuntimeError = "";
            lastMessage = "Build unloaded; its checkpoint and registered depots were preserved. "
                    + "Select a placement and use Start to load its saved build state.";
            return accepted(lastMessage);
        } catch (RuntimeException failure) {
            return runtimeFailure("Unable to unload the build safely", failure);
        }
    }

    private BuildUnloadPolicy.Snapshot unloadSnapshot() {
        return new BuildUnloadPolicy.Snapshot(supervisor == null ? null : supervisor.status().state(),
                placementLoader != null, takeoff.active(), dirtShop.active() || materialShop.active() || surplusDisposal.active(),
                dirtShop.snapshot().pendingStacks() + (materialShop.pending() ? 1 : 0) + (surplusDisposal.pending() ? 1 : 0),
                supervisor != null && supervisor.requiresReconciliation(),
                supervisor != null && supervisor.checkpoint().withdrawalInFlight(), executionAutomationBlocked(),
                depots.maintenanceIdle(), depots.automationBlocked());
    }

    private OperatorResult resetImpl() {
        requireClientThread();
        String supportProblem = temporarySupportStateProblem();
        if (!supportProblem.isBlank()) { return rejected(supportProblem); }
        cancelTakeoff("Takeoff cancelled by Reset; jump input released.");
        boolean cleanupRetry = resetTeardown.cleanupRequired();
        if (!cleanupRetry && supervisor != null) {
            SupervisorState state = supervisor.status().state();
            if (state != SupervisorState.STOPPED
                    && state != SupervisorState.PAUSED
                    && state != SupervisorState.DONE) {
                return rejected("Pause or stop before resetting the checkpoint.");
            }
        }
        cancelDirtShopping("Dirt shopping cancelled by Reset.");
        if (dirtShop.active() || shopPurchaseUncertain()) {
            return rejected(dirtShop.active()
                    ? "Wait for the last dirt purchase acknowledgement before resetting."
                    : shopPurchaseUncertaintyDetail());
        }
        if (execution != null) {
            String executionReset = execution.prepareForExplicitReset();
            if (!executionReset.isBlank()) {
                return rejected(executionReset);
            }
        }
        String depotReset = depots.prepareForExplicitReset();
        if (!depotReset.isBlank()) {
            return rejected(depotReset);
        }
        placementLoader = null;
        cancelBuildCheck();
        lastBuildCheck = null;
        if (!cleanupRetry) {
            resetTeardown.begin();
        }
        supervisor = null;
        plan = null;
        activeContext = null;
        shopHandledThisRestock = false;
        runId = UUID.randomUUID().toString();
        lastRuntimeError = resetTeardown.detail();
        lastMessage = lastRuntimeError;
        try {
            closePlanAdapters();
        } catch (RuntimeException exception) {
            return rejectIncompleteReset(
                    "plan adapters could not be closed",
                    exception
            );
        }
        try {
            deactivateSafetyGuardWhenSafe();
        } catch (RuntimeException exception) {
            return rejectIncompleteReset(
                    "automation safety settings could not be restored",
                    exception
            );
        }
        ResetTeardownCoordinator.Result cleanup = resetTeardown.clearStores();
        if (!cleanup.complete()) {
            lastRuntimeError = cleanup.detail();
            lastMessage = lastRuntimeError;
            logger.warn(lastRuntimeError);
            return rejected(lastRuntimeError);
        }
        lastRuntimeError = "";
        lastMessage = "Checkpoint and loaded plan reset; registered depots were preserved.";
        return accepted(lastMessage);
    }

    OperatorResult buyDirt() {
        return runOperator("BUY_DIRT", null, this::buyDirtImpl);
    }

    /** Buying in place needs purchases, so it applies only while shop.json turns them on. */
    private boolean buyInPlace() {
        return settings.buyMaterialsInPlace() && ShopSettings.current().enabled();
    }

    private OperatorResult buyDirtImpl() {
        if (!ShopSettings.current().enabled()) {
            return rejected("Shop purchases are off. Turn them on in config/schematic-supervisor/"
                    + ShopSettings.FILE_NAME + ", or put dirt in a registered chest.");
        }
        if (!runtimeWorkAllowed()) {
            return rejected(runtimeWorkBlockDetail());
        }
        if (dirtShop.active()) {
            return rejected("Dirt shopping is already running; use Pause or Stop to cancel.");
        }
        if (client.world == null || client.player == null) {
            return rejected("Join the target world before buying dirt.");
        }
        if (automationBlocked()) {
            return rejected(automationBlockDetail());
        }
        if (!depotMaintenanceAllowed()) {
            return rejected("Pause or stop supervision before buying dirt manually.");
        }
        if (supervisor != null && (supervisor.requiresReconciliation()
                || supervisor.checkpoint().withdrawalInFlight())) {
            return rejected("Settle the previous depot transfer before buying dirt.");
        }
        if (contextMustMatch() && !contextMatchesCurrentWorld()) {
            return rejected("The loaded run belongs to a different server, save, or dimension.");
        }
        if (!depots.maintenanceIdle()) {
            return rejected("Wait for registered-depot scans to finish before buying dirt.");
        }
        try {
            beginDirtShopping(false);
            return ("FAILED".equals(dirtShop.snapshot().state())
                    || "CAPACITY_BLOCKED".equals(dirtShop.snapshot().state()))
                    ? rejected(lastMessage) : accepted(lastMessage);
        } catch (RuntimeException exception) {
            cancelDirtShopping("Dirt shopping could not start.");
            deactivateSafetyGuardWhenSafe();
            return runtimeFailure("Unable to buy dirt", exception);
        }
    }

    private void beginDirtShopping(boolean automatic) {
        shopContext = MinecraftRunContext.capture(client);
        automaticShop = automatic;
        if (execution != null) {
            if (automatic) {
                execution.stopMovementForShopping();
            } else {
                execution.stopMovement();
            }
        } else {
            var baritone = BaritoneAPI.getProvider().getPrimaryBaritone();
            if (baritone.getBuilderProcess().isActive()
                    || baritone.getCustomGoalProcess().isActive()
                    || baritone.getPathingBehavior().isPathing()) {
                throw new IllegalStateException("Stop current Baritone work before buying dirt.");
            }
        }
        if (automationBlocked()) {
            throw new IllegalStateException(automationBlockDetail());
        }
        safetyGuard.activate();
        if (automatic) {
            shopHandledThisRestock = true;
        }
        lastRuntimeError = "";
        if (automatic) {
            var checkpoint = supervisor.checkpoint();
            MaterialQuantities available = inventory.observation().mainMaterialTotals();
            if (available == null) { throw new IllegalStateException("Current main inventory is unavailable."); }
            int maximumStacks = DirtPurchaseBudget.maximumStacks(plan.plannedMaterials().get(Material.DIRT),
                    checkpoint.consumedMaterials().get(Material.DIRT), available.get(Material.DIRT),
                    checkpoint.restockRequirement().get(Material.DIRT));
            int reservedSlots = MaterialPurchaseBudget.reservedSlotsForOtherShortages(Material.DIRT,
                    checkpoint.restockRequirement(), inventory.snapshot());
            reservedSlots = InventoryCleanupPolicy.purchaseReserve(buyInPlace(), reservedSlots, inventory.observation());
            dirtShop.start(maximumStacks, reservedSlots);
        } else {
            dirtShop.start();
        }
        updateDirtShoppingStatus();
    }

    private boolean tickMaterialShopping() {
        if (surplusUnsettled()) { return false; }
        restoreMaterialReceiptScansIfSettled();
        if (materialShop.active()) {
            materialShop.tick();
            updateMaterialShoppingStatus();
            if (supervisor != null) { publishStatusPeriodically(supervisor.status()); }
            return true;
        }
        if (materialShop.pending()) {
            if (supervisor == null || supervisor.status().state() == SupervisorState.PAUSED
                    || supervisor.status().state() == SupervisorState.STOPPED
                    || supervisor.status().state() == SupervisorState.DONE) {
                depots.tickCancelledMaintenance();
                if (placementLoader != null) { tickPlacementLoader(); }
                return true;
            }
            if (supervisor.status().state() != SupervisorState.RESTOCKING
                    || supervisor.checkpoint().withdrawalInFlight() || supervisor.requiresReconciliation()
                    || mossDeposit.pending() || mossDeposit.active() || dirtShop.active()) {
                supervisor.pause();
                lastRuntimeError = "Restore the original restock checkpoint before reconciling the material purchase.";
                return true;
            }
            if (!depots.maintenanceIdle()) { depots.tickCancelledMaintenance(); return true; }
            if (execution != null) { execution.stopMovementForShopping(); }
            safetyGuard.activate();
            materialShop.resumePending(plan.planId());
            updateMaterialShoppingStatus();
            return true;
        }
        if (supervisor == null || plan == null || supervisor.status().state() != SupervisorState.RESTOCKING
                || supervisor.checkpoint().withdrawalInFlight() || supervisor.requiresReconciliation()
                || mossDeposit.active() || mossDeposit.pending() || dirtShop.active()) { return false; }
        // With purchases off, the core plans every shortage from registered chests.
        if (!ShopSettings.current().enabled()) { return false; }
        var checkpoint = supervisor.checkpoint();
        MaterialQuantities shortage = checkpoint.restockRequirement().shortageFrom(inventory.snapshot());
        for (MaterialShopPolicy.Product selected : MaterialShopPolicy.Product.values()) {
            Material material = Material.block(selected.itemId());
            if (shortage.get(material) < 1) { continue; }
            if (!depots.maintenanceIdle()) {
                depots.tick();
                lastMessage = "Waiting for registered-depot scans before material purchasing.";
                return true;
            }
            InventoryObservation observed = inventory.observation();
            DepotObservation depotFacts = depots.observation();
            boolean scanned = depotFacts.available() && !depotFacts.truncated()
                    && depotFacts.entries().size() == depotFacts.registeredCount()
                    && depotFacts.entries().stream().allMatch(DepotObservation.Entry::scanned);
            List<MaterialPurchaseBudget.SlotCapacity> slots = new ArrayList<>();
            long plainCount = 0;
            if (observed.available()) {
                for (InventoryObservation.Slot slot : observed.mainSlots()) {
                    var kind = MaterialPurchaseBudget.SlotKind.PROTECTED;
                    int room = 0;
                    if (slot.count() == 0) { kind = MaterialPurchaseBudget.SlotKind.EMPTY; room = 64; }
                    else if (slot.itemId().equals(selected.itemId()) && Boolean.TRUE.equals(slot.plainDefaultComponents())) {
                        kind = MaterialPurchaseBudget.SlotKind.COMPATIBLE_PLAIN_STACK;
                        room = Math.max(0, Math.min(64, slot.maxCount()) - slot.count());
                        plainCount += slot.count();
                    }
                    slots.add(new MaterialPurchaseBudget.SlotCapacity(slot.slot(), kind, room));
                }
            }
            int reservedSlots = MaterialPurchaseBudget.reservedSlotsForOtherShortages(material,
                    checkpoint.restockRequirement(), inventory.snapshot());
            reservedSlots = InventoryCleanupPolicy.purchaseReserve(buyInPlace(), reservedSlots, observed);
            MaterialPurchaseBudget.Decision decision = MaterialPurchaseBudget.decide(new MaterialPurchaseBudget.Request(
                    material, plan.plannedMaterials().get(material), checkpoint.consumedMaterials().get(material),
                    plainCount, checkpoint.restockRequirement().get(material), depots.totalScannedStock().get(material),
                    observed.available(), scanned, slots, reservedSlots), buyInPlace());
            if (decision.needsCapacityRecovery()) {
                if (!mossHandledThisRestock && mossDeposit.shouldStart(observed)) {
                    beginMossStorage();
                    return true;
                }
                if (beginSurplusCapacityRecovery()) { return true; }
            }
            switch (decision.route()) {
                case SATISFIED, DEPOT -> { return false; }
                case SCAN_DEPOTS, BLOCKED -> {
                    supervisor.pause();
                    lastRuntimeError = decision.detail();
                    lastMessage = lastRuntimeError;
                    return true;
                }
                case PURCHASE -> {
                    if (execution != null) { execution.stopMovementForShopping(); }
                    if (automationBlocked()) { haltForAutomationBlock(); return true; }
                    safetyGuard.activate();
                    materialShop.begin(plan.planId(), selected, decision.maximumStacks(), reservedSlots);
                    updateMaterialShoppingStatus();
                    return true;
                }
            }
        }
        return false;
    }

    private void updateMaterialShoppingStatus() {
        lastMessage = "Material shop: " + materialShop.detail();
        if (!materialShop.active() && materialShop.failed()) {
            if (supervisor != null) { supervisor.pause(); }
            lastRuntimeError = lastMessage;
            deactivateSafetyGuardWhenSafe();
        } else if (!materialShop.active()) {
            lastRuntimeError = "";
            restoreMaterialReceiptScansIfSettled();
        }
    }

    private void restoreMaterialReceiptScansIfSettled() {
        if (materialReceiptHeldScans && materialShop.durableReceiptSettled()
                && !surplusUnsettled()
                && !mossDeposit.pending() && !mossDeposit.active() && !dirtShop.active()
                && supervisor != null && supervisor.status().state() == SupervisorState.RESTOCKING
                && depots.maintenanceIdle() && !depots.automationBlocked()) {
            depots.resumeAutomaticScansAfterMoss();
            materialReceiptHeldScans = false;
        }
    }

    private boolean surplusUnsettled() {
        return surplusDisposal.active() || surplusDisposal.pending() || surplusDisposal.unavailable();
    }

    private boolean tickInventoryCleanup(boolean inventoryAccess) {
        if (!buyInPlace() || !settings.discardSurplusDirectly()
                || supervisor == null || plan == null || execution == null || placementLoader != null) { return false; }
        SupervisorState state = supervisor.status().state();
        if (state == SupervisorState.PAUSED || state == SupervisorState.STOPPED || state == SupervisorState.DONE) {
            cleanupBatchActive = false;
            return false;
        }
        if (state == SupervisorState.BUILDING) {
            inventoryCleanupTicks = Math.min(InventoryCleanupPolicy.INTERVAL_TICKS, inventoryCleanupTicks + 1);
        }
        if (surplusUnsettled() || mossDeposit.active() || mossDeposit.pending() || mossDeposit.unavailable()
                || materialShop.active() || materialShop.pending() || materialShop.unavailable()
                || dirtShop.active() || shopPurchaseUncertain() || repairReceiptHeldScans
                || !execution.hoeRepairBlockDetail().isBlank() || !depots.maintenanceIdle()
                || supervisor.requiresReconciliation() || supervisor.checkpoint().withdrawalInFlight()) { return false; }
        InventoryObservation observed = inventory.observation();
        if (cleanupBatchActive && state == SupervisorState.RESTOCKING) {
            if (cleanupBatchStacks >= InventoryCleanupPolicy.MAXIMUM_BATCH_STACKS || !InventoryCleanupPolicy.hasSurplus(observed)) {
                cleanupBatchActive = false; inventoryCleanupTicks = 0;
                return false;
            }
            if (inventoryAccess) { beginDirectSurplusDisposal(); }
            else { lastMessage = "Inventory cleanup is waiting for an idle inventory cursor."; }
            return true;
        }
        if (state != SupervisorState.BUILDING
                || !InventoryCleanupPolicy.due(true, inventoryCleanupTicks, observed)
                || !MinecraftBackgroundBuildAccess.allowsWorldActions(client)) { return false; }
        var facts = execution.observation();
        if (!facts.available() || facts.receipt() != null || !facts.error().isBlank()
                || !Boolean.FALSE.equals(facts.ownedMining()) || !Boolean.FALSE.equals(facts.managerBreaking())) { return false; }
        if (!supervisor.requestInventoryMaintenance()) { return false; }
        cleanupBatchActive = true; cleanupBatchStacks = 0;
        lastMessage = "Clearing approved surplus at the current position before continuing the same build slice.";
        return true;
    }

    private boolean beginSurplusCapacityRecovery() {
        if (settings.discardSurplusDirectly() && mossDeposit.shouldStart(inventory.observation())) {
            beginDirectSurplusDisposal();
            return true;
        }
        if (!settings.discardSurplusWhenStorageFull()) { return false; }
        var exhausted = mossDeposit.takeStorageExhaustion();
        if (exhausted.isEmpty()) { return false; }
        if (execution != null) { execution.stopMovementForShopping(); }
        if (automationBlocked()) { haltForAutomationBlock(); return true; }
        safetyGuard.activate();
        surplusDisposal.begin(true, plan, exhausted.orElseThrow());
        updateSurplusDisposalStatus();
        return true;
    }

    private boolean tickSurplusDisposal() {
        if (surplusDisposal.active()) {
            surplusDisposal.tick();
            updateSurplusDisposalStatus();
            if (supervisor != null) { publishStatusPeriodically(supervisor.status()); }
            return true;
        }
        if (!surplusDisposal.pending()) { return false; }
        if (supervisor == null || supervisor.status().state() == SupervisorState.PAUSED
                || supervisor.status().state() == SupervisorState.STOPPED
                || supervisor.status().state() == SupervisorState.DONE) {
            depots.tickCancelledMaintenance();
            if (placementLoader != null) { tickPlacementLoader(); }
            return true;
        }
        if (supervisor.status().state() != SupervisorState.RESTOCKING
                || supervisor.checkpoint().withdrawalInFlight() || supervisor.requiresReconciliation()
                || mossDeposit.active() || mossDeposit.pending() || materialShop.active()
                || materialShop.pending() || dirtShop.active() || shopPurchaseUncertain()) {
            supervisor.pause();
            lastRuntimeError = "Restore the original restock checkpoint before observing the surplus disposal receipt.";
            lastMessage = lastRuntimeError;
            return true;
        }
        if (!depots.maintenanceIdle()) { depots.tickCancelledMaintenance(); return true; }
        if (execution != null) { execution.stopMovementForShopping(); }
        safetyGuard.activate();
        // Reconciliation never grants another discard, including after the preference is disabled.
        surplusDisposal.resumeReceipt(plan.planId());
        updateSurplusDisposalStatus();
        return true;
    }

    private void updateSurplusDisposalStatus() {
        lastMessage = surplusDisposal.detail();
        if (!surplusDisposal.active() && surplusDisposal.failed()) {
            cleanupBatchActive = false;
            if (supervisor != null) { supervisor.pause(); }
            lastRuntimeError = lastMessage;
            if (!surplusDisposal.pending() && !surplusDisposal.unavailable()) {
                // A later explicit Resume may recheck storage after a no-input preflight failure.
                mossHandledThisRestock = false;
            }
            deactivateSafetyGuardWhenSafe();
        } else if (!surplusDisposal.active() && !surplusDisposal.pending()) {
            lastRuntimeError = "";
            if (surplusDisposal.inventoryReconciled() || surplusDisposal.cleanupDeferred()) {
                // Do not chase recollected items in the same maintenance batch.
                cleanupBatchActive = false;
                inventoryCleanupTicks = 0;
            }
            // Direct mode skips storage for the rest of this refill; additional capacity can still request a fresh discard.
            mossHandledThisRestock = settings.discardSurplusDirectly();
            if (settings.discardSurplusDirectly()) {
                // A pause or restored receipt can hold stock scans; storage no longer releases that hold in direct mode.
                depots.resumeAutomaticScansAfterMoss();
            }
        }
    }

    private boolean tickDirtShopping() {
        if (surplusUnsettled()) { return false; }
        if (mossDeposit.active()) {
            mossDeposit.tick();
            updateMossStorageStatus();
            if (supervisor != null) { publishStatusPeriodically(supervisor.status()); }
            return true;
        }
        MossRestockGate.Decision mossDecision = mossRestockDecision();
        if (mossDecision == MossRestockGate.Decision.WAIT_PAUSED
                && (mossDeposit.pending() || !dirtShop.active())) { return false; }
        if (mossDecision == MossRestockGate.Decision.HOLD) {
            if (supervisor != null) { supervisor.pause(); }
            lastRuntimeError = "Restore the original restock checkpoint before reconciling the moss deposit.";
            return true;
        }
        if (mossDecision == MossRestockGate.Decision.RECONCILE) {
            if (!depots.maintenanceIdle()) { depots.tick(); }
            else { beginMossStorage(); }
            return true;
        }
        if (dirtShop.active()) {
            dirtShop.tick();
            updateDirtShoppingStatus();
            if (supervisor != null) {
                publishStatusPeriodically(supervisor.status());
            }
            return true;
        }
        if (supervisor == null) {
            return false;
        }
        SupervisorStatus status = supervisor.status();
        if (status.state() != SupervisorState.RESTOCKING) {
            shopHandledThisRestock = false;
            mossHandledThisRestock = false;
            return false;
        }
        if (supervisor.checkpoint().restockRequirement().shortageFrom(inventory.snapshot()).get(Material.DIRT) < 1
                || supervisor.checkpoint().withdrawalInFlight()
                || supervisor.requiresReconciliation()) {
            return false;
        }
        if (!depots.maintenanceIdle()) {
            depots.tick();
            if (!haltForAutomationBlock()) {
                lastMessage = "Waiting for registered-depot scans before buying dirt.";
            }
            return true;
        }
        if (mossDecision == MossRestockGate.Decision.DEPOSIT) {
            beginMossStorage();
            return true;
        }
        DirtRestockCapacityPolicy.Decision capacity = dirtRestockCapacity();
        if (capacity.blocked()) {
            if (beginSurplusCapacityRecovery()) { return true; }
            pauseForDirtCapacity(capacity);
            return true;
        }
        if (capacity.route() == DirtRestockCapacityPolicy.Route.USE_AVAILABLE) {
            if (supervisor.acceptAvailableDirtBatch()) {
                lastMessage = capacity.detail();
                if (lastRuntimeError.startsWith(DirtRestockCapacityPolicy.BLOCKER_PREFIX)) {
                    lastRuntimeError = "";
                }
            }
            return false;
        }
        if (capacity.route() != DirtRestockCapacityPolicy.Route.SHOP || shopHandledThisRestock) {
            return false;
        }
        beginDirtShopping(true);
        return true;
    }

    private DirtRestockCapacityPolicy.Decision dirtRestockCapacity() {
        DirtRestockCapacityPolicy.Decision satisfied = new DirtRestockCapacityPolicy.Decision(
                DirtRestockCapacityPolicy.Route.SATISFIED, "");
        if (supervisor == null || dirtShop.active() || !depots.maintenanceIdle()) { return satisfied; }
        var checkpoint = supervisor.checkpoint();
        boolean restocking = checkpoint.state() == SupervisorState.RESTOCKING
                || (checkpoint.state() == SupervisorState.PAUSED
                        && checkpoint.resumeState() == SupervisorState.RESTOCKING);
        if (!restocking || checkpoint.withdrawalInFlight() || supervisor.requiresReconciliation()) {
            return satisfied;
        }
        InventoryObservation observed = inventory.observation();
        if (surplusDisposal.pending() || mossDeposit.pending()
                || !mossHandledThisRestock && mossDeposit.shouldStart(observed)) {
            return new DirtRestockCapacityPolicy.Decision(DirtRestockCapacityPolicy.Route.STORAGE,
                    settings.discardSurplusDirectly()
                            ? "Discard approved plain clearing pickups directly before refilling Dirt."
                            : "Inspect registered chests for plain clearing-pickup storage before refilling Dirt.");
        }
        DepotObservation depotFacts = depots.observation();
        boolean scanned = depotFacts.available() && !depotFacts.truncated()
                && depotFacts.entries().size() == depotFacts.registeredCount()
                && depotFacts.entries().stream().allMatch(DepotObservation.Entry::scanned);
        int reservedSlots = MaterialPurchaseBudget.reservedSlotsForOtherShortages(Material.DIRT,
                checkpoint.restockRequirement(), inventory.snapshot());
        reservedSlots = InventoryCleanupPolicy.purchaseReserve(buyInPlace(), reservedSlots, observed);
        DirtRestockCapacityPolicy.Decision capacity = DirtRestockCapacityPolicy.decide(checkpoint.restockRequirement().get(Material.DIRT),
                observed, depots.totalScannedStock().get(Material.DIRT), scanned, reservedSlots, buyInPlace(),
                ShopSettings.current().enabled());
        return capacity.blocked() && !mossDeposit.capacityDetail().isBlank()
                ? new DirtRestockCapacityPolicy.Decision(capacity.route(), capacity.detail() + " " + mossDeposit.capacityDetail())
                : capacity;
    }

    private MossRestockGate.Decision mossRestockDecision() {
        if (supervisor == null) {
            return mossDeposit.pending() ? MossRestockGate.Decision.WAIT_PAUSED
                    : MossRestockGate.Decision.CONTINUE;
        }
        SupervisorState state = supervisor.status().state();
        boolean restocking = state == SupervisorState.RESTOCKING;
        boolean paused = state == SupervisorState.PAUSED || state == SupervisorState.STOPPED
                || state == SupervisorState.DONE;
        return MossRestockGate.decide(mossDeposit.pending(), restocking, paused,
                supervisor.requiresReconciliation() || supervisor.checkpoint().withdrawalInFlight() || dirtShop.active(),
                restocking && supervisor.checkpoint().restockRequirement()
                        .shortageFrom(inventory.snapshot()).get(Material.DIRT) > 0,
                mossHandledThisRestock, restocking && mossDeposit.shouldStart(inventory.observation()));
    }

    private void beginMossStorage() {
        if (settings.discardSurplusDirectly() && !mossDeposit.pending()) {
            beginDirectSurplusDisposal();
            return;
        }
        if (execution != null) { execution.stopMovementForShopping(); }
        if (automationBlocked()) { haltForAutomationBlock(); return; }
        safetyGuard.activate();
        mossHandledThisRestock = true;
        mossDeposit.begin(plan.planId());
        updateMossStorageStatus();
    }

    private void beginDirectSurplusDisposal() {
        if (execution != null) { execution.stopMovementForShopping(); }
        if (automationBlocked()) { haltForAutomationBlock(); return; }
        safetyGuard.activate();
        mossHandledThisRestock = true;
        if (buyInPlace()) {
            if (!cleanupBatchActive) { cleanupBatchStacks = 0; cleanupBatchActive = true; }
            cleanupBatchStacks++;
        }
        surplusDisposal.beginDirect(settings.discardSurplusDirectly(), plan, buyInPlace());
        updateSurplusDisposalStatus();
    }

    private void updateMossStorageStatus() {
        lastMessage = mossDeposit.detail();
        if (!mossDeposit.active() && mossDeposit.failed()) {
            if (supervisor != null) { supervisor.pause(); }
            lastRuntimeError = mossDeposit.detail();
            deactivateSafetyGuardWhenSafe();
        } else if (!mossDeposit.active()) {
            lastRuntimeError = "";
        }
    }

    private void pauseForDirtCapacity(DirtRestockCapacityPolicy.Decision capacity) {
        supervisor.pause();
        if (haltForAutomationBlock()) { return; }
        lastRuntimeError = capacity.detail();
        lastMessage = capacity.detail();
        deactivateSafetyGuardWhenSafe();
        notifications.alert(capacity.detail(),
                supervisor.checkpoint().restockRequirement().shortageFrom(inventory.snapshot()));
    }

    private void updateDirtShoppingStatus() {
        DirtShopPurchase.Snapshot snapshot = dirtShop.snapshot();
        lastMessage = "Dirt shop: " + snapshot.detail();
        if (snapshot.active()) {
            return;
        }
        boolean wasAutomatic = automaticShop;
        automaticShop = false;
        shopContext = null;
        if (wasAutomatic && ("COMPLETE".equals(snapshot.state())
                || "CAPACITY_BLOCKED".equals(snapshot.state()))) {
            DirtRestockCapacityPolicy.Decision capacity = dirtRestockCapacity();
            if (capacity.blocked()) {
                pauseForDirtCapacity(capacity);
                return;
            }
        }
        if ("FAILED".equals(snapshot.state())
                || (!wasAutomatic && "CAPACITY_BLOCKED".equals(snapshot.state()))) {
            lastRuntimeError = lastMessage;
            if (wasAutomatic && supervisor != null) {
                supervisor.pause();
            }
            notifications.alert(lastMessage, supervisor == null
                    ? MaterialQuantities.empty() : supervisor.status().missingMaterials());
        }
        if (!wasAutomatic || !"COMPLETE".equals(snapshot.state())) {
            deactivateSafetyGuardWhenSafe();
        }
    }

    private boolean cancelDirtShopping(String detail) {
        if (!dirtShop.active()) {
            return false;
        }
        dirtShop.cancel(detail);
        automaticShop = false;
        if (!dirtShop.active()) {
            shopContext = null;
        }
        lastMessage = dirtShop.active()
                ? detail + " Waiting for the last purchase acknowledgement." : detail;
        return true;
    }

    private boolean shopContextMatchesCurrentWorld() {
        if (shopContext == null || client.world == null || client.player == null) {
            return false;
        }
        try {
            return shopContext.equals(MinecraftRunContext.capture(client));
        } catch (RuntimeException exception) {
            return false;
        }
    }

    OperatorResult registerTargetedDepot() {
        return runOperator("REGISTER_DEPOT", null, this::registerTargetedDepotImpl);
    }

    private OperatorResult registerTargetedDepotImpl() {
        requireClientThread();
        if (!runtimeWorkAllowed()) {
            return rejected(runtimeWorkBlockDetail());
        }
        if (automationBlocked()) {
            return rejected(automationBlockDetail());
        }
        if (!depotMaintenanceAllowed()) {
            return rejected("Pause or stop supervision before registering a depot.");
        }
        try {
            return accepted(depots.registerTargetedDepot());
        } catch (RuntimeException exception) {
            return runtimeFailure("Unable to register depot", exception);
        }
    }

    OperatorResult registerNearbyDepots(int radius) {
        return runOperator("REGISTER_NEARBY_DEPOTS", null, () -> {
            requireClientThread();
            if (!runtimeWorkAllowed()) { return rejected(runtimeWorkBlockDetail()); }
            if (automationBlocked()) { return rejected(automationBlockDetail()); }
            if (!depotMaintenanceAllowed()) { return rejected("Pause or stop supervision before registering depots."); }
            try { return accepted(depots.registerNearbyDepots(radius)); }
            catch (RuntimeException exception) { return runtimeFailure("Unable to register nearby depots", exception); }
        });
    }

    private OperatorResult rescanDepotsImpl() {
        requireClientThread();
        if (client.world == null || client.player == null) {
            return rejected("Join the target world before scanning depots.");
        }
        if (activeContext != null && !contextMatchesCurrentWorld()) {
            return rejected("The loaded run belongs to a different server, save, or dimension.");
        }
        if (!runtimeWorkAllowed()) {
            return rejected(runtimeWorkBlockDetail());
        }
        if (automationBlocked()) {
            return rejected(automationBlockDetail());
        }
        if (!depotMaintenanceAllowed()) {
            return rejected("Pause or stop supervision before scanning depots.");
        }
        try {
            return accepted(depots.rescanDepots());
        } catch (RuntimeException exception) {
            return runtimeFailure("Unable to scan depots", exception);
        }
    }

    OperatorResult clearDepots() {
        return runOperator("CLEAR_DEPOTS", null, this::clearDepotsImpl);
    }

    private OperatorResult clearDepotsImpl() {
        requireClientThread();
        if (!runtimeWorkAllowed()) {
            return rejected(runtimeWorkBlockDetail());
        }
        if (automationBlocked()) {
            return rejected(automationBlockDetail());
        }
        if (!depotMaintenanceAllowed()) {
            return rejected("Pause or stop supervision before clearing registered depots.");
        }
        try {
            return accepted(depots.clearDepots());
        } catch (RuntimeException exception) {
            return runtimeFailure("Unable to clear depots", exception);
        }
    }

    List<String> listDepots() {
        requireClientThread();
        return depots.listDepots();
    }

    void tick() {
        try {
            mossCustody.tick();
            tickRuntime();
            if (!closed) { MinecraftBackgroundBuildAccess.returnLeftPage(client, containerWorkSettled()); }
        } finally {
            tickPlacementReconciliationProbe();
            publishObservation();
        }
    }

    /** No restock, shop, pickup, disposal, or depot work is running or about to open a container. */
    private boolean containerWorkSettled() {
        return !dirtShop.active() && dirtShop.snapshot().pendingStacks() == 0
                && !materialShop.active() && !materialShop.pending()
                && !mossDeposit.active() && !mossDeposit.pending()
                && !surplusDisposal.active() && !surplusDisposal.pending()
                && depots.maintenanceIdle()
                && (supervisor == null || supervisor.status().state() != SupervisorState.RESTOCKING
                        && !supervisor.checkpoint().withdrawalInFlight());
    }

    private void refreshGlowstoneRestockBatch() {
        if (buyInPlace()) { return; }
        if (supervisor == null || plan == null || lightingSchedule == null || execution == null
                || placementLoader != null || takeoff.active() || !contextMatchesCurrentWorld()
                || !MinecraftBackgroundBuildAccess.allowsNewInventoryTransaction(client)
                || automationBlocked() || repairReceiptHeldScans || !execution.hoeRepairBlockDetail().isBlank()
                || materialShop.active() || materialShop.pending() || materialShop.unavailable()
                || !materialShop.stateProblem().isBlank()
                || mossDeposit.active() || mossDeposit.pending() || mossDeposit.unavailable()
                || !mossDeposit.stateProblem().isBlank()
                || surplusUnsettled()
                || dirtShop.active() || dirtShop.snapshot().pendingStacks() > 0 || !depots.maintenanceIdle()) { return; }
        var saved = supervisor.checkpoint();
        if (saved.restockRequirement().get(Material.GLOWSTONE) < 1
                || saved.withdrawalInFlight() || supervisor.requiresReconciliation()) { return; }
        ExecutionObservation executionFacts = execution.observation();
        if (!executionFacts.available() || executionFacts.receipt() != null || !executionFacts.error().isBlank()
                || !Boolean.FALSE.equals(executionFacts.ownedMining())
                || !Boolean.FALSE.equals(executionFacts.managerBreaking())) { return; }
        long target = GlowstoneRestockRefreshPolicy.target(plan, lightingSchedule, saved,
                inventory.observation(), depots.observation());
        if (target > 0 && supervisor.refreshGlowstoneRestockBatch(saved, target)) {
            lastMessage = "Fresh registered-depot stock refreshed this lighting restock to " + target + " Glowstone.";
        }
    }

    private boolean recoverWithdrawalCapacity() {
        if (buyInPlace()) { return false; }
        if (supervisor != null && supervisor.status().state() == SupervisorState.BUILDING) {
            withdrawalCapacityRecoveries = 0;
        }
        if (supervisor == null || plan == null || supervisor.status().state() != SupervisorState.RESTOCKING
                || supervisor.checkpoint().withdrawalInFlight() || supervisor.requiresReconciliation()
                || mossDeposit.active() || mossDeposit.pending()
                || materialShop.active() || materialShop.pending() || surplusUnsettled() || dirtShop.active()) {
            return false;
        }
        if (!depots.maintenanceIdle()) {
            lastMessage = "Waiting for registered-depot maintenance before withdrawal capacity admission.";
            return true;
        }
        InventoryObservation observed = inventory.observation();
        var shortage = supervisor.checkpoint().restockRequirement().shortageFrom(inventory.snapshot());
        var route = WithdrawalCapacityPolicy.decide(shortage, observed, mossHandledThisRestock,
                mossDeposit.shouldStart(observed), settings.discardSurplusWhenStorageFull() || settings.discardSurplusDirectly());
        if (route == WithdrawalCapacityPolicy.Route.READY) { return false; }
        if (withdrawalCapacityRecoveries >= SurplusPickupPolicy.MAXIMUM_STACKS) {
            supervisor.pause();
            lastRuntimeError = "Depot withdrawal capacity recovery budget exhausted; items retained for inspection.";
            lastMessage = lastRuntimeError;
            return true;
        }
        withdrawalCapacityRecoveries++;
        if (route == WithdrawalCapacityPolicy.Route.STORE) { beginMossStorage(); return true; }
        if (route == WithdrawalCapacityPolicy.Route.DISPOSE && beginSurplusCapacityRecovery()) { return true; }
        supervisor.pause();
        lastRuntimeError = "Depot withdrawal capacity blocked: compatible main inventory room is insufficient "
                + "or unavailable after registered storage recovery. Items were retained. " + mossDeposit.capacityDetail();
        lastMessage = lastRuntimeError;
        return true;
    }

    private void tickPlacementReconciliationProbe() {
        boolean placementEnabled = placementReconciliationProbe != null && placementReconciliationProbe.enabled();
        boolean clearingEnabled = clearingReconciliationProbe != null && clearingReconciliationProbe.enabled();
        if ((!placementEnabled && !clearingEnabled) || closed || !client.isOnThread()) { return; }
        try {
            String state = stateName();
            if (!"IDLE".equals(state) && !"PAUSED".equals(state)) {
                if (placementEnabled) { placementReconciliationProbe.invalidate(); }
                if (clearingEnabled) { clearingReconciliationProbe.invalidate(); }
                return;
            }
            ExecutionObservation facts = execution == null ? null : execution.observation();
            boolean inactiveExecution = facts == null || switch (facts.mode()) {
                case "IDLE", "SUSPENDED", "SUCCEEDED", "FAILED", "AUTOMATION_BLOCKED" -> true;
                default -> false;
            };
            var baritone = BaritoneAPI.getProvider().getPrimaryBaritone();
            boolean automationIdle = inactiveExecution && placementLoader == null && !takeoff.active() && !approachActive()
                    && !dirtShop.active() && !materialShop.active() && !mossDeposit.active() && !surplusDisposal.active()
                    && depots.maintenanceIdle() && !baritone.getBuilderProcess().isActive()
                    && !baritone.getCustomGoalProcess().isActive() && !baritone.getPathingBehavior().isPathing()
                    && (client.interactionManager == null || !client.interactionManager.isBreakingBlock())
                    && (facts == null || !Boolean.TRUE.equals(facts.ownedMining()));
            if (execution != null) {
                var status = execution.pollSafeReturn().status();
                automationIdle &= status != SafeReturnStatus.RUNNING
                        && status != SafeReturnStatus.WAITING_FOR_SCREEN;
            }
            boolean pending = materialShop.pending() || materialShop.unavailable()
                    || surplusDisposal.pending() || surplusDisposal.unavailable()
                    || mossDeposit.pending() || mossDeposit.unavailable() || repairReceiptHeldScans
                    || dirtShop.snapshot().pendingStacks() > 0
                    || supervisor != null && supervisor.checkpoint().withdrawalInFlight()
                    || facts != null && facts.receipt() != null
                    || execution != null && (!execution.hoeRepairBlockDetail().isBlank()
                        || !execution.temporarySupportBlockDetail().isBlank());
            if (supportStore.load().filter(journal -> !journal.complete()).isPresent()) { pending = true; }
            if (placementEnabled) {
                placementReconciliationProbe.tick(client, state, automationIdle, pending,
                        checkpointPath, plan == null ? null : plan.planId());
            }
            if (clearingEnabled) {
                clearingReconciliationProbe.tick(client, state, automationIdle, pending, checkpointPath, plan);
            }
        } catch (IOException | RuntimeException unavailable) {
            if (placementEnabled) { placementReconciliationProbe.invalidate(); }
            if (clearingEnabled) { clearingReconciliationProbe.invalidate(); }
            // Optional read-only diagnostics must never affect normal runtime behavior.
        }
    }

    private void tickRuntime() {
        requireClientThread();
        if (closed) {
            return;
        }
        if (approachActive()) {
            pausedApproach.tick();
            lastMessage = pausedApproach.detail();
            return;
        }
        if (takeoff.active()) {
            takeoff.tick();
            updateTakeoffStatus();
            if (!takeoff.active()) {
                runActionAfterTakeoff();
            }
            return;
        }
        if (!runtimeWorkAllowed()) {
            lastRuntimeError = runtimeWorkBlockDetail();
            return;
        }
        materialShop.tickCancelledCleanup();
        if (repairReceiptHeldScans && execution != null && execution.hoeRepairBlockDetail().isBlank()
                && !surplusUnsettled()
                && !mossDeposit.active() && !mossDeposit.pending() && !mossDeposit.unavailable()
                && !materialShop.active() && !materialShop.pending() && !materialShop.unavailable()
                && !dirtShop.active() && depots.maintenanceIdle() && !depots.automationBlocked()) {
            depots.resumeAutomaticScansAfterMoss();
            repairReceiptHeldScans = false;
        }
        if (shopPurchaseUncertain()) {
            dirtShop.tick();
            if (!shopPurchaseUncertain()) {
                updateDirtShoppingStatus();
            }
        }
        if (shopPurchaseUncertain()
                || mossDeposit.unavailable() || materialShop.unavailable() || surplusDisposal.unavailable()
                || !AutomationReleasePolicy.mayTickDepots(executionAutomationBlocked())) {
            haltForAutomationBlock();
            return;
        }
        health.tick();
        if (client.world == null || client.player == null) {
            pauseForDisconnect();
            return;
        }
        if ((dirtShop.active() && !shopContextMatchesCurrentWorld())
                || (contextMustMatch() && !contextMatchesCurrentWorld())) {
            pauseForContextMismatch();
            return;
        }
        try {
            long expectedControlSequence = controlSequence;
            var restockTransition = MinecraftBackgroundBuildAccess.leavePassiveScreenForRestock(
                    client, () -> passiveRestockTransitionReady(expectedControlSequence));
            if (restockTransition == BackgroundBuildPolicy.RestockTransition.INVALIDATED) { return; }
            boolean inventoryAccess = MinecraftBackgroundBuildAccess.allowsNewInventoryTransaction(client);
            refreshGlowstoneRestockBatch();
            if ((inventoryAccess || surplusDisposal.active() || surplusDisposal.pending())
                    && tickSurplusDisposal()) { return; }
            if (tickInventoryCleanup(inventoryAccess)) { return; }
            if ((inventoryAccess || materialShop.active() || materialShop.pending())
                    && tickMaterialShopping()) { return; }
            if ((inventoryAccess || dirtShop.active() || mossDeposit.active() || mossDeposit.pending())
                    && tickDirtShopping()) {
                return;
            }
            if (mossDeposit.pending() || surplusDisposal.pending()) {
                depots.tickCancelledMaintenance();
                if (placementLoader != null) { tickPlacementLoader(); }
                return;
            }
            depots.tick();
            if (!AutomationReleasePolicy.mayAdvancePlanAdapters(
                    depots.automationBlocked(),
                    executionAutomationBlocked()
            )) {
                haltForAutomationBlock();
                return;
            }
            if (placementLoader != null) {
                tickPlacementLoader();
                return;
            }
            if (buildCheck != null) {
                tickBuildCheck();
                return;
            }
            if (execution != null) {
                execution.tick();
                if (haltForAutomationBlock()) {
                    return;
                }
            }
            if (verification != null) {
                verification.tick();
            }
            if (supervisor == null) {
                return;
            }
            if (!inventoryAccess && supervisor.status().state() == SupervisorState.RESTOCKING
                    && !supervisor.checkpoint().withdrawalInFlight()) {
                String blocker = MinecraftBackgroundBuildAccess.pageLeaveBlocker(client);
                lastMessage = blocker.isBlank()
                        ? "Restocking is waiting for other work to finish before it leaves the open page."
                        : "Restocking is waiting to leave the open page: " + blocker + ".";
                publishStatusPeriodically(supervisor.status());
                return;
            }
            // A scan may have completed above. Refresh before the core can allocate its first withdrawal.
            refreshGlowstoneRestockBatch();
            if (inventoryAccess && recoverWithdrawalCapacity()) { return; }
            supervisor.tick();
            if (haltForAutomationBlock()) {
                return;
            }
            SupervisorStatus status = supervisor.status();
            if (status.state() == SupervisorState.DONE) {
                deactivateSafetyGuardWhenSafe();
                lastMessage = "All " + plan.chunkCount()
                        + " chunks passed two identical full verification passes."
                        + (plan.plantingDeferred() ? " Construction complete; planting deferred ("
                                + plan.deferredSeedCells() + " seed cells)." : "");
            }
            publishStatusPeriodically(status);
        } catch (RuntimeException exception) {
            failSafeOnTick(exception);
        }
    }

    private boolean passiveRestockTransitionReady(long expectedControlSequence) {
        if (closed || controlSequence != expectedControlSequence || !runtimeWorkAllowed()
                || supervisor == null || plan == null || execution == null || placementLoader != null
                || takeoff.active() || !contextMatchesCurrentWorld()
                || !BackgroundBuildPolicy.requiresPassiveRestockTransition(supervisor.status().state(),
                        cleanupBatchActive || !supervisor.checkpoint().restockRequirement().shortageFrom(inventory.snapshot()).isEmpty(),
                        !supervisor.requiresReconciliation() && !supervisor.checkpoint().withdrawalInFlight())) {
            return false;
        }
        if (automationBlocked() || repairReceiptHeldScans || !execution.hoeRepairBlockDetail().isBlank()
                || materialShop.active() || materialShop.pending() || materialShop.unavailable()
                || !materialShop.stateProblem().isBlank()
                || mossDeposit.active() || mossDeposit.pending() || mossDeposit.unavailable()
                || !mossDeposit.stateProblem().isBlank()
                || surplusUnsettled()
                || dirtShop.active() || dirtShop.snapshot().pendingStacks() > 0
                || !BackgroundBuildPolicy.depotSettledForPassiveRestock(depots.observation())) { return false; }
        ExecutionObservation facts = execution.observation();
        if (!facts.available() || facts.receipt() != null || !facts.error().isBlank()
                || !Boolean.FALSE.equals(facts.ownedMining()) || !Boolean.FALSE.equals(facts.managerBreaking())) {
            return false;
        }
        try {
            return new HoeRepairFileStore(hoeRepairPath).load().filter(journal -> !journal.confirmed()).isEmpty();
        } catch (IOException | RuntimeException unavailable) {
            return false;
        }
    }

    OperatorResult statusResult() {
        requireClientThread();
        return accepted(statusText());
    }

    byte[] observationSnapshot() {
        byte[] snapshot = observationSnapshot;
        return snapshot == null ? null : snapshot.clone();
    }

    byte[] progressSnapshot() {
        return progressCache.snapshot();
    }

    private void publishProgress() {
        if (closed) {
            progressCache.updateUnavailable("The supervisor runtime is closed.");
        } else if (placementLoader != null) {
            progressCache.updateUnavailable("The plan is loading.");
        } else if (supervisor == null) {
            progressCache.updateUnavailable("No plan is loaded.");
        } else {
            SchematicSupervisor current = supervisor;
            progressCache.updateAvailable(current.progressKey(), current::progress);
        }
    }

    private void publishObservation() {
        requireClientThread();
        try {
            // Progress first, so a failing game adapter below cannot stop progress publication.
            publishProgress();
            soilWatchpoints.tick(client, plan, activeContext,
                    activeContext != null && contextMatchesCurrentWorld());
            observationSnapshot = SupervisorProtocolJson.encodeObservation(observe());
        } catch (RuntimeException exception) {
            // Keep HTTP observation available even when a game adapter is failing.
            observationSnapshot = SupervisorProtocolJson.encodeObservation(new AgentObservation(
                    runId, "ERROR", Instant.now(), false, false, controlTokenConfigured,
                    List.of("Observation failed: " + safeMessage(exception)),
                    closed ? List.of() : List.of("PAUSE", "STOP"),
                    null, null, null, safeMessage(exception), lastMessage, "Unknown",
                    controlSequence, lastControlAction, lastControlRequestId)
                    .withProgress(progressCache.revision(),
                            supervisor == null ? null : supervisor.lastConfirmedProgressAt()));
        }
    }

    private AgentObservation observe() {
        refreshIdleRunIdentity();
        boolean connected = client.world != null && client.player != null;
        boolean contextMatches = connected
                && (activeContext == null || contextMatchesCurrentWorld());
        SupervisorStatus status = supervisor == null ? null : supervisor.status();
        boolean shopping = dirtShop.active() || materialShop.active() || surplusDisposal.active();
        ControlAvailability.Result availability = ControlAvailability.evaluate(new ControlAvailability.Facts(
                closed,
                placementLoader != null || supervisor != null || shopping || takeoff.active(),
                connected,
                contextMatches,
                runtimeWorkAllowed() ? "" : orElse(runtimeWorkBlockDetail(), "Runtime work is blocked."),
                automationBlocked() ? orElse(automationBlockDetail(), "Automation is blocked.") : "",
                supervisor != null && supervisor.requiresReconciliation(),
                controlTokenConfigured,
                takeoff.active() ? orElse(takeoff.snapshot().detail(), "Taking off.") : "",
                approachActive() ? orElse(pausedApproach.detail(), "Approaching.") : "",
                shopping ? orElse(lastMessage, "Shopping is in progress.") : "",
                depots.maintenanceIdle(),
                this::depotMaintenanceAllowed,
                () -> {
                    DirtRestockCapacityPolicy.Decision capacity = dirtRestockCapacity();
                    return capacity.blocked() ? orElse(capacity.detail(), "Dirt restock capacity is blocked.") : "";
                },
                status == null ? null : status.state(),
                placementLoader != null || buildCheck != null,
                () -> BuildAccessPreflight.forFloatingBuild(
                        client.player.getAbilities().flying, client.player.getAbilities().allowFlying),
                PlacementPlanLoadSession::selectionProblem,
                lastRuntimeError,
                status == null ? "" : status.lastError()));
        List<String> blockers = availability.blockers();
        List<String> actions = availability.actions();
        String error = availability.error();
        return new AgentObservation(
                runId, stateName(), Instant.now(), connected, contextMatches, controlTokenConfigured,
                blockers, actions, plan == null ? null : plan.planId(),
                placementLoader == null ? null : placementLoader.progress(), status,
                error, lastMessage, connected ? baritoneStatus() : "Disconnected",
                controlSequence, lastControlAction, lastControlRequestId)
                .withTelemetry(inventory.observation(), dirtShop.observation(), inventory.playerObservation())
                .withDepots(depots.observation())
                .withExecution(execution == null ? null : execution.observation())
                .withMossDeposit(mossDeposit.observation())
                .withMaterialShop(materialShop.observation())
                .withSurplusDisposal(surplusDisposal.observation())
                .withSoilWatchpoints(soilWatchpoints.observation())
                .withProgress(progressCache.revision(),
                        supervisor == null ? null : supervisor.lastConfirmedProgressAt())
                .withBuildCheck(buildCheckObservation());
    }

    private static String orElse(String detail, String fallback) {
        return detail == null || detail.isBlank() ? fallback : detail;
    }

    private void refreshIdleRunIdentity() {
        if (placementLoader != null || supervisor != null || dirtShop.active() || materialShop.active() || surplusUnsettled()) {
            return;
        }
        RunContext currentContext = null;
        Object selection = null;
        if (client.world != null && client.player != null) {
            try {
                currentContext = MinecraftRunContext.capture(client);
                selection = PlacementPlanLoadSession.selectionIdentity();
            } catch (RuntimeException exception) {
                // Unidentified worlds cannot retain the decision identity of a known world.
            }
        }
        if (!Objects.equals(observedIdleContext, currentContext)
                || !Objects.equals(observedIdleSelection, selection)) {
            observedIdleContext = currentContext;
            observedIdleSelection = selection;
            runId = UUID.randomUUID().toString();
        }
    }

    String statusText() {
        if (approachActive()) { return "APPROACHING - " + pausedApproach.detail(); }
        if (takeoff.active()) { return "TAKING_OFF - " + takeoff.snapshot().detail(); }
        if (dirtShop.active() || materialShop.active()) {
            return lastMessage;
        }
        if (!runtimeWorkAllowed()) {
            return "ERROR - " + runtimeWorkBlockDetail();
        }
        if (automationBlocked()) {
            return "ERROR - " + automationBlockDetail();
        }
        if (placementLoader != null) {
            int percent = (int) Math.floor(placementLoader.progress() * 100.0);
            return "LOADING " + percent + "% — " + placementLoader.placementName();
        }
        if (buildCheck != null) {
            int percent = (int) Math.floor(buildCheck.progress() * 100.0);
            return "CHECKING " + percent + "% — Checking the build before starting";
        }
        if (supervisor == null) {
            if (!lastRuntimeError.isBlank()) {
                return "ERROR — " + lastRuntimeError;
            }
            return "IDLE — " + lastMessage;
        }
        SupervisorStatus status = supervisor.status();
        String chunk = status.currentChunk() == null
                ? "-"
                : status.currentChunkOrdinal() + "/" + plan.chunkCount();
        LayerProgress layer = status.layerProgress();
        String progress = "chunk " + chunk;
        if (layer != null) {
            progress = layer.stage() + " layer step " + layer.ordinal() + "/" + layer.total()
                    + (layer.y() == null ? "" : " at Y=" + layer.y());
            if (layer.chunkTotal() > 0) {
                progress += " - chunk " + layer.chunkOrdinal() + "/" + layer.chunkTotal()
                        + " in layer";
            }
        }
        String currentError = lastRuntimeError.isBlank() ? status.lastError() : lastRuntimeError;
        String error = currentError.isBlank() ? "" : " - " + currentError;
        return status.state()
                + " - " + progress
                + (status.plantingDeferred() ? " - planting deferred" : "")
                + " - " + status.phase()
                + " - inventory " + status.inventory()
                + error;
    }

    String stateName() {
        if (!runtimeWorkAllowed()) {
            return "ERROR";
        }
        if (automationBlocked()) {
            return "ERROR";
        }
        if (placementLoader != null) {
            return "LOADING";
        }
        if (buildCheck != null) {
            return "CHECKING";
        }
        if (supervisor != null) {
            return supervisor.status().state().name();
        }
        return lastRuntimeError.isBlank() ? "IDLE" : "ERROR";
    }

    /** Depot scans, takeoff, and approaches also run while idle, paused, or stopped. */
    boolean ownsBackgroundWork() {
        return !closed && runtimeWorkAllowed() && !automationBlocked()
                && (takeoff.active() || approachActive() || depots.maintenanceActive());
    }

    @Override
    public void close() {
        requireClientThread();
        if (closed) {
            return;
        }
        closed = true;
        cancelApproach("Approach cancelled because the client is closing.");
        materialShop.cancel("Material shopping cancelled because the client is closing.");
        mossDeposit.cancel("Moss storage cancelled because the client is closing.");
        surplusDisposal.cancel("Surplus disposal cancelled because the client is closing.");
        cancelTakeoff("Takeoff cancelled because the client is closing; jump input released.");
        cancelDirtShopping("Dirt shopping cancelled because the client is closing.");
        placementLoader = null;
        cancelBuildCheck();
        if (supervisor != null) {
            try {
                supervisor.pause();
            } catch (RuntimeException exception) {
                logger.warn("Unable to checkpoint while closing the supervisor", exception);
            }
            try {
                supervisor.markClientStopping();
            } catch (RuntimeException exception) {
                logger.warn(
                        "Unable to persist the unsettled interaction shutdown marker",
                        exception
                );
            }
        }
        try {
            String maintenanceDetail = depots.cancelMaintenance();
            if (!maintenanceDetail.isBlank()) {
                logger.warn("Depot maintenance cleanup while closing: {}", maintenanceDetail);
            }
        } catch (RuntimeException exception) {
            logger.warn("Unable to cancel depot maintenance while closing", exception);
        }
        try {
            closePlanAdapters();
        } catch (RuntimeException exception) {
            lastRuntimeError = "Unable to close plan adapters safely: " + safeMessage(exception);
            logger.warn(lastRuntimeError, exception);
        }
        deactivateSafetyGuardWhenSafe();
        companion.close();
    }

    private void tickPlacementLoader() {
        PlacementPlanLoadSession loader = placementLoader;
        loader.tick(settings.placementBlocksPerTick());
        if (!loader.complete()) {
            return;
        }
            SchematicPlan loadedPlan = loader.result().withPlantingDeferred(settings.deferPlanting())
                    .withGlowstoneAfterStructure(settings.glowstoneAfterStructure());
        String purchaseProblem = materialShop.resumeProblem(loadedPlan.planId());
        if (!purchaseProblem.isBlank()) { throw new IllegalStateException(purchaseProblem); }
        String storageProblem = mossDeposit.resumeProblem(loadedPlan.planId());
        if (!storageProblem.isBlank()) { throw new IllegalStateException(storageProblem); }
        String disposalProblem = surplusDisposal.resumeProblem(loadedPlan.planId());
        if (!disposalProblem.isBlank()) { throw new IllegalStateException(disposalProblem); }
        try { TemporarySupportBinding.requirePlanContext(supportStore, loadedPlan, activeContext); }
        catch (IOException failure) { throw new UncheckedIOException(failure); }
        plan = loadedPlan;
        MinecraftMaterials.trackPlanBlocks(plan.plannedMaterials().asMap().keySet());
        placementLoader = null;
        bindRunContext();
        execution = new MinecraftExecutionPort(
                client,
                plan,
                settings.placementBlocksPerTick(),
                settings.interactionCooldownTicks(),
                settings.tillInteractionCooldownTicks(),
                settings.pathGoalRadius(),
                depots::totalScannedStock
        );
        execution.configureTemporarySupports(plan, activeContext, supportStore);
        execution.configureHoeRepair(settings.autoRepairHoes(), hoeRepairPath);
        execution.configureMossMiningGuard(mossTools, mossCustody);
        lightingSchedule = new io.github.schematicsupervisor.core.LayerBuildSchedule(plan);
        execution.configureLightingRestock(currentOrder -> {
            if (supervisor == null) { return 0; }
            var checkpoint = supervisor.checkpoint();
            if (!lightingSchedule.id().equals(checkpoint.scheduleId()) || checkpoint.repairChunkIndex() >= 0) { return 0; }
            return io.github.schematicsupervisor.core.LightingRestockReserve.additionalPlannedDemand(
                    lightingSchedule, checkpoint.scheduleCursor(), currentOrder, checkpoint.checkedPieces());
        }, () -> supervisor == null ? 0 : Math.max(0,
                plan.plannedMaterials().get(Material.GLOWSTONE)
                        - supervisor.checkpoint().consumedMaterials().get(Material.GLOWSTONE)
                        - inventory.snapshot().get(Material.GLOWSTONE)));
        depots.configureMossWearExecution(execution);
        verification = new MinecraftVerificationPort(
                client,
                settings.verificationBlocksPerTick(),
                execution::temporaryScaffolding,
                java.util.concurrent.ForkJoinPool.commonPool()
        );
        SupervisorPorts ports = new SupervisorPorts(
                execution,
                inventory,
                depots,
                verification,
                health,
                companion,
                checkpointStore,
                notifications,
                Clock.systemUTC()
        );
        supervisor = SchematicSupervisor.loadOrCreate(
                plan,
                settings.supervisorConfig(),
                ports
        );
        if (supervisor.requiresReconciliation()) {
            deactivateSafetyGuardWhenSafe();
            lastMessage = supervisor.status().lastError();
        } else if (supervisor.status().state() == SupervisorState.STOPPED && !mossDeposit.pending()
                && !materialShop.pending() && !surplusDisposal.pending()) {
            String startProblem = supervisor.startProblem();
            if (!startProblem.isBlank()) { throw new IllegalStateException(startProblem); }
            beginBuildCheck();
            lastMessage = "Placement loaded; checking the build before starting.";
        } else if (supervisor.status().state() == SupervisorState.PAUSED) {
            deactivateSafetyGuardWhenSafe();
            lastMessage = "Placement loaded with a paused checkpoint; use Resume.";
        } else if (supervisor.status().state() == SupervisorState.DONE) {
            deactivateSafetyGuardWhenSafe();
            lastMessage = "Placement loaded with a completed checkpoint."
                    + (plan.plantingDeferred() ? " Construction complete; planting deferred." : "");
        }
        logger.info(
                "Loaded plan {} with {} expected material units",
                plan.planId(),
                plan.plannedMaterials()
        );
    }

    /** Reads the received build without moving; the loaded, stopped supervisor starts afterwards. */
    private void beginBuildCheck() {
        if (plan == null || supervisor == null || lightingSchedule == null || client.world == null) {
            throw new IllegalStateException("The build check needs a loaded plan and a joined world");
        }
        BuildCheck.Supports supports = execution == null ? BuildCheck.Supports.NONE : new BuildCheck.Supports(
                execution.temporaryScaffolding(), execution.outstandingSupportSlice().orElse(null));
        buildCheckReader = new MinecraftBuildCheckReader(client.world);
        // The runtime's schedule is built from the same plan, so its pieces match the supervisor's.
        buildCheck = new BuildCheckSession(plan, lightingSchedule, BuildCheckSession.BUILDER_CLEARING, supports,
                java.util.concurrent.ForkJoinPool.commonPool(), System.nanoTime());
        buildCheckStartedAt = Instant.now();
        lastBuildCheck = null;
    }

    private void tickBuildCheck() {
        BuildCheckSession session = buildCheck;
        long now = System.nanoTime();
        if (client.world != buildCheckReader.world()) {
            // A replaced client world cannot be mixed into one snapshot; start the usual way instead.
            session.cancel();
            finishBuildCheck(session, now, "the client world changed during the check");
            return;
        }
        session.tick(buildCheckReader, settings.verificationBlocksPerTick(), System::nanoTime);
        BuildCheckSession.Status status = session.poll(now);
        if (status == BuildCheckSession.Status.COMPLETE || status == BuildCheckSession.Status.FAILED) {
            finishBuildCheck(session, now, session.failure());
        }
    }

    private void finishBuildCheck(BuildCheckSession session, long nowNanos, String failure) {
        buildCheck = null;
        buildCheckReader = null;
        if (supervisor == null) {
            deactivateSafetyGuardWhenSafe();
            return;
        }
        if (supervisor.status().state() != SupervisorState.STOPPED) {
            return;
        }
        if (mossDeposit.pending() || materialShop.pending() || surplusDisposal.pending()) {
            deactivateSafetyGuardWhenSafe();
            lastRuntimeError = "A pending inventory receipt prevents restarting the saved construction ledger.";
            lastMessage = lastRuntimeError;
            return;
        }
        BuildCheck.Result result = session.status() == BuildCheckSession.Status.COMPLETE ? session.result() : null;
        long elapsed = session.elapsedMillis(nowNanos);
        try {
            if (result != null) {
                supervisor.start(result);
            } else {
                logger.warn("Build check failed; starting from the first stage: {}", failure);
                supervisor.start();
            }
        } catch (RuntimeException exception) {
            deactivateSafetyGuardWhenSafe();
            runtimeFailure("Unable to start", exception);
            return;
        }
        lastBuildCheck = result != null
                ? BuildCheckObservation.complete(buildCheckStartedAt, Instant.now(), elapsed, result,
                        supervisor.status().layerProgress())
                : BuildCheckObservation.failed(buildCheckStartedAt, Instant.now(), elapsed,
                        failure == null || failure.isBlank() ? "unknown error" : failure);
        lastMessage = lastBuildCheck.summary();
        lastRuntimeError = "";
        notifications.info(lastMessage);
    }

    /** Discards an unfinished check; the supervisor stays stopped. */
    private boolean cancelBuildCheck() {
        if (buildCheck == null) {
            return false;
        }
        buildCheck.cancel();
        buildCheck = null;
        buildCheckReader = null;
        return true;
    }

    private BuildCheckObservation buildCheckObservation() {
        if (buildCheck != null) {
            return BuildCheckObservation.running(buildCheckStartedAt, buildCheck.progress(),
                    buildCheck.elapsedMillis(System.nanoTime()));
        }
        return lastBuildCheck;
    }

    private void publishStatusPeriodically(SupervisorStatus status) {
        statusPublicationTicks++;
        if (statusPublicationTicks < STATUS_PUBLICATION_INTERVAL_TICKS) {
            return;
        }
        statusPublicationTicks = 0;
        companion.publishStatus(status, baritoneStatus());
    }

    private String baritoneStatus() {
        String depotFlight = depots.flightStatus();
        if (!depotFlight.isBlank()) { return depotFlight; }
        String executionFlight = execution == null ? "" : execution.flightStatus();
        if (!executionFlight.isBlank()) { return executionFlight; }
        String verificationDetail = verification == null ? "" : verification.statusDetail();
        if (!verificationDetail.isBlank()) { return verificationDetail; }
        var baritone = BaritoneAPI.getProvider().getPrimaryBaritone();
        if (baritone.getBuilderProcess().isActive()) {
            return "Building deterministic ordinary-block order";
        }
        if (baritone.getPathingBehavior().isPathing()) {
            return "Following deterministic route";
        }
        return "Idle";
    }

    private boolean depotMaintenanceAllowed() {
        if (repairReceiptHeldScans || execution != null && !execution.hoeRepairBlockDetail().isBlank()
                || takeoff.active() || dirtShop.active() || placementLoader != null || buildCheck != null
                || mossDeposit.active() || !mossDeposit.stateProblem().isBlank()
                || surplusUnsettled()
                || materialShop.active() || !materialShop.stateProblem().isBlank()) {
            return false;
        }
        if (supervisor == null) {
            return true;
        }
        if (supervisor.requiresReconciliation()) { return false; }
        SupervisorState state = supervisor.status().state();
        return state == SupervisorState.STOPPED
                || state == SupervisorState.PAUSED
                || state == SupervisorState.DONE;
    }

    private boolean executionAutomationBlocked() {
        return execution != null && execution.automationBlocked();
    }

    private boolean shopPurchaseUncertain() {
        return !dirtShop.active() && dirtShop.snapshot().pendingStacks() > 0;
    }

    private String shopPurchaseUncertaintyDetail() {
        return "The last dirt purchase was not acknowledged. Waiting for its matching inventory "
                + "update; no further purchase will be sent. If it does not arrive, inspect the "
                + "inventory and restart the client before continuing.";
    }

    private boolean automationBlocked() {
        return depots.automationBlocked() || executionAutomationBlocked() || shopPurchaseUncertain()
                || mossDeposit.unavailable() || materialShop.unavailable() || surplusDisposal.unavailable();
    }

    private String automationBlockDetail() {
        if (surplusDisposal.unavailable()) { return surplusDisposal.stateProblem(); }
        if (materialShop.unavailable()) { return materialShop.stateProblem(); }
        if (mossDeposit.unavailable()) { return mossDeposit.stateProblem(); }
        String executionDetail = executionAutomationBlocked()
                ? execution.automationBlockDetail()
                : "";
        String depotDetail = depots.automationBlocked()
                ? depots.automationBlockDetail()
                : "";
        if (shopPurchaseUncertain()) {
            depotDetail = depotDetail.isBlank() ? shopPurchaseUncertaintyDetail()
                    : depotDetail + " " + shopPurchaseUncertaintyDetail();
        }
        if (executionDetail.isBlank()) {
            return depotDetail;
        }
        if (depotDetail.isBlank()) {
            return executionDetail;
        }
        return executionDetail + " " + depotDetail;
    }

    private boolean haltForAutomationBlock() {
        if (!automationBlocked()) {
            return false;
        }
        lastRuntimeError = automationBlockDetail();
        surplusDisposal.cancel("Surplus disposal cancelled because automation is blocked.");
        materialShop.cancel("Material shopping cancelled because automation is blocked.");
        cancelTakeoff("Takeoff cancelled because automation is blocked; jump input released.");
        cancelDirtShopping("Dirt shopping cancelled because automation is blocked.");
        if ((executionAutomationBlocked() || shopPurchaseUncertain() || mossDeposit.unavailable()
                || materialShop.unavailable() || surplusDisposal.unavailable())
                && supervisor != null) {
            SupervisorState state = supervisor.status().state();
            if (state != SupervisorState.STOPPED
                    && state != SupervisorState.PAUSED
                    && state != SupervisorState.DONE) {
                try {
                    supervisor.pause();
                } catch (RuntimeException exception) {
                    lastRuntimeError += " Fail-closed checkpoint persistence failed: "
                            + safeMessage(exception);
                    logger.error(
                            "Unable to persist the execution automation block",
                            exception
                    );
                }
            }
        }
        return true;
    }

    private void deactivateSafetyGuardWhenSafe() {
        if (!dirtShop.active() && !materialShop.active() && !surplusDisposal.active()
                && !shopPurchaseUncertain() && AutomationReleasePolicy.mayRestoreSafetySettings(
                depots.automationBlocked(),
                executionAutomationBlocked()
        )) {
            safetyGuard.deactivate();
        }
    }

    private void pauseForDisconnect() {
        dirtShop.clearMenuHistory();
        materialShop.cancel("Disconnected; material shopping cancelled.");
        mossDeposit.cancel("Disconnected; moss storage cancelled.");
        surplusDisposal.cancel("Disconnected; surplus disposal cancelled.");
        ServerInventorySnapshotObserver.invalidate();
        cancelTakeoff("Disconnected; takeoff cancelled and jump input released.");
        cancelDirtShopping("Disconnected; dirt shopping cancelled.");
        if (dirtShop.active()) {
            dirtShop.tick();
            updateDirtShoppingStatus();
        }
        if (placementLoader != null) {
            placementLoader = null;
            lastRuntimeError = "Disconnected while loading the selected placement.";
        }
        if (cancelBuildCheck()) {
            lastRuntimeError = "Disconnected during the build check; construction was not started.";
        }
        if (supervisor != null) {
            SupervisorState state = supervisor.status().state();
            if (state != SupervisorState.STOPPED
                    && state != SupervisorState.PAUSED
                    && state != SupervisorState.DONE) {
                try {
                    supervisor.pause();
                    lastRuntimeError = "Disconnected; supervision paused safely.";
                } catch (RuntimeException exception) {
                    lastRuntimeError = "Disconnected and pause failed: " + safeMessage(exception);
                }
            }
            try {
                supervisor.markClientStopping();
            } catch (RuntimeException exception) {
                lastRuntimeError = "Unable to checkpoint unresolved interactions: " + safeMessage(exception);
                logger.error(lastRuntimeError, exception);
            }
        }
        if (automationBlocked()) {
            lastRuntimeError = automationBlockDetail();
        }
        deactivateSafetyGuardWhenSafe();
    }

    private void pauseForContextMismatch() {
        dirtShop.clearMenuHistory();
        materialShop.cancel("World changed; material shopping cancelled.");
        mossDeposit.cancel("World changed; moss storage cancelled.");
        surplusDisposal.cancel("World changed; surplus disposal cancelled.");
        ServerInventorySnapshotObserver.invalidate();
        cancelTakeoff("World changed; takeoff cancelled and jump input released.");
        cancelDirtShopping("World changed; dirt shopping cancelled.");
        if (dirtShop.active()) {
            dirtShop.tick();
            updateDirtShoppingStatus();
        }
        placementLoader = null;
        cancelBuildCheck();
        String mismatch =
                "Server, save, or dimension changed; supervision paused before mutation.";
        boolean firstReport = !mismatch.equals(lastRuntimeError);
        lastRuntimeError = mismatch;
        if (supervisor != null) {
            SupervisorState state = supervisor.status().state();
            if (state != SupervisorState.STOPPED
                    && state != SupervisorState.PAUSED
                    && state != SupervisorState.DONE) {
                try {
                    supervisor.pause();
                } catch (RuntimeException exception) {
                    logger.error("Unable to pause after a run-context mismatch", exception);
                }
            }
            try {
                supervisor.markClientStopping();
            } catch (RuntimeException exception) {
                lastRuntimeError = "Unable to checkpoint unresolved interactions: " + safeMessage(exception);
                logger.error(lastRuntimeError, exception);
            }
        }
        if (automationBlocked()) {
            lastRuntimeError = automationBlockDetail();
        }
        deactivateSafetyGuardWhenSafe();
        if (firstReport) {
            notifications.alert(
                    lastRuntimeError,
                    io.github.schematicsupervisor.core.MaterialQuantities.empty()
            );
        }
    }

    private void failSafeOnTick(RuntimeException exception) {
        materialShop.cancel("Runtime failure; material shopping cancelled.");
        mossDeposit.cancel("Runtime failure; moss storage cancelled.");
        surplusDisposal.cancel("Runtime failure; surplus disposal cancelled.");
        cancelTakeoff("Runtime failure; takeoff cancelled and jump input released.");
        cancelDirtShopping("Runtime failure; dirt shopping cancelled.");
        logger.error("Supervisor tick failed", exception);
        lastRuntimeError = safeMessage(exception);
        placementLoader = null;
        cancelBuildCheck();
        if (supervisor != null) {
            try {
                supervisor.pause();
            } catch (RuntimeException pauseFailure) {
                exception.addSuppressed(pauseFailure);
            }
        } else {
            try {
                closePlanAdapters();
                plan = null;
            } catch (RuntimeException closeFailure) {
                exception.addSuppressed(closeFailure);
            }
        }
        if (automationBlocked()) {
            lastRuntimeError = automationBlockDetail();
        }
        deactivateSafetyGuardWhenSafe();
        notifications.alert(
                "Runtime failure; supervision paused: " + lastRuntimeError,
                io.github.schematicsupervisor.core.MaterialQuantities.empty()
        );
    }

    private void closePlanAdapters() {
        MinecraftMaterials.trackPlanBlocks(java.util.List.of());
        if (supervisor != null) {
            supervisor.markClientStopping();
        }
        RuntimeException failure = null;
        if (execution != null) {
            try {
                execution.close();
                execution = null;
                depots.configureMossWearExecution(null);
            } catch (RuntimeException exception) {
                failure = exception;
            }
        }
        if (verification != null) {
            try {
                verification.close();
                verification = null;
            } catch (RuntimeException exception) {
                if (failure == null) {
                    failure = exception;
                } else {
                    failure.addSuppressed(exception);
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    private void bindRunContext() {
        RunContext context = activeContext;
        if (context == null || !contextMatchesCurrentWorld()) {
            throw new IllegalStateException(
                    "server, save, or dimension changed while loading the placement"
            );
        }
        try {
            TemporarySupportBinding.requirePlanContext(supportStore,
                    Objects.requireNonNull(plan, "loaded plan"), context);
            if (!runtimeWorkAllowed()) {
                throw new IllegalStateException(runtimeWorkBlockDetail());
            }
            BuildStateRepository.Handle selected = buildStates.resolve(
                    Objects.requireNonNull(plan, "loaded plan").planId(), context);
            var savedContext = selected.contextStore().load();
            if (savedContext.isPresent() && !savedContext.orElseThrow().equals(context)) {
                throw new IOException("selected build context changed before binding");
            }
            if (savedContext.isEmpty()) {
                selected.contextStore().save(context);
            }
            ResetTeardownCoordinator selectedReset = new ResetTeardownCoordinator(
                    selected.checkpointStore()::clear, selected.contextStore()::clear);
            activeBuildState = selected;
            checkpointPath = selected.checkpointPath();
            checkpointStore = selected.checkpointStore();
            contextStore = selected.contextStore();
            // Method references retain this immutable handle even after Reset clears plan/context.
            resetTeardown = selectedReset;
        } catch (IOException exception) {
            throw new UncheckedIOException("unable to bind the checkpoint to this world", exception);
        }
    }

    private boolean runtimeWorkAllowed() {
        return resetTeardown.runtimeWorkAllowed() && !unloadTeardown.cleanupRequired();
    }

    private String temporarySupportStateProblem() {
        if (surplusDisposal.active()) { return "Surplus disposal must settle or be paused before reset or unload."; }
        String disposalProblem = surplusDisposal.stateProblem();
        if (!disposalProblem.isBlank()) { return disposalProblem; }
        if (execution != null && !execution.hoeRepairBlockDetail().isBlank()) {
            return execution.hoeRepairBlockDetail();
        }
        try {
            if (new HoeRepairFileStore(hoeRepairPath).load().filter(journal -> !journal.confirmed()).isPresent()) {
                return "A pending hoe repair must settle in its original world before reset or unload; do not repeat /fix.";
            }
        } catch (IOException | RuntimeException failure) {
            return "Hoe repair journal could not be read; preserve it before reset or unload.";
        }
        String purchaseProblem = materialShop.stateProblem();
        if (!purchaseProblem.isBlank()) { return purchaseProblem; }
        String storageProblem = mossDeposit.stateProblem();
        if (!storageProblem.isBlank()) { return storageProblem; }
        if (execution != null && !execution.temporarySupportBlockDetail().isBlank()) {
            return execution.temporarySupportBlockDetail();
        }
        try {
            return supportStore.load().filter(journal -> !journal.complete()).isPresent()
                    ? "The profile has unfinished temporary support ownership or planned credit. "
                            + "Restore and resume its original build before reset or unload."
                    : "";
        } catch (IOException failure) {
            return "Temporary support ownership could not be read: " + failure.getMessage();
        }
    }

    private String runtimeWorkBlockDetail() {
        return unloadTeardown.cleanupRequired() ? unloadTeardown.detail() : resetTeardown.detail();
    }

    private boolean contextMustMatch() {
        if (placementLoader != null || buildCheck != null) {
            return true;
        }
        if (supervisor == null) {
            return false;
        }
        SupervisorState state = supervisor.status().state();
        return state != SupervisorState.STOPPED && state != SupervisorState.DONE;
    }

    private boolean contextMatchesCurrentWorld() {
        if (activeContext == null || client.world == null) {
            return false;
        }
        try {
            return activeContext.equals(MinecraftRunContext.capture(client));
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private OperatorResult runtimeFailure(String prefix, RuntimeException exception) {
        String detail = prefix + ": " + safeMessage(exception);
        lastRuntimeError = detail;
        lastMessage = detail;
        logger.warn(detail, exception);
        return rejected(detail);
    }

    private OperatorResult rejectIncompleteReset(
            String prefix,
            RuntimeException exception
    ) {
        resetTeardown.recordIncomplete(prefix + ": " + safeMessage(exception));
        lastRuntimeError = resetTeardown.detail();
        lastMessage = lastRuntimeError;
        logger.warn(lastRuntimeError, exception);
        return rejected(lastRuntimeError);
    }

    private OperatorResult accepted(String message) {
        return new OperatorResult(true, message, stateName());
    }

    private OperatorResult rejected(String message) {
        return new OperatorResult(false, message, stateName());
    }

    private void requireClientThread() {
        if (!client.isOnThread()) {
            throw new IllegalStateException("runtime operation must run on the client thread");
        }
    }

    private static String safeMessage(RuntimeException exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank()
                ? exception.getClass().getSimpleName()
                : message;
    }

    record OperatorResult(boolean accepted, String message, String state) {
        OperatorResult {
            message = message == null ? "" : message;
            state = state == null ? "ERROR" : state.toUpperCase(Locale.ROOT);
        }
    }
}
