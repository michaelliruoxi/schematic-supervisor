package io.github.schematicsupervisor.fabric;

import io.github.schematicsupervisor.core.Material;
import io.github.schematicsupervisor.core.MaterialQuantities;
import io.github.schematicsupervisor.core.SupervisorPorts;
import java.util.TreeMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.gui.screen.ChatScreen;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;

final class MinecraftInventoryPort implements SupervisorPorts.Inventory {
    private final MinecraftClient client;

    MinecraftInventoryPort(MinecraftClient client) {
        this.client = client;
    }

    @Override
    public MaterialQuantities snapshot() {
        if (client.player == null) {
            return MaterialQuantities.empty();
        }
        TreeMap<Material, Long> result = new TreeMap<>();
        for (ItemStack stack : client.player.getInventory().getMainStacks()) {
            if (stack.isEmpty()) {
                continue;
            }
            Material material = toMaterial(stack);
            if (material != null) {
                long amount = material == Material.HOE ? 1L : stack.getCount();
                result.merge(material, amount, Math::addExact);
            }
        }
        return MaterialQuantities.of(result);
    }

    /** Capture only on the client thread; HTTP readers receive the resulting immutable record. */
    InventoryObservation observation() {
        if (client.player == null) {
            return InventoryObservation.unavailable("Player inventory is unavailable.");
        }
        try {
            var playerInventory = client.player.getInventory();
            var main = playerInventory.getMainStacks();
            if (main.size() != 36) {
                return InventoryObservation.unavailable("Player inventory has an unsupported main-slot layout.");
            }
            ItemStack normalDirt = new ItemStack(Items.DIRT);
            ArrayList<InventoryObservation.Slot> slots = new ArrayList<>(36);
            for (int index = 0; index < 36; index++) {
                slots.add(observeSlot(index, main.get(index), normalDirt));
            }
            var handler = client.player.currentScreenHandler;
            if (handler == null) {
                return InventoryObservation.unavailable("Player screen handler is unavailable.");
            }
            boolean open = client.currentScreen instanceof HandledScreen<?>;
            String title = open ? ((HandledScreen<?>) client.currentScreen).getTitle().getString() : "";
            if (title.length() > 128) { title = title.substring(0, 128); }
            InventoryObservation.Menu menu = new InventoryObservation.Menu(open,
                    !open ? "none" : handler == client.player.playerScreenHandler ? "inventory" : "container",
                    title, handler == null ? null : handler.syncId,
                    handler == null ? InventoryObservation.Slot.empty(-1)
                            : observeSlot(-1, handler.getCursorStack(), normalDirt));
            ArrayList<InventoryObservation.Slot> armor = new ArrayList<>(4);
            for (int index = 36; index < 40; index++) {
                armor.add(observeSlot(index, playerInventory.getStack(index), normalDirt));
            }
            return InventoryObservation.capture(slots,
                    observeSlot(40, client.player.getOffHandStack(), normalDirt),
                    playerInventory.getSelectedSlot(), normalDirt.getMaxCount(), menu)
                    .withEquipmentAndCapacity(armor, observeMaterialCapacity(handler));
        } catch (RuntimeException failure) {
            return InventoryObservation.unavailable("Inventory read failed: " + failure.getClass().getSimpleName());
        }
    }

    /** Current handler restrictions and exact component equality, independently for each normal item. */
    private Map<Material, Long> observeMaterialCapacity(ScreenHandler handler) {
        try {
            if (handler.slots.size() > 512) { return null; }
            Slot[] main = new Slot[36];
            for (Slot slot : handler.slots) {
                int index = slot.getIndex();
                if (slot.inventory == client.player.getInventory() && index >= 0 && index < 36) {
                    if (main[index] != null) { return null; }
                    main[index] = slot;
                }
            }
            TreeMap<Material, Long> result = new TreeMap<>();
            for (Material material : InventoryObservation.CAPACITY_MATERIALS) {
                ItemStack plain = new ItemStack(MinecraftMaterials.item(material));
                List<InventoryObservation.CapacitySlot> facts = new ArrayList<>(36);
                for (int index = 0; index < main.length; index++) {
                    Slot slot = main[index];
                    if (slot == null) { return null; }
                    ItemStack current = slot.getStack();
                    facts.add(new InventoryObservation.CapacitySlot(index,
                            current.isEmpty() ? 0 : current.getCount(),
                            Math.min(plain.getMaxCount(), slot.getMaxItemCount(plain)), slot.canInsert(plain),
                            ItemStack.areItemsAndComponentsEqual(current, plain)));
                }
                result.put(material, InventoryObservation.compatibleCapacity(facts));
            }
            return result;
        } catch (RuntimeException unavailable) {
            // An unsupported handler must not hide otherwise readable item and equipment facts.
            return null;
        }
    }

    PlayerObservation playerObservation() {
        if (client.player == null || client.world == null) { return null; }
        var player = client.player;
        String screenKind = client.currentScreen == null ? "none"
                : client.currentScreen instanceof HandledScreen<?>
                    ? player.currentScreenHandler == player.playerScreenHandler ? "inventory" : "container"
                : client.currentScreen instanceof ChatScreen ? "chat" : "other";
        return new PlayerObservation(player.getX(), player.getY(), player.getZ(),
                player.getAbilities().flying, player.getAbilities().allowFlying, player.isOnGround(),
                player.getHealth(), player.getHungerManager().getFoodLevel(), player.getBlockInteractionRange(),
                screenKind, screenKind.equals("container"), new PlayerObservation.Background(
                        client.currentScreen == null ? "none" : client.currentScreen.getClass().getSimpleName(),
                        client.isWindowFocused(), client.getWindow().isMinimized(),
                        MinecraftBackgroundBuildAccess.allowsWorldActions(client),
                        MinecraftBackgroundTickAccess.keepsWorldTicking(client)));
    }

    static InventoryObservation.Slot observeSlot(int index, ItemStack stack, ItemStack normalDirt) {
        if (stack.isEmpty()) { return InventoryObservation.Slot.empty(index); }
        boolean hoe = MinecraftMaterials.isHoe(stack);
        String displayName = null;
        Boolean plainDefaultComponents = null;
        try {
            displayName = stack.getName().getString();
        } catch (RuntimeException ignored) {
            // Optional display text must not hide otherwise readable inventory counts.
        }
        try {
            plainDefaultComponents = ItemStack.areItemsAndComponentsEqual(stack, new ItemStack(stack.getItem()));
        } catch (RuntimeException ignored) {
            // Unknown component equality is distinct from a confirmed custom stack.
        }
        return new InventoryObservation.Slot(index, Registries.ITEM.getId(stack.getItem()).toString(),
                stack.getCount(), stack.getMaxCount(), toMaterial(stack),
                ItemStack.areItemsAndComponentsEqual(stack, normalDirt), hoe,
                stack.contains(DataComponentTypes.DAMAGE) ? stack.getDamage() : null,
                stack.contains(DataComponentTypes.MAX_DAMAGE) ? stack.getMaxDamage() : null,
                stack.contains(DataComponentTypes.UNBREAKABLE), displayName, plainDefaultComponents);
    }

    static Material toMaterial(ItemStack stack) {
        return MinecraftMaterials.classify(stack).orElse(null);
    }
}
