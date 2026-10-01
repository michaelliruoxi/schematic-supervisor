package io.github.schematicsupervisor.core;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.LongBinaryOperator;

/**
 * Immutable non-negative material counts, ordered built-ins first and then block materials by ID.
 */
public final class MaterialQuantities {
    private static final MaterialQuantities EMPTY = new MaterialQuantities(Map.of());

    private final TreeMap<Material, Long> quantities;

    private MaterialQuantities(Map<Material, Long> values) {
        quantities = new TreeMap<>();
        values.forEach((material, amount) -> {
            Objects.requireNonNull(material, "material");
            Objects.requireNonNull(amount, "amount");
            if (amount < 0) {
                throw new IllegalArgumentException("material quantities must be non-negative");
            }
            if (amount > 0) {
                quantities.put(material, amount);
            }
        });
    }

    public static MaterialQuantities empty() {
        return EMPTY;
    }

    public static MaterialQuantities of(Material material, long amount) {
        return new MaterialQuantities(Map.of(material, amount));
    }

    public static MaterialQuantities of(Map<Material, Long> values) {
        return values.isEmpty() ? EMPTY : new MaterialQuantities(values);
    }

    public static MaterialQuantities fromJsonMap(Map<String, ? extends Number> values) {
        TreeMap<Material, Long> converted = new TreeMap<>();
        values.forEach((key, value) -> converted.put(Material.fromJsonName(key), value.longValue()));
        return of(converted);
    }

    public long get(Material material) {
        return quantities.getOrDefault(material, 0L);
    }

    public boolean isEmpty() {
        return quantities.isEmpty();
    }

    public Map<Material, Long> asMap() {
        return Collections.unmodifiableMap(quantities);
    }

    public Map<String, Long> asJsonMap() {
        LinkedHashMap<String, Long> result = new LinkedHashMap<>();
        quantities.forEach((material, amount) -> result.put(material.jsonName(), amount));
        return Collections.unmodifiableMap(result);
    }

    public MaterialQuantities plus(MaterialQuantities other) {
        return combine(other, Math::addExact);
    }

    public MaterialQuantities minimum(MaterialQuantities other) {
        return combine(other, Math::min);
    }

    public MaterialQuantities shortageFrom(MaterialQuantities available) {
        TreeMap<Material, Long> result = new TreeMap<>();
        for (Material material : quantities.keySet()) {
            long shortage = Math.max(0, get(material) - available.get(material));
            if (shortage > 0) {
                result.put(material, shortage);
            }
        }
        return of(result);
    }

    public MaterialQuantities minusFloorZero(MaterialQuantities other) {
        TreeMap<Material, Long> result = new TreeMap<>();
        for (Material material : quantities.keySet()) {
            long amount = Math.max(0, get(material) - other.get(material));
            if (amount > 0) {
                result.put(material, amount);
            }
        }
        return of(result);
    }

    public MaterialQuantities withAtLeast(Material material, long amount) {
        if (amount < 0) {
            throw new IllegalArgumentException("amount must be non-negative");
        }
        TreeMap<Material, Long> result = new TreeMap<>(quantities);
        if (amount > 0) {
            result.put(material, Math.max(get(material), amount));
        }
        return of(result);
    }

    private MaterialQuantities combine(MaterialQuantities other, LongBinaryOperator operation) {
        TreeMap<Material, Long> result = new TreeMap<>();
        TreeSet<Material> materials = new TreeSet<>(quantities.keySet());
        materials.addAll(other.quantities.keySet());
        for (Material material : materials) {
            long amount = operation.applyAsLong(get(material), other.get(material));
            if (amount > 0) {
                result.put(material, amount);
            }
        }
        return of(result);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof MaterialQuantities that && quantities.equals(that.quantities);
    }

    @Override
    public int hashCode() {
        return quantities.hashCode();
    }

    @Override
    public String toString() {
        return asJsonMap().toString();
    }
}
