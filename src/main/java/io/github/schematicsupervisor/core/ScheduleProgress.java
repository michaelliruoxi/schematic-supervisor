package io.github.schematicsupervisor.core;

import java.util.Arrays;
import java.util.TreeMap;
import java.util.List;
import java.util.Objects;
import java.util.OptionalInt;

/**
 * Display progress for a layer schedule. Work is counted in actions: ordinary placements plus
 * till and plant targets. A piece is one stage in one chunk. Pieces before the cursor are done, and
 * so are later pieces that the start build check found finished. Materials are counted the same
 * way, in items: one per ordinary placement and one seed per plant target.
 */
public final class ScheduleProgress {
    public static final char DONE = 'D';
    public static final char CURRENT = 'C';
    public static final char TO_DO = '-';
    public static final char NO_WORK = '.';

    /** Everything the progress view depends on; cheap to build and compare on every tick. */
    public record Key(String planId, String scheduleId, int cursor, int repairChunkIndex, CompletedPieces checked) {
        public Key {
            checked = checked == null ? CompletedPieces.none() : checked;
        }

        public Key(String planId, String scheduleId, int cursor, int repairChunkIndex) {
            this(planId, scheduleId, cursor, repairChunkIndex, CompletedPieces.none());
        }
    }

    /** Per-schedule facts, computed once and shared by every cursor position. */
    public static final class Index {
        private final String planId;
        private final String scheduleId;
        private final ChunkLayout layout;
        private final String[] stageKinds;
        private final int[] stageYs;
        private final int[] stageStarts;
        private final int[] stageEnds;
        private final int[] entryStage;
        private final int[] entryChunk;
        private final long[] prefixActions;
        // Running item totals per material the schedule uses, like prefixActions.
        private final TreeMap<Material, long[]> prefixItems = new TreeMap<>();

        private Index(SchematicPlan plan, LayerBuildSchedule schedule) {
            planId = plan.planId();
            scheduleId = schedule.id();
            layout = plan.layout();
            List<LayerBuildSchedule.Entry> entries = schedule.entries();
            int stages = schedule.stageCount();
            stageKinds = new String[stages];
            stageYs = new int[stages];
            stageStarts = new int[stages];
            stageEnds = new int[stages];
            entryStage = new int[entries.size()];
            entryChunk = new int[entries.size()];
            prefixActions = new long[entries.size() + 1];
            for (int index = 0; index < entries.size(); index++) {
                LayerBuildSchedule.Entry entry = entries.get(index);
                int stage = entry.progress().ordinal() - 1;
                int previous = index == 0 ? -1 : entryStage[index - 1];
                if (stage < 0 || stage >= stages || (stage != previous && stage != previous + 1)) {
                    throw new IllegalArgumentException("schedule stages must be contiguous and ordered");
                }
                if (stage != previous) {
                    stageKinds[stage] = entry.progress().stage();
                    stageYs[stage] = Objects.requireNonNull(entry.progress().y(), "stage y");
                    stageStarts[stage] = index;
                }
                stageEnds[stage] = index + 1;
                int chunk = entry.order().chunkIndex();
                if (chunk >= layout.chunkCount()) {
                    throw new IllegalArgumentException("schedule entry chunk is outside the plan layout");
                }
                entryStage[index] = stage;
                entryChunk[index] = chunk;
                prefixActions[index + 1] = Math.addExact(prefixActions[index], actions(entry.order()));
                countItems(entry.order(), index);
            }
            if (entries.isEmpty() ? stages != 0 : entryStage[entries.size() - 1] != stages - 1) {
                throw new IllegalArgumentException("schedule stage count does not match its entries");
            }
            for (long[] items : prefixItems.values()) {
                for (int index = 0; index < entries.size(); index++) {
                    items[index + 1] = Math.addExact(items[index + 1], items[index]);
                }
            }
        }

        public int entryCount() {
            return entryChunk.length;
        }

        private void countItems(WorkOrder order, int entry) {
            switch (order) {
                case WorkOrder.OrdinaryBlocks ordinary -> {
                    for (OrdinaryPlacement placement : ordinary.placements()) {
                        items(placement.material())[entry + 1]++;
                    }
                }
                case WorkOrder.Till ignored -> {
                    // Tilling uses a hoe, not an item per target.
                }
                // One seed per target, as ChunkPlan.plantMaterials counts them.
                case WorkOrder.Plant plant -> items(Material.WHEAT_SEEDS)[entry + 1] += plant.targets().size();
            }
        }

        private long[] items(Material material) {
            return prefixItems.computeIfAbsent(material, unused -> new long[entryChunk.length + 1]);
        }
    }

    private final Index index;
    private final int cursor;
    private final int repairChunkIndex;
    private final CompletedPieces checked;
    private final long[] repairRemaining;
    private final long[] checkedAhead;
    // The same two sums in items, per material.
    private final TreeMap<Material, Long> repairRemainingItems = new TreeMap<>();
    private final TreeMap<Material, Long> checkedAheadItems = new TreeMap<>();

    private ScheduleProgress(Index index, int cursor, int repairChunkIndex, CompletedPieces checked) {
        this.index = index;
        this.cursor = cursor;
        this.repairChunkIndex = repairChunkIndex;
        // A repair starts only after every piece is done, so it never has checked pieces ahead.
        this.checked = repairChunkIndex >= 0 ? CompletedPieces.none() : checked;
        repairRemaining = new long[index.stageKinds.length];
        checkedAhead = new long[index.stageKinds.length];
        if (repairChunkIndex >= 0) {
            // A final-verification repair rewinds the cursor and re-walks only one chunk.
            for (int entry = cursor; entry < index.entryCount(); entry++) {
                if (index.entryChunk[entry] == repairChunkIndex) {
                    repairRemaining[index.entryStage[entry]] += entryActions(entry);
                    addEntryItems(repairRemainingItems, entry);
                }
            }
        }
        for (int entry = cursor + 1; entry < index.entryCount(); entry++) {
            if (this.checked.contains(entry)) {
                checkedAhead[index.entryStage[entry]] += entryActions(entry);
                addEntryItems(checkedAheadItems, entry);
            }
        }
    }

    public static Index index(SchematicPlan plan, LayerBuildSchedule schedule) {
        return new Index(Objects.requireNonNull(plan, "plan"), Objects.requireNonNull(schedule, "schedule"));
    }

    public static ScheduleProgress of(Index index, int cursor, int repairChunkIndex) {
        return of(index, cursor, repairChunkIndex, CompletedPieces.none());
    }

    public static ScheduleProgress of(Index index, int cursor, int repairChunkIndex, CompletedPieces checked) {
        Objects.requireNonNull(index, "index");
        Objects.requireNonNull(checked, "checked");
        if (cursor < 0 || cursor > index.entryCount()) {
            throw new IllegalArgumentException("cursor is outside the schedule");
        }
        if (repairChunkIndex < -1 || repairChunkIndex >= index.layout.chunkCount()) {
            throw new IllegalArgumentException("repair chunk is outside the plan layout");
        }
        if (checked.limit() > index.entryCount()) {
            throw new IllegalArgumentException("checked pieces are outside the schedule");
        }
        return new ScheduleProgress(index, cursor, repairChunkIndex, checked);
    }

    public String planId() {
        return index.planId;
    }

    public String scheduleId() {
        return index.scheduleId;
    }

    public ChunkLayout layout() {
        return index.layout;
    }

    public int stageCount() {
        return index.stageKinds.length;
    }

    public long totalActions() {
        return index.prefixActions[index.entryCount()];
    }

    public long doneActions() {
        long done = 0;
        for (int stage = 0; stage < stageCount(); stage++) {
            done += stageDone(stage);
        }
        return done;
    }

    /** Items the whole schedule places or plants; equal to the plan's planned materials. */
    public MaterialQuantities plannedMaterials() {
        TreeMap<Material, Long> planned = new TreeMap<>();
        index.prefixItems.forEach((material, items) -> planned.put(material, items[index.entryCount()]));
        return MaterialQuantities.of(planned);
    }

    /** Items in finished pieces, counted like {@link #doneActions()}. */
    public MaterialQuantities doneMaterials() {
        TreeMap<Material, Long> done = new TreeMap<>();
        index.prefixItems.forEach((material, items) -> done.put(material, repairChunkIndex >= 0
                ? items[index.entryCount()] - repairRemainingItems.getOrDefault(material, 0L)
                : items[cursor] + checkedAheadItems.getOrDefault(material, 0L)));
        return MaterialQuantities.of(done);
    }

    /** Zero-based stage of the piece being built, or empty when every piece is finished. */
    public OptionalInt currentStage() {
        return cursor < index.entryCount() ? OptionalInt.of(index.entryStage[cursor]) : OptionalInt.empty();
    }

    public String stageKind(int stage) {
        return index.stageKinds[Objects.checkIndex(stage, stageCount())];
    }

    public int stageY(int stage) {
        return index.stageYs[Objects.checkIndex(stage, stageCount())];
    }

    public long stageActions(int stage) {
        Objects.checkIndex(stage, stageCount());
        return index.prefixActions[index.stageEnds[stage]] - index.prefixActions[index.stageStarts[stage]];
    }

    public long stageDone(int stage) {
        long actions = stageActions(stage);
        if (repairChunkIndex >= 0) {
            return actions - repairRemaining[stage];
        }
        int start = index.stageStarts[stage];
        int end = index.stageEnds[stage];
        if (end <= cursor) {
            return actions;
        }
        long beforeCursor = start >= cursor ? 0 : index.prefixActions[cursor] - index.prefixActions[start];
        return beforeCursor + checkedAhead[stage];
    }

    /** One letter per layout chunk, row-major: D done, C current, - to do, . no work at this stage. */
    public String chunkStatuses(int stage) {
        Objects.checkIndex(stage, stageCount());
        char[] statuses = new char[index.layout.chunkCount()];
        Arrays.fill(statuses, NO_WORK);
        for (int entry = index.stageStarts[stage]; entry < index.stageEnds[stage]; entry++) {
            statuses[index.entryChunk[entry]] = status(entry);
        }
        return new String(statuses);
    }

    private char status(int entry) {
        if (repairChunkIndex >= 0 && index.entryChunk[entry] != repairChunkIndex) {
            return DONE;
        }
        if (entry < cursor) {
            return DONE;
        }
        if (entry == cursor) {
            return CURRENT;
        }
        return checked.contains(entry) ? DONE : TO_DO;
    }

    private long entryActions(int entry) {
        return index.prefixActions[entry + 1] - index.prefixActions[entry];
    }

    private void addEntryItems(TreeMap<Material, Long> totals, int entry) {
        index.prefixItems.forEach((material, items) ->
                totals.merge(material, items[entry + 1] - items[entry], Math::addExact));
    }

    private static long actions(WorkOrder order) {
        return switch (order) {
            case WorkOrder.OrdinaryBlocks ordinary -> ordinary.placements().size();
            case WorkOrder.Till till -> till.targets().size();
            case WorkOrder.Plant plant -> plant.targets().size();
        };
    }
}
