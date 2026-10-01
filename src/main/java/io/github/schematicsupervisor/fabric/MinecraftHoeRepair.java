package io.github.schematicsupervisor.fabric;

import com.google.gson.JsonElement;
import com.mojang.serialization.JsonOps;
import io.github.schematicsupervisor.fabric.mixin.ClientPlayerInteractionManagerAccessor;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;
import net.minecraft.client.MinecraftClient;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.registry.RegistryOps;

/** Fixed-command adapter. It never repairs by changing local item damage and never repeats pending input. */
final class MinecraftHoeRepair {
    private final MinecraftClient client;
    private final boolean enabled;
    private final Path journalPath;
    private HoeRepairSession session;
    private String unavailable = "";
    private boolean waitingForConsistentMetadata;
    private Object attemptWorld;
    private Object attemptPlayer;
    private Object attemptConnection;

    MinecraftHoeRepair(MinecraftClient client, boolean enabled, Path journalPath) {
        this.client = Objects.requireNonNull(client);
        this.enabled = enabled;
        this.journalPath = Objects.requireNonNull(journalPath);
        try { session = new HoeRepairSession(new HoeRepairFileStore(journalPath)); }
        catch (IOException | RuntimeException failure) { unavailable = "Hoe repair journal is unavailable; preserve it before continuing."; }
    }

    boolean pending() { return session != null && session.pending(); }
    boolean enabled() { return enabled; }
    String detail() {
        if (!unavailable.isEmpty()) { return unavailable; }
        return waitingForConsistentMetadata ? "Waiting for consistent server tool damage metadata." : session.detail();
    }
    String blockDetail() {
        return settle(false) == HoeRepairSession.Status.BLOCKED ? detail() : "";
    }

    /** Called before supported tool use, after selecting its exact usable stack and stopping movement. */
    HoeRepairSession.Status beforeUse() {
        HoeRepairSession.Status status = settle(false);
        if (status != HoeRepairSession.Status.READY || !enabled) { return status; }
        try {
            if (client.player == null || client.world == null || client.interactionManager == null
                    || client.getNetworkHandler() == null || !client.getNetworkHandler().isConnectionOpen()
                    || !MinecraftBackgroundBuildAccess.allowsWorldActions(client)) {
                return HoeRepairSession.Status.BLOCKED;
            }
            int selected = client.player.getInventory().getSelectedSlot();
            ItemStack selectedStack = client.player.getMainHandStack();
            waitingForConsistentMetadata = !LegacyToolDamageIdentity.canCompare(
                    selectedStack.get(DataComponentTypes.CUSTOM_DATA), selectedStack.getDamage());
            // No repair intent or input exists yet. Retain the caller's ordinary finite progress deadline.
            if (waitingForConsistentMetadata) { return HoeRepairSession.Status.WAITING; }
            HoeRepairSession.Tool tool = capture(selectedStack, selected);
            if (tool == null || !tool.needsRepair()) { return HoeRepairSession.Status.READY; }
            RunContext context = context();
            ((ClientPlayerInteractionManagerAccessor) client.interactionManager).supervisor$syncSelectedSlot();
            PlayerInventoryUpdateLedger.Stamp mark = ServerPlayerInventoryObserver.mark(client);
            session.begin(context, tool, mark.epoch(), mark.sequence());
            attemptWorld = client.world;
            attemptPlayer = client.player;
            attemptConnection = client.getNetworkHandler();
            if (!context.equals(context()) || selected != client.player.getInventory().getSelectedSlot()
                    || !tool.equals(capture(client.player.getMainHandStack(), selected))
                    || !MinecraftBackgroundBuildAccess.allowsWorldActions(client)) {
                session.block("The selected repair tool changed before dispatch; the saved intent will not be repeated.");
                return HoeRepairSession.Status.BLOCKED;
            }
            client.getNetworkHandler().sendChatCommand("fix");
            return HoeRepairSession.Status.WAITING;
        } catch (IOException | RuntimeException failure) {
            if (session != null && session.pending()) {
                session.block("The repair command or journal write was uncertain; preserve the pending receipt without repeating /fix.");
            } else { unavailable = "Hoe repair could not prepare its durable command; preserve the journal before continuing."; }
            return HoeRepairSession.Status.BLOCKED;
        }
    }

    String pendingOperationId() {
        return session == null ? "" : session.journal().filter(value -> !value.confirmed())
                .map(HoeRepairSession.Journal::operationId).orElse("");
    }

    String confirmedOperationForTool(int slot) {
        if (session == null || client.player == null || client.world == null || slot < 0 || slot >= 9) { return ""; }
        var journal = session.journal().filter(HoeRepairSession.Journal::confirmed);
        if (journal.isEmpty()) { return ""; }
        HoeRepairSession.Tool current = capture(client.player.getInventory().getStack(slot), slot);
        HoeRepairSession.Journal confirmed = journal.orElseThrow();
        return current != null && current.damage() == 0 && confirmed.context().equals(context())
                && confirmed.before().sameTool(current) ? confirmed.operationId() : "";
    }

    /** Passive settlement is allowed while paused; only an active wait advances its finite deadline. */
    HoeRepairSession.Status settle(boolean advanceDeadline) {
        if (!unavailable.isEmpty()) { return HoeRepairSession.Status.BLOCKED; }
        if (!session.pending()) { return HoeRepairSession.Status.READY; }
        try {
            if (client.world == null || client.player == null || client.getNetworkHandler() == null
                    || !client.getNetworkHandler().isConnectionOpen()) {
                session.block("Disconnected with a pending hoe repair; restore the original world to observe its receipt.");
                return HoeRepairSession.Status.BLOCKED;
            }
            if (attemptWorld != null && (attemptWorld != client.world || attemptPlayer != client.player
                    || attemptConnection != client.getNetworkHandler())) {
                session.block("World, player or connection changed during hoe repair; no command will be repeated.");
                attemptWorld = null;
                attemptPlayer = null;
                attemptConnection = null;
                return HoeRepairSession.Status.BLOCKED;
            }
            int slot = session.journal().orElseThrow().before().slot();
            HoeRepairSession.Tool current = capture(client.player.getInventory().getStack(slot), slot);
            var packet = ServerPlayerInventoryObserver.latest(client, slot);
            HoeRepairSession.Receipt receipt = null;
            if (packet.isPresent()) {
                var update = packet.orElseThrow();
                HoeRepairSession.Tool confirmed = capture(update.stack(), slot);
                if (confirmed != null) {
                    receipt = new HoeRepairSession.Receipt(update.stamp().epoch(), update.stamp().sequence(), confirmed);
                }
            }
            return session.observe(context(), current, receipt, advanceDeadline);
        } catch (IOException | RuntimeException failure) {
            unavailable = "Hoe repair receipt could not be read or saved; preserve its journal without repeating /fix.";
            return HoeRepairSession.Status.BLOCKED;
        }
    }

    void cancel() { settle(false); waitingForConsistentMetadata = false; if (session != null) { session.cancel(); } }

    private RunContext context() {
        return MaterialPurchaseContext.bind(MinecraftRunContext.capture(client), client.player.getUuid(), journalPath);
    }

    private HoeRepairSession.Tool capture(ItemStack original, int slot) {
        if (!MinecraftClearingTools.admitted(original)) { return null; }
        ItemStack normalized = MinecraftClearingTools.identity(original);
        String itemId = Registries.ITEM.getId(normalized.getItem()).toString();
        JsonElement encoded = ItemStack.CODEC.encodeStart(
                RegistryOps.of(JsonOps.INSTANCE, client.world.getRegistryManager()), normalized).result()
                .orElseThrow(() -> new IllegalStateException("Repair tool fingerprint is unavailable"));
        String fingerprint = MossStackFingerprint.fingerprint(itemId, normalized.getCount(), encoded,
                new MossStackFingerprint.Budget()).orElseThrow(() -> new IllegalStateException("Repair tool fingerprint is unavailable"));
        return new HoeRepairSession.Tool(slot, itemId, fingerprint, original.getCount(), original.getDamage(),
                original.getMaxDamage(), original.contains(DataComponentTypes.UNBREAKABLE));
    }
}
