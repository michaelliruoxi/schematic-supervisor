package io.github.schematicsupervisor.core;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Something the builder consumes or keeps in reserve. The six built-in materials keep their names and
 * order; any other full block a schematic places is a block material named by its namespaced ID, such
 * as {@code minecraft:stone}. Materials order built-ins first, then block materials by ID.
 */
public final class Material implements Comparable<Material> {
    public enum Kind {
        /** Placed as a block: the built-in Dirt, Glowstone and Birch Planks, and every other full block. */
        BLOCK,
        SEEDS,
        TOOL,
        FOOD
    }

    private static final Pattern NAMESPACED_ID = Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+");
    private static final Map<String, Material> BLOCKS = new ConcurrentHashMap<>();

    public static final Material DIRT = new Material(0, "DIRT", Kind.BLOCK, "minecraft:dirt");
    public static final Material WHEAT_SEEDS = new Material(1, "WHEAT_SEEDS", Kind.SEEDS, "minecraft:wheat_seeds");
    public static final Material GLOWSTONE = new Material(2, "GLOWSTONE", Kind.BLOCK, "minecraft:glowstone");
    public static final Material BIRCH_PLANKS = new Material(3, "BIRCH_PLANKS", Kind.BLOCK, "minecraft:birch_planks");
    public static final Material HOE = new Material(4, "HOE", Kind.TOOL, "");
    public static final Material FOOD = new Material(5, "FOOD", Kind.FOOD, "");
    private static final List<Material> BUILT_INS = List.of(DIRT, WHEAT_SEEDS, GLOWSTONE, BIRCH_PLANKS, HOE, FOOD);

    private final int order;
    private final String name;
    private final Kind kind;
    private final String itemId;

    private Material(int order, String name, Kind kind, String itemId) {
        this.order = order;
        this.name = name;
        this.kind = kind;
        this.itemId = itemId;
    }

    /** The six materials every build may use, in their fixed order. */
    public static List<Material> builtIns() {
        return BUILT_INS;
    }

    /**
     * The material placed as the block with this namespaced ID: a built-in when one is placed as that
     * block, otherwise the block material with that ID. Equal IDs give the same instance.
     */
    public static Material block(String blockId) {
        String id = Objects.requireNonNull(blockId, "blockId").trim().toLowerCase(Locale.ROOT);
        for (Material builtIn : BUILT_INS) {
            if (builtIn.kind == Kind.BLOCK && builtIn.itemId.equals(id)) {
                return builtIn;
            }
        }
        if (!NAMESPACED_ID.matcher(id).matches() || id.length() > 128) {
            throw new IllegalArgumentException("block material needs a namespaced block ID: " + blockId);
        }
        if (id.equals("minecraft:air") || id.equals("minecraft:wheat_seeds")) {
            throw new IllegalArgumentException("not a placeable block material: " + id);
        }
        return BLOCKS.computeIfAbsent(id, key -> new Material(Integer.MAX_VALUE, key, Kind.BLOCK, key));
    }

    /** Built-in materials by their lower-case JSON name; anything with a namespace is a block material. */
    public static Material fromJsonName(String name) {
        String trimmed = Objects.requireNonNull(name, "name").trim();
        if (trimmed.contains(":")) {
            return block(trimmed);
        }
        String upper = trimmed.toUpperCase(Locale.ROOT);
        for (Material builtIn : BUILT_INS) {
            if (builtIn.name.equals(upper)) {
                return builtIn;
            }
        }
        throw new IllegalArgumentException("No material named " + name);
    }

    /** The name used in checkpoints, observations and messages: {@code dirt}, or {@code minecraft:stone}. */
    public String jsonName() {
        return builtIn() ? name.toLowerCase(Locale.ROOT) : name;
    }

    /** The built-in constant name, such as {@code DIRT}, or a block material's ID. */
    public String name() {
        return name;
    }

    public Kind kind() {
        return kind;
    }

    /** The item that supplies this material; blank for the hoe and food categories. */
    public String itemId() {
        return itemId;
    }

    public boolean builtIn() {
        return order != Integer.MAX_VALUE;
    }

    public boolean placedAsBlock() {
        return kind == Kind.BLOCK;
    }

    @Override
    public int compareTo(Material other) {
        int byOrder = Integer.compare(order, other.order);
        return byOrder != 0 ? byOrder : name.compareTo(other.name);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof Material that && name.equals(that.name);
    }

    @Override
    public int hashCode() {
        return name.hashCode();
    }

    @Override
    public String toString() {
        return name;
    }
}
