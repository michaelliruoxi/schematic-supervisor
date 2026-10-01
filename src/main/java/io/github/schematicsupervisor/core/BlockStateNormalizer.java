package io.github.schematicsupervisor.core;

import java.util.Map;
import java.util.TreeMap;

/**
 * Applies only the two property exceptions allowed by the MVP completion rule.
 */
public final class BlockStateNormalizer {
    private BlockStateNormalizer() {
    }

    public static BlockState normalize(BlockState state) {
        if (state.isAir()) {
            return BlockState.AIR;
        }
        TreeMap<String, String> properties = new TreeMap<>(state.properties());
        if ("minecraft:farmland".equals(state.blockId())) {
            properties.remove("moisture");
        } else if ("minecraft:wheat".equals(state.blockId())) {
            properties.remove("age");
        }
        return new BlockState(state.blockId(), Map.copyOf(properties));
    }

    public static boolean equivalent(BlockState expected, BlockState actual) {
        return normalize(expected).equals(normalize(actual));
    }
}
