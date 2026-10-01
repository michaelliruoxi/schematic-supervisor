package io.github.schematicsupervisor.fabric;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientCommonNetworkHandler;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.item.ItemStack;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.c2s.play.ClickSlotC2SPacket;
import net.minecraft.network.packet.c2s.play.CraftRequestC2SPacket;
import net.minecraft.network.packet.c2s.play.CreativeInventoryActionC2SPacket;
import net.minecraft.network.packet.c2s.play.PickItemFromBlockC2SPacket;
import net.minecraft.network.packet.c2s.play.PickItemFromEntityC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerActionC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerInteractBlockC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerInteractEntityC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerInteractItemC2SPacket;
import net.minecraft.network.packet.c2s.play.SelectMerchantTradeC2SPacket;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;

/** Optional clearing-tool custody. It observes packets, never edits or sends them, and never rearms taint. */
public final class MossToolCustody {
    private static volatile MossToolCustody registered;
    private final MinecraftClient client;
    private final MossMiningToolGuard<ItemStack> guard;
    private Object world;
    private Object player;
    private Object connection;
    private Frame expected;
    private PendingMove pending;
    private Scope scope;
    private volatile boolean tainted;
    private volatile String detail = "Moss tool custody has not been observed yet.";

    MossToolCustody(MinecraftClient client, MossMiningToolGuard<ItemStack> guard) {
        this.client = Objects.requireNonNull(client);
        this.guard = Objects.requireNonNull(guard);
        if (registered != null && registered != this) {
            registered.taint("Moss tool custody ownership was replaced.");
            taint("Moss tool custody ownership was replaced.");
        }
        registered = this;
    }

    /** This owner must outlive all execution adapters. Disconnect and replacement never clear its taint. */
    void tick() {
        try {
            if (expected == null) { return; }
            if (!bindContext()) { return; }
            Frame current = readFrame();
            if (current == null) { taint("Established tool custody became incomparable."); return; }
            observeFrame(current);
            resolveSlotReceipts(current);
        } catch (RuntimeException unavailable) {
            taint("Moss tool custody could not be checked.");
        }
    }

    boolean allowsHoeStart() {
        activate();
        tick();
        return !tainted && pending == null && completeCurrentFrame().isPresent();
    }

    boolean inventoryMovementPending() { return pending != null; }
    boolean tainted() { return tainted; }
    String detail() { return pending != null && !tainted
            ? "Clearing tool use is waiting for server evidence of its owned inventory move." : detail; }

    Optional<List<MossMiningToolGuard.CohortMember<ItemStack>>> stableMainCohort() {
        activate();
        tick();
        if (tainted || pending != null || scope != null) { return Optional.empty(); }
        return completeCurrentFrame().map(Frame::mainMembers);
    }

    void clickSlot(ScreenHandler handler, int slot, int button, SlotActionType action, Runnable dispatch) {
        Objects.requireNonNull(dispatch);
        activate();
        tick();
        Frame before = safeFrame();
        Swap swap = inspectSwap(handler, slot, button, action);
        PlayerInventoryUpdateLedger.Stamp mark = swap == null ? null : ServerPlayerInventoryObserver.mark(client);
        Scope authorization = Scope.click(handler, slot, button, action);
        execute(authorization, () -> { dispatch.run(); return null; });
        try {
            Frame after = readFrame();
            if (before == null || after == null) {
                taint("An owned inventory click had unavailable tool custody facts.");
                return;
            }
            boolean movedTool = swap != null && (MinecraftClearingTools.supportedFamily(swap.beforeSource())
                    || MinecraftClearingTools.supportedFamily(swap.beforeDestination()));
            if (sameCohort(before, after) && !movedTool) { expected = after; return; }
            if (pending != null || swap == null || !swap.matchesAfter(client)
                    || !sameCohort(before.moved(swap.source(), swap.destination()), after)) {
                taint("An owned inventory click changed the clearing-tool cohort unexpectedly.");
                return;
            }
            expected = after;
            pending = new PendingMove(after, swap.source(), swap.destination(), Objects.requireNonNull(mark));
        } catch (RuntimeException unavailable) {
            taint("An owned inventory move could not preserve exact tool custody.");
        }
    }

    <R> R interactBlock(Hand hand, BlockHitResult hit, Supplier<R> dispatch) {
        activate();
        tick();
        if (client.player != null && MinecraftClearingTools.supportedFamily(client.player.getStackInHand(hand))) {
            taint("Owned block interaction can wear a tool outside clearing settlement accounting.");
        }
        return execute(Scope.interact(hand, hit), dispatch);
    }

    <R> R mining(BlockPos target, Supplier<R> dispatch) {
        activate();
        tick();
        return execute(Scope.mining(target), dispatch);
    }

    void mining(BlockPos target, Runnable dispatch) {
        mining(target, () -> { dispatch.run(); return null; });
    }

    /** Called only after the depot owner accepted this exact new window and its initial full receipt. */
    boolean observeOwnedFullReceipt(GenericContainerScreenHandler handler,
                                    ServerInventorySnapshotObserver.FullSnapshot packet) {
        tick();
        if (tainted || packet == null || !bindContext() || scope != null) { return false; }
        try {
            if (handler == null || handler.getClass() != GenericContainerScreenHandler.class
                    || client.player.currentScreenHandler != handler
                    || packet.rows() != handler.getRows() || packet.stamp().syncId() != handler.syncId
                    || handler.getRows() != 3 && handler.getRows() != 6) { return false; }
            var latest = ServerInventorySnapshotObserver.latestMatching(client.world, client.getNetworkHandler(), handler);
            if (latest.isEmpty() || !latest.orElseThrow().stamp().equals(packet.stamp())) { return false; }
            List<ItemStack> slots = packet.slots();
            int chest = handler.getRows() * 9;
            if (slots.size() != chest + 36 || handler.slots.size() != slots.size()
                    || !packet.cursorStack().isEmpty() || !handler.getCursorStack().isEmpty()) { return false; }
            for (int index = 0; index < slots.size(); index++) {
                if (!ItemStack.areEqual(slots.get(index), handler.slots.get(index).getStack())) { return false; }
            }
            for (int index = 0; index < 36; index++) {
                var slot = handler.slots.get(MossDepositFacts.mainHandlerSlot(chest, index));
                if (slot.inventory != client.player.getInventory() || slot.getIndex() != index) { return false; }
            }
            Frame current = readFrame();
            if (current == null || !current.mainOnly() || !current.cursorEmpty()) { return false; }
            observeFrame(current);
            if (tainted) { return false; }
            if (pending != null) {
                if (!sameCohort(pending.after(), current)) {
                    taint("The owned depot receipt disagrees with the pending tool movement.");
                    return false;
                }
                pending = null;
            }
            expected = current;
            detail = "Moss tool custody is stable in the owned full inventory receipt.";
            return true;
        } catch (RuntimeException unavailable) {
            taint("The owned depot receipt could not establish tool custody.");
            return false;
        }
    }

    public static void beforeOutgoing(ClientCommonNetworkHandler source, Packet<?> packet) {
        MossToolCustody owner = registered;
        if (owner == null || source != owner.client.getNetworkHandler() || !mutation(packet)) { return; }
        try {
            if (!owner.client.isOnThread() || !owner.bindContext()) {
                owner.taint("A tool-affecting packet had no owned client context.");
            } else if (owner.scope == null || !owner.scope.accept(packet, owner.client)) {
                owner.taint("An unowned inventory or tool action invalidated Moss tool custody.");
            }
        } catch (RuntimeException unavailable) {
            owner.taint("An outgoing tool action could not be checked.");
        }
    }

    public static void afterIncoming(ClientPlayNetworkHandler source) {
        MossToolCustody owner = registered;
        if (owner == null || source != owner.client.getNetworkHandler()) { return; }
        // Initial join packets are not a tool-use interval. The first owned call seeds the cohort.
        if (owner.expected != null || owner.pending != null) { owner.tick(); }
    }

    private <R> R execute(Scope authorization, Supplier<R> dispatch) {
        Objects.requireNonNull(dispatch);
        if (scope != null) {
            taint("Nested automation scopes cannot establish tool custody.");
            return dispatch.get();
        }
        authorization.bind(client);
        scope = authorization;
        try {
            return dispatch.get();
        } catch (RuntimeException | Error failure) {
            taint("An owned tool or inventory action had an uncertain dispatch.");
            throw failure;
        } finally {
            scope = null;
            if (!authorization.sameContext(client)) {
                taint("Tool custody changed during an owned action.");
            }
        }
    }

    private boolean bindContext() {
        if (!client.isOnThread() || client.player == null || client.world == null
                || client.getNetworkHandler() == null || !client.getNetworkHandler().isConnectionOpen()) {
            if (world != null) { taint("The original Moss tool custody context ended."); }
            return false;
        }
        if (world == null) {
            world = client.world;
            player = client.player;
            connection = client.getNetworkHandler();
        }
        if (world != client.world || player != client.player || connection != client.getNetworkHandler()) {
            taint("The original Moss tool custody context changed.");
            return false;
        }
        return true;
    }

    private void activate() {
        if (expected != null || tainted) { return; }
        try {
            if (!bindContext()) { return; }
            Frame current = readFrame();
            if (current != null) { observeFrame(current); }
        } catch (RuntimeException unavailable) { taint("Initial tool custody could not be checked."); }
    }

    private void observeFrame(Frame current) {
        if (!current.mainOnly()) { taint("A clearing tool left the covered main inventory."); }
        if (expected == null) { expected = current; }
        else if (!sameCohort(expected, current)) { taint("The clearing-tool cohort changed outside an exact owned move."); }
    }

    private Optional<Frame> completeCurrentFrame() {
        if (!bindContext()) { return Optional.empty(); }
        Frame frame = safeFrame();
        return frame != null && frame.mainOnly() && frame.cursorEmpty()
                && expected != null && sameCohort(expected, frame) ? Optional.of(frame) : Optional.empty();
    }

    private Frame safeFrame() {
        try { return bindContext() ? readFrame() : null; }
        catch (RuntimeException unavailable) { taint("Tool custody facts were unavailable."); return null; }
    }

    private Frame readFrame() {
        Map<Integer, ItemStack> identities = new LinkedHashMap<>();
        List<MossMiningToolGuard.CohortMember<ItemStack>> members = new ArrayList<>();
        var inventory = client.player.getInventory();
        // Pinned 1.21.8 includes BODY and SADDLE after the ordinary armor/offhand indices.
        if (inventory.size() != 43) { throw new IllegalStateException("Unexpected player inventory shape"); }
        for (int slot = 0; slot < 43; slot++) {
            ItemStack stack = inventory.getStack(slot);
            if (!capture(identities, members, slot, stack)) { return null; }
        }
        var playerHandler = client.player.playerScreenHandler;
        for (int slot = 0; slot <= 4; slot++) {
            if (!capture(identities, members, 100 + slot, playerHandler.getSlot(slot).getStack())) { return null; }
        }
        var handler = client.player.currentScreenHandler;
        if (handler == null || !capture(identities, members, 200, handler.getCursorStack())) { return null; }
        if (handler != playerHandler
                && !capture(identities, members, 201, playerHandler.getCursorStack())) { return null; }
        return new Frame(Map.copyOf(identities), List.copyOf(members),
                handler.getCursorStack().isEmpty() && playerHandler.getCursorStack().isEmpty());
    }

    private boolean capture(Map<Integer, ItemStack> identities,
                                   List<MossMiningToolGuard.CohortMember<ItemStack>> members,
                                   int slot, ItemStack stack) {
        if (!MinecraftClearingTools.supportedFamily(stack)) { return true; }
        ItemStack identity = identity(stack);
        if (identity == null) { return false; }
        identities.put(slot, identity);
        if (slot < 36) {
            members.add(new MossMiningToolGuard.CohortMember<>(slot, identity.copy(), MinecraftClearingTools.durability(stack)));
        }
        return true;
    }

    private static ItemStack identity(ItemStack stack) {
        return MinecraftClearingTools.admitted(stack) ? MinecraftClearingTools.identity(stack) : null;
    }

    private Swap inspectSwap(ScreenHandler handler, int slot, int button, SlotActionType action) {
        if (action != SlotActionType.SWAP || client.player == null || handler != client.player.playerScreenHandler
                || handler != client.player.currentScreenHandler || !handler.getCursorStack().isEmpty()
                || slot < 0 || slot >= handler.slots.size() || button < 0 || button >= 9) { return null; }
        var source = handler.getSlot(slot);
        if (source.inventory != client.player.getInventory() || source.getIndex() < 0 || source.getIndex() >= 36
                || source.getIndex() == button) { return null; }
        return new Swap(source.getIndex(), button, source.getStack().copy(),
                client.player.getInventory().getStack(button).copy());
    }

    private void resolveSlotReceipts(Frame current) {
        if (tainted || pending == null || !sameCohort(pending.after(), current)) { return; }
        for (int slot : new int[] {pending.source(), pending.destination()}) {
            var observed = ServerPlayerInventoryObserver.latest(client, slot);
            if (observed.isEmpty()) { return; }
            var packet = observed.orElseThrow();
            if (!packet.stamp().epoch().equals(pending.afterStamp().epoch())
                    || packet.stamp().sequence() <= pending.afterStamp().sequence()
                    || !ItemStack.areEqual(packet.stack(), client.player.getInventory().getStack(slot))) { return; }
            ItemStack expectedTool = pending.after().identities().get(slot);
            ItemStack packetIdentity = identity(packet.stack());
            if (expectedTool == null ? MinecraftClearingTools.supportedFamily(packet.stack())
                    : packetIdentity == null || !ItemStack.areEqual(expectedTool, packetIdentity)) { return; }
        }
        pending = null;
        expected = current;
        detail = "Moss tool custody is stable after the owned inventory move.";
    }

    private void taint(String reason) {
        tainted = true;
        detail = reason;
        if (client.isOnThread()) { guard.invalidateCustody(); }
        else { client.execute(guard::invalidateCustody); }
    }

    private static boolean sameCohort(Frame first, Frame second) {
        if (!first.identities().keySet().equals(second.identities().keySet())) { return false; }
        for (var entry : first.identities().entrySet()) {
            if (!ItemStack.areEqual(entry.getValue(), second.identities().get(entry.getKey()))) { return false; }
        }
        return true;
    }

    private static boolean mutation(Packet<?> packet) {
        return packet instanceof ClickSlotC2SPacket || packet instanceof CreativeInventoryActionC2SPacket
                || packet instanceof PlayerActionC2SPacket || packet instanceof PlayerInteractBlockC2SPacket
                || packet instanceof PlayerInteractItemC2SPacket || packet instanceof PlayerInteractEntityC2SPacket
                || packet instanceof PickItemFromBlockC2SPacket || packet instanceof PickItemFromEntityC2SPacket
                || packet instanceof CraftRequestC2SPacket || packet instanceof SelectMerchantTradeC2SPacket;
    }

    private record Frame(Map<Integer, ItemStack> identities,
                         List<MossMiningToolGuard.CohortMember<ItemStack>> mainMembers,
                         boolean cursorEmpty) {
        boolean mainOnly() { return identities.keySet().stream().allMatch(slot -> slot >= 0 && slot < 36); }
        Frame moved(int source, int destination) {
            Map<Integer, ItemStack> moved = new LinkedHashMap<>(identities);
            ItemStack left = moved.remove(source);
            ItemStack right = moved.remove(destination);
            if (left != null) { moved.put(destination, left); }
            if (right != null) { moved.put(source, right); }
            return new Frame(Map.copyOf(moved), List.of(), cursorEmpty);
        }
    }

    private record Swap(int source, int destination, ItemStack beforeSource, ItemStack beforeDestination) {
        boolean matchesAfter(MinecraftClient client) {
            return ItemStack.areEqual(beforeSource, client.player.getInventory().getStack(destination))
                    && ItemStack.areEqual(beforeDestination, client.player.getInventory().getStack(source));
        }
    }

    private record PendingMove(Frame after, int source, int destination, PlayerInventoryUpdateLedger.Stamp afterStamp) { }

    static final class Scope {
        private final Object handler;
        private final int syncId;
        private final int slot;
        private final int button;
        private final SlotActionType action;
        private final Hand hand;
        private final BlockHitResult hit;
        private final BlockPos target;
        private Object world;
        private Object player;
        private Object connection;
        private int packets;
        private Scope(Object handler, int syncId, int slot, int button, SlotActionType action,
                      Hand hand, BlockHitResult hit, BlockPos target) {
            this.handler = handler; this.syncId = syncId;
            this.slot = slot; this.button = button; this.action = action;
            this.hand = hand; this.hit = hit; this.target = target;
        }
        static Scope click(ScreenHandler handler, int slot, int button, SlotActionType action) {
            return click(handler, handler.syncId, slot, button, action);
        }
        static Scope click(Object handler, int syncId, int slot, int button, SlotActionType action) {
            return new Scope(Objects.requireNonNull(handler), syncId, slot, button,
                    Objects.requireNonNull(action), null, null, null);
        }
        static Scope interact(Hand hand, BlockHitResult hit) {
            return new Scope(null, 0, 0, 0, null, Objects.requireNonNull(hand), Objects.requireNonNull(hit), null);
        }
        static Scope mining(BlockPos target) {
            return new Scope(null, 0, 0, 0, null, null, null, Objects.requireNonNull(target).toImmutable());
        }
        void bind(MinecraftClient client) {
            world = client.world; player = client.player; connection = client.getNetworkHandler();
        }
        boolean sameContext(MinecraftClient client) {
            return world != null && player != null && connection != null && world == client.world
                    && player == client.player && connection == client.getNetworkHandler();
        }
        boolean accept(Packet<?> packet, MinecraftClient client) {
            if (!sameContext(client)) { return false; }
            if (packet instanceof ClickSlotC2SPacket click) {
                return acceptClick(client.player.currentScreenHandler, click.syncId(), click.slot(),
                        click.button(), click.actionType());
            }
            if (packet instanceof PlayerInteractBlockC2SPacket interaction) {
                return acceptInteraction(interaction.getHand(), interaction.getBlockHitResult());
            }
            return packet instanceof PlayerActionC2SPacket mining && acceptMining(mining.getAction(), mining.getPos());
        }
        // Pure packet-field predicates are shared with focused tests; client lifetime remains checked above.
        boolean acceptClick(Object currentHandler, int packetSync, int packetSlot, int packetButton,
                            SlotActionType packetAction) {
            return claim(handler != null && handler == currentHandler && syncId == packetSync
                    && slot == packetSlot && button == packetButton && action == packetAction);
        }
        boolean acceptInteraction(Hand packetHand, BlockHitResult packetHit) {
            return claim(hit != null && packetHit != null && hand == packetHand
                    && hit.getBlockPos().equals(packetHit.getBlockPos()) && hit.getSide() == packetHit.getSide()
                    && hit.getPos().equals(packetHit.getPos()) && hit.isInsideBlock() == packetHit.isInsideBlock()
                    && hit.isAgainstWorldBorder() == packetHit.isAgainstWorldBorder()
                    && hit.getType() == packetHit.getType());
        }
        boolean acceptMining(PlayerActionC2SPacket.Action packetAction, BlockPos packetTarget) {
            return claim(target != null && target.equals(packetTarget)
                    && (packetAction == PlayerActionC2SPacket.Action.START_DESTROY_BLOCK
                    || packetAction == PlayerActionC2SPacket.Action.STOP_DESTROY_BLOCK
                    || packetAction == PlayerActionC2SPacket.Action.ABORT_DESTROY_BLOCK));
        }
        private boolean claim(boolean matches) {
            return matches && ++packets <= 1;
        }
    }
}
