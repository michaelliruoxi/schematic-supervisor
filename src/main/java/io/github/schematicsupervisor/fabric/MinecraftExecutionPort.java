package io.github.schematicsupervisor.fabric;

import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.Settings;
import baritone.api.pathing.goals.GoalNear;
import baritone.api.utils.input.Input;
import io.github.schematicsupervisor.core.BlockPosition;
import io.github.schematicsupervisor.core.BlockObservation;
import io.github.schematicsupervisor.core.ChunkCoordinate;
import io.github.schematicsupervisor.core.VerificationScope;
import io.github.schematicsupervisor.core.PlannedConsumptionCredit;
import io.github.schematicsupervisor.core.BuildVolume;
import io.github.schematicsupervisor.core.ExecutionSnapshot;
import io.github.schematicsupervisor.core.ExecutionSettlementSnapshot;
import io.github.schematicsupervisor.core.ExecutionStatus;
import io.github.schematicsupervisor.core.FlightRestoreSnapshot;
import io.github.schematicsupervisor.core.Material;
import io.github.schematicsupervisor.core.MaterialQuantities;
import io.github.schematicsupervisor.core.OrdinaryPlacement;
import io.github.schematicsupervisor.core.SafeReturnSnapshot;
import io.github.schematicsupervisor.core.SafeReturnStatus;
import io.github.schematicsupervisor.core.SchematicPlan;
import io.github.schematicsupervisor.core.SupervisorPorts;
import io.github.schematicsupervisor.core.WorkOrder;
import io.github.schematicsupervisor.fabric.mixin.ClientWorldPendingUpdatesAccessor;
import io.github.schematicsupervisor.fabric.mixin.ClientPlayerInteractionManagerAccessor;
import io.github.schematicsupervisor.fabric.mixin.PendingBlockUpdatesAccessor;
import java.time.Instant;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.HashMap;
import java.util.Optional;
import java.util.BitSet;
import java.util.Collection;
import java.util.TreeMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.Set;
import java.util.function.Predicate;
import java.util.function.Supplier;
import net.minecraft.block.Blocks;
import net.minecraft.block.Block;
import net.minecraft.block.FarmlandBlock;
import net.minecraft.block.ShapeContext;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.network.ClientPlayerInteractionManager;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.item.Items;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.registry.Registries;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

/**
 * Client-thread executor for deterministic work orders. All state transitions are confirmed from
 * the client world before progress or consumption is reported.
 */
final class MinecraftExecutionPort implements SupervisorPorts.Execution, AutoCloseable {
    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger("schematic-supervisor");
    private static final int PATH_START_GRACE_TICKS = 5;
    private static final int BUILDER_SETTLE_TICKS = 10;
    private static final int MAX_INTERACTION_ATTEMPTS = 3;
    private static final int MINIMUM_INTERACTION_CONFIRM_TICKS = 20;
    private static final int TILL_SELECTION_BUDGET = 256;
    private static final int MAX_TICKS_WITHOUT_CONFIRMED_BLOCK_PROGRESS = 20 * 60;
    private static final int MAX_MOSS_CLEARING_TICKS = 20 * 5;
    private static final int MAX_SUPPORT_MINING_TICKS = 20 * 5;
    private static final int SUPPORT_ACKNOWLEDGEMENT_TICKS = 20 * 5;
    private static final SafeReturnSnapshot SAFE_RETURN_IDLE = new SafeReturnSnapshot(
            SafeReturnStatus.IDLE,
            ""
    );

    private final MinecraftClient client;
    private final MinecraftFlightNavigation flightNavigation;
    private final BuildVolume buildVolume;
    private final int scanBudgetPerTick;
    private final int interactionCooldownTicks;
    private final InteractionDispatchCadence tillCadence;
    private final InteractionDispatchCadence plantCadence;
    private final InteractionDispatchCadence ordinaryCadence;
    private final int pathGoalRadius;
    private final Supplier<MaterialQuantities> depotAvailability;
    private final Set<BlockPosition> tillPrerequisites;
    private final ConfirmedBlockProgressGuard confirmedBlockProgress =
            new ConfirmedBlockProgressGuard(MAX_TICKS_WITHOUT_CONFIRMED_BLOCK_PROGRESS);
    private final ConfirmedBlockProgressGuard flightApproachProgress =
            new ConfirmedBlockProgressGuard(MinecraftFlightNavigation.MAXIMUM_ACTIVE_TICKS);
    private final BaritoneCancellationGuard cancellationGuard;
    // Recovery's bounded takeoff; it only re-activates flight the server still allows.
    private final FlightTakeoffSession flightRestore;
    private final TreeMap<Material, Long> pendingConsumed = new TreeMap<>();
    private final IdentityHashMap<net.minecraft.block.BlockState,
            io.github.schematicsupervisor.core.BlockState> convertedStates =
            new IdentityHashMap<>();

    private WorkOrder order;
    private ExecutionMode mode = ExecutionMode.IDLE;
    private long progressMarker;
    private String detail = "";
    private MaterialQuantities requestedAvailable = MaterialQuantities.empty();

    private List<OrdinaryPlacement> ordinaryPlacements = List.of();
    private List<BlockPosition> manualTargets = List.of();
    private TillTargetSelector tillSelector;
    private PlantTargetSelector plantSelector;
    private BitSet correctTargets = new BitSet();
    private int preparationCursor;
    private int monitorCursor;
    private int completionCursor;
    private boolean completionAllCorrect;
    private int builderIdleTicks;

    private int manualTargetIndex;
    private int interactionWaitTicks;
    private int interactionAttempts;
    private int cooldownTicks;
    private int pathStartGraceTicks;
    private GoalNear activeGoal;
    private boolean confirmedBlockProgressThisTick;

    private BlockPos lastSafePosition;
    private BlockPos lastObservedPlayerPosition;
    private GoalNear safeReturnGoal;
    private SafeReturnSnapshot safeReturnSnapshot = SAFE_RETURN_IDLE;
    private int safeReturnGraceTicks;
    private BaritoneSettingLease baritoneSettings;
    private boolean controlReleased = true;
    private boolean flightExecution;
    private boolean flightSafeReturn;
    private int flightTargetIndex = -1;
    private int flightScanCursor;
    private double flightBestDistance;
    private boolean flightBestCurrentPosition;
    private FlightPlacementFace flightFace;
    private BlockPosition unreceivedAnchor;
    private final Set<FlightPlacementFace> unavailableFlightFaces = new HashSet<>();
    private int flightValidAnchorFaces;
    private int flightCachedAnchorFaces;
    private int flightInvalidAnchors;
    private int flightUnreceivedAnchors;
    private String lastFlightFaceRejection = "";
    private InteractionReceipt interactionReceipt;
    private FlightInteractionConfirmation.Result receiptResult;
    private String receiptFailure = "";
    private String interactionRayDetail = "";
    private ExecutionObservation.Receipt lastReceiptObservation;
    private ExecutionObservation.Receipt lastFailureReceiptObservation;
    private final MossToolSelection.History mossToolSelectionHistory = new MossToolSelection.History();
    private final MossToolReceiptHistory<ItemStack> mossMetadataHistory = new MossToolReceiptHistory<>(
            ItemStack::copy, MinecraftExecutionPort::sameMossOtherComponents,
            stack -> stack.contains(DataComponentTypes.DAMAGE) ? stack.getDamage() : null);
    private final ExecutionObstructionHistory obstructionHistory = new ExecutionObstructionHistory();
    private BlockPosition chunkApproachTarget;
    private int chunkApproachTicksRemaining;
    private long observedNavigationProgress;
    private OwnedBlockBreaking.Lease miningLease;
    private ClientPlayerInteractionManager miningManager;
    private BlockPos ownedMiningTarget;
    private PlainInteractionItems.MossPickupReselection mossPickupReselection;
    private PlannedClearingTarget clearingTarget;
    private int clearingHandSlot = -1;
    private MossMiningToolGuard<ItemStack> mossTools =
            new MossMiningToolGuard<>(ItemStack::areItemsAndComponentsEqual);
    private MossToolCustody mossCustody;
    private MossWearOpening mossWearOpening;
    private ServerInventorySnapshotStamp lastMossWearOpening;
    private MossMiningToolGuard.OwnedTool<ItemStack> ownedMossHoe;
    private MossMiningToolGuard.TrackedTool<ItemStack> preparedMossHoe;
    private MossHoeRepair pendingMossHoeRepair;
    private int mossCandidateIndex = -1;
    private double mossCandidateDistance = Double.POSITIVE_INFINITY;
    private TemporarySupportBinding supports;
    private TemporarySupportController.Candidate supportCandidate;
    private TemporarySupportController.Operation supportOperation;
    private boolean waitingForScreen;
    private MinecraftHoeRepair hoeRepair;
    private SchematicPlan stemPlan;
    private StemClearingSweep stemSweep;
    private int stemSweepCursor;
    private int stemPredictionWaitTicks;
    private StemClearingSweep.Target stemTarget;
    private int stemHandSlot = -1;
    private ItemStack stemHandIdentity = ItemStack.EMPTY;
    private java.util.function.ToLongFunction<WorkOrder> upcomingLightingDemand = ignored -> 0;
    private java.util.function.LongSupplier remainingGlowstoneBudget = () -> Long.MAX_VALUE;

    void configureLightingRestock(java.util.function.ToLongFunction<WorkOrder> upcoming,
                                  java.util.function.LongSupplier remainingBudget) {
        upcomingLightingDemand = Objects.requireNonNull(upcoming);
        remainingGlowstoneBudget = Objects.requireNonNull(remainingBudget);
    }

    MinecraftExecutionPort(
            MinecraftClient client,
            BuildVolume buildVolume,
            int scanBudgetPerTick,
            int interactionCooldownTicks,
            int pathGoalRadius
    ) {
        this(
                client,
                buildVolume,
                scanBudgetPerTick,
                interactionCooldownTicks,
                pathGoalRadius,
                MaterialQuantities::empty,
                Set.of()
        );
    }

    MinecraftExecutionPort(
            MinecraftClient client,
            BuildVolume buildVolume,
            int scanBudgetPerTick,
            int interactionCooldownTicks,
            int pathGoalRadius,
            Supplier<MaterialQuantities> depotAvailability
    ) {
        this(
                client,
                buildVolume,
                scanBudgetPerTick,
                interactionCooldownTicks,
                pathGoalRadius,
                depotAvailability,
                Set.of()
        );
    }

    MinecraftExecutionPort(
            MinecraftClient client,
            SchematicPlan plan,
            int scanBudgetPerTick,
            int interactionCooldownTicks,
            int pathGoalRadius,
            Supplier<MaterialQuantities> depotAvailability
    ) {
        this(client, plan, scanBudgetPerTick, interactionCooldownTicks,
                interactionCooldownTicks, pathGoalRadius, depotAvailability);
    }

    MinecraftExecutionPort(
            MinecraftClient client,
            SchematicPlan plan,
            int scanBudgetPerTick,
            int interactionCooldownTicks,
            int tillInteractionCooldownTicks,
            int pathGoalRadius,
            Supplier<MaterialQuantities> depotAvailability
    ) {
        this(
                client,
                Objects.requireNonNull(plan, "plan").buildVolume(),
                scanBudgetPerTick,
                interactionCooldownTicks,
                pathGoalRadius,
                depotAvailability,
                tillPrerequisites(plan),
                tillInteractionCooldownTicks
        );
        stemPlan = plan;
    }

    private MinecraftExecutionPort(
            MinecraftClient client,
            BuildVolume buildVolume,
            int scanBudgetPerTick,
            int interactionCooldownTicks,
            int pathGoalRadius,
            Supplier<MaterialQuantities> depotAvailability,
            Set<BlockPosition> tillPrerequisites
    ) {
        this(client, buildVolume, scanBudgetPerTick, interactionCooldownTicks,
                pathGoalRadius, depotAvailability, tillPrerequisites, interactionCooldownTicks);
    }

    private MinecraftExecutionPort(
            MinecraftClient client,
            BuildVolume buildVolume,
            int scanBudgetPerTick,
            int interactionCooldownTicks,
            int pathGoalRadius,
            Supplier<MaterialQuantities> depotAvailability,
            Set<BlockPosition> tillPrerequisites,
            int tillInteractionCooldownTicks
    ) {
        this.client = Objects.requireNonNull(client, "client");
        this.flightNavigation = new MinecraftFlightNavigation(client);
        this.buildVolume = Objects.requireNonNull(buildVolume, "buildVolume");
        if (scanBudgetPerTick < 1) {
            throw new IllegalArgumentException("scanBudgetPerTick must be positive");
        }
        if (interactionCooldownTicks < 1) {
            throw new IllegalArgumentException("interactionCooldownTicks must be positive");
        }
        if (pathGoalRadius < 1) {
            throw new IllegalArgumentException("pathGoalRadius must be positive");
        }
        this.scanBudgetPerTick = scanBudgetPerTick;
        this.interactionCooldownTicks = interactionCooldownTicks;
        this.tillCadence = new InteractionDispatchCadence(tillInteractionCooldownTicks);
        this.plantCadence = new InteractionDispatchCadence(interactionCooldownTicks);
        this.ordinaryCadence = new InteractionDispatchCadence(interactionCooldownTicks);
        this.pathGoalRadius = pathGoalRadius;
        this.depotAvailability = Objects.requireNonNull(
                depotAvailability,
                "depotAvailability"
        );
        this.tillPrerequisites = Set.copyOf(
                Objects.requireNonNull(tillPrerequisites, "tillPrerequisites")
        );
        cancellationGuard = new BaritoneCancellationGuard(
                this::prepareBaritoneCancellation,
                () -> baritone().getPathingBehavior().cancelEverything(),
                () -> baritone().getPathingBehavior().forceCancel(),
                () -> baritone().getBuilderProcess().onLostControl(),
                () -> baritone().getCustomGoalProcess().onLostControl(),
                this::restoreBaritoneSettings
        );
        flightRestore = new FlightTakeoffSession(new MinecraftFlightTakeoffPort(client, this::flightRestoreProblem));
    }

    @Override
    public void start(WorkOrder newOrder) {
        Objects.requireNonNull(newOrder, "order");
        if (supports != null) { supports.requireOrder(newOrder); }
        if (interactionReceipt != null || !receiptFailure.isEmpty()) {
            throw new IllegalStateException("An unsettled interaction must be reconciled before restarting execution");
        }
        requireAutomationAvailable("start execution");
        if (!releaseAutomationControl(
                "could not fully release prior Baritone execution before starting",
                true
        )) {
            throw automationBlockedException();
        }
        resetOrderState();
        order = newOrder;
        ClientPlayerEntity player = client.player;
        if (client.world == null || player == null || client.interactionManager == null) {
            fail("Client world, player, or interaction manager is unavailable");
            return;
        }
        flightExecution |= player.getAbilities().flying;
        if (flightExecution && !flightAvailable()) {
            fail("Active flight was lost; the floating build cannot fall back to ground movement");
            return;
        }
        lastSafePosition = player.getBlockPos().toImmutable();
        lastObservedPlayerPosition = lastSafePosition;
        switch (newOrder) {
            case WorkOrder.OrdinaryBlocks ordinary -> {
                ordinaryPlacements = ordinary.placements();
                correctTargets = new BitSet(ordinaryPlacements.size());
            }
            case WorkOrder.Till till -> {
                manualTargets = TopDownTargetOrder.copyOf(till.targets());
                correctTargets = new BitSet(manualTargets.size());
                tillSelector = new TillTargetSelector(manualTargets);
            }
            case WorkOrder.Plant plant -> {
                manualTargets = TopDownTargetOrder.copyOf(plant.targets());
                correctTargets = new BitSet(manualTargets.size());
                plantSelector = new PlantTargetSelector(manualTargets);
            }
        }
        stemSweep = stemPlan == null ? null : new StemClearingSweep(stemPlan, newOrder);
        mode = stemSweep == null ? ExecutionMode.PREPARING : ExecutionMode.STEM_SWEEP_SCAN;
    }

    /**
     * Advances only bounded scans and one interaction state transition per client tick.
     */
    void tick() {
        waitingForScreen = false;
        tillCadence.tick();
        plantCadence.tick();
        ordinaryCadence.tick();
        settleInteractionReceipt(true);
        if (hoeRepair != null) { settleMossHoeRepairAllowance(hoeRepair.settle(false)); }
        if (cancellationGuard.blocked()) {
            return;
        }
        if (flightRestore.active()) {
            // Before the movement-input check: the takeoff holds Jump itself, and the order has failed.
            flightRestore.tick();
            return;
        }
        if (safeReturnSnapshot.status() == SafeReturnStatus.RUNNING) {
            tickSafeReturn();
            return;
        }
        if (!mode.ticksWork()) {
            return;
        }
        if (client.world == null || client.player == null || client.interactionManager == null) {
            fail("Client world, player, or interaction manager became unavailable");
            return;
        }
        try {
            if (hoeRepair != null && hoeRepair.pending()) {
                HoeRepairSession.Status repair = hoeRepair.settle(true);
                detail = hoeRepair.detail();
                if (repair != HoeRepairSession.Status.READY) { return; }
                settleMossHoeRepairAllowance(repair);
            }
            if (!receiptFailure.isEmpty()) {
                fail(receiptFailure);
                return;
            }
            InteractionTickDispatch.Kind interactionKind = mode == ExecutionMode.SUPPORT_MINING
                    ? InteractionTickDispatch.Kind.SUPPORT_MINING
                    : mode == ExecutionMode.FLIGHT_CLEARING_MOSS || mode == ExecutionMode.FLIGHT_CLEARING_LIGHT
                            || mode == ExecutionMode.STEM_SWEEP_MINING
                            ? InteractionTickDispatch.Kind.MOSS_MINING : InteractionTickDispatch.Kind.CLICK;
            boolean screenBlocksActions = !MinecraftBackgroundBuildAccess.allowsWorldActions(client);
            waitingForScreen = screenBlocksActions;
            InteractionTickDispatch.Next dispatch = InteractionTickDispatch.next(
                    interactionReceipt != null, interactionKind, screenBlocksActions);
            var options = client.options;
            switch (ExecutionTickGate.decide(new ExecutionTickGate.Facts(dispatch, screenBlocksActions,
                    flightExecution, flightAvailable(), miningLease != null,
                    options.attackKey.isPressed() || options.useKey.isPressed(),
                    options.forwardKey.isPressed() || options.backKey.isPressed() || options.leftKey.isPressed()
                            || options.rightKey.isPressed() || options.jumpKey.isPressed()
                            || options.sneakKey.isPressed(),
                    mode))) {
                case WAIT_RECEIPT -> {
                    return;
                }
                case FAIL_FLIGHT_LOST -> {
                    fail(ExecutionTickGate.FLIGHT_LOST);
                    return;
                }
                case WAIT_SCREEN -> {
                    if (miningLease != null) {
                        cancelOwnedMining();
                        mode = dispatch == InteractionTickDispatch.Next.SETTLE_SUPPORT
                                ? ExecutionMode.SUPPORT_CONFIRMATION : mode == ExecutionMode.STEM_SWEEP_MINING
                                        ? ExecutionMode.STEM_SWEEP_CONFIRMATION : ExecutionMode.FLIGHT_CLEARING_CONFIRMATION;
                    }
                    flightNavigation.tick();
                    if (flightNavigation.failed()) {
                        fail(flightNavigation.detail());
                        return;
                    }
                    detail = "Waiting for the inventory cursor or container to clear before building.";
                    return;
                }
                case FAIL_MANUAL_INTERACTION -> {
                    fail(ExecutionTickGate.MANUAL_INTERACTION);
                    return;
                }
                case FAIL_MANUAL_MOVEMENT -> {
                    fail(ExecutionTickGate.MANUAL_MOVEMENT);
                    return;
                }
                case ADVANCE -> {
                    // Every check passed; the mode below does this tick's work.
                }
            }
            confirmedBlockProgressThisTick = false;
            boolean activeFlightApproach = flightExecution && mode.requiresFlightApproachProgress();
            observePlayerProgress();
            switch (mode) {
                case PREPARING -> tickPreparation();
                case STEM_SWEEP_SCAN -> tickStemSweep();
                case STEM_SWEEP_NAVIGATION -> tickStemNavigation();
                case STEM_SWEEP_MINING -> tickStemMining();
                case STEM_SWEEP_CONFIRMATION -> tickStemConfirmation();
                case FLIGHT_CHUNK_APPROACH -> tickChunkApproach();
                case ORDINARY_RUNNING -> tickOrdinaryRunning();
                case ORDINARY_COMPLETION_SCAN -> tickOrdinaryCompletionScan();
                case FLIGHT_ORDINARY_SELECTING -> tickFlightOrdinarySelection();
                case FLIGHT_ORDINARY_NAVIGATING -> tickFlightOrdinaryNavigation();
                case FLIGHT_ORDINARY_READY -> tickFlightOrdinaryReady();
                case FLIGHT_ORDINARY_WAITING_CONFIRMATION -> tickFlightOrdinaryConfirmation();
                case FLIGHT_CLEARING_NAVIGATION -> tickMossClearingNavigation();
                case FLIGHT_CLEARING_MOSS, FLIGHT_CLEARING_LIGHT -> tickMossClearing();
                case FLIGHT_CLEARING_CONFIRMATION -> tickMossClearingConfirmation();
                case SUPPORT_PREVIEW -> tickSupportPreview();
                case SUPPORT_NAVIGATING -> tickSupportNavigation();
                case SUPPORT_READY -> tickSupportReady();
                case SUPPORT_MINING -> tickSupportMining();
                case SUPPORT_CONFIRMATION -> tickSupportConfirmation();
                case TILL_SELECTING -> tickTillSelection();
                case PLANT_SELECTING -> tickPlantSelection();
                case MANUAL_NAVIGATING -> tickManualNavigation();
                case MANUAL_READY -> tickManualReady();
                case MANUAL_WAITING_CONFIRMATION -> tickManualConfirmation();
                case WAITING_MATERIALS -> tickWaitingForMaterials();
                case IDLE, SUSPENDED, SUCCEEDED, FAILED, NEEDS_PREREQUISITES, AUTOMATION_BLOCKED -> {
                    // Guarded above.
                }
            }
            // Repair waiting is neither a block interaction nor new block progress. Its separate
            // finite deadline runs above, so it does not consume the remaining block-progress budget.
            if (hoeRepair != null && hoeRepair.pending()) { return; }
            long navigationProgress = flightNavigation.progressMarker();
            if (navigationProgress > observedNavigationProgress) {
                progressMarker++;
                observedNavigationProgress = navigationProgress;
            }
            boolean hardProgressDeadlineExpired = confirmedBlockProgressThisTick
                    ? confirmedBlockProgress.tick(true)
                    : mode.requiresConfirmedBlockProgress(flightExecution)
                    && confirmedBlockProgress.tick(false);
            if (hardProgressDeadlineExpired) {
                fail(
                        "No confirmed block-state progress for "
                                + MAX_TICKS_WITHOUT_CONFIRMED_BLOCK_PROGRESS
                                + " client ticks"
                );
                return;
            }
            // Charge the entry phase, including a received-chunk transition that immediately
            // switches to rescanning. Replanning and chunk receipt cannot renew this deadline.
            boolean approachDeadlineExpired = confirmedBlockProgressThisTick
                    ? flightApproachProgress.tick(true)
                    : activeFlightApproach && flightApproachProgress.tick(false);
            if (approachDeadlineExpired && !mode.terminal()) {
                fail("Flight approach made no confirmed block-state progress for "
                        + MinecraftFlightNavigation.MAXIMUM_ACTIVE_TICKS
                        + " active client ticks across route planning and rescans");
            }
        } catch (HotbarTransferDeferred deferred) {
            waitingForScreen = true;
            flightNavigation.waitForScreen();
            detail = deferred.getMessage();
        } catch (RuntimeException exception) {
            fail(errorDetail(exception));
        }
    }

    @Override
    public ExecutionSnapshot poll() {
        requireAutomationAvailable("poll execution");
        if (mode == ExecutionMode.WAITING_MATERIALS && hasRequestedMaterials()) {
            try {
                resumeAfterMaterials();
            } catch (RuntimeException exception) {
                fail(errorDetail(exception));
            }
        }
        MaterialQuantities consumed = MaterialQuantities.of(pendingConsumed);
        pendingConsumed.clear();
        if (hoeRepair != null && hoeRepair.pending()) {
            return new ExecutionSnapshot(ExecutionStatus.WAITING_FOR_MAINTENANCE, progressMarker,
                    MaterialQuantities.empty(), consumed, hoeRepair.detail());
        }
        if (mode == ExecutionMode.IDLE) { return ExecutionSnapshot.idle(); }
        if (mode == ExecutionMode.AUTOMATION_BLOCKED) { throw automationBlockedException(); }
        ExecutionStatus status = mode.reportedStatus(waitingForScreen);
        if (status == ExecutionStatus.SUCCEEDED) { return ExecutionSnapshot.succeeded(progressMarker, consumed); }
        return new ExecutionSnapshot(status, progressMarker,
                status == ExecutionStatus.NEEDS_MATERIALS ? requestedAvailable : MaterialQuantities.empty(),
                consumed, detail);
    }

    @Override
    public ExecutionSettlementSnapshot pollSettlement() {
        settleInteractionReceipt(false);
        if (hoeRepair != null) { settleMossHoeRepairAllowance(hoeRepair.settle(false)); }
        MaterialQuantities consumed = MaterialQuantities.of(pendingConsumed);
        pendingConsumed.clear();
        return new ExecutionSettlementSnapshot(consumed, interactionReceipt != null, receiptFailure);
    }

    void configureTemporarySupports(SchematicPlan plan, RunContext context, TemporarySupportStore store) {
        if (supports != null || order != null) { throw new IllegalStateException("Support binding is already configured"); }
        try { supports = new TemporarySupportBinding(plan, context, store); }
        catch (IOException failure) { throw new UncheckedIOException(failure); }
    }

    void configureHoeRepair(boolean enabled, java.nio.file.Path journalPath) {
        if (hoeRepair != null || order != null) { throw new IllegalStateException("Hoe repair is already configured"); }
        hoeRepair = new MinecraftHoeRepair(client, enabled, journalPath);
    }

    void configureMossMiningGuard(MossMiningToolGuard<ItemStack> sharedGuard, MossToolCustody custody) {
        if (order != null || mossTools.trackedIdentityCount() != 0) {
            throw new IllegalStateException("Moss wear ownership must be bound before execution");
        }
        mossTools = Objects.requireNonNull(sharedGuard, "sharedGuard");
        mossCustody = Objects.requireNonNull(custody, "custody");
    }

    /** The depot calls this immediately before its existing owned chest interaction. */
    void beforeMossWearDepotOpen() {
        mossWearOpening = null;
        try {
            captureMossWearOpening();
        } catch (RuntimeException unavailable) {
            mossTools.invalidateCustody();
        }
    }

    void discardMossWearDepotOpen() { mossWearOpening = null; }

    private void captureMossWearOpening() {
        if (!mossWearSettled() || client.player.currentScreenHandler != client.player.playerScreenHandler) { return; }
        mossCustody.stableMainCohort().flatMap(mossTools::beforeOwnedOpen).ifPresent(ticket ->
                mossWearOpening = new MossWearOpening(client.world, client.player, client.getNetworkHandler(), ticket));
    }

    /** Accepts the initial depot gate or a durably confirmed pickup-deposit reopen, before any next transfer. */
    void afterMossWearDepotReceipt(net.minecraft.screen.GenericContainerScreenHandler handler,
                                 ServerInventorySnapshotObserver.FullSnapshot packet) {
        try {
            acceptMossWearDepotReceipt(handler, packet);
        } catch (RuntimeException unavailable) {
            mossTools.invalidateCustody();
        } finally {
            mossWearOpening = null;
        }
    }

    private void acceptMossWearDepotReceipt(net.minecraft.screen.GenericContainerScreenHandler handler,
                                           ServerInventorySnapshotObserver.FullSnapshot packet) {
        MossWearOpening opening = mossWearOpening;
        mossWearOpening = null;
        if (mossCustody == null || packet == null) { return; }
        if (!mossCustody.observeOwnedFullReceipt(handler, packet)) { return; }
        if (opening == null || !mossWearSettled() || opening.world() != client.world
                || opening.player() != client.player || opening.connection() != client.getNetworkHandler()
                || client.player.currentScreenHandler != handler || packet.stamp().syncId() != handler.syncId
                || packet.rows() != handler.getRows() || !packet.cursorStack().isEmpty()
                || !handler.getCursorStack().isEmpty()
                || lastMossWearOpening != null && !packet.stamp().isLaterReopenThan(lastMossWearOpening)) { return; }
        List<ItemStack> slots = packet.slots();
        int chestSize = handler.getRows() * 9;
        if (slots.size() != chestSize + 36 || handler.slots.size() != slots.size()) { return; }
        for (int index = 0; index < slots.size(); index++) {
            if (!ItemStack.areEqual(slots.get(index), handler.slots.get(index).getStack())) { return; }
        }
        for (int index = 0; index < 36; index++) {
            var slot = handler.slots.get(MossDepositFacts.mainHandlerSlot(chestSize, index));
            if (slot.inventory != client.player.getInventory() || slot.getIndex() != index) { return; }
        }
        lastMossWearOpening = packet.stamp();
        mossCustody.stableMainCohort().ifPresent(cohort -> {
            if (mossTools.refreshFromReceipt(opening.ticket(), cohort)) {
                List<String> tools = cohort.stream().map(member -> "slot=" + member.slot()
                        + ",damage=" + member.durability().damage() + ",allowance="
                        + mossTools.diagnostic(member.identity(), member.durability()).allowance()).toList();
                LOGGER.info("Moss wear baseline refreshed from owned depot: epoch={}, opening={}, sequence={}, tools={}",
                        packet.stamp().observerEpoch(), packet.stamp().openGeneration(), packet.stamp().fullSequence(), tools);
            }
        });
    }

    private boolean mossWearSettled() {
        if (mossCustody == null || client.world == null || client.player == null || client.getNetworkHandler() == null
                || client.interactionManager == null || !mossCustody.allowsHoeStart()
                || mossCustody.inventoryMovementPending() || interactionReceipt != null || !receiptFailure.isEmpty()
                || miningLease != null || ownedMossHoe != null || client.interactionManager.isBreakingBlock()
                || pendingMossHoeRepair != null || hoeRepair != null && hoeRepair.pending()) { return false; }
        try {
            return ((PendingBlockUpdatesAccessor) ((ClientWorldPendingUpdatesAccessor) client.world)
                    .supervisor$getPendingUpdateManager()).supervisor$getPendingBlockUpdates().isEmpty();
        } catch (RuntimeException unavailable) { return false; }
    }

    private record MossWearOpening(Object world, Object player, Object connection,
                                   MossMiningToolGuard.RefreshTicket<ItemStack> ticket) { }

    String hoeRepairBlockDetail() {
        if (hoeRepair == null) { return ""; }
        String failure = hoeRepair.blockDetail();
        return !failure.isBlank() ? failure : hoeRepair.pending() ? hoeRepair.detail() : "";
    }

    @Override
    public Optional<PlannedConsumptionCredit> pendingPlannedCredit() {
        return supports == null || supports.controller == null ? Optional.empty()
                : supports.controller.pendingPlannedCredit();
    }

    @Override
    public void acknowledgePlannedCredit(String id) {
        if (supports == null || supports.controller == null) {
            throw new IllegalStateException("No support controller owns the planned credit");
        }
        try { supports.controller.acknowledgePlannedCredit(id); }
        catch (IOException failure) { throw new UncheckedIOException(failure); }
    }

    String temporarySupportBlockDetail() {
        return supports != null && supports.outstanding()
                ? "Temporary support ownership or its planned credit is unfinished; resume the original layer "
                        + "to settle it before reset, unload, or another build" : "";
    }

    @Override
    public void stopMovement() {
        if (hoeRepair != null) { hoeRepair.cancel(); }
        if (supports != null && supports.controller != null) { supports.controller.pause(); }
        if (!releaseAutomationControl(
                "could not fully stop Baritone execution movement",
                false
        )) {
            throw automationBlockedException();
        }
        if (isActiveOrder()) {
            mode = ExecutionMode.SUSPENDED;
        }
    }

    @Override
    public void cancelCurrentPath() {
        stopMovement();
    }

    /** Releases movement while preserving the inventory-triggered restock continuation. */
    void stopMovementForShopping() {
        boolean waitingForMaterials = mode == ExecutionMode.WAITING_MATERIALS;
        stopMovement();
        if (waitingForMaterials) {
            mode = ExecutionMode.WAITING_MATERIALS;
        }
    }

    @Override
    public boolean restartCurrentPath() {
        if (cancellationGuard.blocked() || interactionReceipt != null || !receiptFailure.isEmpty()) {
            return false;
        }
        if (order == null || !mode.restartable()) {
            return false;
        }
        safeReturnSnapshot = SAFE_RETURN_IDLE;
        requestedAvailable = MaterialQuantities.empty();
        detail = "";
        try {
            if (stemSweep != null && stemSweepCursor < stemSweep.size()) {
                stemSweepCursor = 0;
                stemPredictionWaitTicks = 0;
                stemTarget = null;
                stemHandSlot = -1;
                stemHandIdentity = ItemStack.EMPTY;
                mode = ExecutionMode.STEM_SWEEP_SCAN;
                return true;
            }
            if (order instanceof WorkOrder.OrdinaryBlocks) {
                dispatchOrdinaryBuild();
            } else {
                if (manualTargetIndex >= manualTargets.size()) {
                    succeed();
                } else {
                    beginManualTarget();
                }
            }
            return !cancellationGuard.blocked()
                    && mode != ExecutionMode.FAILED
                    && mode != ExecutionMode.WAITING_MATERIALS;
        } catch (RuntimeException exception) {
            fail(errorDetail(exception));
            return false;
        }
    }

    @Override
    public void beginReturnToLastSafePosition() {
        stopMovement();
        if (lastSafePosition == null || client.player == null) {
            safeReturnSnapshot = SafeReturnSnapshot.failed(
                    "No confirmed safe position is available"
            );
            return;
        }
        try {
            if (flightExecution || flightAvailable()) {
                if (!flightAvailable()) {
                    finishSafeReturnFailure("Active flight was lost before safe return");
                    return;
                }
                if (!releaseAutomationControl("could not release ground movement before flight return", true)) {
                    throw automationBlockedException();
                }
                flightNavigation.beginReturnTo(lastSafePosition, 1.0);
                flightSafeReturn = true;
                safeReturnSnapshot = SafeReturnSnapshot.running();
                return;
            }
            applyBaritoneSettings(false);
            safeReturnGoal = new GoalNear(lastSafePosition, 1);
            safeReturnGraceTicks = PATH_START_GRACE_TICKS;
            if (arrived(safeReturnGoal)) {
                finishSafeReturnSuccess();
                return;
            }
            startCustomGoal(safeReturnGoal);
            safeReturnSnapshot = SafeReturnSnapshot.running();
        } catch (RuntimeException exception) {
            finishSafeReturnFailure(errorDetail(exception));
        }
    }

    @Override
    public SafeReturnSnapshot pollSafeReturn() {
        requireAutomationAvailable("poll safe-return execution");
        if (safeReturnSnapshot.status() == SafeReturnStatus.RUNNING
                && client.player != null && client.world != null
                && !MinecraftBackgroundBuildAccess.allowsWorldActions(client)) {
            return new SafeReturnSnapshot(SafeReturnStatus.WAITING_FOR_SCREEN,
                    "Safe return is waiting for the inventory cursor or container to clear.");
        }
        return safeReturnSnapshot;
    }

    @Override
    public void cancelSafeReturn() {
        if (!releaseAutomationControl(
                "could not fully cancel Baritone safe-return movement",
                false
        )) {
            throw automationBlockedException();
        }
        safeReturnGoal = null;
        safeReturnSnapshot = SAFE_RETURN_IDLE;
    }

    @Override
    public boolean flightRestorable() {
        ClientPlayerEntity player = client.player;
        return player != null && flightExecution && !cancellationGuard.blocked()
                && player.getAbilities().allowFlying && !player.getAbilities().flying;
    }

    @Override
    public void beginFlightRestore() {
        if (flightRestore.active()) { return; }
        if (!flightRestorable()) {
            throw new IllegalStateException("Flight is already active, or the server no longer allows it");
        }
        if (!releaseAutomationControl("could not release movement before restoring flight", false)) {
            throw automationBlockedException();
        }
        flightRestore.start();
    }

    @Override
    public FlightRestoreSnapshot pollFlightRestore() {
        if (flightRestore.active()) { return FlightRestoreSnapshot.running(); }
        FlightTakeoffSession.Snapshot snapshot = flightRestore.snapshot();
        return switch (snapshot.state()) {
            case "IDLE" -> FlightRestoreSnapshot.idle();
            case "COMPLETE" -> flightAvailable()
                    ? FlightRestoreSnapshot.succeeded()
                    : FlightRestoreSnapshot.failed("Flight was not active after the takeoff");
            default -> FlightRestoreSnapshot.failed(snapshot.detail());
        };
    }

    @Override
    public void cancelFlightRestore() {
        flightRestore.cancel("Flight restore cancelled; jump input released.");
    }

    private String flightRestoreProblem() {
        if (cancellationGuard.blocked()) { return cancellationGuard.blockedDetail(); }
        if (interactionReceipt != null || !receiptFailure.isEmpty()) {
            return "An unsettled interaction must settle before flight is restored.";
        }
        try {
            var inputs = baritone().getInputOverrideHandler();
            for (Input input : Input.values()) {
                if (inputs.isInputForcedDown(input)) {
                    return "Baritone still holds forced input " + input + ".";
                }
            }
        } catch (RuntimeException unavailable) {
            return "Baritone input state is unavailable: " + errorDetail(unavailable);
        }
        return "";
    }

    /** Confirmed or uncertain support ownership must remain visible to full-volume verification. */
    Collection<BlockPosition> temporaryScaffolding() {
        return supports == null || supports.controller == null ? List.of() : supports.controller.outstandingSupports();
    }

    /** The slice that must settle unfinished temporary supports before any other order can start. */
    Optional<WorkOrder> outstandingSupportSlice() {
        return supports != null && supports.outstanding() ? Optional.ofNullable(supports.slice) : Optional.empty();
    }

    @Override
    public void close() {
        cancelFlightRestore();
        if (interactionReceipt != null) { settleMossCharge(interactionReceipt, false); }
        if (hoeRepair != null) {
            settleMossHoeRepairAllowance(hoeRepair.settle(false));
            hoeRepair.cancel();
        }
        if (supports != null && supports.controller != null) { supports.controller.pause(); }
        if (!releaseAutomationControl(
                "could not fully release Baritone execution while closing",
                false
        )) {
            throw automationBlockedException();
        }
        resetOrderState();
        flightExecution = false;
        order = null;
        mode = ExecutionMode.IDLE;
        safeReturnGoal = null;
        safeReturnSnapshot = SAFE_RETURN_IDLE;
    }

    boolean automationBlocked() {
        return cancellationGuard.blocked() || hoeRepair != null && !hoeRepair.blockDetail().isBlank();
    }

    String automationBlockDetail() {
        return cancellationGuard.blocked() ? cancellationGuard.blockedDetail()
                : hoeRepair == null ? "" : hoeRepair.blockDetail();
    }

    String prepareForExplicitReset() {
        if (!hoeRepairBlockDetail().isBlank()) { return hoeRepairBlockDetail(); }
        if (!temporarySupportBlockDetail().isBlank()) { return temporarySupportBlockDetail(); }
        boolean released = cancellationGuard.blocked()
                ? cancellationGuard.retryForReset()
                : releaseAutomationControl(
                        "could not decisively release Baritone execution for explicit reset",
                        true
                );
        activeGoal = null;
        safeReturnGoal = null;
        lastObservedPlayerPosition = null;
        if (!released) {
            markAutomationBlocked("");
            return cancellationGuard.blockedDetail();
        }
        ExecutionResetPolicy.Normalization normalization =
                ExecutionResetPolicy.afterSuccessfulRelease(order != null);
        mode = switch (normalization.orderIntent()) {
            case IDLE -> ExecutionMode.IDLE;
            case SUSPENDED -> ExecutionMode.SUSPENDED;
        };
        controlReleased = true;
        safeReturnSnapshot = new SafeReturnSnapshot(
                normalization.safeReturnStatus(),
                ""
        );
        safeReturnGraceTicks = normalization.safeReturnGraceTicks();
        requestedAvailable = MaterialQuantities.empty();
        detail = "";
        return "";
    }

    private void tickStemSweep() {
        if (supports != null && supports.outstanding()) {
            beginSupportExecution();
            return;
        }
        if (cooldownTicks > 0) { cooldownTicks--; return; }
        ClientWorld world = Objects.requireNonNull(client.world, "world");
        int budget = scanBudgetPerTick;
        long deadline = System.nanoTime() + 2_000_000L;
        int initialCursor = stemSweepCursor;
        while (stemSweepCursor < stemSweep.size() && budget-- > 0) {
            if ((budget & 63) == 0 && System.nanoTime() >= deadline) { break; }
            BlockPosition position = stemSweep.position(stemSweepCursor);
            if (!chunkLoaded(world, position)) { beginChunkApproach(position); return; }
            BlockPos target = toMinecraft(position);
            var state = world.getBlockState(target);
            if (StemClearingSweep.isStem(blockId(state.getBlock()))
                    && stemSweep.allows(position, MinecraftBlockStates.toCore(state))) {
                if (predictionPending(world, target)) {
                    if (++stemPredictionWaitTicks >= StemClearingSweep.CLEARING_BUDGET_TICKS) {
                        fail("A stem target retained an unresolved block prediction during the bounded scan");
                    } else {
                        detail = "Waiting for an existing prediction before binding a stem target";
                    }
                    return;
                }
                stemPredictionWaitTicks = 0;
                stemTarget = new StemClearingSweep.Target(position, MinecraftBlockStates.toCore(state));
                if (!flightAvailable()) {
                    fail("An allowed stem needs clearing before this order; activate flight for the bounded stem sweep");
                    return;
                }
                String problem = stemClearingProblem(StemClearingSweep.Stage.APPROACH);
                if (!problem.isEmpty()) { fail(problem); return; }
                beginFlightRoute(target, null, target);
                mode = ExecutionMode.STEM_SWEEP_NAVIGATION;
                detail = "Approaching a permitted stem in a source-air or crop cell";
                return;
            }
            stemPredictionWaitTicks = 0;
            stemSweepCursor++;
        }
        if (stemSweepCursor != initialCursor) { progressMarker++; }
        if (stemSweepCursor >= stemSweep.size()) {
            stemTarget = null;
            mode = ExecutionMode.PREPARING;
            detail = "The bounded stem sweep is complete; checking the original work order";
        } else {
            detail = "Scanning source-air and crop cells for permitted stems: "
                    + stemSweepCursor + "/" + stemSweep.size();
        }
    }

    private void tickStemNavigation() {
        ClientWorld world = Objects.requireNonNull(client.world, "world");
        BlockPos target = toMinecraft(stemTarget.position());
        StemClearingSweep.ApproachAction approach = StemClearingSweep.approachAction(
                ClientChunkAvailability.isLoaded(world, target), flightNavigation.activeInteractionRoute());
        if (approach == StemClearingSweep.ApproachAction.RECEIVE_TARGET_CHUNK) {
            beginChunkApproach(stemTarget.position());
            return;
        }
        if (approach == StemClearingSweep.ApproachAction.FOLLOW_PLANNED_ROUTE) {
            // A received detour can briefly unload the distant target at a chunk boundary.
            // Keep its route budget and live segment guards; inspect no target until it returns.
            flightNavigation.tick();
            detail = "Following received stem approach while the target chunk is unreceived: "
                    + flightNavigation.detail();
            if (flightNavigation.failed()) { fail(detail); }
            return;
        }
        if (world.getBlockState(target).isAir() && !predictionPending(world, target)) {
            finishStemTarget();
            return;
        }
        String problem = stemClearingProblem(StemClearingSweep.Stage.APPROACH);
        if (!problem.isEmpty()) { fail(problem); return; }
        flightNavigation.tick();
        detail = "Approaching permitted stem: " + flightNavigation.detail();
        if (flightNavigation.failed()) { fail(detail); return; }
        if (!flightNavigation.arrived()) { return; }
        problem = stemClearingProblem(StemClearingSweep.Stage.INTERACTION);
        if (!problem.isEmpty()) { fail(problem); return; }
        BlockHitResult hit = verifiedHit(target, null);
        if (hit == null) {
            if (!flightNavigation.retryInteractionArrival(interactionRayDetail)) {
                fail(flightNavigation.detail());
            }
            return;
        }
        ClientPlayerEntity player = Objects.requireNonNull(client.player, "player");
        ClientPlayerInteractionManager manager = Objects.requireNonNull(client.interactionManager, "interactionManager");
        if (interactionReceipt != null || miningLease != null || manager.isBreakingBlock()
                || predictionPending(world, target) || pendingMossHoeRepair != null
                || !MinecraftBackgroundBuildAccess.allowsWorldActions(client)) {
            fail("Stem clearing cannot borrow another interaction, tool repair, or block prediction");
            return;
        }
        OptionalInt hand = PlainInteractionItems.findStemHotbarSlot(player.getInventory());
        if (hand.isEmpty()) { fail("Stem clearing needs an empty or permitted plain item in the hotbar"); return; }
        player.getInventory().setSelectedSlot(hand.getAsInt());
        stemHandSlot = hand.getAsInt();
        stemHandIdentity = player.getMainHandStack().copy();
        problem = stemClearingProblem(StemClearingSweep.Stage.INTERACTION);
        hit = verifiedHit(target, null);
        if (!problem.isEmpty() || hit == null || !safeStemHand(player.getMainHandStack())
                || predictionPending(world, target) || !MinecraftBackgroundBuildAccess.allowsWorldActions(client)
                || client.options.attackKey.isPressed() || client.options.useKey.isPressed()) {
            fail(!problem.isEmpty() ? problem : "Stem clearing lost its exact ray or safe hand before START");
            return;
        }
        Material sentinel = order instanceof WorkOrder.OrdinaryBlocks ordinary
                ? ordinary.placements().getFirst().material() : Material.HOE;
        miningLease = OwnedBlockBreaking.acquire(manager);
        miningManager = manager;
        ownedMiningTarget = target.toImmutable();
        aimAt(hit);
        interactionReceipt = new InteractionReceipt(world, player, target.toImmutable(), sentinel, Blocks.AIR,
                new FlightInteractionConfirmation(inventoryCount(sentinel), false,
                        player.getAbilities().creativeMode, StemClearingSweep.CLEARING_BUDGET_TICKS));
        receiptResult = FlightInteractionConfirmation.Result.WAITING;
        mode = ExecutionMode.STEM_SWEEP_MINING;
        ((ClientPlayerInteractionManagerAccessor) manager).supervisor$syncSelectedSlot();
        if (!attackOwnedBlock(manager, target, hit.getSide())) {
            fail("The permitted stem attack was rejected");
            return;
        }
        player.swingHand(Hand.MAIN_HAND);
        detail = "Clearing only " + stemTarget.observedState().blockId() + " at " + target.toShortString();
        latchStemCompletion(world, target);
    }

    private void tickStemMining() {
        if (receiptResult != FlightInteractionConfirmation.Result.WAITING) { tickStemConfirmation(); return; }
        ClientWorld world = Objects.requireNonNull(client.world, "world");
        BlockPos target = toMinecraft(stemTarget.position());
        if (!ClientChunkAvailability.isLoaded(world, target)) {
            cancelOwnedMining();
            mode = ExecutionMode.STEM_SWEEP_CONFIRMATION;
            return;
        }
        if (!world.getBlockState(target).isAir()
                && !stemSweep.matches(stemTarget, MinecraftBlockStates.toCore(world.getBlockState(target)))) {
            fail("The exact stem state changed during owned clearing");
            return;
        }
        if (latchStemCompletion(world, target)) { return; }
        String problem = stemClearingProblem(StemClearingSweep.Stage.INTERACTION);
        if (!problem.isEmpty()) { fail(problem); return; }
        ClientPlayerEntity player = Objects.requireNonNull(client.player, "player");
        BlockHitResult hit = verifiedHit(target, null);
        if (hit == null || miningLease == null || miningManager != client.interactionManager
                || !OwnedBlockBreaking.isOwned(miningManager)
                || player.getInventory().getSelectedSlot() != stemHandSlot
                || !safeStemHand(player.getMainHandStack())
                || !ItemStack.areItemsAndComponentsEqual(stemHandIdentity, player.getMainHandStack())) {
            fail("Stem clearing lost its exact ray, owned break, or unchanged safe hand");
            return;
        }
        aimAt(hit);
        if (!advanceOwnedMining(miningManager, target, hit.getSide())) {
            fail("The server did not accept continued stem clearing");
            return;
        }
        player.swingHand(Hand.MAIN_HAND);
        latchStemCompletion(world, target);
    }

    private boolean latchStemCompletion(ClientWorld world, BlockPos target) {
        if (miningManager != null && miningManager.isBreakingBlock() && !world.getBlockState(target).isAir()) {
            return false;
        }
        cancelOwnedMining();
        mode = ExecutionMode.STEM_SWEEP_CONFIRMATION;
        detail = "Waiting for the server to acknowledge stem removal without material consumption";
        return true;
    }

    private void tickStemConfirmation() {
        if (receiptResult == FlightInteractionConfirmation.Result.CONFIRMED) {
            cancelOwnedMining();
            receiptResult = null;
            finishStemTarget();
        } else if (receiptResult == FlightInteractionConfirmation.Result.RETRYABLE) {
            receiptResult = null;
            fail("The server did not confirm stem removal within the bounded attempt");
        }
    }

    private void finishStemTarget() {
        flightNavigation.stop();
        stemSweepCursor++;
        stemTarget = null;
        stemHandSlot = -1;
        stemHandIdentity = ItemStack.EMPTY;
        progressMarker++;
        confirmedBlockProgressThisTick = true;
        unavailableFlightFaces.clear();
        lastFlightFaceRejection = "";
        cooldownTicks = interactionCooldownTicks;
        mode = ExecutionMode.STEM_SWEEP_SCAN;
    }

    private String stemClearingProblem(StemClearingSweep.Stage stage) {
        ClientWorld world = Objects.requireNonNull(client.world, "world");
        ClientPlayerEntity player = Objects.requireNonNull(client.player, "player");
        if (stemTarget == null) { return "The stem clearing target is unavailable"; }
        BlockPos target = toMinecraft(stemTarget.position());
        if (!ClientChunkAvailability.isLoaded(world, target)) { return "The stem target chunk is not received"; }
        var actual = world.getBlockState(target);
        if (!stemSweep.matches(stemTarget, MinecraftBlockStates.toCore(actual))) {
            return "The exact stem or planned source-air/crop cell changed before interaction";
        }
        if (actual.hasBlockEntity() || !world.getFluidState(target).isEmpty()) {
            return "The stem target contains a block entity or fluid";
        }
        if (!player.getAbilities().allowModifyWorld || player.isSpectator()) {
            return "World modification is unavailable for stem clearing";
        }
        return StemClearingSweep.occupancyProblem(stage, () -> {
            Box occupied = new Box(target);
            boolean playerOverlap = player.getBoundingBox().intersects(occupied);
            List<Entity> occupants = playerOverlap ? null : world.getOtherEntities(player, occupied.stretch(0, 2, 0));
            return MossClearingEntityPolicy.hasConflict(playerOverlap, occupants,
                    entity -> new MossClearingEntityPolicy.Facts(entity.getClass() == ItemEntity.class,
                            entity.getType() == EntityType.ITEM, entity.canHit(), entity.isCollidable(player),
                            entity.hasVehicle(), entity.hasPassengers()));
        });
    }

    private static boolean safeStemHand(ItemStack stack) {
        return PlainInteractionItems.safeStemHand(stack);
    }

    private boolean resumeUnfinishedStemSweep() {
        if (stemSweep == null || stemSweepCursor >= stemSweep.size()) { return false; }
        if (interactionReceipt != null || !receiptFailure.isEmpty()) {
            throw new IllegalStateException("An unsettled stem interaction must be reconciled before resuming the scan");
        }
        stemTarget = null;
        stemHandSlot = -1;
        stemHandIdentity = ItemStack.EMPTY;
        mode = ExecutionMode.STEM_SWEEP_SCAN;
        return true;
    }

    private void tickPreparation() {
        if (stemSweep != null && stemSweepCursor < stemSweep.size()) {
            mode = ExecutionMode.STEM_SWEEP_SCAN;
            return;
        }
        int size = targetCount();
        int budget = scanBudgetPerTick;
        while (budget > 0 && preparationCursor < size) {
            BlockPosition target = targetPosition(preparationCursor);
            if (!chunkLoaded(Objects.requireNonNull(client.world, "world"), target)) {
                beginChunkApproach(target);
                return;
            }
            boolean correct = order instanceof WorkOrder.OrdinaryBlocks
                    ? ordinaryCorrect(preparationCursor)
                    : manualCorrect(preparationCursor);
            if (correct) {
                if (!correctTargets.get(preparationCursor)) { progressMarker++; }
                correctTargets.set(preparationCursor);
            } else {
                correctTargets.clear(preparationCursor);
            }
            preparationCursor++;
            budget--;
        }
        if (preparationCursor < size) {
            return;
        }
        if (supports != null && supports.outstanding()) {
            beginSupportExecution();
            return;
        }
        if (correctTargets.cardinality() == size) {
            succeed();
            return;
        }
        if (order instanceof WorkOrder.OrdinaryBlocks) {
            dispatchOrdinaryBuild();
        } else {
            manualTargetIndex = correctTargets.nextClearBit(0);
            beginManualTarget();
        }
    }

    private void beginChunkApproach(BlockPosition target) {
        if (!flightAvailable()) {
            fail("The layer chunk at " + target + " has not been received; active flight is required to approach it");
            return;
        }
        flightExecution = true;
        if (chunkApproachTicksRemaining <= 0) {
            fail("The layer's bounded chunk-receipt approach expired");
            return;
        }
        if (!releaseAutomationControl("could not release ground movement before receiving the layer chunk", true)) {
            throw automationBlockedException();
        }
        chunkApproachTarget = target;
        flightNavigation.beginApproachChunk(Math.floorDiv(target.x(), 16),
                Math.floorDiv(target.z(), 16), toMinecraft(target));
        mode = ExecutionMode.FLIGHT_CHUNK_APPROACH;
        detail = "Approaching the first unreceived layer chunk before inspecting its blocks";
    }

    private void tickUnreceivedInteractionApproach(BlockPosition target) {
        if (!flightNavigation.activeInteractionRoute()) {
            beginChunkApproach(target);
            return;
        }
        // The route retains its original time/node budget and checks every movement segment.
        // Do not inspect or interact with the target while its chunk is unavailable.
        flightNavigation.tick();
        detail = "Continuing guarded interaction approach while the target chunk is unreceived: "
                + flightNavigation.detail();
        if (flightNavigation.failed()) { fail(detail); }
    }

    private void tickChunkApproach() {
        if (chunkApproachTarget == null) {
            fail("Layer chunk approach has no target");
            return;
        }
        ClientWorld world = Objects.requireNonNull(client.world, "world");
        if (chunkLoaded(world, chunkApproachTarget)) {
            flightNavigation.stop();
            progressMarker++;
            chunkApproachTarget = null;
            preparationCursor = 0;
            stemSweepCursor = 0;
            stemPredictionWaitTicks = 0;
            stemTarget = null;
            flightFace = null;
            unavailableFlightFaces.clear();
            lastFlightFaceRejection = "";
            mode = ExecutionMode.PREPARING;
            detail = "The layer chunk was received; rescanning its actual blocks";
            return;
        }
        if (--chunkApproachTicksRemaining <= 0) {
            fail("The layer's bounded chunk-receipt approach expired");
            return;
        }
        flightNavigation.tick();
        detail = "Receiving layer chunk: " + flightNavigation.detail();
        if (flightNavigation.failed() || flightNavigation.arrived()) {
            fail("Could not receive the layer chunk before inspection: " + flightNavigation.detail());
        }
    }

    private void dispatchOrdinaryBuild() {
        if (resumeUnfinishedStemSweep()) { return; }
        if (!(order instanceof WorkOrder.OrdinaryBlocks ordinary)) {
            throw new IllegalStateException("Current order is not an ordinary build");
        }
        if (supports != null && supports.outstanding()) {
            beginSupportExecution();
            return;
        }
        if (flightExecution) {
            beginFlightOrdinarySelection();
            return;
        }
        MaterialQuantities unavailable = unavailableOrdinaryMaterials();
        if (!unavailable.isEmpty()) {
            needMaterials(unavailable);
            return;
        }
        List<OrdinaryPlacement> remaining = OrdinaryPlacementAcceptance.remaining(
                ordinaryPlacements,
                correctTargets
        );
        if (remaining.isEmpty()) {
            succeed();
            return;
        }
        applyBaritoneSettings(true);
        SparseBaritoneSchematic schematic = new SparseBaritoneSchematic(
                ordinary,
                remaining,
                buildVolume.minY(),
                buildVolume.maxY()
        );
        controlReleased = false;
        baritone().getBuilderProcess().build(
                "supervised-chunk-" + ordinary.chunkIndex(),
                schematic,
                new BlockPos(schematic.originX(), schematic.originY(), schematic.originZ())
        );
        builderIdleTicks = 0;
        mode = ExecutionMode.ORDINARY_RUNNING;
    }

    private boolean flightAvailable() {
        return client.player != null && client.player.getAbilities().allowFlying
                && client.player.getAbilities().flying;
    }

    String flightStatus() {
        if (flightRestore.active()) { return "Restoring lost flight: " + flightRestore.snapshot().detail(); }
        if (flightNavigation.active() || flightSafeReturn) { return flightNavigation.detail(); }
        if (!flightExecution || !isActiveOrder() || mode == ExecutionMode.SUSPENDED
                || mode == ExecutionMode.WAITING_MATERIALS) { return ""; }
        return detail.isBlank() ? "Flight construction: " + mode.name().toLowerCase(java.util.Locale.ROOT)
                : detail;
    }

    /** Captures live facts without polling settlement, advancing clocks, or changing controls. */
    ExecutionObservation observation() {
        if (client.world == null || client.player == null || client.interactionManager == null) {
            return ExecutionObservation.unavailable(mode.name(), detail, lastReceiptObservation, lastFailureReceiptObservation,
                    "Client world, player, or interaction manager is unavailable",
                    obstructionHistory.observation(client.world, client.player))
                    .withMossToolSelection(mossToolSelectionHistory.observation(client.world, client.player));
        }
        try {
            return new ExecutionObservation(true, mode.name(), detail, observationTarget(),
                    interactionReceipt == null ? null : receiptObservation(interactionReceipt, ""),
                    lastReceiptObservation, lastFailureReceiptObservation, miningOwned(), client.interactionManager.isBreakingBlock(),
                    flightStatus(), receiptFailure, obstructionHistory.observation(client.world, client.player))
                    .withMossToolSelection(mossToolSelectionHistory.observation(client.world, client.player));
        } catch (RuntimeException failure) {
            return ExecutionObservation.unavailable(mode.name(), detail, lastReceiptObservation, lastFailureReceiptObservation,
                    "Execution observation unavailable: " + errorDetail(failure),
                    obstructionHistory.observation(client.world, client.player))
                    .withMossToolSelection(mossToolSelectionHistory.observation(client.world, client.player));
        }
    }

    private ExecutionObservation.Target observationTarget() {
        BlockPosition target = null;
        String expected = null;
        if (stemTarget != null) {
            target = stemTarget.position();
            expected = "minecraft:air";
        } else if (supportOperation != null) {
            target = supportOperation.target();
            expected = supportOperation.expected().blockId();
        } else if (supportCandidate != null && supports != null && supports.outstanding()) {
            target = supportCandidate.target();
            expected = supportCandidate.expected().blockId();
        } else if (order instanceof WorkOrder.OrdinaryBlocks && flightTargetIndex >= 0
                && flightTargetIndex < ordinaryPlacements.size()) {
            OrdinaryPlacement placement = ordinaryPlacements.get(flightTargetIndex);
            target = placement.position();
            expected = placement.state().blockId();
        } else if (order != null && !(order instanceof WorkOrder.OrdinaryBlocks)
                && manualTargetIndex >= 0 && manualTargetIndex < manualTargets.size()) {
            target = manualTargets.get(manualTargetIndex);
            expected = order instanceof WorkOrder.Till ? "minecraft:farmland" : "minecraft:wheat";
        }
        if (target == null) { return null; }
        boolean received = chunkLoaded(client.world, target);
        return new ExecutionObservation.Target(target, expected,
                received ? blockId(client.world.getBlockState(toMinecraft(target)).getBlock()) : null, received);
    }

    private Boolean miningOwned() {
        return client.interactionManager == null ? null
                : miningLease != null && miningManager == client.interactionManager
                        && OwnedBlockBreaking.isOwned(client.interactionManager);
    }

    private ExecutionObservation.Receipt receiptObservation(InteractionReceipt receipt, String captureError) {
        boolean worldMatches = client.world == receipt.world() && client.player == receipt.player();
        ExecutionObservation.Target target = new ExecutionObservation.Target(
                corePosition(receipt.target()), blockId(receipt.expectedBlock()), null, null);
        try {
            boolean received = worldMatches && ClientChunkAvailability.isLoaded(receipt.world(), receipt.target());
            target = new ExecutionObservation.Target(corePosition(receipt.target()), blockId(receipt.expectedBlock()),
                    received ? blockId(receipt.world().getBlockState(receipt.target()).getBlock()) : null,
                    worldMatches ? received : null);
            ClientPlayerInteractionManager manager = worldMatches ? client.interactionManager : null;
            ClientPlayerInteractionManagerAccessor facts = manager instanceof ClientPlayerInteractionManagerAccessor accessor
                    ? accessor : null;
            BlockPos breaking = facts == null ? null : facts.supervisor$getCurrentBreakingPos();
            Float localBreakingDelta = null;
            if (received) {
                net.minecraft.block.BlockState state = receipt.world().getBlockState(receipt.target());
                if (!state.isAir()) {
                    float delta = state.calcBlockBreakingDelta(receipt.player(), receipt.world(), receipt.target());
                    if (Float.isFinite(delta) && delta >= 0) { localBreakingDelta = delta; }
                }
            }
            return new ExecutionObservation.Receipt(Instant.now(), mode.name(), target,
                    received ? predictionPending(receipt.world(), receipt.target()) : null,
                    receipt.material().jsonName(), receipt.confirmation().inventoryBefore(),
                    worldMatches ? inventoryCount(receipt.material()) : null,
                    receipt.confirmation().result().name(), receipt.confirmation().ageTicks(),
                    receipt.confirmation().budgetTicks(), worldMatches,
                    worldMatches ? miningOwned() : null, manager == null ? null : manager.isBreakingBlock(),
                    breaking == null ? null : corePosition(breaking),
                    facts == null ? null : facts.supervisor$getCurrentBreakingProgress(),
                    localBreakingDelta,
                    facts == null ? null : facts.supervisor$getBlockBreakingCooldown(),
                    worldMatches ? receipt.player().getInventory().getSelectedSlot() : null,
                    worldMatches ? Registries.ITEM.getId(receipt.player().getMainHandStack().getItem()).toString() : null,
                    captureError);
        } catch (RuntimeException failure) {
            return new ExecutionObservation.Receipt(Instant.now(), mode.name(),
                    new ExecutionObservation.Target(target.position(), target.expectedBlock(), null, null),
                    null, receipt.material().jsonName(), receipt.confirmation().inventoryBefore(), null,
                    receipt.confirmation().result().name(), receipt.confirmation().ageTicks(),
                    receipt.confirmation().budgetTicks(), worldMatches, null, null, null, null, null, null, null, null,
                    "Receipt observation unavailable: " + errorDetail(failure));
        }
    }

    private static String blockId(Block block) {
        return Registries.BLOCK.getId(block).toString();
    }

    private static BlockPosition corePosition(BlockPos position) {
        return new BlockPosition(position.getX(), position.getY(), position.getZ());
    }

    private void captureLastReceipt(InteractionReceipt receipt, String error) {
        try {
            lastReceiptObservation = receiptObservation(receipt, error);
        } catch (RuntimeException ignored) {
            // Optional diagnostic collection must never change interaction settlement.
        }
    }

    private void beginFlightRoute(BlockPos target, Direction face, BlockPos reservedCell) {
        beginFlightRoute(target, face, reservedCell, false);
    }

    private void beginFlightRoute(BlockPos target, Direction face, BlockPos reservedCell,
                                  boolean tillTopFaceAim) {
        if (!flightAvailable()) {
            throw new IllegalStateException("Active flight is required for this work order");
        }
        if (!releaseAutomationControl("could not release ground movement before flight", true)) {
            throw automationBlockedException();
        }
        flightNavigation.begin(target, interactionReach(), face, reservedCell, tillTopFaceAim);
        if (flightNavigation.failed()) {
            detail = flightNavigation.detail();
        }
    }

    private double interactionReach() {
        return Math.max(0.5, Math.min(5.5,
                Objects.requireNonNull(client.player, "player").getBlockInteractionRange() - 0.35));
    }

    private void beginFlightOrdinarySelection() {
        if (!flightAvailable()) {
            fail("Active flight was lost before resuming the floating build");
            return;
        }
        flightNavigation.stop();
        clearingTarget = null;
        clearingHandSlot = -1;
        flightTargetIndex = -1;
        flightFace = null;
        unreceivedAnchor = null;
        flightBestDistance = Double.POSITIVE_INFINITY;
        flightBestCurrentPosition = false;
        flightScanCursor = 0;
        flightValidAnchorFaces = 0;
        flightCachedAnchorFaces = 0;
        flightInvalidAnchors = 0;
        flightUnreceivedAnchors = 0;
        mossCandidateIndex = -1;
        mossCandidateDistance = Double.POSITIVE_INFINITY;
        mode = ExecutionMode.FLIGHT_ORDINARY_SELECTING;
        detail = "Selecting a supported block in the current layer slice";
    }

    private void tickFlightOrdinarySelection() {
        ClientWorld world = Objects.requireNonNull(client.world, "world");
        ClientPlayerEntity player = Objects.requireNonNull(client.player, "player");
        int end = Math.min(ordinaryPlacements.size(), flightScanCursor + scanBudgetPerTick);
        while (flightScanCursor < end) {
            int index = flightScanCursor++;
            OrdinaryPlacement placement = ordinaryPlacements.get(index);
            if (!chunkLoaded(world, placement.position())) {
                beginChunkApproach(placement.position());
                return;
            }
            if (ordinaryCorrect(index)) {
                markFlightOrdinaryCorrect(index);
                continue;
            }
            correctTargets.clear(index);
            BlockPos target = toMinecraft(placement.position());
            if (!world.getBlockState(target).isAir()) {
                if (MossClearingPolicy.allowsReplacement(placement, blockId(world.getBlockState(target).getBlock()))) {
                    double distance = player.getEyePos().squaredDistanceTo(Vec3d.ofCenter(target));
                    if (distance < mossCandidateDistance) {
                        mossCandidateIndex = index;
                        mossCandidateDistance = distance;
                    }
                    continue;
                }
                fail(occupiedTargetDetail(target));
                return;
            }
            for (Direction offset : Direction.values()) {
                BlockPos anchor = target.offset(offset);
                if (!ClientChunkAvailability.isLoaded(world, anchor)) {
                    flightUnreceivedAnchors++;
                    if (unreceivedAnchor == null) {
                        unreceivedAnchor = new BlockPosition(anchor.getX(), anchor.getY(), anchor.getZ());
                    }
                    continue;
                }
                FlightPlacementFace candidate = new FlightPlacementFace(index, anchor, offset.getOpposite());
                if (!validPlacementAnchor(anchor, candidate.face())) {
                    flightInvalidAnchors++;
                    continue;
                }
                flightValidAnchorFaces++;
                if (unavailableFlightFaces.contains(candidate)) {
                    flightCachedAnchorFaces++;
                    continue;
                }
                double distance = player.getEyePos().squaredDistanceTo(Vec3d.ofCenter(anchor));
                boolean currentPosition = flightNavigation.canBeginAtCurrentPosition(
                        anchor, interactionReach(), candidate.face(), target);
                if (FlightPlacementPreference.prefer(currentPosition, distance,
                        flightBestCurrentPosition, flightBestDistance)) {
                    flightBestDistance = distance;
                    flightBestCurrentPosition = currentPosition;
                    flightTargetIndex = index;
                    flightFace = candidate;
                }
            }
        }
        if (flightScanCursor < ordinaryPlacements.size()) { return; }
        if (correctTargets.cardinality() == ordinaryPlacements.size()) {
            succeed();
            return;
        }
        if (flightFace == null) {
            if (mossCandidateIndex >= 0) {
                beginMossClearing(mossCandidateIndex);
                return;
            }
            if (unreceivedAnchor != null) {
                beginChunkApproach(unreceivedAnchor);
                return;
            }
            if (beginTemporarySupport()) { return; }
            fail("No reachable existing support face or valid bounded temporary support column remains in this layer"
                    + unsupportedTargetNeighbors(world)
                    + "; remaining=" + (ordinaryPlacements.size() - correctTargets.cardinality())
                    + ", validFaces=" + flightValidAnchorFaces
                    + ", cachedFaces=" + flightCachedAnchorFaces + "/" + unavailableFlightFaces.size()
                    + ", invalidAnchors=" + flightInvalidAnchors
                    + ", unreceivedAnchors=" + flightUnreceivedAnchors
                    + (lastFlightFaceRejection.isEmpty() ? "" : "; lastRejected=" + lastFlightFaceRejection));
            return;
        }
        Material material = ordinaryPlacements.get(flightTargetIndex).material();
        if (inventoryCount(material) < 1) {
            needMaterials(unavailableOrdinaryMaterials());
            return;
        }
        interactionAttempts = 0;
        beginFlightRoute(flightFace.anchor(), flightFace.face(),
                toMinecraft(ordinaryPlacements.get(flightTargetIndex).position()));
        // A face already usable from here goes straight to the ready checks without a navigation tick.
        if (flightNavigation.arrived()) {
            mode = ExecutionMode.FLIGHT_ORDINARY_READY;
            detail = flightNavigation.detail();
        } else {
            mode = ExecutionMode.FLIGHT_ORDINARY_NAVIGATING;
        }
    }

    private String unsupportedTargetNeighbors(ClientWorld world) {
        int index = correctTargets.nextClearBit(0);
        if (index >= ordinaryPlacements.size()) { return ""; }
        BlockPos target = toMinecraft(ordinaryPlacements.get(index).position());
        StringBuilder observed = new StringBuilder("; target=").append(target.toShortString())
                .append("; neighbors=");
        for (Direction direction : Direction.values()) {
            BlockPos neighbor = target.offset(direction);
            observed.append(direction.name()).append(':').append(
                    ClientChunkAvailability.isLoaded(world, neighbor)
                            ? blockId(world.getBlockState(neighbor).getBlock()) : "unreceived").append(' ');
        }
        return observed.toString().trim();
    }

    private boolean beginTemporarySupport() {
        if (supports == null || !(order instanceof WorkOrder.OrdinaryBlocks slice)) { return false; }
        supports.requireOrder(order);
        try {
            Optional<TemporarySupportController> started = TemporarySupportController.begin(
                    supports.plan, slice, supports.context, supportWorld(), supports.store);
            if (started.isEmpty()) { return false; }
            supports.controller = started.orElseThrow();
            supports.slice = slice;
            beginSupportExecution();
            return true;
        } catch (IOException failure) { throw new UncheckedIOException(failure); }
    }

    private void beginSupportExecution() {
        supports.requireOrder(order);
        if (!flightAvailable()) { fail("Active flight is required for the owned support column"); return; }
        supports.controller.resume();
        flightExecution = true;
        supportCandidate = null;
        mode = ExecutionMode.SUPPORT_PREVIEW;
        detail = "Continuing the original layer's bounded temporary support column";
    }

    private BlockObservation supportWorld() {
        return new BlockObservation() {
            @Override public boolean isChunkLoaded(ChunkCoordinate chunk) {
                return client.world != null && ClientChunkAvailability.isLoaded(client.world, chunk.x(), chunk.z());
            }
            @Override public io.github.schematicsupervisor.core.BlockState blockState(BlockPosition position) {
                BlockPos target = toMinecraft(position);
                if (client.world == null || !ClientChunkAvailability.isLoaded(client.world, target)
                        || predictionPending(client.world, target)) { return null; }
                return MinecraftBlockStates.toCore(client.world.getBlockState(target));
            }
            @Override public List<BlockPosition> temporaryScaffolding(VerificationScope scope) {
                return List.copyOf(MinecraftExecutionPort.this.temporaryScaffolding());
            }
        };
    }

    private TemporarySupportController.Snapshot supportSnapshot() {
        RunContext current = MinecraftRunContext.capture(client);
        if (!supports.context.equals(current)) { throw new IllegalStateException("Temporary support world context changed"); }
        var column = supports.controller.journal().column();
        Map<BlockPosition, TemporarySupportController.CellRead> cells = new HashMap<>();
        for (BlockPosition position : List.of(column.anchor(), column.supports().get(0),
                column.supports().get(1), column.seed().position())) {
            BlockPos target = toMinecraft(position);
            boolean received = client.world != null && ClientChunkAvailability.isLoaded(client.world, target);
            cells.put(position, new TemporarySupportController.CellRead(received,
                    received && predictionPending(client.world, target),
                    received ? MinecraftBlockStates.toCore(client.world.getBlockState(target)) : null));
        }
        TreeMap<Material, Long> totals = new TreeMap<>();
        for (Material material : Material.builtIns()) { totals.put(material, inventoryCount(material)); }
        return new TemporarySupportController.Snapshot(current, cells, totals,
                Objects.requireNonNull(client.player, "player").getAbilities().creativeMode);
    }

    private void tickSupportPreview() {
        try {
            TemporarySupportController.Snapshot snapshot = supportSnapshot();
            TemporarySupportController.Decision decision = supports.controller.preview(snapshot);
            detail = decision.detail();
            switch (decision.status()) {
                case COMPLETE -> {
                    supportCandidate = null;
                    receiptResult = null;
                    preparationCursor = 0;
                    mode = ExecutionMode.PREPARING;
                }
                case ACTION -> {
                    supportCandidate = decision.candidate();
                    BlockPos clicked = toMinecraft(supportCandidate.kind().removes()
                            ? supportCandidate.target() : supportCandidate.against());
                    beginFlightRoute(clicked, supportCandidate.kind().removes() ? null : Direction.UP,
                            toMinecraft(supportCandidate.target()));
                    mode = ExecutionMode.SUPPORT_NAVIGATING;
                }
                case MAINTENANCE -> supports.controller.settleObservedState(snapshot);
                case UNCERTAIN -> {
                    receiptFailure = "Temporary support reconciliation required: " + decision.detail();
                    fail(receiptFailure);
                }
                case BLOCKED -> fail("Temporary support: " + decision.detail());
                case PAUSED -> mode = ExecutionMode.SUSPENDED;
                case WAITING -> {
                    MaterialQuantities reserve = supportMaterialRequirement();
                    if (!reserve.shortageFrom(MaterialQuantities.of(snapshot.inventory())).isEmpty()) {
                        needMaterials(reserve);
                    }
                }
            }
        } catch (IOException failure) { throw new UncheckedIOException(failure); }
    }

    private MaterialQuantities supportMaterialRequirement() {
        var journal = supports.controller.journal();
        if (journal.cleanupRequested() || client.player.getAbilities().creativeMode) {
            return MaterialQuantities.empty();
        }
        long remaining = journal.cells().stream()
                .filter(cell -> cell.stage() == TemporarySupportJournal.CellStage.PLANNED).count();
        Material seed = journal.column().seed().material();
        TreeMap<Material, Long> needed = new TreeMap<>();
        needed.put(Material.DIRT, remaining + (seed == Material.DIRT ? 1 : 0));
        if (seed != Material.DIRT) { needed.put(seed, 1L); }
        return MaterialQuantities.of(needed);
    }

    private void tickSupportNavigation() {
        flightNavigation.tick();
        detail = "Temporary support " + supportCandidate.kind() + ": " + flightNavigation.detail();
        if (flightNavigation.failed()) { fail(detail); }
        else if (flightNavigation.arrived()) { mode = ExecutionMode.SUPPORT_READY; }
    }

    private void tickSupportReady() {
        if (cooldownTicks > 0) { cooldownTicks--; return; }
        ClientPlayerEntity player = Objects.requireNonNull(client.player, "player");
        ClientWorld world = Objects.requireNonNull(client.world, "world");
        ClientPlayerInteractionManager manager = Objects.requireNonNull(client.interactionManager, "interactionManager");
        TemporarySupportController.Decision preview;
        try { preview = supports.controller.preview(supportSnapshot()); }
        catch (IOException failure) { throw new UncheckedIOException(failure); }
        if (preview.status() != TemporarySupportController.Status.ACTION
                || preview.candidate().kind() != supportCandidate.kind()
                || !preview.candidate().target().equals(supportCandidate.target())
                || !Objects.equals(preview.candidate().against(), supportCandidate.against())) {
            flightNavigation.stop();
            mode = ExecutionMode.SUPPORT_PREVIEW;
            return;
        }
        supportCandidate = preview.candidate();
        boolean removal = supportCandidate.kind().removes();
        BlockPos target = toMinecraft(supportCandidate.target());
        BlockPos clicked = removal ? target : toMinecraft(supportCandidate.against());
        BlockHitResult hit = verifiedHit(clicked, removal ? null : Direction.UP);
        if (hit == null) {
            if (flightNavigation.retryInteractionArrival(interactionRayDetail)) { mode = ExecutionMode.SUPPORT_NAVIGATING; }
            else { fail("Temporary support approach failed: " + flightNavigation.detail()); }
            return;
        }
        Box occupied = new Box(target);
        if (!player.getAbilities().allowModifyWorld || player.isSpectator()
                || manager.isBreakingBlock() || miningLease != null
                || interactionReceipt != null || supportOperation != null
                || predictionPending(world, target) || predictionPending(world, clicked)
                || !world.getFluidState(target).isEmpty() || world.getBlockState(target).hasBlockEntity()
                || supportEntityConflict(world, player, occupied)
                || player.currentScreenHandler != player.playerScreenHandler
                || !player.currentScreenHandler.getCursorStack().isEmpty()) {
            fail("Temporary support interaction is blocked by occupied space, inventory state, or pending input");
            return;
        }
        if (removal) {
            if (!world.getBlockState(target).isOf(Blocks.DIRT)) {
                fail("Only a journal-owned dirt support may be removed"); return;
            }
            if (!prepareMossMiningHand(Material.DIRT, world.getBlockState(target))) { return; }
            hit = verifiedHit(target, null);
            if (hit == null || predictionPending(world, target)
                    || !world.getBlockState(target).isOf(Blocks.DIRT)
                    || !MinecraftBackgroundBuildAccess.allowsWorldActions(client)) {
                fail("The owned support changed during clearing-tool preparation"); return;
            }
        } else {
            Material material = supportCandidate.material();
            if (!world.getBlockState(target).isAir() || !validPlacementAnchor(clicked, Direction.UP)) {
                fail("The validated support target or anchor changed before placement"); return;
            }
            if (!selectIntoHotbar(PlainInteractionItems.plainBlock(material))
                    || !PlainInteractionItems.plainBlock(material).test(player.getMainHandStack())) {
                needMaterials(supportMaterialRequirement()); return;
            }
            ItemPlacementContext placement = new ItemPlacementContext(player, Hand.MAIN_HAND, player.getMainHandStack(), hit);
            if (!placement.getBlockPos().equals(target) || !placement.canPlace()
                    || !player.canPlaceOn(target, hit.getSide(), player.getMainHandStack())
                    || !world.canPlace(ordinaryBlock(material).getDefaultState(), target, ShapeContext.absent())) {
                fail("Temporary support placement failed its exact target and collision checks"); return;
            }
        }
        aimAt(hit);
        try {
            // All navigation, stack selection, body checks and prediction checks precede durable intent.
            supportOperation = supports.controller.issue(supportCandidate, supportSnapshot());
            Material material = removal ? Material.DIRT : supportOperation.material();
            long before = removal ? inventoryCount(material) : supportOperation.inventoryBefore();
            if (removal) {
                if (preparedMossHoe != null) {
                    ItemStack hand = player.getMainHandStack();
                    if (!MinecraftClearingTools.usable(hand, world.getBlockState(target))) {
                        fail("The support-clearing tool changed before mining"); return;
                    }
                    ownedMossHoe = mossTools.begin(player.getInventory().getSelectedSlot(), preparedMossHoe,
                            mossToolIdentity(hand), mossToolDurability(hand)).orElse(null);
                    if (ownedMossHoe == null) { fail("The support-clearing tool has no owned wear allowance"); return; }
                }
                miningLease = OwnedBlockBreaking.acquire(manager);
                miningManager = manager;
                ownedMiningTarget = target.toImmutable();
            }
            // Bind tool wear and support intent before input, including instant breaks.
            interactionReceipt = new InteractionReceipt(world, player, target.toImmutable(), material,
                    removal ? Blocks.AIR : ordinaryBlock(material),
                    removal ? FlightInteractionConfirmation.forSupportRemoval(before,
                            player.getAbilities().creativeMode, MAX_SUPPORT_MINING_TICKS,
                            SUPPORT_ACKNOWLEDGEMENT_TICKS)
                            : new FlightInteractionConfirmation(before, true, player.getAbilities().creativeMode,
                                    Math.max(100, interactionCooldownTicks * 8)),
                    supportOperation, ownedMossHoe == null ? null : ownedMossHoe.charge());
            receiptResult = FlightInteractionConfirmation.Result.WAITING;
            mode = removal ? ExecutionMode.SUPPORT_MINING : ExecutionMode.SUPPORT_CONFIRMATION;
            if (removal) {
                ((ClientPlayerInteractionManagerAccessor) manager).supervisor$syncSelectedSlot();
                attackOwnedBlock(manager, target, hit.getSide());
            } else {
                interactOwnedBlock(player, Hand.MAIN_HAND, hit);
            }
            player.swingHand(Hand.MAIN_HAND);
            detail = "Waiting for " + supportOperation.kind() + " receipt at " + target.toShortString();
        } catch (IOException failure) { throw new UncheckedIOException(failure); }
    }

    private void tickSupportMining() {
        if (interactionReceipt == null) { tickSupportConfirmation(); return; }
        BlockPos target = interactionReceipt.target();
        ClientWorld world = Objects.requireNonNull(client.world, "world");
        if (!ClientChunkAvailability.isLoaded(world, target) || world.getBlockState(target).isAir()
                || miningManager == null || !miningManager.isBreakingBlock()
                || interactionReceipt.confirmation().miningBudgetExpired()) {
            cancelOwnedMining();
            mode = ExecutionMode.SUPPORT_CONFIRMATION;
            return;
        }
        if (!client.player.getAbilities().allowModifyWorld || client.player.isSpectator()
                || supportEntityConflict(world, client.player, new Box(target))
                || !world.getBlockState(target).isOf(Blocks.DIRT) || miningManager != client.interactionManager
                || miningLease == null || !OwnedBlockBreaking.isOwned(miningManager)) {
            fail("Owned support mining state changed"); return;
        }
        ItemStack hand = client.player.getMainHandStack();
        if (ownedMossHoe != null) {
            if (!MinecraftClearingTools.usable(hand, world.getBlockState(target))
                    || !mossTools.owns(ownedMossHoe, client.player.getInventory().getSelectedSlot(),
                    mossToolIdentity(hand), mossToolDurability(hand))) {
                fail("The owned support-clearing tool or selected slot changed"); return;
            }
        } else if (!hand.isEmpty() && !PlainInteractionItems.plainBlock(Material.DIRT).test(hand)) {
            fail("The harmless support-clearing hand changed"); return;
        }
        BlockHitResult hit = verifiedHit(target, null);
        if (hit == null) { fail("Owned support mining lost its exact ray: " + interactionRayDetail); return; }
        aimAt(hit);
        if (!advanceOwnedMining(miningManager, target, hit.getSide())) {
            cancelOwnedMining();
            mode = ExecutionMode.SUPPORT_CONFIRMATION;
            return;
        }
        client.player.swingHand(Hand.MAIN_HAND);
    }

    private static boolean supportEntityConflict(ClientWorld world, ClientPlayerEntity player, Box target) {
        boolean playerOverlap = player.getBoundingBox().intersects(target);
        // A removed upper support can leave a harmless drop above the lower owned support.
        return MossClearingEntityPolicy.hasConflict(playerOverlap,
                playerOverlap ? null : world.getOtherEntities(player, target.stretch(0, 2, 0)),
                entity -> new MossClearingEntityPolicy.Facts(entity.getClass() == ItemEntity.class,
                        entity.getType() == EntityType.ITEM, entity.canHit(), entity.isCollidable(player),
                        entity.hasVehicle(), entity.hasPassengers()));
    }

    private void tickSupportConfirmation() {
        if (receiptResult == FlightInteractionConfirmation.Result.CONFIRMED) {
            cancelOwnedMining();
            receiptResult = null;
            supportCandidate = null;
            progressMarker++;
            confirmedBlockProgressThisTick = true;
            cooldownTicks = interactionCooldownTicks;
            mode = ExecutionMode.SUPPORT_PREVIEW;
        } else if (receiptResult == FlightInteractionConfirmation.Result.RETRYABLE) {
            receiptResult = null;
            fail("The server rejected the bounded temporary support operation; ownership was preserved");
        }
    }

    private boolean validPlacementAnchor(BlockPos anchor, Direction face) {
        ClientWorld world = Objects.requireNonNull(client.world, "world");
        if (!ClientChunkAvailability.isLoaded(world, anchor)) { return false; }
        net.minecraft.block.BlockState state = world.getBlockState(anchor);
        return PlacementAnchorPolicy.permits(new PlacementAnchorPolicy.Facts(
                state.getBlock().getClass() == Block.class, state.isOf(Blocks.MOSS_BLOCK),
                state.isOf(Blocks.FARMLAND) && state.getBlock().getClass() == FarmlandBlock.class,
                state.isFullCube(world, anchor), state.hasBlockEntity(), state.isReplaceable(),
                !world.getFluidState(anchor).isEmpty()), face);
    }

    private void tickFlightOrdinaryNavigation() {
        BlockPosition target = ordinaryPlacements.get(flightTargetIndex).position();
        if (!chunkLoaded(Objects.requireNonNull(client.world, "world"), target)) {
            tickUnreceivedInteractionApproach(target);
            return;
        }
        if (ordinaryCorrect(flightTargetIndex)) {
            markFlightOrdinaryCorrect(flightTargetIndex);
            beginFlightOrdinarySelection();
            return;
        }
        flightNavigation.tick();
        detail = flightNavigation.detail();
        if (flightNavigation.failed()) {
            rejectFlightFace(flightNavigation.detail());
        } else if (flightNavigation.arrived()) {
            mode = ExecutionMode.FLIGHT_ORDINARY_READY;
        }
    }

    private void tickFlightOrdinaryReady() {
        if (ordinaryCorrect(flightTargetIndex)) {
            markFlightOrdinaryCorrect(flightTargetIndex);
            beginFlightOrdinarySelection();
            return;
        }
        // The click interval runs from the previous dispatch, through acknowledgement and movement.
        if (!ordinaryCadence.ready()) { return; }
        if (cooldownTicks > 0) { cooldownTicks--; return; }
        OrdinaryPlacement placement = ordinaryPlacements.get(flightTargetIndex);
        BlockPos target = toMinecraft(placement.position());
        ClientWorld world = Objects.requireNonNull(client.world, "world");
        ClientPlayerEntity player = Objects.requireNonNull(client.player, "player");
        if (!chunkLoaded(world, placement.position())) {
            beginChunkApproach(placement.position());
            return;
        }
        if (!world.getBlockState(target).isAir()) {
            if (MossClearingPolicy.allowsReplacement(placement, blockId(world.getBlockState(target).getBlock()))) {
                beginMossClearing(flightTargetIndex);
                return;
            }
            fail(occupiedTargetDetail(target));
            return;
        }
        if (!validPlacementAnchor(flightFace.anchor(), flightFace.face())) {
            rejectFlightFace("The previously selected anchor is no longer a valid solid placement support");
            return;
        }
        BlockHitResult hit = verifiedHit(flightFace.anchor(), flightFace.face());
        if (hit == null) {
            if (flightNavigation.retryInteractionArrival(interactionRayDetail)) {
                mode = ExecutionMode.FLIGHT_ORDINARY_NAVIGATING;
                detail = "Finding another exact placement approach: " + interactionRayDetail;
            } else {
                fail(flightNavigation.detail());
            }
            return;
        }
        Material material = placement.material();
        if (inventoryCount(material) < 1) {
            needMaterials(unavailableOrdinaryMaterials());
            return;
        }
        if (!selectIntoHotbar(material) || !stackPredicate(material).test(player.getMainHandStack())) {
            fail("The exact planned block could not be selected for flight placement");
            return;
        }
        ItemPlacementContext context = new ItemPlacementContext(player, Hand.MAIN_HAND,
                player.getMainHandStack(), hit);
        Block block = ordinaryBlock(material);
        if (!context.getBlockPos().equals(target) || !context.canPlace()
                || !player.canPlaceOn(target, hit.getSide(), player.getMainHandStack())
                || !world.canPlace(block.getDefaultState(), target, ShapeContext.absent())) {
            rejectFlightFace("Exact target, placement permission, or collision check rejected this face");
            return;
        }
        interactWithReceipt(hit, target, material, block, ExecutionMode.FLIGHT_ORDINARY_WAITING_CONFIRMATION);
    }

    private void rejectFlightFace(String reason) {
        unavailableFlightFaces.add(flightFace);
        String observed = "anchor=" + flightFace.anchor().toShortString() + "/" + flightFace.face() + ": " + reason;
        lastFlightFaceRejection = observed.substring(0, Math.min(observed.length(), 220));
        beginFlightOrdinarySelection();
    }

    private void tickFlightOrdinaryConfirmation() {
        if (receiptResult == FlightInteractionConfirmation.Result.CONFIRMED) {
            receiptResult = null;
            markFlightOrdinaryCorrect(flightTargetIndex);
            unavailableFlightFaces.clear();
            lastFlightFaceRejection = "";
            beginFlightOrdinarySelection();
            // Choose the next face in the confirming tick, as tilling and planting do.
            if (mode == ExecutionMode.FLIGHT_ORDINARY_SELECTING) { tickFlightOrdinarySelection(); }
        } else if (receiptResult == FlightInteractionConfirmation.Result.RETRYABLE) {
            receiptResult = null;
            retryInteraction(ExecutionMode.FLIGHT_ORDINARY_READY);
        }
    }

    private void beginMossClearing(int index) {
        OrdinaryPlacement placement = ordinaryPlacements.get(index);
        if (inventoryCount(placement.material()) < 1) {
            needMaterials(unavailableOrdinaryMaterials());
            return;
        }
        clearingTarget = null;
        clearingHandSlot = -1;
        String problem = mossClearingProblem(placement);
        if (!problem.isEmpty()) { fail(problem); return; }
        flightTargetIndex = index;
        BlockPos target = toMinecraft(placement.position());
        clearingTarget = new PlannedClearingTarget(placement,
                MinecraftBlockStates.toCore(Objects.requireNonNull(client.world, "world").getBlockState(target)));
        beginFlightRoute(target, null, target);
        mode = ExecutionMode.FLIGHT_CLEARING_NAVIGATION;
    }

    private void tickMossClearingNavigation() {
        OrdinaryPlacement placement = ordinaryPlacements.get(flightTargetIndex);
        ClientWorld world = Objects.requireNonNull(client.world, "world");
        BlockPos target = toMinecraft(placement.position());
        if (!ClientChunkAvailability.isLoaded(world, target)) {
            tickUnreceivedInteractionApproach(placement.position());
            return;
        }
        if (world.getBlockState(target).isAir() || ordinaryCorrect(flightTargetIndex)) {
            beginFlightOrdinarySelection();
            return;
        }
        String problem = mossClearingProblem(placement);
        if (!problem.isEmpty()) { fail(problem); return; }
        flightNavigation.tick();
        detail = "Approaching planned block replacement: " + flightNavigation.detail();
        if (flightNavigation.failed()) { fail(detail); return; }
        if (!flightNavigation.arrived()) { return; }
        BlockHitResult hit = verifiedHit(target, null);
        if (hit == null) {
            if (flightNavigation.retryInteractionArrival(interactionRayDetail)) {
                detail = "Finding another exact clearing approach: " + interactionRayDetail;
            } else {
                fail(flightNavigation.detail());
            }
            return;
        }
        if (!prepareMossMiningHand(placement.material(), world.getBlockState(target))) { return; }
        ClientPlayerEntity player = Objects.requireNonNull(client.player, "player");
        ClientPlayerInteractionManager manager = Objects.requireNonNull(client.interactionManager, "interactionManager");
        // Repair and item selection can defer this tick; recheck every target and input guard before START.
        problem = mossClearingProblem(placement);
        hit = verifiedHit(target, null);
        if (!problem.isEmpty() || hit == null) {
            fail(!problem.isEmpty() ? problem : "Planned clearing lost its exact ray: " + interactionRayDetail);
            return;
        }
        if (!player.getAbilities().allowModifyWorld || player.isSpectator()
                || manager.isBreakingBlock() || predictionPending(world, target)
                || interactionReceipt != null || !MinecraftBackgroundBuildAccess.allowsWorldActions(client)) {
            fail("Planned clearing cannot begin while modification is unavailable or another break is pending");
            return;
        }
        if (preparedMossHoe != null) {
            ItemStack hand = player.getMainHandStack();
            if (!usableMossHoe(hand, world.getBlockState(target))) {
                fail("The selected clearing tool changed before mining");
                return;
            }
            ownedMossHoe = mossTools.begin(player.getInventory().getSelectedSlot(), preparedMossHoe,
                    mossToolIdentity(hand), mossToolDurability(hand)).orElse(null);
            if (ownedMossHoe == null) {
                fail("The selected clearing tool has no safe wear allowance");
                return;
            }
        }
        miningLease = OwnedBlockBreaking.acquire(manager);
        miningManager = manager;
        ownedMiningTarget = target.toImmutable();
        clearingHandSlot = player.getInventory().getSelectedSlot();
        aimAt(hit);
        interactionReceipt = new InteractionReceipt(world, player, target.toImmutable(), placement.material(),
                Blocks.AIR, new FlightInteractionConfirmation(inventoryCount(placement.material()),
                        false, player.getAbilities().creativeMode, clearingTarget.tickBudget()),
                null, ownedMossHoe == null ? null : ownedMossHoe.charge());
        receiptResult = FlightInteractionConfirmation.Result.WAITING;
        mode = clearingTarget.lightReplacement() ? ExecutionMode.FLIGHT_CLEARING_LIGHT : ExecutionMode.FLIGHT_CLEARING_MOSS;
        mossPickupReselection = new PlainInteractionItems.MossPickupReselection();
        // Cancelled targets remain cached; each new attempt needs START after exact selected-slot sync.
        ((ClientPlayerInteractionManagerAccessor) manager).supervisor$syncSelectedSlot();
        if (!attackOwnedBlock(manager, target, hit.getSide())) {
            fail("The planned clearing attack was rejected");
            return;
        }
        player.swingHand(Hand.MAIN_HAND);
        detail = "Clearing " + clearingTarget.observedState().blockId()
                + " only at planned replacement " + target.toShortString();
        latchMossClearingCompletion(world, target);
    }

    private void tickMossClearing() {
        if (receiptResult != FlightInteractionConfirmation.Result.WAITING) {
            tickMossClearingConfirmation();
            return;
        }
        OrdinaryPlacement placement = ordinaryPlacements.get(flightTargetIndex);
        ClientWorld world = Objects.requireNonNull(client.world, "world");
        BlockPos target = toMinecraft(placement.position());
        if (!ClientChunkAvailability.isLoaded(world, target)) {
            cancelOwnedMining();
            mode = ExecutionMode.FLIGHT_CLEARING_CONFIRMATION;
            return;
        }
        if (!world.getBlockState(target).isAir() && (clearingTarget == null
                || !clearingTarget.matches(placement, MinecraftBlockStates.toCore(world.getBlockState(target))))) {
            fail("The exact planned clearing target changed during mining");
            return;
        }
        if (latchMossClearingCompletion(world, target)) { return; }
        String problem = mossClearingProblem(placement);
        if (!problem.isEmpty()) { fail(problem); return; }
        ClientPlayerEntity player = Objects.requireNonNull(client.player, "player");
        BlockHitResult hit = verifiedHit(target, null);
        if (hit == null || miningManager != client.interactionManager || miningLease == null
                || !OwnedBlockBreaking.isOwned(miningManager)) {
            fail("The exact planned clearing interaction is no longer in reach: " + interactionRayDetail);
            return;
        }
        Predicate<ItemStack> plainReplacement = PlainInteractionItems.plainBlock(placement.material());
        if (ownedMossHoe != null) {
            ItemStack hand = player.getMainHandStack();
            if (!usableMossHoe(hand, world.getBlockState(target))
                    || !mossTools.owns(ownedMossHoe, player.getInventory().getSelectedSlot(),
                    mossToolIdentity(hand), mossToolDurability(hand))) {
                fail("The owned clearing tool or selected slot changed during mining");
                return;
            }
        } else if (!player.getMainHandStack().isEmpty() && !plainReplacement.test(player.getMainHandStack())) {
            PlayerInventory inventory = player.getInventory();
            OptionalInt replacement = mossPickupReselection.chooseSlot(
                    ItemStack.areItemsAndComponentsEqual(player.getMainHandStack(), new ItemStack(Items.MOSS_BLOCK)),
                    PlayerInventory.getHotbarSize(), index -> plainReplacement.test(inventory.getStack(index)));
            if (replacement.isEmpty()) {
                fail("The plain moss-clearing item changed");
                return;
            }
            inventory.setSelectedSlot(replacement.getAsInt());
            if (!plainReplacement.test(player.getMainHandStack())) {
                fail("The plain moss-clearing replacement is unavailable");
                return;
            }
            // Preserve the owned attempt and receipt deadline while synchronizing the replacement hand.
            ((ClientPlayerInteractionManagerAccessor) miningManager).supervisor$syncSelectedSlot();
        }
        aimAt(hit);
        if (!advanceOwnedMining(miningManager, target, hit.getSide())) {
            fail("The server did not accept continued planned clearing");
            return;
        }
        player.swingHand(Hand.MAIN_HAND);
        latchMossClearingCompletion(world, target);
    }

    private boolean latchMossClearingCompletion(ClientWorld world, BlockPos target) {
        if (miningManager != null && miningManager.isBreakingBlock()
                && !world.getBlockState(target).isAir()) { return false; }
        // Latch before a later correction can restore the obstruction and revive the cached break.
        cancelOwnedMining();
        mode = ExecutionMode.FLIGHT_CLEARING_CONFIRMATION;
        detail = "Waiting for the server to acknowledge the cleared replacement target";
        return true;
    }

    private void tickMossClearingConfirmation() {
        if (receiptResult == FlightInteractionConfirmation.Result.CONFIRMED) {
            cancelOwnedMining();
            receiptResult = null;
            progressMarker++;
            confirmedBlockProgressThisTick = true;
            // Only a confirmed world change makes previously rejected approach faces worth retrying.
            unavailableFlightFaces.clear();
            lastFlightFaceRejection = "";
            cooldownTicks = interactionCooldownTicks;
            beginFlightOrdinarySelection();
        } else if (receiptResult == FlightInteractionConfirmation.Result.RETRYABLE) {
            receiptResult = null;
            fail("The server did not confirm the cleared replacement target within the bounded attempt");
        }
    }

    private String mossClearingProblem(OrdinaryPlacement placement) {
        ClientWorld world = Objects.requireNonNull(client.world, "world");
        ClientPlayerEntity player = Objects.requireNonNull(client.player, "player");
        BlockPos target = toMinecraft(placement.position());
        boolean received = ClientChunkAvailability.isLoaded(world, target);
        net.minecraft.block.BlockState state = received ? world.getBlockState(target) : null;
        if (clearingTarget != null && (state == null
                || !clearingTarget.matches(placement, MinecraftBlockStates.toCore(state)))) {
            return "The exact planned clearing target changed before interaction";
        }
        Box occupied = new Box(target);
        Box supportBox = occupied.stretch(0, 2, 0);
        boolean playerOverlap = player.getBoundingBox().intersects(occupied);
        List<Entity> occupants = playerOverlap ? null : world.getOtherEntities(player, supportBox);
        boolean entityConflict = MossClearingEntityPolicy.hasConflict(playerOverlap, occupants,
                entity -> new MossClearingEntityPolicy.Facts(entity.getClass() == ItemEntity.class,
                        entity.getType() == EntityType.ITEM, entity.canHit(), entity.isCollidable(player),
                        entity.hasVehicle(), entity.hasPassengers()));
        String rejection = MossClearingPolicy.rejection(buildVolume, order, new MossClearingPolicy.Observation(
                placement.position(), state == null ? "unknown" : blockId(state.getBlock()),
                received, state != null && state.hasBlockEntity(),
                received && !world.getFluidState(target).isEmpty(),
                entityConflict,
                inventoryCount(placement.material()) > 0));
        if (!rejection.isEmpty()) {
            try {
                captureMossObstruction(placement, world, player, received, state, occupied, supportBox,
                        playerOverlap, occupants, rejection);
            } catch (RuntimeException ignored) {
                // A diagnostic failure must preserve the original clearing rejection.
            }
        }
        return rejection;
    }

    private void captureMossObstruction(OrdinaryPlacement placement, ClientWorld world, ClientPlayerEntity player,
                                        boolean received, net.minecraft.block.BlockState state,
                                        Box occupied, Box supportBox, boolean playerOverlap,
                                        List<Entity> occupants, String rejection) {
        Instant capturedAt = Instant.now();
        ExecutionObservation.Target target = new ExecutionObservation.Target(
                placement.position(), placement.state().blockId(), null, received);
        List<ExecutionObstruction.EntitySample> samples = new ArrayList<>();
        Integer totalEntities = occupants == null ? null : occupants.size();
        Boolean supportOverlap = null;
        String error = "";
        try {
            target = new ExecutionObservation.Target(placement.position(), placement.state().blockId(),
                    received && state != null ? blockId(state.getBlock()) : null, received);
            if (received) {
                supportOverlap = player.getBoundingBox().intersects(supportBox);
                if (occupants == null) { occupants = world.getOtherEntities(player, supportBox); }
                totalEntities = occupants.size();
                for (int index = 0; index < Math.min(occupants.size(), ExecutionObstruction.MAX_ENTITY_SAMPLES); index++) {
                    Entity entity = occupants.get(index);
                    Box entityBox = entity.getBoundingBox();
                    ItemStack item = entity instanceof ItemEntity drop ? drop.getStack() : null;
                    samples.add(new ExecutionObstruction.EntitySample(
                            Registries.ENTITY_TYPE.getId(entity.getType()).toString(),
                            entity instanceof LivingEntity, entity instanceof PlayerEntity,
                            entityBox.intersects(occupied), entityBox.intersects(supportBox),
                            item == null ? null : Registries.ITEM.getId(item.getItem()).toString(),
                            item == null ? null : item.getCount()));
                }
            }
        } catch (RuntimeException failure) {
            // Optional diagnostics never change the rejection or expose entity labels through exception text.
            error = "Obstruction details unavailable: " + failure.getClass().getSimpleName();
        }
        obstructionHistory.capture(new ExecutionObstruction(capturedAt, mode.name(), rejection, target,
                world == client.world && player == client.player, null, playerOverlap, supportOverlap,
                received ? totalEntities : null, samples,
                received && totalEntities != null && totalEntities > samples.size(), error),
                world, player);
    }

    private boolean selectHarmlessMiningStack(Material replacementMaterial) {
        PlayerInventory inventory = Objects.requireNonNull(client.player, "player").getInventory();
        OptionalInt hotbarSlot = PlainInteractionItems.findHotbarSlot(inventory, replacementMaterial);
        if (hotbarSlot.isPresent()) {
            inventory.setSelectedSlot(hotbarSlot.getAsInt());
            return true;
        }
        Predicate<ItemStack> matches = PlainInteractionItems.plainBlock(replacementMaterial);
        return selectIntoHotbar(matches)
                && matches.test(Objects.requireNonNull(client.player, "player").getMainHandStack());
    }

    private boolean prepareMossMiningHand(Material replacementMaterial, net.minecraft.block.BlockState moss) {
        // Only an actual selection captures diagnostics; a pending repair does not select again.
        MossSelectionCapture capture = pendingMossHoeRepair == null ? captureMossSelection(moss) : null;
        BitSet evaluated = new BitSet(MossToolSelection.MAX_CANDIDATES);
        MossMiningToolGuard.Diagnostic[] evaluatedGuards = new MossMiningToolGuard.Diagnostic[MossToolSelection.MAX_CANDIDATES];
        boolean ready = false;
        try {
            ready = prepareMossMiningHand(replacementMaterial, moss, evaluated, evaluatedGuards);
            return ready;
        } finally {
            finishMossSelection(capture, evaluated, evaluatedGuards, ready);
        }
    }

    private boolean prepareMossMiningHand(Material replacementMaterial, net.minecraft.block.BlockState moss,
                                        BitSet evaluated, MossMiningToolGuard.Diagnostic[] evaluatedGuards) {
        ClientPlayerEntity player = Objects.requireNonNull(client.player, "player");
        PlayerInventory inventory = player.getInventory();
        boolean miningWasOwned = ownedMossHoe != null || miningLease != null;
        preparedMossHoe = null;
        if (ownedMossHoe != null && (interactionReceipt == null
                || interactionReceipt.mossCharge() != ownedMossHoe.charge())) {
            mossTools.settleCharge(ownedMossHoe.charge(), MossMiningToolGuard.ChargeOutcome.UNKNOWN);
        }
        ownedMossHoe = null;
        if (pendingMossHoeRepair != null) {
            detail = "Waiting for the original moss-clearing hoe repair receipt.";
            return false;
        }
        boolean hoeCustodyAllowed = mossCustody == null || mossCustody.allowsHoeStart();
        // Rank the matching tool family by effective speed; recover only into an empty hotbar slot.
        for (int slot : hoeCustodyAllowed ? MinecraftClearingTools.rankedSlots(player, moss) : List.<Integer>of()) {
            if (slot < MossToolSelection.MAX_CANDIDATES) { evaluated.set(slot); }
            ItemStack stack = inventory.getStack(slot);
            if (!usableMossHoe(stack, moss)) { continue; }
            if (slot < evaluatedGuards.length) {
                try {
                    evaluatedGuards[slot] = mossTools.diagnostic(mossToolIdentity(stack), mossToolDurability(stack));
                } catch (RuntimeException unavailable) {
                    // Guard diagnostics cannot change the original admission or selection decision.
                }
            }
            var tracked = mossTools.track(mossToolIdentity(stack), mossToolDurability(stack));
            if (tracked.isEmpty()) { continue; }
            var tool = tracked.orElseThrow();
            MossMiningToolGuard.Preparation preparation = tool.prepare(mossToolDurability(stack),
                    hoeRepair != null && hoeRepair.enabled());
            if (preparation == MossMiningToolGuard.Preparation.WAIT_REPAIR) {
                detail = "Waiting for the original moss-clearing hoe repair receipt.";
                return false;
            }
            if (preparation == MossMiningToolGuard.Preparation.USE_PLAIN_HAND) { continue; }
            int selectedSlot = slot;
            if (slot >= PlayerInventory.getHotbarSize()) {
                OptionalInt destination = MinecraftBackgroundBuildAccess.hotbarRecoveryDestination(client,
                        pendingMossHoeRepair != null || hoeRepair != null && hoeRepair.pending(),
                        miningWasOwned || interactionReceipt != null
                                || Objects.requireNonNull(client.interactionManager, "interactionManager").isBreakingBlock());
                if (destination.isEmpty()) { continue; }
                ItemStack expected = stack.copy();
                Predicate<ItemStack> exactTool = candidate -> ItemStack.areEqual(candidate, expected);
                selectedSlot = destination.getAsInt();
                try {
                    if (!transferIntoHotbar(slot, selectedSlot, exactTool, true)) { continue; }
                } catch (HotbarTransferDeferred unavailable) {
                    // The optimization never delays an otherwise safe plain-hand attempt.
                    continue;
                }
                if (!exactTool.test(player.getMainHandStack())) {
                    fail("The recovered clearing tool changed during hotbar transfer");
                    return false;
                }
            } else {
                inventory.setSelectedSlot(slot);
            }
            if (preparation == MossMiningToolGuard.Preparation.REPAIR) {
                HoeRepairSession.Status repair = hoeRepair.beforeUse();
                if (repair != HoeRepairSession.Status.READY) {
                    if (hoeRepair.pending()) {
                        String operation = hoeRepair.pendingOperationId();
                        tool.awaitRepair(operation);
                        pendingMossHoeRepair = new MossHoeRepair(selectedSlot, tool, operation);
                    }
                    detail = hoeRepair.detail();
                    // Arrival already released owned velocity. Keep ARRIVED while /fix settles.
                    return false;
                }
            }
            ItemStack selected = player.getMainHandStack();
            if (usableMossHoe(selected, moss)
                    && (mossCustody == null || mossCustody.allowsHoeStart())
                    && mossTools.matches(tool, mossToolIdentity(selected), mossToolDurability(selected))
                    && tool.canStart(mossToolDurability(selected))) {
                preparedMossHoe = tool;
                return true;
            }
            if (slot >= PlayerInventory.getHotbarSize()) { break; }
        }
        if (selectHarmlessMiningStack(replacementMaterial)) { return true; }
        fail("Clearing needs a safe matching tool, an empty hand, or a plain planned replacement block");
        return false;
    }

    private MossSelectionCapture captureMossSelection(net.minecraft.block.BlockState moss) {
        try {
            ClientPlayerEntity player = client.player;
            if (player == null || client.world == null || flightTargetIndex < 0
                    || flightTargetIndex >= ordinaryPlacements.size()) { return null; }
            List<MossToolSelection.Candidate> candidates = new ArrayList<>();
            for (int slot = 0; slot < MossToolSelection.MAX_CANDIDATES; slot++) {
                ItemStack stack = player.getInventory().getStack(slot);
                if (!MinecraftClearingTools.supportedFamily(stack)) { continue; }
                String item = Registries.ITEM.getId(stack.getItem()).toString();
                try {
                    var component = stack.get(DataComponentTypes.TOOL);
                    float speed = stack.getMiningSpeedMultiplier(moss);
                    boolean usable = usableMossHoe(stack, moss);
                    ItemStack identity = mossToolIdentity(stack);
                    var guard = usable ? mossTools.diagnostic(identity, mossToolDurability(stack)) : null;
                    candidates.add(new MossToolSelection.Candidate(slot, item, stack.getCount(),
                            stack.contains(DataComponentTypes.DAMAGE) ? stack.getDamage() : null,
                            stack.contains(DataComponentTypes.MAX_DAMAGE) ? stack.getMaxDamage() : null,
                            stack.contains(DataComponentTypes.UNBREAKABLE), usable,
                            component == null ? null : component.damagePerBlock(), Float.isFinite(speed) ? speed : null,
                            guard, MinecraftMossToolFingerprint.capture(identity), false, "")
                            .withMetadataEvidence(captureMossMetadata(slot, stack)));
                } catch (RuntimeException unavailable) {
                    candidates.add(new MossToolSelection.Candidate(slot, item, stack.getCount(), null, null,
                            null, null, null, null, null, null, false, "CANDIDATE_UNAVAILABLE"));
                }
            }
            return new MossSelectionCapture(Instant.now(), ordinaryPlacements.get(flightTargetIndex).position(),
                    client.world, player, mossTools.trackedIdentityCount(), hoeRepair != null && hoeRepair.enabled(), candidates);
        } catch (RuntimeException unavailable) {
            // Diagnostic failures must never alter the original selection or expose exception text.
            return null;
        }
    }

    private void finishMossSelection(MossSelectionCapture capture, BitSet evaluated,
                                     MossMiningToolGuard.Diagnostic[] evaluatedGuards, boolean ready) {
        if (capture == null) { return; }
        try {
            boolean contextMatches = capture.world() == client.world && capture.player() == client.player;
            Integer selectedSlot = contextMatches ? client.player.getInventory().getSelectedSlot() : null;
            String selectedItem = contextMatches ? Registries.ITEM.getId(client.player.getMainHandStack().getItem()).toString() : null;
            String outcome = ready ? preparedMossHoe == null ? "PLAIN_HAND"
                    : MinecraftClearingTools.family(client.player.getMainHandStack()).name()
                    : mode == ExecutionMode.FAILED ? "FAILED" : "DEFERRED";
            mossToolSelectionHistory.capture(new MossToolSelection(capture.capturedAt(), capture.target(), contextMatches,
                    outcome, selectedSlot, selectedItem, capture.identitiesBefore(), mossTools.trackedIdentityCount(),
                    mossTools.identityCap(), capture.repairEnabled(), capture.candidates().stream()
                    .map(candidate -> candidate.withEvaluation(evaluated.get(candidate.slot()),
                            evaluatedGuards[candidate.slot()])).toList(), false,
                    contextMatches ? "" : "CONTEXT_CHANGED"), capture.world(), capture.player());
        } catch (RuntimeException unavailable) {
            // A historical optional snapshot cannot change mining, selection, repair, or receipt handling.
        }
    }

    private record MossSelectionCapture(Instant capturedAt, BlockPosition target, ClientWorld world,
                                        ClientPlayerEntity player, int identitiesBefore, boolean repairEnabled,
                                        List<MossToolSelection.Candidate> candidates) { }

    private MossToolSelection.MetadataEvidence captureMossMetadata(int slot, ItemStack current) {
        MossToolMetadataProbe.Snapshot currentProbe = null;
        try {
            currentProbe = MossToolMetadataProbe.capture(current.get(DataComponentTypes.CUSTOM_DATA), current.getDamage());
            var latest = ServerPlayerInventoryObserver.latest(client, slot);
            if (latest.isEmpty()) { return new MossToolSelection.MetadataEvidence(currentProbe, null, ""); }
            var update = latest.orElseThrow();
            ItemStack applied = update.stack();
            var appliedProbe = MossToolMetadataProbe.capture(applied.get(DataComponentTypes.CUSTOM_DATA), applied.getDamage());
            var receipt = new MossToolSelection.AppliedMetadata(update.stamp().epoch(), update.stamp().sequence(),
                    update.slot(), applied.contains(DataComponentTypes.DAMAGE) ? applied.getDamage() : null,
                    ItemStack.areEqual(applied, current), appliedProbe,
                    mossMetadataHistory.observe(client.world, client.player, client.getNetworkHandler(), update));
            return new MossToolSelection.MetadataEvidence(currentProbe, receipt, "");
        } catch (RuntimeException unavailable) {
            // Historical optional metadata cannot alter selection, packet state, or wear accounting.
            return new MossToolSelection.MetadataEvidence(currentProbe, null, "METADATA_UNAVAILABLE");
        }
    }

    private static boolean sameMossOtherComponents(ItemStack before, ItemStack after) {
        ItemStack left = mossToolIdentity(before);
        ItemStack right = mossToolIdentity(after);
        // Observational comparison only; admission still compares all non-damage custom data exactly.
        left.remove(DataComponentTypes.CUSTOM_DATA);
        right.remove(DataComponentTypes.CUSTOM_DATA);
        return ItemStack.areEqual(left, right);
    }

    private void settleMossHoeRepairAllowance(HoeRepairSession.Status repair) {
        if (pendingMossHoeRepair == null || repair != HoeRepairSession.Status.READY || hoeRepair == null) { return; }
        MossHoeRepair pending = pendingMossHoeRepair;
        boolean credited = false;
        try {
            ItemStack stack = client.player.getInventory().getStack(pending.slot());
            credited = MinecraftClearingTools.admitted(stack)
                    && mossTools.matches(pending.tool(), mossToolIdentity(stack), mossToolDurability(stack))
                    && pending.operationId().equals(hoeRepair.confirmedOperationForTool(pending.slot()))
                    && pending.tool().acceptRepair(pending.operationId(), mossToolDurability(stack));
        } catch (RuntimeException unavailable) {
            // The durable repair already settled; unavailable optional evidence grants no extra wear.
        }
        if (!credited) { pending.tool().abandonCompletedRepair(repair, pending.operationId()); }
        // Settle before target skipping, order transitions, or tilling can use the repaired hoe.
        pendingMossHoeRepair = null;
    }

    private static boolean usableMossHoe(ItemStack stack, net.minecraft.block.BlockState moss) {
        return MinecraftClearingTools.usable(stack, moss);
    }

    private static ItemStack mossToolIdentity(ItemStack stack) {
        ItemStack identity = stack.copy();
        // Normalize only the copied identity; live tool damage is changed exclusively by the server.
        var customData = stack.get(DataComponentTypes.CUSTOM_DATA);
        if (customData != null) {
            identity.set(DataComponentTypes.CUSTOM_DATA, LegacyToolDamageIdentity.normalize(customData, stack.getDamage()));
        }
        identity.set(DataComponentTypes.DAMAGE, 0);
        return identity;
    }

    private static MossMiningToolGuard.Durability mossToolDurability(ItemStack stack) {
        return new MossMiningToolGuard.Durability(stack.getDamage(), stack.getMaxDamage(),
                stack.contains(DataComponentTypes.UNBREAKABLE));
    }

    private record MossHoeRepair(int slot, MossMiningToolGuard.TrackedTool<ItemStack> tool, String operationId) { }

    private void cancelOwnedMining() {
        if (interactionReceipt != null && interactionReceipt.supportOperation() != null
                && interactionReceipt.supportOperation().kind().removes()) {
            interactionReceipt.confirmation().finishMining();
        }
        if (ownedMossHoe != null && (interactionReceipt == null
                || interactionReceipt.mossCharge() != ownedMossHoe.charge())) {
            mossTools.settleCharge(ownedMossHoe.charge(), MossMiningToolGuard.ChargeOutcome.UNKNOWN);
        }
        ownedMossHoe = null;
        preparedMossHoe = null;
        BlockPos target = ownedMiningTarget;
        ownedMiningTarget = null;
        if (miningLease == null) { return; }
        miningLease.close();
        miningLease = null;
        ClientPlayerInteractionManager manager = miningManager;
        miningManager = null;
        if (manager != null && manager == client.interactionManager && client.player != null && client.world != null) {
            if (mossCustody != null && target != null) {
                mossCustody.mining(target, manager::cancelBlockBreaking);
            } else {
                manager.cancelBlockBreaking();
            }
        }
    }

    private boolean attackOwnedBlock(ClientPlayerInteractionManager manager, BlockPos target, Direction face) {
        return mossCustody == null ? manager.attackBlock(target, face)
                : mossCustody.mining(target, () -> manager.attackBlock(target, face));
    }

    private boolean advanceOwnedMining(ClientPlayerInteractionManager manager, BlockPos target, Direction face) {
        return mossCustody == null ? manager.updateBlockBreakingProgress(target, face)
                : mossCustody.mining(target, () -> manager.updateBlockBreakingProgress(target, face));
    }

    private ActionResult interactOwnedBlock(ClientPlayerEntity player, Hand hand, BlockHitResult hit) {
        ClientPlayerInteractionManager manager = Objects.requireNonNull(client.interactionManager, "interactionManager");
        return mossCustody == null ? manager.interactBlock(player, hand, hit)
                : mossCustody.interactBlock(hand, hit, () -> manager.interactBlock(player, hand, hit));
    }

    private void aimAt(BlockHitResult hit) {
        ClientPlayerEntity player = Objects.requireNonNull(client.player, "player");
        Vec3d aim = hit.getPos().subtract(player.getEyePos());
        player.setYaw((float) (Math.toDegrees(Math.atan2(aim.z, aim.x)) - 90.0));
        player.setPitch((float) -Math.toDegrees(Math.atan2(aim.y, Math.sqrt(aim.x * aim.x + aim.z * aim.z))));
    }

    private void markFlightOrdinaryCorrect(int index) {
        if (!correctTargets.get(index)) {
            correctTargets.set(index);
            progressMarker++;
            confirmedBlockProgressThisTick = true;
        }
    }

    private BlockHitResult verifiedHit(BlockPos clicked, Direction requiredFace) {
        return verifiedHit(clicked, requiredFace, false);
    }

    private BlockHitResult verifiedHit(BlockPos clicked, Direction requiredFace, boolean tillTopFaceAim) {
        ClientPlayerEntity player = Objects.requireNonNull(client.player, "player");
        ClientWorld world = Objects.requireNonNull(client.world, "world");
        ExactInteractionRay.Result result = tillTopFaceAim
                ? ExactInteractionRay.traceTillTopFace(world, player, player.getEyePos(), clicked, interactionReach())
                : ExactInteractionRay.trace(world, player,
                        player.getEyePos(), clicked, requiredFace, interactionReach());
        interactionRayDetail = result.detail();
        if (!result.accepted()) { return null; }
        if (!player.canInteractWithBlockAt(clicked, 0.0)) {
            interactionRayDetail = "The player cannot interact with target " + clicked.toShortString()
                    + " at the current position, despite a clear outline ray";
            return null;
        }
        return result.hit();
    }

    private void interactWithReceipt(BlockHitResult hit, BlockPos target, Material material,
            Block expectedBlock, ExecutionMode waitingMode) {
        ClientPlayerEntity player = Objects.requireNonNull(client.player, "player");
        ClientWorld world = Objects.requireNonNull(client.world, "world");
        if (!ClientChunkAvailability.isLoaded(world, target)
                || !ClientChunkAvailability.isLoaded(world, hit.getBlockPos())) {
            fail("The interaction target or support chunk has not been received; no click was sent");
            return;
        }
        if (interactionReceipt != null || predictionPending(world, target)) {
            fail("A previous interaction prediction is still outstanding at the planned target");
            return;
        }
        long before = inventoryCount(material);
        Vec3d aim = hit.getPos().subtract(player.getEyePos());
        player.setYaw((float) (Math.toDegrees(Math.atan2(aim.z, aim.x)) - 90.0));
        player.setPitch((float) -Math.toDegrees(Math.atan2(aim.y,
                Math.sqrt(aim.x * aim.x + aim.z * aim.z))));
        if (order instanceof WorkOrder.Till && waitingMode == ExecutionMode.MANUAL_WAITING_CONFIRMATION) {
            tillCadence.dispatched();
        }
        if (order instanceof WorkOrder.Plant && waitingMode == ExecutionMode.MANUAL_WAITING_CONFIRMATION) {
            plantCadence.dispatched();
        }
        if (waitingMode == ExecutionMode.FLIGHT_ORDINARY_WAITING_CONFIRMATION) {
            ordinaryCadence.dispatched();
        }
        ActionResult action = interactOwnedBlock(player, Hand.MAIN_HAND, hit);
        interactionAttempts++;
        if (!action.isAccepted()) {
            retryInteraction(waitingMode == ExecutionMode.MANUAL_WAITING_CONFIRMATION
                    ? ExecutionMode.MANUAL_READY : ExecutionMode.FLIGHT_ORDINARY_READY);
            return;
        }
        player.swingHand(Hand.MAIN_HAND);
        interactionReceipt = new InteractionReceipt(world, player, target.toImmutable(), material,
                expectedBlock, new FlightInteractionConfirmation(before, material != Material.HOE,
                        player.getAbilities().creativeMode, Math.max(100, interactionCooldownTicks * 8)));
        receiptResult = FlightInteractionConfirmation.Result.WAITING;
        mode = waitingMode;
        detail = "Waiting for server acknowledgement at " + target.toShortString();
    }

    private void settleInteractionReceipt(boolean advanceDeadline) {
        InteractionReceipt receipt = interactionReceipt;
        if (receipt == null) { return; }
        if (client.world != receipt.world() || client.player != receipt.player()) {
            receiptFailure = "Player or world changed with an outstanding interaction; reconciliation is required";
            if (receipt.supportOperation() != null) {
                supports.controller.uncertain(receipt.supportOperation(), receiptFailure);
            }
            captureLastReceipt(receipt, receiptFailure);
            settleMossCharge(receipt, false);
            interactionReceipt = null;
            return;
        }
        try {
            boolean loaded = ClientChunkAvailability.isLoaded(receipt.world(), receipt.target());
            receiptResult = receipt.confirmation().observe(
                    !loaded || predictionPending(receipt.world(), receipt.target()),
                    loaded && receipt.world().getBlockState(receipt.target()).isOf(receipt.expectedBlock()),
                    inventoryCount(receipt.material()), advanceDeadline);
            if (receiptResult == FlightInteractionConfirmation.Result.WAITING) { return; }
            captureLastReceipt(receipt, "");
            settleMossCharge(receipt, receiptResult == FlightInteractionConfirmation.Result.CONFIRMED);
            if (receipt.supportOperation() != null) {
                settleSupportReceipt(receipt);
                return;
            }
            interactionReceipt = null;
            if (receiptResult == FlightInteractionConfirmation.Result.CONFIRMED) {
                long consumed = receipt.confirmation().consumed();
                if (consumed > 0) { addConsumed(receipt.material(), consumed); }
            } else if (receiptResult == FlightInteractionConfirmation.Result.UNCERTAIN) {
                receiptFailure = "The server acknowledgement, target state, and inventory did not agree at "
                        + receipt.target().toShortString() + "; reconciliation is required before another click";
            }
        } catch (RuntimeException exception) {
            receiptFailure = "Interaction confirmation failed: " + errorDetail(exception);
            if (receipt.supportOperation() != null) {
                supports.controller.uncertain(receipt.supportOperation(), receiptFailure);
            }
            captureLastReceipt(receipt, receiptFailure);
            settleMossCharge(receipt, false);
            interactionReceipt = null;
        }
    }

    private void settleMossCharge(InteractionReceipt receipt, boolean confirmed) {
        if (receipt.mossCharge() != null) {
            mossTools.settleCharge(receipt.mossCharge(), confirmed
                    ? MossMiningToolGuard.ChargeOutcome.CONFIRMED : MossMiningToolGuard.ChargeOutcome.UNKNOWN);
        }
    }

    private void settleSupportReceipt(InteractionReceipt receipt) {
        try {
            TemporarySupportController.Settlement settlement;
            if (receiptResult == FlightInteractionConfirmation.Result.CONFIRMED) {
                settlement = supports.controller.acknowledge(receipt.supportOperation(), supportSnapshot());
            } else if (receiptResult == FlightInteractionConfirmation.Result.RETRYABLE) {
                settlement = supports.controller.reject(receipt.supportOperation(), supportSnapshot());
            } else {
                supports.controller.uncertain(receipt.supportOperation(), "The bounded support receipt is uncertain");
                settlement = new TemporarySupportController.Settlement(TemporarySupportController.Outcome.UNCERTAIN);
            }
            if (settlement.outcome() == TemporarySupportController.Outcome.WAITING) { return; }
            interactionReceipt = null;
            if (settlement.outcome() == TemporarySupportController.Outcome.UNCERTAIN) {
                receiptFailure = "Temporary support ownership could not be confirmed; reconciliation is required";
            } else {
                supportOperation = null;
            }
            // Temporary placements/removals never enter pendingConsumed. The planned starter's
            // durable outbox is credited by the core before acknowledging its journal identity.
        } catch (IOException failure) { throw new UncheckedIOException(failure); }
    }

    private static boolean predictionPending(ClientWorld world, BlockPos target) {
        var manager = ((ClientWorldPendingUpdatesAccessor) world).supervisor$getPendingUpdateManager();
        return ((PendingBlockUpdatesAccessor) manager).supervisor$getPendingBlockUpdates()
                .containsKey(target.asLong());
    }

    private void retryInteraction(ExecutionMode readyMode) {
        if (interactionAttempts >= MAX_INTERACTION_ATTEMPTS) {
            fail("The deterministic interaction failed after bounded attempts");
        } else {
            cooldownTicks = interactionCooldownTicks;
            mode = readyMode;
        }
    }

    private static Block ordinaryBlock(Material material) {
        return MinecraftMaterials.block(material);
    }

    private record FlightPlacementFace(int index, BlockPos anchor, Direction face) { }

    private record InteractionReceipt(ClientWorld world, ClientPlayerEntity player, BlockPos target,
            Material material, Block expectedBlock, FlightInteractionConfirmation confirmation,
            TemporarySupportController.Operation supportOperation,
            MossMiningToolGuard.ChargeToken<ItemStack> mossCharge) {
        InteractionReceipt(ClientWorld world, ClientPlayerEntity player, BlockPos target,
                Material material, Block expectedBlock, FlightInteractionConfirmation confirmation) {
            this(world, player, target, material, expectedBlock, confirmation, null, null);
        }
        InteractionReceipt(ClientWorld world, ClientPlayerEntity player, BlockPos target,
                Material material, Block expectedBlock, FlightInteractionConfirmation confirmation,
                TemporarySupportController.Operation supportOperation) {
            this(world, player, target, material, expectedBlock, confirmation, supportOperation, null);
        }
    }

    private void tickOrdinaryRunning() {
        scanOrdinaryTransitions(scanBudgetPerTick);
        if (correctTargets.cardinality() == ordinaryPlacements.size()) {
            succeed();
            return;
        }
        if (baritone().getBuilderProcess().isActive()) {
            builderIdleTicks = 0;
            return;
        }
        builderIdleTicks++;
        if (builderIdleTicks >= BUILDER_SETTLE_TICKS) {
            completionCursor = 0;
            completionAllCorrect = true;
            mode = ExecutionMode.ORDINARY_COMPLETION_SCAN;
        }
    }

    private void tickOrdinaryCompletionScan() {
        int budget = scanBudgetPerTick;
        while (budget > 0 && completionCursor < ordinaryPlacements.size()) {
            boolean correct = observeOrdinaryTransition(completionCursor);
            completionAllCorrect &= correct;
            completionCursor++;
            budget--;
        }
        if (completionCursor < ordinaryPlacements.size()) {
            return;
        }
        if (completionAllCorrect) {
            succeed();
            return;
        }
        MaterialQuantities unavailable = unavailableOrdinaryMaterials();
        if (!unavailable.isEmpty()) {
            needMaterials(unavailable);
            return;
        }
        int remaining = ordinaryPlacements.size() - correctTargets.cardinality();
        fail("Baritone stopped with " + remaining + " unconfirmed ordinary target(s)");
    }

    private void scanOrdinaryTransitions(int budget) {
        if (ordinaryPlacements.isEmpty()) {
            return;
        }
        int scanned = Math.min(budget, ordinaryPlacements.size());
        for (int count = 0; count < scanned; count++) {
            observeOrdinaryTransition(monitorCursor);
            monitorCursor = (monitorCursor + 1) % ordinaryPlacements.size();
        }
    }

    private boolean observeOrdinaryTransition(int index) {
        OrdinaryPlacement placement = ordinaryPlacements.get(index);
        if (!chunkLoaded(Objects.requireNonNull(client.world, "world"), placement.position())) {
            return false;
        }
        io.github.schematicsupervisor.core.BlockState actual = ordinaryActual(index);
        boolean correctNow = OrdinaryPlacementAcceptance.isSatisfied(
                placement,
                actual,
                tillPrerequisites.contains(placement.position())
        );
        boolean correctBefore = correctTargets.get(index);
        if (correctNow && !correctBefore) {
            correctTargets.set(index);
            progressMarker++;
            confirmedBlockProgressThisTick = true;
            if (OrdinaryPlacementAcceptance.confirmsMaterialConsumption(
                    placement,
                    actual
            )) {
                addConsumed(placement.material(), 1);
            }
        } else if (!correctNow && correctBefore) {
            correctTargets.clear(index);
        }
        return correctNow;
    }

    private boolean ordinaryCorrect(int index) {
        return OrdinaryPlacementAcceptance.isSatisfied(
                ordinaryPlacements.get(index),
                ordinaryActual(index),
                tillPrerequisites.contains(ordinaryPlacements.get(index).position())
        );
    }

    private io.github.schematicsupervisor.core.BlockState ordinaryActual(int index) {
        ClientWorld world = Objects.requireNonNull(client.world, "world");
        OrdinaryPlacement placement = ordinaryPlacements.get(index);
        if (!chunkLoaded(world, placement.position())) {
            return io.github.schematicsupervisor.core.BlockState.AIR;
        }
        net.minecraft.block.BlockState actual = world.getBlockState(toMinecraft(placement.position()));
        return convertedStates.computeIfAbsent(actual, MinecraftBlockStates::toCore);
    }

    private void beginManualTarget() {
        if (resumeUnfinishedStemSweep()) { return; }
        if (order instanceof WorkOrder.Till) {
            if (!releaseAutomationControl("could not stop movement before selecting nearby soil", false)) {
                return;
            }
            Objects.requireNonNull(tillSelector, "tillSelector").begin();
            manualTargetIndex = -1;
            mode = ExecutionMode.TILL_SELECTING;
            tickTillSelection();
            return;
        }
        if (!releaseAutomationControl("could not stop movement before selecting nearby planting cells", false)) {
            return;
        }
        Objects.requireNonNull(plantSelector, "plantSelector").begin();
        manualTargetIndex = -1;
        mode = ExecutionMode.PLANT_SELECTING;
        tickPlantSelection();
    }

    private void tickPlantSelection() {
        ClientPlayerEntity player = Objects.requireNonNull(client.player, "player");
        plantSelector.scan(Math.min(scanBudgetPerTick, TILL_SELECTION_BUDGET), index -> {
            PlantTargetSelector.TargetState state = plantTargetState(index);
            if (state == PlantTargetSelector.TargetState.COMPLETE) { markManualTargetCorrect(index); }
            return state;
        }, this::plantTargetReachable, index -> player.getEyePos()
                .squaredDistanceTo(Vec3d.ofCenter(clickedPosition(manualTargets.get(index)))));
        if (!plantSelector.complete()) {
            detail = "Selecting visible planting cells before moving to the next patch";
            return;
        }
        if (plantSelector.waitingForPrediction()) {
            plantSelector.begin();
            detail = "Waiting for confirmed planting cells and farmland support";
            return;
        }
        manualTargetIndex = plantSelector.selectedIndex();
        if (manualTargetIndex < 0) {
            manualTargetIndex = manualTargets.size();
            succeed();
            return;
        }
        BlockPosition target = manualTargets.get(manualTargetIndex);
        if (!chunkLoaded(Objects.requireNonNull(client.world, "world"), target)) {
            beginChunkApproach(target);
            return;
        }
        beginSelectedManualTarget();
    }

    private PlantTargetSelector.TargetState plantTargetState(int index) {
        if (correctTargets.get(index)) { return PlantTargetSelector.TargetState.COMPLETE; }
        ClientWorld world = Objects.requireNonNull(client.world, "world");
        BlockPos target = toMinecraft(manualTargets.get(index));
        if (!ClientChunkAvailability.isLoaded(world, target)) { return PlantTargetSelector.TargetState.UNRECEIVED; }
        if (predictionPending(world, target) || predictionPending(world, target.down())) {
            return PlantTargetSelector.TargetState.PENDING;
        }
        if (world.getBlockState(target).isOf(Blocks.WHEAT)) { return PlantTargetSelector.TargetState.COMPLETE; }
        return world.getBlockState(target).isAir() && world.getBlockState(target.down()).isOf(Blocks.FARMLAND)
                ? PlantTargetSelector.TargetState.READY : PlantTargetSelector.TargetState.INVALID;
    }

    private boolean plantTargetReachable(int index) {
        ClientPlayerEntity player = Objects.requireNonNull(client.player, "player");
        ClientWorld world = Objects.requireNonNull(client.world, "world");
        BlockPos support = clickedPosition(manualTargets.get(index));
        return flightExecution
                ? flightNavigation.canBeginAtCurrentPosition(support, interactionReach(), Direction.UP, null)
                : player.canInteractWithBlockAt(support, 0.0)
                    && ExactInteractionRay.trace(world, player, player.getEyePos(), support,
                            Direction.UP, interactionReach()).accepted();
    }

    private void tickTillSelection() {
        ClientPlayerEntity player = Objects.requireNonNull(client.player, "player");
        tillSelector.scan(Math.min(scanBudgetPerTick, TILL_SELECTION_BUDGET), index -> {
            if (correctTargets.get(index)) { return false; }
            if (!manualCorrect(index)) { return true; }
            markManualTargetCorrect(index);
            return false;
        }, index -> {
            ClientWorld world = Objects.requireNonNull(client.world, "world");
            BlockPos target = toMinecraft(manualTargets.get(index));
            return !ClientChunkAvailability.isLoaded(world, target) || predictionPending(world, target)
                    || world.getBlockState(target).isOf(Blocks.DIRT)
                    || world.getBlockState(target).isOf(Blocks.FARMLAND);
        }, this::tillTargetReachable, index -> player.getEyePos()
                .squaredDistanceTo(Vec3d.ofCenter(toMinecraft(manualTargets.get(index)))));
        if (!tillSelector.complete()) {
            detail = "Selecting reachable planned soil before moving to the next patch";
            return;
        }
        if (tillSelector.invalidIndex() >= 0) {
            manualTargetIndex = tillSelector.invalidIndex();
            beginSelectedManualTarget();
            return;
        }
        manualTargetIndex = tillSelector.selectedIndex();
        if (manualTargetIndex < 0) {
            manualTargetIndex = manualTargets.size();
            succeed();
            return;
        }
        BlockPosition target = manualTargets.get(manualTargetIndex);
        if (!chunkLoaded(Objects.requireNonNull(client.world, "world"), target)) {
            beginChunkApproach(target);
            return;
        }
        beginSelectedManualTarget();
    }

    private boolean tillTargetReachable(int index) {
        ClientPlayerEntity player = Objects.requireNonNull(client.player, "player");
        ClientWorld world = Objects.requireNonNull(client.world, "world");
        BlockPos target = toMinecraft(manualTargets.get(index));
        if (!ClientChunkAvailability.isLoaded(world, target)
                || !world.getBlockState(target).isOf(Blocks.DIRT)
                || !world.getBlockState(target.up()).isAir()
                || predictionPending(world, target)) {
            return false;
        }
        return flightExecution
                ? flightNavigation.canBeginAtCurrentPosition(target, interactionReach(), Direction.UP, null, true)
                : player.canInteractWithBlockAt(target, 0.0)
                    && ExactInteractionRay.traceTillTopFace(world, player, player.getEyePos(), target,
                            interactionReach()).accepted();
    }

    private void beginSelectedManualTarget() {
        if (!validateManualBeforeNavigation()) { return; }
        Material material = manualMaterial();
        if (inventoryCount(material) < 1) {
            needMaterials(manualRestockRequirement(material));
            return;
        }
        BlockPos clickedPosition = clickedPosition(manualTargets.get(manualTargetIndex));
        interactionAttempts = 0;
        interactionWaitTicks = 0;
        if (flightExecution) {
            beginFlightRoute(clickedPosition, Direction.UP, null, order instanceof WorkOrder.Till);
            mode = flightNavigation.arrived()
                    ? ExecutionMode.MANUAL_READY : ExecutionMode.MANUAL_NAVIGATING;
            detail = flightNavigation.detail();
            return;
        }
        applyBaritoneSettings(false);
        activeGoal = new GoalNear(clickedPosition, pathGoalRadius);
        interactionAttempts = 0;
        interactionWaitTicks = 0;
        if (arrived(activeGoal) || (order instanceof WorkOrder.Till
                ? tillTargetReachable(manualTargetIndex) : plantTargetReachable(manualTargetIndex))) {
            mode = ExecutionMode.MANUAL_READY;
            return;
        }
        startCustomGoal(activeGoal);
        pathStartGraceTicks = PATH_START_GRACE_TICKS;
        mode = ExecutionMode.MANUAL_NAVIGATING;
    }

    private void tickManualNavigation() {
        if (flightExecution) {
            BlockPosition target = manualTargets.get(manualTargetIndex);
            if (!chunkLoaded(Objects.requireNonNull(client.world, "world"), target)) {
                // A received detour, such as a descent outside the north edge, can briefly unload the
                // distant target at a chunk boundary. Keep the route and its segment guards and inspect
                // no target until it returns; validating it here would restart a chunk approach that
                // stops at the same boundary, so the route could never arrive.
                tickUnreceivedInteractionApproach(target);
                return;
            }
        }
        if (order instanceof WorkOrder.Till && manualCorrect(manualTargetIndex)) {
            // Another actor may finish this soil while the route is approaching it.
            confirmManualTarget();
            beginManualTarget();
            return;
        }
        if (!validateManualBeforeNavigation()) { return; }
        if (flightExecution) {
            flightNavigation.tick();
            detail = flightNavigation.detail();
            if (flightNavigation.failed()) {
                fail(detail);
            } else if (flightNavigation.arrived()) {
                mode = ExecutionMode.MANUAL_READY;
            }
            return;
        }
        if (activeGoal == null) {
            fail("Manual target path has no active goal");
            return;
        }
        if (arrived(activeGoal)) {
            if (!releaseAutomationControl(
                    "could not fully stop Baritone before the manual interaction",
                    false
            )) {
                return;
            }
            applyBaritoneSettings(false);
            mode = ExecutionMode.MANUAL_READY;
            return;
        }
        if (baritone().getCustomGoalProcess().isActive()
                || baritone().getPathingBehavior().isPathing()) {
            pathStartGraceTicks = 0;
            return;
        }
        if (pathStartGraceTicks > 0) {
            pathStartGraceTicks--;
            return;
        }
        fail("Path ended before the interaction target was reached");
    }

    private void tickManualReady() {
        BlockPosition target = manualTargets.get(manualTargetIndex);
        if (!chunkLoaded(Objects.requireNonNull(client.world, "world"), target)) {
            beginChunkApproach(target);
            return;
        }
        if (manualCorrect(manualTargetIndex)) {
            confirmManualTarget();
            beginManualTarget();
            return;
        }
        if (!validateManualBeforeNavigation()) { return; }
        if (order instanceof WorkOrder.Till && !tillCadence.ready()) { return; }
        if (order instanceof WorkOrder.Plant && !plantCadence.ready()) { return; }
        if (cooldownTicks > 0) {
            cooldownTicks--;
            return;
        }
        String invalid = validateManualTarget();
        if (!invalid.isEmpty()) {
            fail(invalid);
            return;
        }
        Material material = manualMaterial();
        if (inventoryCount(material) < 1) {
            needMaterials(manualRestockRequirement(material));
            return;
        }
        if (!selectIntoHotbar(material)) {
            fail("Required " + material.jsonName() + " could not be selected in the hotbar");
            return;
        }

        if (material == Material.HOE && hoeRepair != null) {
            HoeRepairSession.Status repair = hoeRepair.beforeUse();
            if (repair != HoeRepairSession.Status.READY) {
                // The ready interaction has arrived; stop any residual owned velocity while
                // retaining MANUAL_READY and its target until the server repair receipt settles.
                flightNavigation.stop();
                if (!releaseAutomationControl("could not stop movement for hoe repair", false)) {
                    hoeRepair.cancel();
                }
                detail = hoeRepair.detail();
                return;
            }
        }

        ClientPlayerEntity player = Objects.requireNonNull(client.player, "player");
        BlockPos clicked = clickedPosition(manualTargets.get(manualTargetIndex));
        if (flightExecution) {
            if (!stackPredicate(material).test(player.getMainHandStack())) {
                fail("The exact manual interaction item was not selected");
                return;
            }
            BlockHitResult verifiedHit = verifiedHit(clicked, Direction.UP, order instanceof WorkOrder.Till);
            if (verifiedHit == null) {
                if (flightNavigation.retryInteractionArrival(interactionRayDetail)) {
                    mode = ExecutionMode.MANUAL_NAVIGATING;
                    detail = "Finding another exact top-face approach: " + interactionRayDetail;
                } else {
                    fail(flightNavigation.detail());
                }
                return;
            }
            interactWithReceipt(verifiedHit, toMinecraft(manualTargets.get(manualTargetIndex)),
                    material, order instanceof WorkOrder.Till ? Blocks.FARMLAND : Blocks.WHEAT,
                    ExecutionMode.MANUAL_WAITING_CONFIRMATION);
            return;
        }
        if (!player.canInteractWithBlockAt(clicked, 0.0)) {
            activeGoal = new GoalNear(clicked, 1);
            startCustomGoal(activeGoal);
            pathStartGraceTicks = PATH_START_GRACE_TICKS;
            mode = ExecutionMode.MANUAL_NAVIGATING;
            return;
        }
        BlockHitResult hit;
        if (order instanceof WorkOrder.Till) {
            hit = verifiedHit(clicked, Direction.UP, true);
            if (hit == null) {
                fail("The exact tilling target has no clear top-face interaction: " + interactionRayDetail);
                return;
            }
        } else {
            hit = verifiedHit(clicked, Direction.UP);
            if (hit == null) {
                fail("The exact planting support has no clear top-face interaction: " + interactionRayDetail);
                return;
            }
        }
        if (order instanceof WorkOrder.Till) { tillCadence.dispatched(); }
        if (order instanceof WorkOrder.Plant) { plantCadence.dispatched(); }
        ActionResult action = interactOwnedBlock(player, Hand.MAIN_HAND, hit);
        interactionAttempts++;
        if (!action.isAccepted()) {
            if (interactionAttempts >= MAX_INTERACTION_ATTEMPTS) {
                fail("Server rejected the deterministic block interaction");
            } else {
                cooldownTicks = interactionCooldownTicks;
            }
            return;
        }
        player.swingHand(Hand.MAIN_HAND);
        interactionWaitTicks = 0;
        mode = ExecutionMode.MANUAL_WAITING_CONFIRMATION;
    }

    private void tickManualConfirmation() {
        if (flightExecution) {
            if (receiptResult == FlightInteractionConfirmation.Result.CONFIRMED) {
                receiptResult = null;
                confirmManualTarget();
                beginManualTarget();
            } else if (receiptResult == FlightInteractionConfirmation.Result.RETRYABLE) {
                receiptResult = null;
                retryInteraction(ExecutionMode.MANUAL_READY);
            }
            return;
        }
        if (manualCorrect(manualTargetIndex)) {
            confirmManualTarget();
            beginManualTarget();
            return;
        }
        interactionWaitTicks++;
        int confirmationLimit = Math.max(
                MINIMUM_INTERACTION_CONFIRM_TICKS,
                interactionCooldownTicks * 4
        );
        if (interactionWaitTicks < confirmationLimit) {
            return;
        }
        if (interactionAttempts >= MAX_INTERACTION_ATTEMPTS) {
            fail("Interaction was not confirmed by a world-state transition");
            return;
        }
        cooldownTicks = interactionCooldownTicks;
        mode = ExecutionMode.MANUAL_READY;
    }

    private void confirmManualTarget() {
        markManualTargetCorrect(manualTargetIndex);
        manualTargetIndex = correctTargets.nextClearBit(0);
        interactionAttempts = 0;
        interactionWaitTicks = 0;
    }

    private void markManualTargetCorrect(int index) {
        if (!correctTargets.get(index)) {
            correctTargets.set(index);
            progressMarker++;
            confirmedBlockProgressThisTick = true;
            if (!flightExecution && order instanceof WorkOrder.Plant) {
                addConsumed(Material.WHEAT_SEEDS, 1);
            }
        }
    }

    private boolean manualCorrect(int index) {
        ClientWorld world = Objects.requireNonNull(client.world, "world");
        BlockPosition target = manualTargets.get(index);
        if (!chunkLoaded(world, target)) {
            return false;
        }
        net.minecraft.block.BlockState state = world.getBlockState(toMinecraft(target));
        return order instanceof WorkOrder.Till
                ? state.isOf(Blocks.FARMLAND) && !predictionPending(world, toMinecraft(target))
                : state.isOf(Blocks.WHEAT) && !predictionPending(world, toMinecraft(target))
                    && !predictionPending(world, toMinecraft(target).down());
    }

    private boolean validateManualBeforeNavigation() {
        if (order instanceof WorkOrder.Plant) {
            switch (plantTargetState(manualTargetIndex)) {
                case COMPLETE -> { confirmManualTarget(); beginManualTarget(); return false; }
                case PENDING -> { beginManualTarget(); return false; }
                case UNRECEIVED -> { beginChunkApproach(manualTargets.get(manualTargetIndex)); return false; }
                case INVALID -> { fail(validateManualTarget()); return false; }
                case READY -> { return true; }
            }
        }
        if (!(order instanceof WorkOrder.Till)) { return true; }
        ClientWorld world = Objects.requireNonNull(client.world, "world");
        BlockPosition target = manualTargets.get(manualTargetIndex);
        BlockPos position = toMinecraft(target);
        TillTargetCheck.Facts facts = TillTargetCheck.Facts.unreceived();
        if (chunkLoaded(world, target)) {
            net.minecraft.block.BlockState state = world.getBlockState(position);
            facts = new TillTargetCheck.Facts(true, predictionPending(world, position),
                    predictionPending(world, position.up()), state.isOf(Blocks.FARMLAND), state.isAir(),
                    state.isOf(Blocks.DIRT), world.getBlockState(position.up()).isAir());
        }
        TillTargetCheck.Decision check = TillTargetCheck.decide(facts);
        switch (check.next()) {
            case APPROACH_CHUNK -> beginChunkApproach(target);
            case WAIT_FOR_PREDICTION -> {
                if (releaseAutomationControl("could not stop movement while soil prediction settles", false)) {
                    tillSelector.begin();
                    mode = ExecutionMode.TILL_SELECTING;
                    detail = "Waiting for confirmed till prerequisites at " + position.toShortString();
                }
            }
            case ALREADY_TILLED -> {
                confirmManualTarget();
                beginManualTarget();
            }
            case NEEDS_DIRT -> {
                if (releaseAutomationControl("could not stop movement for missing planned soil", false)) {
                    mode = ExecutionMode.NEEDS_PREREQUISITES;
                    detail = "Received air at planned till target " + position.toShortString()
                            + "; reconcile planned dirt before resuming tilling";
                }
            }
            case FAIL -> fail(check.failure());
            case PROCEED -> {
                return true;
            }
        }
        return false;
    }

    private String validateManualTarget() {
        ClientWorld world = Objects.requireNonNull(client.world, "world");
        BlockPosition target = manualTargets.get(manualTargetIndex);
        if (!chunkLoaded(world, target)) {
            return "Interaction target chunk is not loaded";
        }
        BlockPos targetPosition = toMinecraft(target);
        net.minecraft.block.BlockState targetState = world.getBlockState(targetPosition);
        if (order instanceof WorkOrder.Till) {
            return targetState.isOf(Blocks.DIRT) ? "" : TillTargetCheck.NOT_DIRT;
        }
        if (!targetState.isAir()) {
            return "Plant target is occupied by a non-wheat block";
        }
        return world.getBlockState(targetPosition.down()).isOf(Blocks.FARMLAND)
                ? ""
                : "Plant target has no confirmed farmland support";
    }

    private void tickWaitingForMaterials() {
        // The core does not poll the execution port while a depot transfer is active. Resumption
        // occurs from poll() only after the core has left RESTOCKING, preventing build navigation
        // from racing an open-container transfer.
    }

    private void resumeAfterMaterials() {
        requestedAvailable = MaterialQuantities.empty();
        detail = "";
        if (order instanceof WorkOrder.OrdinaryBlocks) {
            dispatchOrdinaryBuild();
        } else {
            beginManualTarget();
        }
    }

    private MaterialQuantities unavailableOrdinaryMaterials() {
        TreeMap<Material, Long> remaining = new TreeMap<>();
        for (int index = correctTargets.nextClearBit(0);
                index >= 0 && index < ordinaryPlacements.size();
                index = correctTargets.nextClearBit(index + 1)) {
            Material material = ordinaryPlacements.get(index).material();
            remaining.merge(material, 1L, Math::addExact);
        }
        int missingMaterialKinds = 0;
        for (Material material : remaining.keySet()) {
            if (remaining.getOrDefault(material, 0L) > 0 && inventoryCount(material) < 1) {
                missingMaterialKinds++;
            }
        }
        for (Material material : remaining.keySet()) {
            long count = remaining.getOrDefault(material, 0L);
            if (count > 0 && inventoryCount(material) < 1) {
                return MaterialQuantities.of(
                        material,
                        capacityAwareTarget(material, count, missingMaterialKinds - 1)
                );
            }
        }
        return MaterialQuantities.empty();
    }

    private MaterialQuantities manualRestockRequirement(Material material) {
        if (material == Material.HOE) {
            return MaterialQuantities.of(Material.HOE, 1);
        }
        if (material == Material.WHEAT_SEEDS) {
            return MaterialQuantities.of(material, RestockBatchPolicy.seedTargetAvailable(
                    depotAvailability.get().get(material), additionalMainInventoryCapacity(material)));
        }
        long remaining = Math.max(1, manualTargets.size() - correctTargets.cardinality());
        return MaterialQuantities.of(
                material,
                capacityAwareTarget(material, remaining)
        );
    }

    private long capacityAwareTarget(Material material, long remaining) {
        return capacityAwareTarget(material, remaining, 0);
    }

    private long capacityAwareTarget(Material material, long remaining, int reservedSlots) {
        long depotStock = depotAvailability.get().get(material);
        long capacity = additionalMainInventoryCapacity(material);
        if (material == Material.GLOWSTONE && inventoryCount(material) == 0) {
            return RestockBatchPolicy.glowstoneTargetAvailable(remaining,
                    upcomingLightingDemand.applyAsLong(order), remainingGlowstoneBudget.getAsLong(),
                    depotStock, capacity, reservedSlots);
        }
        return RestockBatchPolicy.targetAvailable(
                remaining,
                depotStock,
                capacity,
                maximumStackSize(material),
                reservedSlots
        );
    }

    private long additionalMainInventoryCapacity(Material material) {
        ClientPlayerEntity player = client.player;
        if (player == null) {
            return 0;
        }
        Predicate<ItemStack> matches = stackPredicate(material);
        int maximumStack = maximumStackSize(material);
        long capacity = 0;
        for (ItemStack stack : player.getInventory().getMainStacks()) {
            if (stack.isEmpty()) {
                capacity = Math.addExact(capacity, maximumStack);
            } else if (matches.test(stack)) {
                capacity = Math.addExact(
                        capacity,
                        Math.max(0, stack.getMaxCount() - stack.getCount())
                );
            }
        }
        return capacity;
    }

    private static int maximumStackSize(Material material) {
        return MinecraftMaterials.maximumStackSize(material);
    }

    private boolean hasRequestedMaterials() {
        for (Map.Entry<Material, Long> entry : requestedAvailable.asMap().entrySet()) {
            if (inventoryCount(entry.getKey()) < entry.getValue()) {
                return false;
            }
        }
        return true;
    }

    private long inventoryCount(Material material) {
        ClientPlayerEntity player = client.player;
        if (player == null) {
            return 0;
        }
        Predicate<ItemStack> matches = stackPredicate(material);
        long total = 0;
        PlayerInventory inventory = player.getInventory();
        for (ItemStack stack : inventory.getMainStacks()) {
            if (matches.test(stack)) {
                total = Math.addExact(total, stack.getCount());
            }
        }
        return total;
    }

    private boolean selectIntoHotbar(Material material) {
        return selectIntoHotbar(stackPredicate(material));
    }

    private boolean selectIntoHotbar(Predicate<ItemStack> matches) {
        ClientPlayerEntity player = Objects.requireNonNull(client.player, "player");
        PlayerInventory inventory = player.getInventory();
        for (int index = 0; index < PlayerInventory.getHotbarSize(); index++) {
            if (matches.test(inventory.getStack(index))) {
                inventory.setSelectedSlot(index);
                return true;
            }
        }
        if (player.currentScreenHandler != player.playerScreenHandler) {
            return false;
        }
        // A visible Inventory page refills like gameplay and the other pages that allow world actions.
        int sourceInventoryIndex = -1;
        for (int index = PlayerInventory.getHotbarSize();
                index < inventory.getMainStacks().size();
                index++) {
            if (matches.test(inventory.getStack(index))) {
                sourceInventoryIndex = index;
                break;
            }
        }
        if (sourceInventoryIndex < 0) {
            return false;
        }
        if (!MinecraftBackgroundBuildAccess.allowsHotbarTransfer(client)) {
            throw new HotbarTransferDeferred("Hotbar refill is waiting for the inventory cursor or container to clear.");
        }
        OptionalInt destination = MinecraftBackgroundBuildAccess.hotbarDestination(client);
        if (destination.isEmpty()) {
            throw new HotbarTransferDeferred("Hotbar refill needs an empty slot or a plain build supply or pickup; tools and special items stay in place.");
        }
        int destinationHotbarIndex = destination.getAsInt();
        return transferIntoHotbar(sourceInventoryIndex, destinationHotbarIndex, matches, false);
    }

    private boolean transferIntoHotbar(int sourceInventoryIndex, int destinationHotbarIndex,
                                       Predicate<ItemStack> sourceMatches, boolean emptyDestinationOnly) {
        ClientPlayerEntity player = Objects.requireNonNull(client.player, "player");
        PlayerInventory inventory = player.getInventory();
        var handler = player.currentScreenHandler;
        OptionalInt sourceSlot = handler.getSlotIndex(
                inventory,
                sourceInventoryIndex
        );
        if (sourceSlot.isEmpty()) {
            return false;
        }
        {
            OptionalInt destinationSlot = handler.getSlotIndex(inventory, destinationHotbarIndex);
            if (destinationSlot.isEmpty()) { return false; }
            var source = handler.getSlot(sourceSlot.getAsInt());
            var target = handler.getSlot(destinationSlot.getAsInt());
            ItemStack sourceStack = source.getStack();
            ItemStack displacedStack = target.getStack();
            boolean permittedDestination = displacedStack.isEmpty()
                    || (!emptyDestinationOnly && PlainInteractionItems.plainBuildOrPickup(displacedStack));
            if (player.currentScreenHandler != handler || handler != player.playerScreenHandler
                    || !MinecraftBackgroundBuildAccess.allowsHotbarTransfer(client)
                    || source.inventory != inventory || source.getIndex() != sourceInventoryIndex
                    || target.inventory != inventory || target.getIndex() != destinationHotbarIndex
                    || !sourceMatches.test(sourceStack) || !source.canTakeItems(player)
                    || !permittedDestination || !target.canInsert(sourceStack)
                    || target.getMaxItemCount(sourceStack) < sourceStack.getCount()
                    || (!displacedStack.isEmpty() && (!target.canTakeItems(player)
                        || !source.canInsert(displacedStack)
                        || source.getMaxItemCount(displacedStack) < displacedStack.getCount()))) {
                throw new HotbarTransferDeferred("Hotbar refill is waiting because its exact inventory slots changed.");
            }
        }
        Runnable transfer = () -> Objects.requireNonNull(client.interactionManager, "interactionManager").clickSlot(
                handler.syncId,
                sourceSlot.getAsInt(),
                destinationHotbarIndex,
                SlotActionType.SWAP,
                player
        );
        if (mossCustody == null) { transfer.run(); }
        else { mossCustody.clickSlot(handler, sourceSlot.getAsInt(), destinationHotbarIndex, SlotActionType.SWAP, transfer); }
        inventory.setSelectedSlot(destinationHotbarIndex);
        return true;
    }

    private static final class HotbarTransferDeferred extends RuntimeException {
        private static final long serialVersionUID = 1L;

        private HotbarTransferDeferred(String detail) { super(detail); }
    }

    static Predicate<ItemStack> stackPredicate(Material material) {
        return MinecraftMaterials.stackPredicate(material);
    }

    private Material manualMaterial() {
        return order instanceof WorkOrder.Till ? Material.HOE : Material.WHEAT_SEEDS;
    }

    private BlockPos clickedPosition(BlockPosition target) {
        BlockPos position = toMinecraft(target);
        return order instanceof WorkOrder.Plant ? position.down() : position;
    }

    private boolean arrived(GoalNear goal) {
        ClientPlayerEntity player = client.player;
        return player != null && goal.isInGoal(
                player.getBlockX(),
                player.getBlockY(),
                player.getBlockZ()
        );
    }

    private void tickSafeReturn() {
        if (flightSafeReturn) {
            try {
                flightNavigation.tick();
                if (flightNavigation.failed()) {
                    finishSafeReturnFailure(flightNavigation.detail());
                } else if (flightNavigation.arrived()) {
                    finishSafeReturnSuccess();
                }
            } catch (RuntimeException exception) {
                finishSafeReturnFailure(errorDetail(exception));
            }
            return;
        }
        if (client.player == null || safeReturnGoal == null) {
            finishSafeReturnFailure("Player or safe-return goal became unavailable");
            return;
        }
        if (arrived(safeReturnGoal)) {
            finishSafeReturnSuccess();
            return;
        }
        try {
            if (baritone().getCustomGoalProcess().isActive()
                    || baritone().getPathingBehavior().isPathing()) {
                safeReturnGraceTicks = 0;
                return;
            }
            if (safeReturnGraceTicks > 0) {
                safeReturnGraceTicks--;
                return;
            }
            finishSafeReturnFailure("Safe-return path ended before confirmed arrival");
        } catch (RuntimeException exception) {
            finishSafeReturnFailure(errorDetail(exception));
        }
    }

    private void observePlayerProgress() {
        ClientPlayerEntity player = client.player;
        ClientWorld world = client.world;
        if (player == null || world == null) {
            return;
        }
        BlockPos current = player.getBlockPos().toImmutable();
        if (lastObservedPlayerPosition != null
                && !lastObservedPlayerPosition.equals(current)
                && mode.countsMovementAsProgress()) {
            progressMarker++;
        }
        lastObservedPlayerPosition = current;
        if (player.isOnGround() && ClientChunkAvailability.isLoaded(world, current)
                && world.getFluidState(current).isEmpty()) {
            net.minecraft.block.BlockState support = world.getBlockState(current.down());
            if (!support.isAir() && world.getFluidState(current.down()).isEmpty()) {
                lastSafePosition = current;
            }
        }
    }

    private void applyBaritoneSettings(boolean building) {
        requireAutomationAvailable("change Baritone execution settings");
        Settings settings = BaritoneAPI.getSettings();
        if (baritoneSettings == null) {
            baritoneSettings = BaritoneSettingLease.forDeterministicBuild(settings, building);
        } else {
            baritoneSettings.setBuilding(building);
        }
    }

    private void prepareBaritoneCancellation() {
        cancelOwnedMining();
        flightNavigation.stop();
        flightSafeReturn = false;
        BaritoneSettingLease lease = baritoneSettings;
        if (lease != null) {
            lease.disableMutation();
        }
    }

    private void restoreBaritoneSettings() {
        if (baritoneSettings != null) {
            baritoneSettings.close();
            baritoneSettings = null;
        }
        baritone().getInputOverrideHandler().clearAllKeys();
    }

    private void startCustomGoal(GoalNear goal) {
        requireAutomationAvailable("start a Baritone path");
        controlReleased = false;
        baritone().getCustomGoalProcess().setGoalAndPath(goal);
    }

    private boolean releaseAutomationControl(String context, boolean forceAttempt) {
        // Flight-only execution can own mining while the ground controller is already released.
        cancelOwnedMining();
        flightNavigation.stop();
        flightSafeReturn = false;
        if (cancellationGuard.blocked()) {
            markAutomationBlocked("");
            return false;
        }
        if (!forceAttempt && controlReleased && baritoneSettings == null) {
            activeGoal = null;
            lastObservedPlayerPosition = null;
            return true;
        }
        boolean released = cancellationGuard.stop(context);
        activeGoal = null;
        lastObservedPlayerPosition = null;
        if (released) {
            controlReleased = true;
            return true;
        }
        markAutomationBlocked("");
        return false;
    }

    private IBaritone baritone() {
        IBaritone instance = BaritoneAPI.getProvider().getBaritoneForMinecraft(client);
        return Objects.requireNonNull(instance, "No Baritone instance is attached to the client");
    }

    private void finishSafeReturnSuccess() {
        if (!releaseAutomationControl(
                "could not fully stop Baritone after confirmed safe-return arrival",
                false
        )) {
            safeReturnSnapshot = SafeReturnSnapshot.running();
            return;
        }
        safeReturnGoal = null;
        safeReturnSnapshot = SafeReturnSnapshot.succeeded();
    }

    private void finishSafeReturnFailure(String failureDetail) {
        if (!releaseAutomationControl(
                "could not fully stop Baritone after safe-return failure",
                false
        )) {
            safeReturnSnapshot = SafeReturnSnapshot.running();
            return;
        }
        safeReturnGoal = null;
        safeReturnSnapshot = SafeReturnSnapshot.failed(
                failureDetail == null || failureDetail.isBlank()
                        ? "Safe return failed"
                        : failureDetail
        );
    }

    private void markAutomationBlocked(String precedingDetail) {
        requestedAvailable = MaterialQuantities.empty();
        String blockedDetail = cancellationGuard.blockedDetail();
        detail = precedingDetail == null || precedingDetail.isBlank()
                ? blockedDetail
                : precedingDetail + ". " + blockedDetail;
        mode = ExecutionMode.AUTOMATION_BLOCKED;
    }

    private void requireAutomationAvailable(String action) {
        if (cancellationGuard.blocked()) {
            throw new IllegalStateException(
                    action + " is blocked: " + cancellationGuard.blockedDetail()
            );
        }
    }

    private IllegalStateException automationBlockedException() {
        return new IllegalStateException(cancellationGuard.blockedDetail());
    }

    private void succeed() {
        if (supports != null && supports.outstanding()) {
            beginSupportExecution();
            return;
        }
        if (!releaseAutomationControl(
                "could not fully stop Baritone after confirmed execution success",
                false
        )) {
            return;
        }
        requestedAvailable = MaterialQuantities.empty();
        detail = "";
        mode = ExecutionMode.SUCCEEDED;
    }

    private void fail(String failureDetail) {
        String normalizedDetail = failureDetail == null || failureDetail.isBlank()
                ? "Execution failed"
                : failureDetail;
        if (supports != null && supports.controller != null) {
            supports.controller.pause();
            if (supportOperation != null && interactionReceipt == null && receiptFailure.isBlank()) {
                receiptFailure = "A persisted support intent lost its live receipt; reconciliation is required";
                supports.controller.uncertain(supportOperation, receiptFailure);
            }
        }
        try {
            if (interactionReceipt != null) {
                lastFailureReceiptObservation = receiptObservation(interactionReceipt, normalizedDetail);
            } else if (lastReceiptObservation != null && lastReceiptObservation.mode().equals(mode.name())
                    && (lastReceiptObservation.result().equals("RETRYABLE")
                        || lastReceiptObservation.result().equals("UNCERTAIN"))) {
                lastFailureReceiptObservation = lastReceiptObservation;
            }
        } catch (RuntimeException ignored) {
            // Preserve the execution failure even if optional diagnostics cannot be collected.
        }
        if (!releaseAutomationControl(
                "could not fully stop Baritone after execution failure",
                false
        )) {
            markAutomationBlocked(normalizedDetail);
            return;
        }
        requestedAvailable = MaterialQuantities.empty();
        detail = normalizedDetail;
        mode = ExecutionMode.FAILED;
    }

    private void needMaterials(MaterialQuantities requirement) {
        if (supports != null && supports.controller != null) { supports.controller.pause(); }
        if (!releaseAutomationControl(
                "could not fully stop Baritone before restocking",
                false
        )) {
            return;
        }
        requestedAvailable = Objects.requireNonNull(requirement, "requirement");
        if (requestedAvailable.isEmpty()) {
            throw new IllegalArgumentException("material requirement must not be empty");
        }
        detail = "Waiting for exact material availability";
        mode = ExecutionMode.WAITING_MATERIALS;
    }

    private void addConsumed(Material material, long amount) {
        pendingConsumed.merge(material, amount, Math::addExact);
    }

    private int targetCount() {
        return order instanceof WorkOrder.OrdinaryBlocks
                ? ordinaryPlacements.size()
                : manualTargets.size();
    }

    private BlockPosition targetPosition(int index) {
        return order instanceof WorkOrder.OrdinaryBlocks
                ? ordinaryPlacements.get(index).position() : manualTargets.get(index);
    }

    private String occupiedTargetDetail(BlockPos target) {
        ClientWorld world = Objects.requireNonNull(client.world, "world");
        return "Flight placement will not overwrite an occupied target at " + target.toShortString()
                + ": actual=" + world.getBlockState(target)
                + ", chunkReceived=" + ClientChunkAvailability.isLoaded(world, target)
                + ", worldY=" + world.getBottomY() + ".." + world.getTopYInclusive();
    }

    private boolean isActiveOrder() {
        return order != null && mode.holdsOrder();
    }

    private void resetOrderState() {
        stemSweep = null;
        stemSweepCursor = 0;
        stemPredictionWaitTicks = 0;
        stemTarget = null;
        stemHandSlot = -1;
        stemHandIdentity = ItemStack.EMPTY;
        clearingTarget = null;
        clearingHandSlot = -1;
        ordinaryPlacements = List.of();
        manualTargets = List.of();
        tillSelector = null;
        plantSelector = null;
        correctTargets = new BitSet();
        convertedStates.clear();
        progressMarker = 0;
        detail = "";
        requestedAvailable = MaterialQuantities.empty();
        preparationCursor = 0;
        monitorCursor = 0;
        completionCursor = 0;
        completionAllCorrect = true;
        builderIdleTicks = 0;
        manualTargetIndex = 0;
        interactionWaitTicks = 0;
        interactionAttempts = 0;
        cooldownTicks = 0;
        pathStartGraceTicks = 0;
        activeGoal = null;
        safeReturnGoal = null;
        safeReturnSnapshot = SAFE_RETURN_IDLE;
        confirmedBlockProgressThisTick = false;
        confirmedBlockProgress.beginOrder();
        flightApproachProgress.beginOrder();
        flightTargetIndex = -1;
        flightFace = null;
        unreceivedAnchor = null;
        unavailableFlightFaces.clear();
        lastFlightFaceRejection = "";
        chunkApproachTarget = null;
        chunkApproachTicksRemaining = MinecraftFlightNavigation.MAXIMUM_ACTIVE_TICKS;
        observedNavigationProgress = flightNavigation.progressMarker();
        if (interactionReceipt == null && receiptFailure.isEmpty()) {
            receiptResult = null;
        }
    }

    private static boolean chunkLoaded(ClientWorld world, BlockPosition position) {
        return ClientChunkAvailability.isLoaded(world,
                Math.floorDiv(position.x(), 16),
                Math.floorDiv(position.z(), 16)
        );
    }

    private static BlockPos toMinecraft(BlockPosition position) {
        return new BlockPos(position.x(), position.y(), position.z());
    }

    private static String errorDetail(Throwable throwable) {
        String message = throwable.getMessage();
        return message == null || message.isBlank()
                ? throwable.getClass().getSimpleName()
                : message;
    }

    private static Set<BlockPosition> tillPrerequisites(SchematicPlan plan) {
        HashSet<BlockPosition> result = new HashSet<>();
        plan.chunks().forEach(chunk -> result.addAll(chunk.tillTargets()));
        return Set.copyOf(result);
    }

}
