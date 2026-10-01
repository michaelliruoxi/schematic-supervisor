package io.github.schematicsupervisor.fabric;

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.mojang.serialization.JsonOps;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.registry.RegistryOps;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.slot.Slot;

/** Read-only main-inventory evidence from full virtual-container packets. */
final class MinecraftMaterialPurchaseCapture {
    private MinecraftMaterialPurchaseCapture() { }

    static Optional<MaterialPurchaseFacts.Snapshot> capture(MinecraftClient client,
            GenericContainerScreenHandler handler, ServerInventorySnapshotObserver.FullSnapshot packet,
            MaterialPurchaseFacts.Product product) {
        try {
            if (!validHandler(client, handler) || packet == null
                    || packet.stamp().syncId() != handler.syncId || packet.rows() != handler.getRows()) {
                return Optional.empty();
            }
            return Optional.of(captureStacks(client, handler, packet.slots(), packet.cursorStack(), product));
        } catch (RuntimeException | StackOverflowError unavailable) { return Optional.empty(); }
    }

    static boolean matchesLive(MinecraftClient client, GenericContainerScreenHandler handler,
            MaterialPurchaseFacts.Snapshot before, MaterialPurchaseFacts.Product product) {
        try {
            if (before == null || !validHandler(client, handler)) { return false; }
            return before.equals(captureStacks(client, handler,
                    handler.slots.stream().map(slot -> slot.getStack().copy()).toList(),
                    handler.getCursorStack().copy(), product));
        } catch (RuntimeException | StackOverflowError unavailable) { return false; }
    }

    static int compatibleStackCapacity(MinecraftClient client, GenericContainerScreenHandler handler,
                                       MaterialPurchaseFacts.Product product, int reservedEmptySlots) {
        if (!validHandler(client, handler)) { return 0; }
        if (reservedEmptySlots < 0 || reservedEmptySlots > 36) { return 0; }
        ItemStack plain = plainStack(product);
        int room = 0;
        List<Integer> emptyRoom = new ArrayList<>();
        for (int index = 0; index < 36; index++) {
            Slot slot = handler.slots.get(mainHandlerSlot(handler.getRows() * 9, index));
            ItemStack current = slot.getStack();
            if (!slot.canInsert(plain)) { continue; }
            int maximum = Math.min(64, slot.getMaxItemCount(plain));
            if (current.isEmpty()) { room += maximum; emptyRoom.add(maximum); }
            else if (ItemStack.areItemsAndComponentsEqual(current, plain)) {
                room += Math.max(0, maximum - current.getCount());
            }
        }
        if (emptyRoom.size() < reservedEmptySlots) { return 0; }
        emptyRoom.sort(java.util.Comparator.reverseOrder());
        for (int index = 0; index < reservedEmptySlots; index++) { room -= emptyRoom.get(index); }
        return room / 64;
    }

    private static int mainHandlerSlot(int containerSize, int mainIndex) {
        return containerSize + (mainIndex < 9 ? 27 + mainIndex : mainIndex - 9);
    }

    private static boolean validHandler(MinecraftClient client, GenericContainerScreenHandler handler) {
        if (client == null || !client.isOnThread() || client.player == null || client.world == null
                || client.getNetworkHandler() == null || handler == null
                || handler.getClass() != GenericContainerScreenHandler.class
                || client.player.currentScreenHandler != handler || handler.syncId < 1) { return false; }
        int size = handler.getRows() * 9;
        if (size < 9 || size > 54 || handler.slots.size() != size + 36
                || handler.getInventory().size() != size) { return false; }
        for (int index = 0; index < size; index++) {
            Slot slot = handler.slots.get(index);
            if (slot.id != index || slot.inventory != handler.getInventory() || slot.getIndex() != index) {
                return false;
            }
        }
        for (int index = 0; index < 36; index++) {
            int mapped = mainHandlerSlot(size, index);
            Slot slot = handler.slots.get(mapped);
            if (slot.id != mapped || slot.inventory != client.player.getInventory() || slot.getIndex() != index) {
                return false;
            }
        }
        return true;
    }

    private static MaterialPurchaseFacts.Snapshot captureStacks(MinecraftClient client,
            GenericContainerScreenHandler handler, List<ItemStack> packetSlots, ItemStack cursor,
            MaterialPurchaseFacts.Product product) {
        if (packetSlots.size() != handler.slots.size()) { throw unavailable(); }
        MossStackFingerprint.Budget budget = new MossStackFingerprint.Budget();
        ItemStack plain = plainStack(product);
        List<MaterialPurchaseFacts.StackFacts> main = new ArrayList<>(36);
        for (int index = 0; index < 36; index++) {
            main.add(stackFacts(client, packetSlots.get(mainHandlerSlot(handler.getRows() * 9, index)), plain, budget));
        }
        // Generic full-container packets do not acknowledge offhand or armor; these are local guards only.
        List<MaterialPurchaseFacts.StackFacts> armor = new ArrayList<>(4);
        for (int index = 36; index < 40; index++) {
            armor.add(stackFacts(client, client.player.getInventory().getStack(index).copy(), plain, budget));
        }
        return new MaterialPurchaseFacts.Snapshot(main, stackFacts(client, cursor, plain, budget),
                stackFacts(client, client.player.getOffHandStack().copy(), plain, budget), armor);
    }

    private static ItemStack plainStack(MaterialPurchaseFacts.Product product) {
        return new ItemStack(switch (product) {
            case DIRT -> Items.DIRT;
            case GLOWSTONE -> Items.GLOWSTONE;
            case BIRCH_PLANKS -> Items.BIRCH_PLANKS;
        });
    }

    private static MaterialPurchaseFacts.StackFacts stackFacts(MinecraftClient client, ItemStack observed,
            ItemStack plain, MossStackFingerprint.Budget budget) {
        if (observed == null) { throw unavailable(); }
        ItemStack stack = observed.copy();
        if (stack.isEmpty()) {
            String hash = MossStackFingerprint.fingerprint("minecraft:air", 0, JsonNull.INSTANCE, budget)
                    .orElseThrow(MinecraftMaterialPurchaseCapture::unavailable);
            return new MaterialPurchaseFacts.StackFacts(hash, "minecraft:air", 0, 0, true, false);
        }
        String itemId = Registries.ITEM.getId(stack.getItem()).toString();
        JsonElement encoded = ItemStack.CODEC.encodeStart(
                RegistryOps.of(JsonOps.INSTANCE, client.world.getRegistryManager()), stack).result()
                .orElseThrow(MinecraftMaterialPurchaseCapture::unavailable);
        String hash = MossStackFingerprint.fingerprint(itemId, stack.getCount(), encoded, budget)
                .orElseThrow(MinecraftMaterialPurchaseCapture::unavailable);
        return new MaterialPurchaseFacts.StackFacts(hash, itemId, stack.getCount(), stack.getMaxCount(),
                false, ItemStack.areItemsAndComponentsEqual(stack, plain));
    }

    private static IllegalStateException unavailable() {
        return new IllegalStateException("Complete bounded purchase inventory facts are unavailable");
    }
}
