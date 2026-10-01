package io.github.schematicsupervisor.fabric;

import com.google.gson.JsonNull;
import com.mojang.serialization.JsonOps;
import io.github.schematicsupervisor.core.BlockPosition;
import io.github.schematicsupervisor.core.DepotId;
import io.github.schematicsupervisor.core.SchematicPlan;
import io.github.schematicsupervisor.fabric.mixin.ClientWorldPendingUpdatesAccessor;
import io.github.schematicsupervisor.fabric.mixin.PendingBlockUpdatesAccessor;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.registry.RegistryOps;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.math.BlockPos;

/** Optional one-stack void disposal. No world changes, guessed commands, purchases, or planned-material credit. */
final class MinecraftSurplusDisposal implements SurplusDisposalController.Port {
    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger(MinecraftSurplusDisposal.class);
    private static final int RECEIPT_TIMEOUT_TICKS = 100;
    private final MinecraftClient client;
    private final MinecraftDepotPort depots;
    private final SurplusDisposalFileStore store;
    private final Path recoveryRequestPath;
    private SurplusDisposalRecovery.Request recoveryRequest;
    private SurplusInventoryRecovery.Request inventoryRecoveryRequest;
    private boolean inventoryRecoveryActive;
    private final MinecraftFlightNavigation flight;
    private final MinecraftShopInventoryReceipt shopReceipt;
    private boolean inPlace;
    private net.minecraft.util.math.Vec3d localPosition;
    private net.minecraft.client.gui.screen.ingame.InventoryScreen ownedPlayerInventory;
    private PlayerInventoryDiscardSequence localDiscardSequence;
    private PlayerInventoryUpdateLedger.Stamp discardPacketBarrier;
    private MossToolCustody custody;
    private SurplusDisposalController controller;
    private SurplusDisposalAuthorization proof;
    private List<MossDepositJournal.Context> directRegistered = List.of();
    private MossDepositJournal.Context directContext;
    private SchematicPlan plan;
    private List<SurplusDisposalPolicy.Site> candidates = List.of();
    private SurplusDisposalPolicy.Site site;
    private int candidateIndex;
    private int scanIndex;
    private int receiptTicks;
    private int authorizationRefreshes;
    private int reopenRequests;
    private int settlingTicks;
    private int confirmedStacks;
    private int confirmedItems;
    private long cancellationSequence;
    private List<ItemStack> baseline = List.of();
    private Object world;
    private Object player;
    private Object connection;
    private Phase phase = Phase.IDLE;
    private boolean failed;
    private String loadFailure = "";
    private String detail = "Surplus disposal has not started.";

    enum Phase { IDLE, PREPARING, LOCAL_READY, LOCAL_SETTLING, SCANNING, FLYING, EGRESS, REOPENING, CONFIRMED, DEFERRED, BLOCKED }
    record Observation(String phase, String detail, boolean active, boolean pending,
                       boolean unavailable, int confirmedStacks, int confirmedItems) { }

    MinecraftSurplusDisposal(MinecraftClient client, MinecraftDepotPort depots, Path journalPath) {
        this.client = Objects.requireNonNull(client); this.depots = Objects.requireNonNull(depots);
        store = new SurplusDisposalFileStore(journalPath);
        recoveryRequestPath = journalPath.resolveSibling("surplus-disposal-recovery-request.json");
        flight = new MinecraftFlightNavigation(client, this::permitsPendingTravel);
        shopReceipt = new MinecraftShopInventoryReceipt(client);
        reload();
    }
    void configureMossCustody(MossToolCustody value) {
        custody = Objects.requireNonNull(value);
        shopReceipt.configureMossCustody(value);
    }
    boolean active() { return phase == Phase.PREPARING || phase == Phase.LOCAL_READY || phase == Phase.LOCAL_SETTLING || phase == Phase.SCANNING || phase == Phase.FLYING || phase == Phase.EGRESS || phase == Phase.REOPENING; }
    boolean pending() { return journal().filter(value -> value.stage() == SurplusDisposalJournal.Stage.PENDING).isPresent(); }
    boolean failed() { return failed; }
    boolean cleanupDeferred() { return phase == Phase.DEFERRED; }
    boolean inventoryReconciled() { return phase == Phase.CONFIRMED
            && journal().filter(value -> value.stage() == SurplusDisposalJournal.Stage.INVENTORY_RECONCILED).isPresent(); }
    boolean unavailable() { return !loadFailure.isBlank(); }
    String detail() { return detail; }
    Optional<SurplusDisposalJournal> journal() { return controller == null ? Optional.empty() : controller.journal(); }
    Observation observation() { return new Observation(phase.name(), detail, active(), pending(), unavailable(), confirmedStacks, confirmedItems); }
    String stateProblem() {
        return unavailable() ? loadFailure : pending()
                ? "An unresolved surplus disposal needs its original full inventory receipt; preserve this build." : "";
    }
    String resumeProblem(String planId) {
        return unavailable() ? loadFailure : pending() && (!journal().orElseThrow().before().context().planId().equals(planId)
                || !contextMatches(journal().orElseThrow().before().context())) ? stateProblem() : "";
    }

    void begin(boolean enabled, SchematicPlan selectedPlan, SurplusStorageExhaustion exhaustion) {
        requireClientThread();
        if (active()) { throw new IllegalStateException("Surplus disposal is already active"); }
        reload();
        recoveryRequest = null; inventoryRecoveryRequest = null; inventoryRecoveryActive = false;
        if (unavailable() || pending()) { block(stateProblem()); return; }
        if (SurplusDisposalPolicy.source(exhaustion, enabled, System.nanoTime()).isEmpty()) {
            block("Surplus disposal requires explicit opt-in and fresh complete zero-room storage evidence."); return;
        }
        inPlace = false;
        plan = Objects.requireNonNull(selectedPlan); proof = SurplusDisposalAuthorization.afterStorage(exhaustion);
        authorizationRefreshes = 0;
        prepareFlight();
    }

    /** Opens one registered chest only to observe inventory; never deposits or checks storage capacity. */
    void beginDirect(boolean enabled, SchematicPlan selectedPlan) {
        beginDirect(enabled, selectedPlan, false);
    }

    void beginDirect(boolean enabled, SchematicPlan selectedPlan, boolean atCurrentPosition) {
        requireClientThread();
        if (active()) { throw new IllegalStateException("Surplus disposal is already active"); }
        reload();
        recoveryRequest = null; inventoryRecoveryRequest = null; inventoryRecoveryActive = false;
        if (unavailable() || pending()) { block(stateProblem()); return; }
        if (!enabled || !depots.maintenanceIdle() || !safePlayerFrame()) {
            block("Direct surplus disposal requires its explicit setting and an idle player inventory."); return;
        }
        plan = Objects.requireNonNull(selectedPlan);
        inPlace = atCurrentPosition;
        if (inPlace) {
            ownedPlayerInventory = null; localDiscardSequence = null; discardPacketBarrier = null;
            RunContext current = MinecraftRunContext.capture(client);
            directContext = new MossDepositJournal.Context(current.worldIdentityHash(), current.dimension(), plan.planId(),
                    SurplusDisposalAuthorization.SHOP_RECEIPT_ID, 0, 0, 0);
            directRegistered = List.of(directContext);
            world = client.world; player = client.player; connection = client.getNetworkHandler();
            localPosition = client.player.getPos();
            proof = null; baseline = List.of(); failed = false; authorizationRefreshes = 0; reopenRequests = 0;
            phase = Phase.PREPARING;
            shopReceipt.open();
            detail = "Reading the shop inventory receipt at the current position before surplus cleanup.";
            return;
        }
        directRegistered = registeredContexts();
        directContext = directRegistered.stream().min(java.util.Comparator.comparingDouble(context ->
                client.player.squaredDistanceTo(context.depotX() + 0.5, context.depotY() + 0.5, context.depotZ() + 0.5))).orElse(null);
        if (directContext == null || !contextMatches(directContext)) {
            block("Direct disposal needs a registered chest for inventory confirmation."); return;
        }
        world = client.world; player = client.player; connection = client.getNetworkHandler();
        proof = null; failed = false; receiptTicks = 0;
        authorizationRefreshes = 0;
        phase = Phase.PREPARING;
        String problem = depots.beginMossChest(new DepotId(directContext.depotId()), box -> true);
        if (!problem.isBlank()) { block("The direct-disposal inventory receipt chest is unavailable."); return; }
        detail = "Reading one inventory receipt before direct disposal; pickup storage is disabled.";
    }

    private void openLocalReceipt() {
        if (++reopenRequests > 3) { block("In-place disposal receipt reopen budget exhausted; intent retained."); return; }
        phase = Phase.REOPENING;
        shopReceipt.open();
        detail = "Confirming the discarded stack through a fresh shop inventory receipt at the same position.";
    }

    private void tickInPlace() throws IOException {
        if (!contextMatches(directContext) || client.player.getPos().squaredDistanceTo(localPosition) > 0.25) {
            block("Player position or context changed during in-place cleanup; no discard is replayed."); return;
        }
        if (phase == Phase.PREPARING) {
            var received = shopReceipt.tick(directContext);
            if (received.isEmpty()) { return; }
            var observed = received.orElseThrow();
            shopReceipt.close();
            if (!InventoryCleanupPolicy.hasSurplus(new MinecraftInventoryPort(client).observation())) {
                phase = Phase.CONFIRMED; detail = "No approved plain surplus remains to discard."; return;
            }
            proof = proof == null ? SurplusDisposalAuthorization.inPlace(observed, System.nanoTime())
                    : proof.refresh(observed, System.nanoTime());
            if (!matchesStoredInventory(observed.slots())) { refreshAuthorization(); return; }
            baseline = copyInventory(); receiptTicks = 0; phase = Phase.LOCAL_READY;
            localDiscardSequence = new PlayerInventoryDiscardSequence(); discardPacketBarrier = null;
            detail = "Waiting for the shop to close before opening the player inventory for disposal.";
            return;
        }
        if (phase == Phase.LOCAL_READY) {
            if (ownedPlayerInventory == null ? !preparePlayerFrame() : !ownedPlayerInventoryFrame()) {
                block("In-place cleanup requires its own player inventory, never a shop menu."); return;
            }
            if (!SurplusDisposalPolicy.freshAuthorization(proof, System.nanoTime()) || !baselineMatches()) {
                closePlayerInventory();
                refreshAuthorization(); return;
            }
            if (client.player.getVelocity().lengthSquared() >= 0.0001) {
                if (++receiptTicks > 20) { block("In-place cleanup waits for player movement to stop."); }
                return;
            }
            var action = localDiscardSequence.tick(ownedPlayerInventory == null
                    ? PlayerInventoryDiscardSequence.View.CLOSED : PlayerInventoryDiscardSequence.View.OWNED_INVENTORY, false);
            if (action == PlayerInventoryDiscardSequence.Action.OPEN_INVENTORY) {
                ownedPlayerInventory = new net.minecraft.client.gui.screen.ingame.InventoryScreen(client.player);
                client.setScreen(ownedPlayerInventory);
                detail = "Player inventory is open; waiting before discarding one approved plain stack.";
            } else if (action == PlayerInventoryDiscardSequence.Action.DISCARD_ONCE) {
                controller.beginAuthorized(true, proof, null, client.player.getUuidAsString(), System.nanoTime());
                localDiscardSequence.dispatched();
                phase = Phase.LOCAL_SETTLING;
                detail = "Discard sent from player inventory; waiting for server updates before reopening the shop.";
            } else if (action == PlayerInventoryDiscardSequence.Action.BLOCKED) {
                block("The player inventory changed before disposal; no shop-menu discard is allowed.");
            }
            return;
        }
        if (phase == Phase.LOCAL_SETTLING) {
            var view = ownedPlayerInventoryFrame() ? PlayerInventoryDiscardSequence.View.OWNED_INVENTORY
                    : PlayerInventoryDiscardSequence.View.FOREIGN;
            var action = localDiscardSequence.tick(view, receivedDiscardSlotUpdate());
            if (action == PlayerInventoryDiscardSequence.Action.BLOCKED) {
                block("The player inventory changed after disposal; preserve the pending intent without replay.");
            } else if (action == PlayerInventoryDiscardSequence.Action.OPEN_RECEIPT) {
                closePlayerInventory();
                if (!safePlayerFrame()) { block("Player inventory must be closed before the receipt shop opens."); return; }
                openLocalReceipt();
            }
            return;
        }
        if (phase == Phase.REOPENING) {
            var received = shopReceipt.tick(directContext);
            if (received.isEmpty()) { return; }
            var observed = received.orElseThrow();
            var result = inventoryRecoveryActive ? controller.reconcileInventory(inventoryRecoveryRequest, observed)
                    : controller.reconcile(observed);
            if (result == SurplusDisposalController.Result.UNCERTAIN && !inventoryRecoveryActive) {
                inventoryRecoveryActive = true;
                result = controller.reconcileInventory(inventoryRecoveryRequest, observed);
            }
            switch (result) {
                case WAITING -> { }
                case UNCERTAIN -> block("The in-place disposal receipt changed beyond the approved stack; intent retained.");
                case REOPEN_REQUIRED -> { shopReceipt.close(); openLocalReceipt(); }
                case CONFIRMED -> {
                    shopReceipt.close();
                    confirmedStacks++;
                    confirmedItems += journal().orElseThrow().before().slots().main()
                            .get(journal().orElseThrow().sourceMainIndex()).stack().count();
                    phase = Phase.CONFIRMED; baseline = List.of(); proof = null;
                    detail = "Approved surplus discarded and confirmed in place; no travel or build credit.";
                }
                case OPERATOR_RECONCILED -> block("Unexpected operator recovery in an in-place disposal receipt.");
                case INVENTORY_RECONCILED -> {
                    shopReceipt.close();
                    phase = Phase.CONFIRMED; baseline = List.of(); proof = null;
                    detail = "Current inventory reconciled through two stable server receipts; original discard remains unverified with zero discard or build credit.";
                    inventoryRecoveryRequest = null; inventoryRecoveryActive = false;
                }
            }
        }
    }

    private void tickPreparation() {
        if (pending() || unavailable()) { block("Disposal preparation cannot replace an unresolved intent."); return; }
        if (!contextMatches(directContext) || !new HashSet<>(registeredContexts()).equals(new HashSet<>(directRegistered))) {
            block("Direct-disposal context or registered receipt chest changed."); return;
        }
        if (!prepareReceiptScreen()) { return; }
        depots.tick();
        if (depots.automationBlocked()) { block("Direct-disposal inventory observation lost depot ownership."); return; }
        var handler = depots.mossChest();
        if (handler == null) {
            if (!depots.mossChestActive()) { block("The direct-disposal receipt chest could not be opened."); }
            return;
        }
        if (++receiptTicks > RECEIPT_TIMEOUT_TICKS) { block("Direct-disposal inventory receipt timed out; nothing was discarded."); return; }
        var packet = ServerInventorySnapshotObserver.latestMatching(client.world, client.getNetworkHandler(), handler);
        if (packet.isEmpty()) { return; }
        var captured = MinecraftMossDepositCapture.capture(client, handler, packet.orElseThrow());
        if (captured.isEmpty() || !MinecraftMossDepositCapture.matchesLiveBaseline(client, handler, captured.orElseThrow())) { return; }
        var observed = new MossDepositJournal.Observation(directContext, packet.orElseThrow().stamp(), captured.orElseThrow());
        proof = proof == null ? SurplusDisposalAuthorization.direct(directRegistered, observed, System.nanoTime())
                : proof.refresh(observed, System.nanoTime());
        depots.observeMossStock(handler, packet.orElseThrow());
        if (!depots.closeMossChest().isBlank()) { block("Direct-disposal receipt chest cleanup is blocked."); return; }
        prepareFlight();
    }

    private void refreshAuthorization() {
        if (proof == null || !proof.canRefresh(pending(), authorizationRefreshes) || unavailable()
                || !sameContext() || !registryMatches() || !contextMatches(proof.finalInventory().context())
                || !depots.maintenanceIdle() || !safePlayerFrame()) {
            block("Disposal receipt refresh is unavailable or its bounded budget is exhausted; nothing was discarded."); return;
        }
        authorizationRefreshes++;
        if (inPlace) {
            baseline = List.of();
            phase = Phase.PREPARING;
            shopReceipt.open();
            detail = "Refreshing the in-place inventory receipt before any discard.";
            return;
        }
        flight.stop();
        baseline = List.of(); candidates = List.of();
        directRegistered = proof.registered(); directContext = proof.finalInventory().context();
        phase = Phase.PREPARING; receiptTicks = 0;
        String problem = depots.beginMossChest(new DepotId(directContext.depotId()), box -> true);
        if (!problem.isBlank()) { block("The disposal refresh receipt chest is unavailable."); return; }
        detail = "Refreshing the full pre-dispatch inventory receipt before rechecking disposal authorization.";
    }

    private void prepareFlight() {
        if (!plan.planId().equals(proof.finalInventory().context().planId()) || !registryMatches()
                || !contextMatches(proof.finalInventory().context()) || !depots.maintenanceIdle() || !safePlayerFrame()) {
            block("Surplus disposal context, depot ownership, or player inventory is unavailable."); return;
        }
        world = client.world; player = client.player; connection = client.getNetworkHandler();
        if (!matchesStoredInventory(proof.finalInventory().slots())) {
            refreshAuthorization(); return;
        }
        baseline = copyInventory();
        candidates = SurplusDisposalPolicy.candidates(plan.buildVolume(), proof.registered(),
                new BlockPosition(client.player.getBlockX(), client.player.getBlockY(), client.player.getBlockZ()),
                client.world.getBottomY(), client.world.getTopYInclusive());
        failed = false; candidateIndex = 0; scanIndex = 0; reopenRequests = 0; settlingTicks = 0;
        phase = Phase.SCANNING;
        detail = "Checking received empty space to the world bottom away from the build and depots.";
    }

    /** A restored intent never dispatches; it can only travel to acquire a new full inventory receipt. */
    void resumeReceipt(String planId) {
        requireClientThread();
        if (active()) { throw new IllegalStateException("Surplus disposal is already active"); }
        reload();
        inPlace = journal().map(value -> value.storage().mode() == SurplusDisposalAuthorization.Mode.IN_PLACE_SHOP).orElse(false);
        if (!pending() || !resumeProblem(planId).isBlank()) {
            block("The pending disposal no longer matches its original build, player, or registered depot."); return;
        }
        if (!depots.maintenanceIdle()) {
            block("Pending disposal receipt is waiting for depot maintenance to settle."); return;
        }
        world = client.world; player = client.player; connection = client.getNetworkHandler();
        if (inPlace) {
            localPosition = client.player.getPos();
            directContext = journal().orElseThrow().before().context();
            if (!preparePlayerFrame()) { block("Pending in-place disposal requires an idle player inventory."); return; }
            ownedPlayerInventory = null; localDiscardSequence = null; discardPacketBarrier = null;
            try { inventoryRecoveryRequest = loadInventoryRecoveryRequest(); }
            catch (IOException | RuntimeException invalid) {
                block("Scoped inventory recovery request is invalid or expired; original intent is preserved."); return;
            }
            inventoryRecoveryActive = inventoryRecoveryRequest != null;
            failed = false; reopenRequests = 0;
            openLocalReceipt();
            return;
        }
        if (!preparePlayerFrame()) {
            block("Pending disposal receipt: " + playerFrameProblem(true)); return;
        }
        try { recoveryRequest = loadRecoveryRequest(); }
        catch (IOException | RuntimeException invalid) {
            block("Explicit disposal recovery request is invalid or expired; original intent is preserved."); return;
        }
        failed = false; reopenRequests = 0;
        site = journal().orElseThrow().site();
        beginReceiptRoute();
    }

    void tick() {
        requireClientThread();
        if (!active()) { return; }
        try {
            if (world != client.world || player != client.player || connection != client.getNetworkHandler()) {
                block("Surplus disposal connection changed; preserve the existing intent without replay."); return;
            }
            if (inPlace) { tickInPlace(); return; }
            if (phase == Phase.PREPARING) { tickPreparation(); return; }
            if (phase == Phase.REOPENING) { tickReceipt(); return; }
            if (!preparePlayerFrame()) { block("Surplus disposal paused: " + playerFrameProblem(true)); return; }
            if (phase == Phase.SCANNING || phase == Phase.FLYING) {
                if (!registryMatches()) { block("Disposal registered receipt chests changed before disposal."); return; }
                if (!SurplusDisposalPolicy.freshAuthorization(proof, System.nanoTime()) || !baselineMatches()) {
                    refreshAuthorization(); return;
                }
            }
            switch (phase) {
                case SCANNING -> tickScan();
                case FLYING -> {
                    flight.tick(); detail = flight.detail();
                    if (flight.failed()) { block("No safe flight route to the verified disposal site."); }
                    else if (flight.arrived()) {
                        if (client.player.getVelocity().lengthSquared() > 0.0001) {
                            if (++settlingTicks > 20) { block("Disposal flight pose did not settle; no items were discarded."); }
                            else { detail = "Waiting for the reached disposal flight pose to settle."; }
                            return;
                        }
                        controller.beginAuthorized(true, proof, site, client.player.getUuidAsString(), System.nanoTime());
                        beginReceiptRoute();
                    }
                }
                case EGRESS -> {
                    flight.tick(); detail = "Moving horizontally away from the disposal column before receipt travel. " + flight.detail();
                    if (flight.failed()) { block("Disposal egress failed; retain the intent for observation only."); }
                    else if (flight.arrived()) { flight.stop(); openReceipt(); }
                }
                default -> { }
            }
        } catch (IOException | RuntimeException failure) {
            if (failure instanceof MinecraftShopInventoryReceipt.ReadTimeout && deferOptionalReadTimeout()) {
                LOGGER.warn("Optional in-place cleanup deferred before any discard: {}", failure.getMessage());
                return;
            }
            LOGGER.warn("Surplus disposal failed in phase {}; the original intent is retained without another THROW", phase, failure);
            block("Surplus disposal stopped with an unavailable result; no THROW will be repeated.");
        }
    }

    private boolean deferOptionalReadTimeout() {
        try {
            if (!InventoryCleanupPolicy.mayDeferReadTimeout(inPlace, phase == Phase.PREPARING, pending(), unavailable(),
                    new MinecraftInventoryPort(client).observation()) || !sameContext() || !contextMatches(directContext)) { return false; }
            shopReceipt.close();
            if (!safePlayerFrame()) { return false; }
        } catch (RuntimeException unsettledMenu) { return false; }
        phase = Phase.DEFERRED; failed = false; baseline = List.of(); proof = null;
        detail = "Cleanup inventory read timed out before any discard; enough room remains, so buying and building continue until the next cleanup interval.";
        return true;
    }

    private void tickScan() {
        int budget = SurplusDisposalPolicy.SCAN_BUDGET;
        while (budget-- > 0 && candidateIndex < candidates.size()) {
            site = candidates.get(candidateIndex);
            if (!receivedAir(site.cell(scanIndex))) { candidateIndex++; scanIndex = 0; continue; }
            if (++scanIndex == site.cells()) {
                phase = Phase.FLYING;
                BlockPosition feet = site.feet();
                flight.beginReturnTo(new BlockPos(feet.x(), feet.y(), feet.z()), 0.2);
                detail = "Flying to the verified disposal column.";
                return;
            }
        }
        if (candidateIndex >= candidates.size()) {
            block("No bounded received empty disposal column is available; no items were discarded.");
        }
    }

    @Override public boolean matchesForDispatch(SurplusDisposalJournal intent) {
        if (intent.storage().mode() == SurplusDisposalAuthorization.Mode.IN_PLACE_SHOP) {
            return inPlace && phase == Phase.LOCAL_READY && intent.site() == null && ownedPlayerInventoryFrame() && sameContext()
                    && contextMatches(intent.before().context()) && intent.playerUuid().equals(client.player.getUuidAsString())
                    && proof != null && intent.before().equals(proof.finalInventory())
                    && SurplusDisposalPolicy.freshAuthorization(proof, System.nanoTime()) && baselineMatches()
                    && client.player.getPos().squaredDistanceTo(localPosition) < 0.01
                    && client.player.getVelocity().lengthSquared() < 0.0001;
        }
        if (phase != Phase.FLYING || !flight.arrived() || !safePlayerFrame() || !sameContext()
                || !SurplusDisposalPolicy.freshAuthorization(proof, System.nanoTime()) || !registryMatches()
                || !baselineMatches() || !intent.before().equals(proof.finalInventory())
                || intent.storage().mode() != proof.mode()
                || !intent.site().equals(site) || !SurplusDisposalPolicy.separated(site, plan.buildVolume(), proof.registered())) { return false; }
        BlockPosition feet = site.feet();
        if (client.player.squaredDistanceTo(feet.x() + 0.5, feet.y(), feet.z() + 0.5) > 0.3 * 0.3
                || !intent.playerUuid().equals(client.player.getUuidAsString())
                || !client.world.isSpaceEmpty(client.player, client.player.getBoundingBox())
                || client.player.getVelocity().lengthSquared() > 0.0001) { return false; }
        int slotIndex = playerSlot(intent.sourceMainIndex());
        var slot = client.player.playerScreenHandler.getSlot(slotIndex);
        ItemStack source = slot.getStack();
        return slot.inventory == client.player.getInventory() && slot.getIndex() == intent.sourceMainIndex()
                && slot.canTakeItems(client.player) && SurplusPickupPolicy.allowed(Registries.ITEM.getId(source.getItem()).toString())
                && ItemStack.areItemsAndComponentsEqual(source, new ItemStack(source.getItem()))
                && site.clear(this::receivedAir);
    }

    @Override public void throwOnce(SurplusDisposalJournal intent) throws IOException {
        if (!matchesForDispatch(intent)) { throw new IOException("Disposal guards changed after durable intent"); }
        if (inPlace) {
            discardPacketBarrier = ServerPlayerInventoryObserver.mark(client);
            // A downward building pitch drops items at the player's feet. Send a level look before the drop.
            client.player.setPitch(0.0f);
            client.getNetworkHandler().sendPacket(new net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket.LookAndOnGround(
                    client.player.getYaw(), 0.0f, client.player.isOnGround(), client.player.horizontalCollision));
        }
        int slot = playerSlot(intent.sourceMainIndex());
        var handler = client.player.playerScreenHandler;
        Runnable click = () -> client.interactionManager.clickSlot(handler.syncId, slot, 1, SlotActionType.THROW, client.player);
        if (custody == null) { click.run(); }
        else { custody.clickSlot(handler, slot, 1, SlotActionType.THROW, click); }
    }

    private void beginReceiptRoute() {
        flight.stop();
        var context = journal().orElseThrow().before().context();
        double dx = client.player.getX() - (site.feet().x() + 0.5);
        double dz = client.player.getZ() - (site.feet().z() + 0.5);
        if (dx * dx + dz * dz < 22 * 22) {
            // Leave horizontally, above the falling item, before any route may descend toward a chest.
            if (client.player.getY() < site.feet().y() - 0.3) {
                block("Pending disposal requires safe receipt travel above its original drop height."); return;
            }
            int x = site.feet().x(), z = site.feet().z();
            if (Math.abs(context.depotX() - x) >= Math.abs(context.depotZ() - z)) { x += context.depotX() >= x ? 24 : -24; }
            else { z += context.depotZ() >= z ? 24 : -24; }
            flight.beginReturnTo(new BlockPos(x, Math.max(site.feet().y(), client.player.getBlockY()), z), 0.2);
            phase = Phase.EGRESS;
        } else { openReceipt(); }
    }

    private void openReceipt() {
        if (++reopenRequests > 2) { block("Disposal full receipt reopen budget is exhausted; preserve the pending intent."); return; }
        var context = journal().orElseThrow().before().context();
        if (!contextMatches(context)) { block("Disposal receipt depot or build context changed."); return; }
        String problem = depots.beginMossChest(new DepotId(context.depotId()), this::permitsPendingTravel);
        if (!problem.isBlank()) { block("The original disposal receipt chest is unavailable."); return; }
        phase = Phase.REOPENING; receiptTicks = 0;
        detail = "Reopening the original registered depot for the exact post-disposal server inventory receipt.";
    }

    private void tickReceipt() throws IOException {
        var context = journal().orElseThrow().before().context();
        if (!contextMatches(context)) { block("Disposal receipt context changed."); return; }
        if (!prepareReceiptScreen()) { return; }
        depots.tick();
        if (depots.automationBlocked()) { block("Depot ownership is unresolved during disposal receipt."); return; }
        var handler = depots.mossChest();
        if (handler == null) {
            if (!depots.mossChestActive()) { block("The disposal receipt chest could not be opened."); }
            return;
        }
        if (++receiptTicks > RECEIPT_TIMEOUT_TICKS) { block("Timed out waiting for disposal receipt; no input will be replayed."); return; }
        var packet = ServerInventorySnapshotObserver.latestMatching(client.world, client.getNetworkHandler(), handler);
        if (packet.isEmpty()) { return; }
        var captured = MinecraftMossDepositCapture.capture(client, handler, packet.orElseThrow());
        if (captured.isEmpty() || !MinecraftMossDepositCapture.matchesLiveBaseline(client, handler, captured.orElseThrow())) { return; }
        depots.observeMossStock(handler, packet.orElseThrow());
        var observed = new MossDepositJournal.Observation(context, packet.orElseThrow().stamp(), captured.orElseThrow());
        if (recoveryRequest != null && (!recoveryRequest.fresh(Instant.now()) || !acknowledgedHoesRepaired())) {
            block("Acknowledged disposal recovery needs the same repaired hoes at full durability."); return;
        }
        var result = recoveryRequest == null ? controller.reconcile(observed)
                : controller.reconcileOperator(recoveryRequest, observed);
        switch (result) {
            case WAITING -> { }
            case UNCERTAIN -> block("Disposal inventory receipt differs from the exact approved one-stack decrease.");
            case REOPEN_REQUIRED -> {
                if (!depots.closeMossChest().isBlank()) { block("Disposal receipt chest could not be safely closed."); }
                else { openReceipt(); }
            }
            case CONFIRMED -> {
                confirmedStacks++;
                confirmedItems += journal().orElseThrow().before().slots().main()
                        .get(journal().orElseThrow().sourceMainIndex()).stack().count();
                if (!depots.closeMossChest().isBlank()) { block("Disposal receipt confirmed, but chest cleanup is blocked."); return; }
                phase = Phase.CONFIRMED;
                detail = "One approved surplus stack left inventory with a durable full server receipt; no build credit changed.";
                baseline = List.of(); proof = null; candidates = List.of();
            }
            case OPERATOR_RECONCILED -> {
                if (!depots.closeMossChest().isBlank()) { block("Operator recovery is saved, but receipt chest cleanup is blocked."); return; }
                phase = Phase.CONFIRMED;
                detail = "Acknowledged manual cleanup and hoe repairs match two stable server inventory receipts; no discard or build credit was added.";
                baseline = List.of(); proof = null; candidates = List.of(); recoveryRequest = null;
            }
            case INVENTORY_RECONCILED -> block("Inventory recovery cannot replace a physical depot receipt.");
        }
    }

    private SurplusDisposalRecovery.Request loadRecoveryRequest() throws IOException {
        if (!Files.exists(recoveryRequestPath, LinkOption.NOFOLLOW_LINKS)) { return null; }
        if (!Files.isRegularFile(recoveryRequestPath, LinkOption.NOFOLLOW_LINKS) || Files.size(recoveryRequestPath) > 4096) {
            throw new IOException("Recovery acknowledgement must be a bounded regular file");
        }
        var json = com.google.gson.JsonParser.parseString(Files.readString(recoveryRequestPath));
        var gson = new com.google.gson.GsonBuilder().serializeNulls().create();
        var request = gson.fromJson(json, SurplusDisposalRecovery.Request.class);
        if (request == null || !gson.toJsonTree(request).equals(json)) { throw new IOException("Incomplete recovery acknowledgement"); }
        if (!request.matches(journal().orElseThrow())) { return null; }
        if (!request.fresh(Instant.now())) { throw new IOException("Recovery acknowledgement expired"); }
        return request;
    }

    private SurplusInventoryRecovery.Request loadInventoryRecoveryRequest() throws IOException {
        Path path = recoveryRequestPath.resolveSibling("surplus-inventory-resume-request.json");
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) { return null; }
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) > 16_384) {
            throw new IOException("Inventory resume request must be a bounded regular file");
        }
        var json = com.google.gson.JsonParser.parseString(Files.readString(path));
        var gson = new com.google.gson.GsonBuilder().serializeNulls().create();
        var request = gson.fromJson(json, SurplusInventoryRecovery.Request.class);
        if (request == null || !gson.toJsonTree(request).equals(json)) { throw new IOException("Incomplete inventory resume request"); }
        if (!request.matches(journal().orElseThrow())) { return null; }
        if (!request.fresh(Instant.now())) { throw new IOException("Inventory resume request expired"); }
        return request;
    }

    private boolean acknowledgedHoesRepaired() {
        for (int slot : recoveryRequest.repairedMainSlots()) {
            ItemStack stack = client.player.getInventory().getStack(slot);
            if (stack.getCount() != 1 || !stack.isDamageable() || stack.getDamage() != 0) { return false; }
        }
        return true;
    }

    void cancel(String reason) {
        cancellationSequence++;
        if (controller != null) { controller.cancel(); }
        if (active()) { block("Surplus disposal cancelled; any existing intent is observation-only."); }
    }
    private void block(String reason) {
        cancellationSequence++;
        flight.stop();
        if (inPlace) {
            try { closePlayerInventory(); } catch (RuntimeException changedInventory) { /* Foreign screens stay untouched. */ }
            shopReceipt.cancel();
        }
        if (!inPlace && (phase == Phase.PREPARING || phase == Phase.REOPENING)) { depots.cancelMaintenance(); }
        phase = Phase.BLOCKED; failed = true; detail = reason;
        // Atomic replacement may commit and then report failure: publish the durable pending state before Resume.
        reload();
        if (controller != null) { controller.cancel(); }
        baseline = List.of(); candidates = List.of();
    }
    private void reload() {
        try {
            controller = new SurplusDisposalController(store, this);
            // Restore receipt ownership before placement loading checks the saved context.
            inPlace = journal().map(value -> value.storage().mode()
                    == SurplusDisposalAuthorization.Mode.IN_PLACE_SHOP).orElse(false);
            loadFailure = "";
        }
        catch (IOException | RuntimeException invalid) {
            controller = null; loadFailure = "Surplus disposal journal is unavailable; preserve it without another THROW.";
        }
    }
    private boolean sameContext() { return world == client.world && player == client.player && connection == client.getNetworkHandler(); }
    private boolean ownedPlayerInventoryFrame() {
        return ownedPlayerInventory != null && client.currentScreen == ownedPlayerInventory && sameContext()
                && playerFrameProblem(false).isBlank();
    }
    private void closePlayerInventory() {
        if (ownedPlayerInventory == null) { return; }
        if (!ownedPlayerInventoryFrame()) { throw new IllegalStateException("The owned player inventory changed"); }
        client.player.closeHandledScreen();
        ownedPlayerInventory = null;
    }
    private boolean receivedDiscardSlotUpdate() {
        if (discardPacketBarrier == null || !sameContext() || client.player == null || !pending()) { return false; }
        int source = journal().orElseThrow().sourceMainIndex();
        return ServerPlayerInventoryObserver.latest(client, source).filter(update ->
                update.stamp().epoch().equals(discardPacketBarrier.epoch())
                && update.stamp().sequence() > discardPacketBarrier.sequence() && update.stack().isEmpty()
                && client.player.getInventory().getStack(source).isEmpty()).isPresent();
    }
    private boolean permitsPendingTravel(net.minecraft.util.math.Box body) {
        return !pending() || SurplusDisposalPolicy.permitsTravel(journal().orElseThrow().site(), body);
    }
    private boolean preparePlayerFrame() {
        long expectedCancellation = cancellationSequence;
        var transition = MinecraftBackgroundBuildAccess.leavePassiveScreenForRestock(client,
                () -> cancellationSequence == expectedCancellation && sameContext() && playerFrameProblem(false).isBlank());
        return cancellationSequence == expectedCancellation
                && transition != BackgroundBuildPolicy.RestockTransition.INVALIDATED && safePlayerFrame();
    }
    private boolean prepareReceiptScreen() {
        if (client.player != null && client.player.currentScreenHandler == client.player.playerScreenHandler
                && client.currentScreen != null && !preparePlayerFrame()) {
            block("Disposal inventory receipt: " + playerFrameProblem(true));
            return false;
        }
        return true;
    }
    private boolean safePlayerFrame() { return playerFrameProblem(true).isBlank(); }
    private String playerFrameProblem(boolean requireClosedScreen) {
        if (!client.isOnThread() || client.player == null || client.world == null || client.interactionManager == null
                || client.getNetworkHandler() == null || !client.getNetworkHandler().isConnectionOpen()) {
            return "The original connected player is unavailable.";
        }
        if (client.getOverlay() != null) { return "A loading overlay is open."; }
        if (client.player.currentScreenHandler != client.player.playerScreenHandler || client.player.playerScreenHandler.syncId != 0) {
            return "A foreign inventory handler is open.";
        }
        if (!client.player.playerScreenHandler.getCursorStack().isEmpty()) { return "The player is holding an item on the cursor."; }
        if (requireClosedScreen && client.currentScreen != null || !MinecraftBackgroundBuildAccess.allowsWorldActions(client)) {
            return "The open screen cannot be safely left for the inventory receipt.";
        }
        if (!client.player.getAbilities().allowFlying || !client.player.getAbilities().flying
                || client.player.isSpectator() || client.player.hasVehicle() || !client.player.isAlive()) {
            return "The player must be alive in permitted active flight.";
        }
        if (MinecraftBackgroundBuildAccess.mouseButtonHeld(client)) {
            return "A held mouse button is an active player interaction.";
        }
        if (!predictionsEmpty()) { return "A world interaction is still awaiting confirmation."; }
        for (int slot = 0; slot <= 4; slot++) {
            if (!client.player.playerScreenHandler.getSlot(slot).getStack().isEmpty()) { return "Crafting inputs or output are occupied."; }
        }
        return "";
    }
    private boolean predictionsEmpty() {
        var manager = ((ClientWorldPendingUpdatesAccessor) client.world).supervisor$getPendingUpdateManager();
        return ((PendingBlockUpdatesAccessor) manager).supervisor$getPendingBlockUpdates().isEmpty();
    }
    private boolean receivedAir(BlockPosition position) {
        BlockPos pos = new BlockPos(position.x(), position.y(), position.z());
        return ClientChunkAvailability.isLoaded(client.world, pos) && client.world.getWorldBorder().contains(pos)
                && client.world.getBlockState(pos).isAir() && client.world.getFluidState(pos).isEmpty();
    }
    private boolean contextMatches(MossDepositJournal.Context expected) {
        try {
            RunContext current = MinecraftRunContext.capture(client);
            return (!pending() || journal().orElseThrow().playerUuid().equals(client.player.getUuidAsString()))
                    && current.worldIdentityHash().equals(expected.worldIdentityHash()) && current.dimension().equals(expected.dimension())
                    && (inPlace ? SurplusDisposalAuthorization.shopContext(expected)
                            : depots.mossStorageCandidates().stream().anyMatch(depot -> sameDepot(expected, depot)));
        } catch (RuntimeException unavailableContext) { return false; }
    }
    private boolean registryMatches() {
        if (proof == null) { return false; }
        if (inPlace) { return proof.mode() == SurplusDisposalAuthorization.Mode.IN_PLACE_SHOP
                && contextMatches(proof.finalInventory().context()); }
        var current = registeredContexts();
        return current.size() == proof.registered().size() && new HashSet<>(current).equals(new HashSet<>(proof.registered()));
    }
    private List<MossDepositJournal.Context> registeredContexts() {
        return depots.mossStorageCandidates().stream().map(depot -> new MossDepositJournal.Context(
                depot.worldIdentityHash(), depot.dimension(), plan.planId(), depot.id().value(), depot.x(), depot.y(), depot.z())).toList();
    }
    private static boolean sameDepot(MossDepositJournal.Context expected, RegisteredDepot depot) {
        return depot.id().value().equals(expected.depotId()) && depot.worldIdentityHash().equals(expected.worldIdentityHash())
                && depot.dimension().equals(expected.dimension()) && depot.x() == expected.depotX()
                && depot.y() == expected.depotY() && depot.z() == expected.depotZ();
    }
    private List<ItemStack> copyInventory() {
        List<ItemStack> result = new ArrayList<>(41);
        for (int index = 0; index < 40; index++) { result.add(client.player.getInventory().getStack(index).copy()); }
        result.add(client.player.getOffHandStack().copy());
        return List.copyOf(result);
    }
    private boolean baselineMatches() {
        if (baseline.size() != 41) { return false; }
        for (int index = 0; index < 40; index++) {
            if (!ItemStack.areEqual(baseline.get(index), client.player.getInventory().getStack(index))) { return false; }
        }
        return ItemStack.areEqual(baseline.get(40), client.player.getOffHandStack());
    }
    private boolean matchesStoredInventory(MossDepositFacts.Snapshot before) {
        try {
            MossStackFingerprint.Budget budget = new MossStackFingerprint.Budget();
            for (int index = 0; index < 36; index++) {
                if (!matches(client.player.getInventory().getStack(index), before.main().get(index).stack(), budget)) { return false; }
            }
            for (int index = 0; index < 4; index++) {
                if (!matches(client.player.getInventory().getStack(36 + index), before.armor().get(index), budget)) { return false; }
            }
            return matches(client.player.getOffHandStack(), before.offhand(), budget) && before.cursor().empty();
        } catch (RuntimeException | StackOverflowError invalid) { return false; }
    }
    private boolean matches(ItemStack stack, MossDepositFacts.StackFacts facts, MossStackFingerprint.Budget budget) {
        String id = stack.isEmpty() ? "minecraft:air" : Registries.ITEM.getId(stack.getItem()).toString();
        if (!id.equals(facts.itemId()) || stack.getCount() != facts.count() || stack.isEmpty() != facts.empty()) { return false; }
        var json = stack.isEmpty() ? JsonNull.INSTANCE : ItemStack.CODEC.encodeStart(
                RegistryOps.of(JsonOps.INSTANCE, client.world.getRegistryManager()), stack).result().orElseThrow();
        return MossStackFingerprint.fingerprint(id, stack.getCount(), json, budget).filter(facts.fingerprint()::equals).isPresent();
    }
    private static int playerSlot(int main) { return main < 9 ? main + 36 : main; }
    private void requireClientThread() {
        if (!client.isOnThread()) { throw new IllegalStateException("Surplus disposal requires the client thread"); }
    }
}
