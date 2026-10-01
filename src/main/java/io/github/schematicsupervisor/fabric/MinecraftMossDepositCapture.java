package io.github.schematicsupervisor.fabric;

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.mojang.serialization.JsonOps;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.registry.RegistryOps;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.slot.Slot;

/** Read-only adapter. Caller owns verified container identity, exact handler, and packet freshness. */
final class MinecraftMossDepositCapture {
    private MinecraftMossDepositCapture() { }

    static Optional<MossDepositFacts.Snapshot> capture(MinecraftClient client,
            GenericContainerScreenHandler acceptedHandler, ServerInventorySnapshotObserver.FullSnapshot packet) {
        try {
            if (!validHandler(client, acceptedHandler) || packet == null
                    || packet.stamp().syncId() != acceptedHandler.syncId
                    || packet.rows() != acceptedHandler.getRows()) { return Optional.empty(); }
            return Optional.of(captureStacks(client, acceptedHandler, packet.slots(), packet.cursorStack()));
        } catch (RuntimeException | StackOverflowError unavailable) { return Optional.empty(); }
    }

    /** Recheck immediately before input, including after saving intent; slot packets/prediction may stale a full packet. */
    static boolean matchesLiveBaseline(MinecraftClient client, GenericContainerScreenHandler acceptedHandler,
                                       MossDepositFacts.Snapshot baseline) {
        try {
            if (baseline == null || !validHandler(client, acceptedHandler)) { return false; }
            List<ItemStack> live = acceptedHandler.slots.stream().map(slot -> slot.getStack().copy()).toList();
            return baseline.equals(captureStacks(client, acceptedHandler, live, acceptedHandler.getCursorStack().copy()));
        } catch (RuntimeException | StackOverflowError unavailable) { return false; }
    }

    private static boolean validHandler(MinecraftClient client, GenericContainerScreenHandler handler) {
        if (client == null || !client.isOnThread() || client.player == null || client.world == null
                || client.getNetworkHandler() == null || handler == null
                || handler.getClass() != GenericContainerScreenHandler.class
                || client.player.currentScreenHandler != handler || handler.syncId <= 0) { return false; }
        int chest = handler.getRows() * 9;
        if ((chest != 27 && chest != 54) || handler.slots.size() != chest + 36
                || handler.getInventory().size() != chest) { return false; }
        for (int index = 0; index < chest; index++) {
            Slot slot = handler.slots.get(index);
            if (slot.id != index || slot.inventory != handler.getInventory() || slot.getIndex() != index) { return false; }
        }
        for (int index = 0; index < 36; index++) {
            int handlerIndex = MossDepositFacts.mainHandlerSlot(chest, index);
            Slot slot = handler.slots.get(handlerIndex);
            if (slot.id != handlerIndex || slot.inventory != client.player.getInventory() || slot.getIndex() != index) {
                return false;
            }
        }
        return true;
    }

    private static MossDepositFacts.Snapshot captureStacks(MinecraftClient client, GenericContainerScreenHandler handler,
                                                           List<ItemStack> packetSlots, ItemStack cursor) {
        if (packetSlots.size() != handler.slots.size()) { throw unavailable(); }
        MossStackFingerprint.Budget budget = new MossStackFingerprint.Budget();
        Map<String, ItemStack> plainPickups = new LinkedHashMap<>();
        for (String itemId : SurplusPickupPolicy.ITEM_IDS) {
            plainPickups.put(itemId, new ItemStack(Registries.ITEM.get(net.minecraft.util.Identifier.of(itemId))));
        }
        int chestSize = handler.getRows() * 9;
        List<MossDepositFacts.SlotFacts> chest = new ArrayList<>(chestSize);
        List<MossDepositFacts.SlotFacts> main = new ArrayList<>(36);
        for (int index = 0; index < chestSize; index++) {
            chest.add(slotFacts(client, handler.slots.get(index), packetSlots.get(index), plainPickups, budget));
        }
        for (int index = 0; index < 36; index++) {
            int mapped = MossDepositFacts.mainHandlerSlot(chestSize, index);
            main.add(slotFacts(client, handler.slots.get(mapped), packetSlots.get(mapped), plainPickups, budget));
        }
        // These equipment copies are additional local guards, not evidence acknowledged by the generic-container packet.
        List<MossDepositFacts.StackFacts> armor = new ArrayList<>(4);
        for (int index = 36; index < 40; index++) {
            armor.add(stackFacts(client, client.player.getInventory().getStack(index).copy(), plainPickups, budget));
        }
        return new MossDepositFacts.Snapshot(chest, main, stackFacts(client, cursor, plainPickups, budget),
                stackFacts(client, client.player.getOffHandStack().copy(), plainPickups, budget), armor);
    }

    private static MossDepositFacts.SlotFacts slotFacts(MinecraftClient client, Slot slot, ItemStack stack,
                                                       Map<String, ItemStack> plainPickups, MossStackFingerprint.Budget budget) {
        Map<String, MossDepositFacts.Insertion> insertion = new LinkedHashMap<>();
        plainPickups.forEach((id, plain) -> insertion.put(id,
                new MossDepositFacts.Insertion(slot.canInsert(plain), slot.getMaxItemCount(plain))));
        return new MossDepositFacts.SlotFacts(slot.id, slot.getIndex(), stackFacts(client, stack, plainPickups, budget),
                slot.canTakeItems(client.player), insertion);
    }

    private static MossDepositFacts.StackFacts stackFacts(MinecraftClient client, ItemStack observed,
                                                         Map<String, ItemStack> plainPickups, MossStackFingerprint.Budget budget) {
        if (observed == null) { throw unavailable(); }
        ItemStack stack = observed.copy();
        if (stack.isEmpty()) {
            String hash = MossStackFingerprint.fingerprint("minecraft:air", 0, JsonNull.INSTANCE, budget)
                    .orElseThrow(MinecraftMossDepositCapture::unavailable);
            return new MossDepositFacts.StackFacts(hash, "minecraft:air", 0, 0, true, false);
        }
        String itemId = Registries.ITEM.getId(stack.getItem()).toString();
        // Registry-aware ItemStack encoding includes actual item, component changes, and count. No encoded data escapes.
        JsonElement encoded = ItemStack.CODEC.encodeStart(
                RegistryOps.of(JsonOps.INSTANCE, client.world.getRegistryManager()), stack).result()
                .orElseThrow(MinecraftMossDepositCapture::unavailable);
        String hash = MossStackFingerprint.fingerprint(itemId, stack.getCount(), encoded, budget)
                .orElseThrow(MinecraftMossDepositCapture::unavailable);
        ItemStack allowed = plainPickups.get(itemId);
        boolean plain = allowed != null && ItemStack.areItemsAndComponentsEqual(stack, allowed);
        return new MossDepositFacts.StackFacts(hash, itemId, stack.getCount(), stack.getMaxCount(), false, plain);
    }

    private static IllegalStateException unavailable() {
        return new IllegalStateException("Complete bounded inventory facts are unavailable");
    }
}
