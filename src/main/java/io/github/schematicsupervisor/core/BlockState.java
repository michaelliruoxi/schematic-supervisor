package io.github.schematicsupervisor.core;

import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

public record BlockState(String blockId, Map<String, String> properties) {
    public static final BlockState AIR = new BlockState("minecraft:air", Map.of());

    public BlockState {
        Objects.requireNonNull(blockId, "blockId");
        Objects.requireNonNull(properties, "properties");
        String normalizedId = blockId.trim().toLowerCase(Locale.ROOT);
        if (normalizedId.isEmpty()) {
            throw new IllegalArgumentException("blockId must not be blank");
        }
        blockId = normalizedId;

        TreeMap<String, String> copy = new TreeMap<>();
        properties.forEach((key, value) -> {
            String normalizedKey = Objects.requireNonNull(key, "property key")
                    .trim()
                    .toLowerCase(Locale.ROOT);
            String normalizedValue = Objects.requireNonNull(value, "property value")
                    .trim()
                    .toLowerCase(Locale.ROOT);
            if (normalizedKey.isEmpty() || normalizedValue.isEmpty()) {
                throw new IllegalArgumentException("block properties must not contain blank keys or values");
            }
            copy.put(normalizedKey, normalizedValue);
        });
        properties = Collections.unmodifiableMap(copy);
    }

    public BlockState(String blockId) {
        this(blockId, Map.of());
    }

    public boolean isAir() {
        return switch (blockId) {
            case "minecraft:air", "minecraft:cave_air", "minecraft:void_air" -> true;
            default -> false;
        };
    }
}
