package io.github.schematicsupervisor.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Read-only comparison of the received build volume with the layer schedule, run before a build
 * starts. A piece (one stage in one chunk) is finished when every target already satisfies the
 * rule its executor uses. Chunks without received data stay unchecked, and their pieces are built
 * or re-checked when the schedule reaches them.
 */
public final class BuildCheck {
    public static final int MAX_PROBLEMS = 10;
    private static final String FARMLAND = "minecraft:farmland";
    private static final String DIRT = "minecraft:dirt";
    private static final String WHEAT = "minecraft:wheat";

    private BuildCheck() {
    }

    /** Blocks the builder replaces or removes by itself when it reaches them. */
    public interface Clearing {
        Clearing NONE = new Clearing() {
            @Override public boolean replacesAtPlacement(OrdinaryPlacement placement, BlockState actual) {
                return false;
            }

            @Override public boolean sweepsOpenCell(BlockState actual) {
                return false;
            }
        };

        /** The builder clears {@code actual} from this planned placement before placing its block. */
        boolean replacesAtPlacement(OrdinaryPlacement placement, BlockState actual);

        /** The sweep before each order removes {@code actual} from cells planned as air or crop. */
        boolean sweepsOpenCell(BlockState actual);
    }

    public enum ProblemKind {
        /** A planned cell holds a block the builder will not replace. */
        WRONG,
        /** A cell planned as air holds a block the builder will not remove. */
        EXTRA
    }

    public record Problem(ProblemKind kind, BlockPosition position, int chunkIndex,
                          BlockState expected, BlockState actual) {
        public Problem {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(position, "position");
            Objects.requireNonNull(expected, "expected");
            Objects.requireNonNull(actual, "actual");
            if (chunkIndex < 0) {
                throw new IllegalArgumentException("chunk index must be non-negative");
            }
        }
    }

    /**
     * Captured block states for one plan. A null state marks a cell that was not observed, such as
     * a block with an unconfirmed prediction. Only chunks marked received are compared.
     */
    public static final class Snapshot {
        private final BuildVolume volume;
        private final ChunkLayout layout;
        private final BlockState[] states;
        private final BitSet received = new BitSet();
        private final int sizeX;
        private final int sizeZ;

        public Snapshot(BuildVolume volume, ChunkLayout layout) {
            this.volume = Objects.requireNonNull(volume, "volume");
            this.layout = Objects.requireNonNull(layout, "layout");
            PlanLimits.requireVolume(volume);
            states = new BlockState[Math.toIntExact(volume.blockCount())];
            sizeX = volume.maxX() - volume.minX() + 1;
            sizeZ = volume.maxZ() - volume.minZ() + 1;
        }

        public BuildVolume volume() {
            return volume;
        }

        public void put(int x, int y, int z, BlockState state) {
            states[index(x, y, z)] = state;
        }

        public void markReceived(int chunkIndex) {
            received.set(Objects.checkIndex(chunkIndex, layout.chunkCount()));
        }

        public void markUnreceived(int chunkIndex) {
            received.clear(Objects.checkIndex(chunkIndex, layout.chunkCount()));
        }

        public boolean received(int chunkIndex) {
            return received.get(chunkIndex);
        }

        int receivedCount() {
            return received.cardinality();
        }

        int index(int x, int y, int z) {
            if (x < volume.minX() || x > volume.maxX() || y < volume.minY() || y > volume.maxY()
                    || z < volume.minZ() || z > volume.maxZ()) {
                throw new IllegalArgumentException("position is outside the captured volume");
            }
            return ((y - volume.minY()) * sizeX + (x - volume.minX())) * sizeZ + (z - volume.minZ());
        }

        int index(BlockPosition position) {
            return index(position.x(), position.y(), position.z());
        }

        BlockState state(int index) {
            return states[index];
        }

        boolean sameShape(SchematicPlan plan) {
            return volume.equals(plan.buildVolume()) && layout.equals(plan.layout());
        }
    }

    /**
     * Unfinished temporary supports: their cells are not extra blocks, and the slice that owns them
     * must run next even when its targets are finished.
     */
    public record Supports(Collection<BlockPosition> positions, WorkOrder slice) {
        public static final Supports NONE = new Supports(List.of(), null);

        public Supports {
            positions = List.copyOf(Objects.requireNonNull(positions, "positions"));
        }
    }

    public record Result(
            String planId,
            String scheduleId,
            int pieceCount,
            CompletedPieces completePieces,
            int chunksChecked,
            int chunkCount,
            long totalActions,
            long doneActions,
            long placementsLeft,
            long tillingLeft,
            long plantingLeft,
            long uncheckedActions,
            long wrongBlocks,
            long wrongBlocksCleared,
            long extraBlocks,
            long extraBlocksCleared,
            long temporarySupports,
            List<Problem> problems
    ) {
        public Result {
            Objects.requireNonNull(planId, "planId");
            Objects.requireNonNull(scheduleId, "scheduleId");
            Objects.requireNonNull(completePieces, "completePieces");
            problems = List.copyOf(Objects.requireNonNull(problems, "problems"));
            if (pieceCount < 0 || completePieces.limit() > pieceCount) {
                throw new IllegalArgumentException("finished pieces must belong to the schedule");
            }
            if (chunksChecked < 0 || chunksChecked > chunkCount || doneActions < 0 || doneActions > totalActions
                    || wrongBlocksCleared < 0 || wrongBlocksCleared > wrongBlocks
                    || extraBlocksCleared < 0 || extraBlocksCleared > extraBlocks
                    || placementsLeft < 0 || tillingLeft < 0 || plantingLeft < 0 || uncheckedActions < 0
                    || temporarySupports < 0 || problems.size() > MAX_PROBLEMS) {
                throw new IllegalArgumentException("build check counts are inconsistent");
            }
        }

        /** Wrong and extra blocks the builder leaves in place; final verification fails on them. */
        public long attentionBlocks() {
            return wrongBlocks - wrongBlocksCleared + extraBlocks - extraBlocksCleared;
        }

        public long workLeft() {
            return placementsLeft + tillingLeft + plantingLeft;
        }

        public int firstIncompletePiece() {
            return completePieces.nextIncomplete(0, pieceCount);
        }
    }

    public static Result evaluate(SchematicPlan plan, LayerBuildSchedule schedule, Snapshot snapshot,
                                  Clearing clearing, Supports supports) {
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(schedule, "schedule");
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(clearing, "clearing");
        Objects.requireNonNull(supports, "supports");
        if (!schedule.id().equals(LayerBuildSchedule.id(plan.plantingDeferred(), plan.glowstoneAfterStructure()))) {
            throw new IllegalArgumentException("build check schedule does not belong to the plan");
        }
        if (!snapshot.sameShape(plan)) {
            throw new IllegalArgumentException("build check snapshot does not match the plan volume");
        }
        BuildVolume volume = plan.buildVolume();
        int cells = Math.toIntExact(volume.blockCount());
        BlockState[] expected = new BlockState[cells];
        OrdinaryPlacement[] placements = new OrdinaryPlacement[cells];
        BitSet tillSoil = new BitSet(cells);
        Map<BlockState, BlockState> canonical = new HashMap<>();
        for (ChunkPlan chunk : plan.chunks()) {
            for (TargetBlock target : chunk.expectedBlocks()) {
                expected[snapshot.index(target.position())] = canonical.computeIfAbsent(target.state(), state -> state);
            }
            for (OrdinaryPlacement placement : chunk.ordinaryPlacements()) {
                placements[snapshot.index(placement.position())] = placement;
            }
            for (BlockPosition soil : chunk.tillTargets()) {
                tillSoil.set(snapshot.index(soil));
            }
        }
        Set<Integer> supportCells = new HashSet<>();
        for (BlockPosition position : supports.positions()) {
            if (volume.contains(position)) {
                supportCells.add(snapshot.index(position));
            }
        }

        // A sweep before an order covers its chunk from the volume floor to one block above its targets.
        List<List<int[]>> sweeps = new ArrayList<>();
        int[] sweepTop = new int[plan.chunkCount()];
        Arrays.fill(sweepTop, Integer.MIN_VALUE);
        for (int chunk = 0; chunk < plan.chunkCount(); chunk++) {
            sweeps.add(new ArrayList<>());
        }
        for (int piece = 0; piece < schedule.size(); piece++) {
            WorkOrder order = schedule.entry(piece).order();
            int top = maximumY(order) + 1;
            sweeps.get(order.chunkIndex()).add(new int[] {piece, top});
            sweepTop[order.chunkIndex()] = Math.max(sweepTop[order.chunkIndex()], top);
        }

        Equivalence equivalence = new Equivalence();
        List<Problem> problems = new ArrayList<>();
        Map<Integer, Set<Integer>> sweptStems = new HashMap<>();
        long wrong = 0;
        long wrongCleared = 0;
        long extra = 0;
        long extraCleared = 0;
        long supportBlocks = 0;
        // Natural position order (Y, then X, then Z) keeps the reported sample deterministic.
        for (int y = volume.minY(); y <= volume.maxY(); y++) {
            for (int x = volume.minX(); x <= volume.maxX(); x++) {
                for (int z = volume.minZ(); z <= volume.maxZ(); z++) {
                    int chunkIndex = chunkIndex(plan.layout(), x, z);
                    if (!snapshot.received(chunkIndex)) {
                        continue;
                    }
                    int cell = snapshot.index(x, y, z);
                    BlockState actual = snapshot.state(cell);
                    if (actual == null) {
                        continue;
                    }
                    BlockState planned = expected[cell];
                    if (planned == null) {
                        if (actual.isAir()) {
                            continue;
                        }
                        if (supportCells.contains(cell)) {
                            supportBlocks++;
                            continue;
                        }
                        extra++;
                        if (clearing.sweepsOpenCell(actual) && y <= sweepTop[chunkIndex]) {
                            extraCleared++;
                            sweptStems.computeIfAbsent(chunkIndex, ignored -> new HashSet<>()).add(y);
                        } else if (problems.size() < MAX_PROBLEMS) {
                            problems.add(new Problem(ProblemKind.EXTRA, new BlockPosition(x, y, z), chunkIndex,
                                    BlockState.AIR, actual));
                        }
                        continue;
                    }
                    if (actual.isAir() || equivalence.test(planned, actual)
                            || FARMLAND.equals(planned.blockId()) && DIRT.equals(actual.blockId())) {
                        continue;
                    }
                    wrong++;
                    OrdinaryPlacement placement = placements[cell];
                    boolean swept = placement == null && WHEAT.equals(planned.blockId())
                            && clearing.sweepsOpenCell(actual) && y <= sweepTop[chunkIndex];
                    if (placement != null && clearing.replacesAtPlacement(placement, actual) || swept) {
                        wrongCleared++;
                        // An unfinished planting piece already sweeps its own crop cells.
                        if (swept && plan.plantingDeferred()) {
                            sweptStems.computeIfAbsent(chunkIndex, ignored -> new HashSet<>()).add(y);
                        }
                    } else if (problems.size() < MAX_PROBLEMS) {
                        problems.add(new Problem(ProblemKind.WRONG, new BlockPosition(x, y, z), chunkIndex,
                                planned, actual));
                    }
                }
            }
        }

        BitSet complete = new BitSet(schedule.size());
        long[] pieceActions = new long[schedule.size()];
        long totalActions = 0;
        long doneActions = 0;
        long placementsLeft = 0;
        long tillingLeft = 0;
        long plantingLeft = 0;
        long unchecked = 0;
        for (int piece = 0; piece < schedule.size(); piece++) {
            WorkOrder order = schedule.entry(piece).order();
            boolean received = snapshot.received(order.chunkIndex());
            boolean finished = received;
            switch (order) {
                case WorkOrder.OrdinaryBlocks ordinary -> {
                    pieceActions[piece] = ordinary.placements().size();
                    for (OrdinaryPlacement placement : ordinary.placements()) {
                        int cell = snapshot.index(placement.position());
                        BlockState actual = received ? snapshot.state(cell) : null;
                        if (actual == null) {
                            unchecked++;
                            finished = false;
                        } else if (!PlacementAcceptance.satisfied(placement, actual, tillSoil.get(cell))) {
                            placementsLeft++;
                            finished = false;
                        }
                    }
                }
                case WorkOrder.Till till -> {
                    pieceActions[piece] = till.targets().size();
                    for (BlockPosition soil : till.targets()) {
                        BlockState actual = received ? snapshot.state(snapshot.index(soil)) : null;
                        if (actual == null) {
                            unchecked++;
                            finished = false;
                        } else if (!FARMLAND.equals(actual.blockId())) {
                            tillingLeft++;
                            finished = false;
                        }
                    }
                }
                case WorkOrder.Plant plant -> {
                    pieceActions[piece] = plant.targets().size();
                    for (BlockPosition crop : plant.targets()) {
                        BlockState actual = received ? snapshot.state(snapshot.index(crop)) : null;
                        if (actual == null) {
                            unchecked++;
                            finished = false;
                        } else if (!WHEAT.equals(actual.blockId())) {
                            plantingLeft++;
                            finished = false;
                        }
                    }
                }
            }
            totalActions += pieceActions[piece];
            if (finished && !order.equals(supports.slice())) {
                complete.set(piece);
                doneActions += pieceActions[piece];
            }
        }

        // Removable stems in open cells are swept only before an order runs in their chunk.
        for (Map.Entry<Integer, Set<Integer>> stems : sweptStems.entrySet()) {
            for (int stemY : stems.getValue()) {
                for (int[] sweep : sweeps.get(stems.getKey())) {
                    if (sweep[1] >= stemY) {
                        if (complete.get(sweep[0])) {
                            complete.clear(sweep[0]);
                            doneActions -= pieceActions[sweep[0]];
                        }
                        break;
                    }
                }
            }
        }

        return new Result(plan.planId(), schedule.id(), schedule.size(), CompletedPieces.of(complete),
                snapshot.receivedCount(), plan.chunkCount(), totalActions, doneActions, placementsLeft,
                tillingLeft, plantingLeft, unchecked, wrong, wrongCleared, extra, extraCleared, supportBlocks,
                problems);
    }

    private static int maximumY(WorkOrder order) {
        return switch (order) {
            case WorkOrder.OrdinaryBlocks ordinary -> ordinary.placements().stream()
                    .mapToInt(placement -> placement.position().y()).max().orElseThrow();
            case WorkOrder.Till till -> till.targets().stream().mapToInt(BlockPosition::y).max().orElseThrow();
            case WorkOrder.Plant plant -> plant.targets().stream().mapToInt(BlockPosition::y).max().orElseThrow();
        };
    }

    private static int chunkIndex(ChunkLayout layout, int x, int z) {
        return (Math.floorDiv(z, 16) - layout.origin().z()) * layout.columns()
                + Math.floorDiv(x, 16) - layout.origin().x();
    }

    /** Planned and captured states repeat heavily; compare each distinct pair once. */
    private static final class Equivalence {
        private final IdentityHashMap<BlockState, IdentityHashMap<BlockState, Boolean>> results =
                new IdentityHashMap<>();

        boolean test(BlockState planned, BlockState actual) {
            if (planned == actual) {
                return true;
            }
            return results.computeIfAbsent(planned, ignored -> new IdentityHashMap<>())
                    .computeIfAbsent(actual, ignored -> BlockStateNormalizer.equivalent(planned, actual));
        }
    }
}
