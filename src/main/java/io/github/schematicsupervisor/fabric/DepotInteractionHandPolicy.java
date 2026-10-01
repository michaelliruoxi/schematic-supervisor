package io.github.schematicsupervisor.fabric;

import java.util.Objects;

/** Item permission is specific to the registered chest's default main-hand block action. */
final class DepotInteractionHandPolicy {
    record Context(boolean exactRegisteredChest, boolean mainHand, boolean sneaking,
                   boolean cancelInteraction, boolean playerHandler, boolean emptyCursor) { }

    private DepotInteractionHandPolicy() { }

    static boolean allowsItem(String id, boolean empty, boolean defaultComponents) {
        if (empty) { return true; }
        if (!defaultComponents || id == null) { return false; }
        return switch (id) {
            case "minecraft:dirt", "minecraft:glowstone", "minecraft:birch_planks",
                    "minecraft:wheat_seeds", "minecraft:pumpkin_seeds", "minecraft:melon_seeds", "minecraft:moss_block",
                    "minecraft:jack_o_lantern" -> true;
            default -> false;
        };
    }

    static String rejection(Context context, boolean safeItem) {
        Objects.requireNonNull(context, "context");
        if (!context.exactRegisteredChest()) { return "depot hand requires the exact received registered chest in reach"; }
        if (!context.mainHand()) { return "depot access permits only the main-hand chest interaction"; }
        if (context.sneaking() || context.cancelInteraction()) {
            return "stop bypassing the chest block action before registered-depot access";
        }
        if (!context.playerHandler() || !context.emptyCursor()) {
            return "depot access requires the player handler and an empty cursor";
        }
        return safeItem ? "" : "depot access needs an empty or permitted plain item in the hotbar";
    }
}
