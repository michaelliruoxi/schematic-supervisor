package io.github.schematicsupervisor.fabric;

import io.github.schematicsupervisor.core.BlockState;
import java.util.Map;
import java.util.TreeMap;
import net.minecraft.registry.Registries;
import net.minecraft.state.property.Property;

final class MinecraftBlockStates {
    private MinecraftBlockStates() {
    }

    static BlockState toCore(net.minecraft.block.BlockState state) {
        if (state.isAir()) {
            return BlockState.AIR;
        }
        String blockId = Registries.BLOCK.getId(state.getBlock()).toString();
        Map<String, String> properties = new TreeMap<>();
        state.getEntries().forEach((property, value) ->
                properties.put(property.getName(), propertyValueName(property, value)));
        return new BlockState(blockId, properties);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static String propertyValueName(Property property, Comparable value) {
        return property.name(value);
    }
}
