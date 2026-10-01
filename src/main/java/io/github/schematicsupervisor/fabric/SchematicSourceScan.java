package io.github.schematicsupervisor.fabric;

import io.github.schematicsupervisor.core.BlockPosition;
import io.github.schematicsupervisor.core.BlockState;
import io.github.schematicsupervisor.core.BuildVolume;
import io.github.schematicsupervisor.core.ChunkLayout;
import io.github.schematicsupervisor.core.PlanLimits;
import io.github.schematicsupervisor.core.SchematicCompiler;
import io.github.schematicsupervisor.core.SchematicPlan;
import io.github.schematicsupervisor.core.TargetBlock;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.UnaryOperator;

/** Reads every cell of a source container, independently of rendered or loaded world chunks. */
final class SchematicSourceScan {
    @FunctionalInterface
    interface Reader {
        BlockState get(int x, int y, int z);
    }

    /** Captured placement of the source container's minimum corner and unit axes. */
    record Transform(BlockPosition origin, BlockPosition xAxis, BlockPosition yAxis, BlockPosition zAxis) {
        Transform {
            Objects.requireNonNull(origin, "origin");
            requireUnitAxis(xAxis);
            requireUnitAxis(yAxis);
            requireUnitAxis(zAxis);
            if (dot(xAxis, yAxis) != 0 || dot(xAxis, zAxis) != 0 || dot(yAxis, zAxis) != 0) {
                throw new IllegalArgumentException("source placement axes must be perpendicular");
            }
        }

        BlockPosition apply(int x, int y, int z) {
            return new BlockPosition(
                    coordinate(origin.x(), xAxis.x(), yAxis.x(), zAxis.x(), x, y, z),
                    coordinate(origin.y(), xAxis.y(), yAxis.y(), zAxis.y(), x, y, z),
                    coordinate(origin.z(), xAxis.z(), yAxis.z(), zAxis.z(), x, y, z));
        }

        BuildVolume bounds(int width, int height, int depth) {
            BlockPosition opposite = apply(width - 1, height - 1, depth - 1);
            return new BuildVolume(Math.min(origin.x(), opposite.x()), Math.min(origin.y(), opposite.y()),
                    Math.min(origin.z(), opposite.z()), Math.max(origin.x(), opposite.x()),
                    Math.max(origin.y(), opposite.y()), Math.max(origin.z(), opposite.z()));
        }

        private static void requireUnitAxis(BlockPosition axis) {
            Objects.requireNonNull(axis, "axis");
            if (Math.abs((long) axis.x()) + Math.abs((long) axis.y()) + Math.abs((long) axis.z()) != 1) {
                throw new IllegalArgumentException("source placement axes must be signed unit vectors");
            }
        }

        private static int dot(BlockPosition left, BlockPosition right) {
            return left.x() * right.x() + left.y() * right.y() + left.z() * right.z();
        }

        private static int coordinate(int origin, int ax, int ay, int az, int x, int y, int z) {
            return Math.toIntExact((long) origin + (long) ax * x + (long) ay * y + (long) az * z);
        }
    }

    private final int width;
    private final int depth;
    private final long size;
    private final Transform transform;
    private final BuildVolume volume;
    private final long expectedNonAir;
    private final Reader source;
    // Why a block outside the built-in palette can't be placed like dirt, or blank when it can.
    private final UnaryOperator<String> placeableProblem;
    private final List<TargetBlock> targets;
    private long cursor;
    private SchematicPlan result;

    /** A scan that accepts only the built-in palette: dirt, farmland, wheat, glowstone, and birch planks. */
    SchematicSourceScan(int width, int height, int depth, Transform transform,
                        BuildVolume volume, long expectedNonAir, Reader source) {
        this(width, height, depth, transform, volume, expectedNonAir, source,
                blockId -> "is outside the built-in palette");
    }

    SchematicSourceScan(int width, int height, int depth, Transform transform,
                        BuildVolume volume, long expectedNonAir, Reader source,
                        UnaryOperator<String> placeableProblem) {
        if (width < 1 || height < 1 || depth < 1 || expectedNonAir < -1) {
            throw new IllegalArgumentException("invalid schematic source dimensions or block count");
        }
        this.width = width;
        this.depth = depth;
        this.size = Math.multiplyExact(Math.multiplyExact((long) width, height), depth);
        this.transform = Objects.requireNonNull(transform, "transform");
        this.volume = Objects.requireNonNull(volume, "volume");
        this.expectedNonAir = expectedNonAir;
        this.source = Objects.requireNonNull(source, "source");
        this.placeableProblem = Objects.requireNonNull(placeableProblem, "placeableProblem");
        PlanLimits.requireVolume(volume);
        ChunkLayout.covering(volume);
        if (size != volume.blockCount() || !transform.bounds(width, height, depth).equals(volume)) {
            throw new IllegalStateException("Source container dimensions or transforms do not match the selected placement.");
        }
        if (expectedNonAir > size) {
            throw new IllegalStateException("Schematic metadata block count exceeds its source volume.");
        }
        if (expectedNonAir >= 0) { PlanLimits.requireTargetCount(expectedNonAir); }
        targets = new ArrayList<>((int) Math.min(expectedNonAir < 0 ? size : expectedNonAir, 500_000));
    }

    double progress() { return (double) cursor / size; }
    boolean complete() { return result != null; }

    SchematicPlan result() {
        if (result == null) { throw new IllegalStateException("placement loading is not complete"); }
        return result;
    }

    void tick(int blockBudget) {
        if (blockBudget < 1) { throw new IllegalArgumentException("block budget must be positive"); }
        if (result != null) { return; }
        long stop = Math.min(size, Math.addExact(cursor, blockBudget));
        long plane = Math.multiplyExact((long) width, depth);
        while (cursor < stop) {
            int y = Math.toIntExact(cursor / plane);
            int z = Math.toIntExact(cursor % plane / width);
            int x = Math.toIntExact(cursor % width);
            BlockState state = Objects.requireNonNull(source.get(x, y, z), "source block state");
            if (!state.isAir()) {
                BlockPosition position = transform.apply(x, y, z);
                requireSupported(state.blockId(), position);
                PlanLimits.requireTargetCount((long) targets.size() + 1);
                targets.add(new TargetBlock(position, state));
            }
            cursor++;
        }
        if (cursor == size) {
            if (expectedNonAir >= 0 && targets.size() != expectedNonAir) {
                throw new IllegalStateException("Complete schematic source scan found " + targets.size()
                        + " non-air blocks, but metadata requires " + expectedNonAir
                        + ". Save or reload the complete schematic before starting.");
            }
            result = SchematicCompiler.compile(volume, targets);
        }
    }

    private void requireSupported(String blockId, BlockPosition position) {
        if (switch (blockId) {
            case "minecraft:farmland", "minecraft:wheat", "minecraft:dirt", "minecraft:glowstone",
                    "minecraft:birch_planks" -> true;
            default -> false;
        }) {
            return;
        }
        String problem = placeableProblem.apply(blockId);
        if (problem != null && !problem.isBlank()) {
            throw new IllegalStateException("Unsupported block " + blockId + " at " + position + ": it " + problem
                    + ". Supported: farmland, wheat, and full blocks without properties, such as dirt, stone,"
                    + " planks, or glowstone.");
        }
    }
}
