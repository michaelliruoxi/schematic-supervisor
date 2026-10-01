package io.github.schematicsupervisor.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.IntStream;

/**
 * Immutable layer-first schedule. Every execution order is bounded to one Y and one chunk. Each stage
 * visits its chunks along the {@link ChunkTour}.
 */
public final class LayerBuildSchedule {
    public static final String ID = "layers-v2";
    public static final String DEFERRED_PLANTING_ID = "layers-v2-deferred-planting";
    public static final String STRUCTURE_FIRST_ID = "layers-v2-structure-first";
    public static final String STRUCTURE_FIRST_DEFERRED_PLANTING_ID = "layers-v2-structure-first-deferred-planting";
    // Version-one schedules visited each stage's chunks in row-major order. They remain readable so
    // that a saved checkpoint can be mapped to the chunk tour when it loads.
    public static final String ROW_MAJOR_ID = "layers-v1";
    public static final String ROW_MAJOR_DEFERRED_PLANTING_ID = "layers-v1-deferred-planting";
    public static final String ROW_MAJOR_STRUCTURE_FIRST_ID = "layers-v1-structure-first";
    public static final String ROW_MAJOR_STRUCTURE_FIRST_DEFERRED_PLANTING_ID =
            "layers-v1-structure-first-deferred-planting";
    private final String id;
    private final List<Entry> entries;
    private final int stageCount;

    public record Entry(WorkOrder order, LayerProgress progress) { }
    private record Stage(String name, int y, List<WorkOrder> orders) { }

    public LayerBuildSchedule(SchematicPlan plan) {
        this(plan, false);
    }

    /** The schedule a saved checkpoint names, including a version-one row-major schedule. */
    public static LayerBuildSchedule forId(SchematicPlan plan, String scheduleId) {
        return new LayerBuildSchedule(plan.withPlantingDeferred(plantingDeferred(scheduleId))
                .withGlowstoneAfterStructure(glowstoneAfterStructure(scheduleId)), rowMajor(scheduleId));
    }

    private LayerBuildSchedule(SchematicPlan plan, boolean rowMajor) {
        id = rowMajor ? rowMajorId(plan.plantingDeferred(), plan.glowstoneAfterStructure())
                : id(plan.plantingDeferred(), plan.glowstoneAfterStructure());
        int[] chunkOrder = rowMajor ? IntStream.range(0, plan.chunkCount()).toArray()
                : ChunkTour.order(plan.layout());
        Map<Integer, Map<Integer, List<OrdinaryPlacement>>> structures = new TreeMap<>();
        Map<Integer, Map<Integer, List<OrdinaryPlacement>>> lighting = new TreeMap<>();
        Map<Integer, Map<Integer, List<BlockPosition>>> tilling = new TreeMap<>();
        Map<Integer, Map<Integer, List<BlockPosition>>> planting = new TreeMap<>();
        for (int index = 0; index < plan.chunks().size(); index++) {
            ChunkPlan chunk = plan.chunk(index);
            for (OrdinaryPlacement placement : chunk.ordinaryPlacements()) {
                add(placement.material() == Material.GLOWSTONE ? lighting : structures,
                        placement.position().y(), index, placement);
            }
            for (BlockPosition position : chunk.tillTargets()) {
                add(tilling, position.y(), index, position);
            }
            if (!plan.plantingDeferred()) {
                for (BlockPosition position : chunk.plantTargets()) {
                    add(planting, Math.subtractExact(position.y(), 1), index, position);
                }
            }
        }
        List<Stage> stages = new ArrayList<>();
        if (plan.glowstoneAfterStructure()) {
            structures.forEach((y, chunks) -> addOrdinary(stages, plan, chunkOrder, "STRUCTURE", y, chunks));
            lighting.forEach((y, chunks) -> addOrdinary(stages, plan, chunkOrder, "LIGHTING", y, chunks));
        } else {
            TreeSet<Integer> supports = new TreeSet<>(structures.keySet());
            for (int y : lighting.keySet()) {
                supports.add(Math.addExact(y, 1));
            }
            for (int supportY : supports) {
                addOrdinary(stages, plan, chunkOrder, "STRUCTURE", supportY, structures.get(supportY));
                // Hanging lights require the underside of the completed floor above them.
                int lightY = Math.subtractExact(supportY, 1);
                addOrdinary(stages, plan, chunkOrder, "LIGHTING", lightY, lighting.get(lightY));
            }
        }
        TreeSet<Integer> farmFloors = new TreeSet<>(Collections.reverseOrder());
        farmFloors.addAll(tilling.keySet());
        farmFloors.addAll(planting.keySet());
        for (int floorY : farmFloors) {
            addFarming(stages, plan, chunkOrder, floorY, tilling.get(floorY), false);
            addFarming(stages, plan, chunkOrder, Math.addExact(floorY, 1), planting.get(floorY), true);
        }
        stageCount = stages.size();
        List<Entry> builtEntries = new ArrayList<>();
        for (int stageIndex = 0; stageIndex < stages.size(); stageIndex++) {
            Stage stage = stages.get(stageIndex);
            for (int chunkIndex = 0; chunkIndex < stage.orders().size(); chunkIndex++) {
                builtEntries.add(new Entry(stage.orders().get(chunkIndex), new LayerProgress(
                        "LAYERS", stage.name(), stageIndex + 1, stageCount, stage.y(),
                        chunkIndex + 1, stage.orders().size())));
            }
        }
        entries = List.copyOf(builtEntries);
    }

    public int size() { return entries.size(); }
    public String id() { return id; }
    public Entry entry(int cursor) { return entries.get(cursor); }
    public List<Entry> entries() { return entries; }
    public int stageCount() { return stageCount; }

    /** The current schedule for these options; its stages visit chunks along the chunk tour. */
    public static String id(boolean plantingDeferred, boolean glowstoneAfterStructure) {
        return glowstoneAfterStructure
                ? (plantingDeferred ? STRUCTURE_FIRST_DEFERRED_PLANTING_ID : STRUCTURE_FIRST_ID)
                : (plantingDeferred ? DEFERRED_PLANTING_ID : ID);
    }

    private static String rowMajorId(boolean plantingDeferred, boolean glowstoneAfterStructure) {
        return glowstoneAfterStructure
                ? (plantingDeferred ? ROW_MAJOR_STRUCTURE_FIRST_DEFERRED_PLANTING_ID : ROW_MAJOR_STRUCTURE_FIRST_ID)
                : (plantingDeferred ? ROW_MAJOR_DEFERRED_PLANTING_ID : ROW_MAJOR_ID);
    }

    public static boolean glowstoneAfterStructure(String scheduleId) {
        return switch (scheduleId) {
            case ID, DEFERRED_PLANTING_ID, ROW_MAJOR_ID, ROW_MAJOR_DEFERRED_PLANTING_ID -> false;
            case STRUCTURE_FIRST_ID, STRUCTURE_FIRST_DEFERRED_PLANTING_ID,
                    ROW_MAJOR_STRUCTURE_FIRST_ID, ROW_MAJOR_STRUCTURE_FIRST_DEFERRED_PLANTING_ID -> true;
            default -> throw unsupported(scheduleId);
        };
    }

    public static boolean plantingDeferred(String scheduleId) {
        return switch (scheduleId) {
            case ID, STRUCTURE_FIRST_ID, ROW_MAJOR_ID, ROW_MAJOR_STRUCTURE_FIRST_ID -> false;
            case DEFERRED_PLANTING_ID, STRUCTURE_FIRST_DEFERRED_PLANTING_ID,
                    ROW_MAJOR_DEFERRED_PLANTING_ID, ROW_MAJOR_STRUCTURE_FIRST_DEFERRED_PLANTING_ID -> true;
            default -> throw unsupported(scheduleId);
        };
    }

    /** Whether a supported schedule is a version-one schedule that visits chunks in row-major order. */
    public static boolean rowMajor(String scheduleId) {
        glowstoneAfterStructure(scheduleId);
        return scheduleId.startsWith("layers-v1");
    }

    private static IllegalArgumentException unsupported(String scheduleId) {
        return new IllegalArgumentException("Unsupported layer schedule: " + scheduleId);
    }

    public int nextForChunk(int startInclusive, int chunkIndex) {
        for (int index = startInclusive; index < entries.size(); index++) {
            if (entries.get(index).order().chunkIndex() == chunkIndex) { return index; }
        }
        return entries.size();
    }

    public LayerProgress completedProgress(boolean done) {
        return new LayerProgress("LAYERS", done ? "DONE" : "VERIFY",
                stageCount, stageCount, null, 0, 0);
    }

    private static <T> void add(Map<Integer, Map<Integer, List<T>>> layers,
                               int y, int chunk, T item) {
        layers.computeIfAbsent(y, ignored -> new TreeMap<>())
                .computeIfAbsent(chunk, ignored -> new ArrayList<>()).add(item);
    }

    private static void addOrdinary(List<Stage> stages, SchematicPlan plan, int[] chunkOrder, String name,
                                    int y, Map<Integer, List<OrdinaryPlacement>> chunks) {
        if (chunks == null) { return; }
        List<WorkOrder> orders = new ArrayList<>();
        for (int index : chunkOrder) {
            List<OrdinaryPlacement> placements = chunks.get(index);
            if (placements != null) {
                orders.add(new WorkOrder.OrdinaryBlocks(index, plan.chunk(index).chunk(), placements));
            }
        }
        stages.add(new Stage(name, y, orders));
    }

    private static void addFarming(List<Stage> stages, SchematicPlan plan, int[] chunkOrder, int y,
                                   Map<Integer, List<BlockPosition>> chunks, boolean plant) {
        if (chunks == null) { return; }
        List<WorkOrder> orders = new ArrayList<>();
        for (int index : chunkOrder) {
            List<BlockPosition> targets = chunks.get(index);
            if (targets != null) {
                orders.add(plant ? new WorkOrder.Plant(index, plan.chunk(index).chunk(), targets)
                        : new WorkOrder.Till(index, plan.chunk(index).chunk(), targets));
            }
        }
        stages.add(new Stage(plant ? "PLANT" : "TILL", y, orders));
    }
}
