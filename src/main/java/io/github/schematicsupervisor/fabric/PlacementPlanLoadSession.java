package io.github.schematicsupervisor.fabric;

import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.litematica.schematic.container.LitematicaBlockStateContainer;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.schematic.placement.SubRegionPlacement;
import fi.dy.masa.litematica.schematic.placement.SubRegionPlacement.RequiredEnabled;
import fi.dy.masa.litematica.selection.Box;
import fi.dy.masa.litematica.util.PositionUtils;
import io.github.schematicsupervisor.core.BlockPosition;
import io.github.schematicsupervisor.core.BuildVolume;
import io.github.schematicsupervisor.core.ChunkLayout;
import io.github.schematicsupervisor.core.PlanLimits;
import io.github.schematicsupervisor.core.SchematicPlan;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;
import net.minecraft.block.BlockState;
import net.minecraft.util.BlockMirror;
import net.minecraft.util.BlockRotation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3i;

/** Incrementally reads the selected Litematica source container on the client thread. */
final class PlacementPlanLoadSession {
    private final Selection selection;
    private final String placementName;
    private final SchematicSourceScan scan;
    private final IdentityHashMap<BlockState, io.github.schematicsupervisor.core.BlockState> mappedStates =
            new IdentityHashMap<>();

    PlacementPlanLoadSession() {
        selection = inspectSelection();
        placementName = selection.placement().getName();
        Vec3i size = selection.container().getSize();
        SchematicSourceScan.Transform transform = sourceTransform(selection.origin(),
                selection.regionPosition(), selection.signedSize(), selection.mirror(),
                selection.rotation(), selection.regionMirror(), selection.regionRotation());
        scan = new SchematicSourceScan(size.getX(), size.getY(), size.getZ(), transform,
                selection.volume(), selection.expectedNonAir(), this::readSourceState, PlaceableBlocks::problem);
    }

    static String selectionProblem() {
        try {
            inspectSelection();
            return "";
        } catch (RuntimeException exception) {
            String message = exception.getMessage();
            return message == null || message.isBlank() ? "Selected placement is not ready." : message;
        }
    }

    static Object selectionIdentity() {
        try {
            return inspectSelection();
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private static Selection inspectSelection() {
        SchematicPlacement selected = DataManager.getSchematicPlacementManager().getSelectedSchematicPlacement();
        if (selected == null) { throw new IllegalStateException("Select one Litematica placement before starting."); }
        if (!selected.isEnabled()) { throw new IllegalStateException("The selected Litematica placement is disabled."); }
        Map<String, Box> boxes = selected.getSubRegionBoxes(RequiredEnabled.PLACEMENT_ENABLED);
        if (boxes.size() != 1) {
            throw new IllegalStateException("Select exactly one enabled Litematica sub-region; combining regions is not supported.");
        }
        Map.Entry<String, Box> entry = boxes.entrySet().iterator().next();
        String regionName = entry.getKey();
        Box box = entry.getValue();
        BlockPos first = Objects.requireNonNull(box.getPos1(), "selected box position 1");
        BlockPos second = Objects.requireNonNull(box.getPos2(), "selected box position 2");
        BuildVolume volume = new BuildVolume(Math.min(first.getX(), second.getX()),
                Math.min(first.getY(), second.getY()), Math.min(first.getZ(), second.getZ()),
                Math.max(first.getX(), second.getX()), Math.max(first.getY(), second.getY()),
                Math.max(first.getZ(), second.getZ()));
        PlanLimits.requireVolume(volume);
        ChunkLayout.covering(volume);
        rejectOverlappingPlacements(selected, volume);
        LitematicaSchematic schematic = selected.getSchematic();
        SubRegionPlacement region = selected.getRelativeSubRegionPlacement(regionName);
        LitematicaBlockStateContainer container = schematic.getSubRegionContainer(regionName);
        BlockPos signedSize = schematic.getAreaSize(regionName);
        if (region == null || container == null || signedSize == null) {
            throw new IllegalStateException("The selected region's complete schematic source data is unavailable.");
        }
        Vec3i size = container.getSize();
        if (Math.abs((long) signedSize.getX()) != size.getX()
                || Math.abs((long) signedSize.getY()) != size.getY()
                || Math.abs((long) signedSize.getZ()) != size.getZ()) {
            throw new IllegalStateException("The selected region's source container dimensions are inconsistent.");
        }
        return new Selection(selected, schematic, regionName, region, container, volume,
                selected.getOrigin().toImmutable(), region.getPos().toImmutable(), signedSize.toImmutable(),
                selected.getMirror(), selected.getRotation(), region.getMirror(), region.getRotation(),
                schematic.getMetadata().getTimeModified(), schematic.getMetadata().wasModifiedSinceSaved(),
                schematic.getSubRegionCount() == 1 ? schematic.getMetadata().getTotalBlocks() : -1);
    }

    private record Selection(SchematicPlacement placement, LitematicaSchematic schematic,
                             String regionName, SubRegionPlacement region, LitematicaBlockStateContainer container,
                             BuildVolume volume, BlockPos origin, BlockPos regionPosition, BlockPos signedSize,
                             BlockMirror mirror, BlockRotation rotation, BlockMirror regionMirror,
                             BlockRotation regionRotation, long modifiedTime, boolean modifiedSinceSaved,
                             long expectedNonAir) { }

    String placementName() { return placementName; }
    double progress() { return scan.progress(); }
    boolean complete() { return scan.complete(); }
    SchematicPlan result() { return scan.result(); }

    void tick(int blockBudget) {
        if (blockBudget < 1) { throw new IllegalArgumentException("block budget must be positive"); }
        if (scan.complete()) { return; }
        if (!selection.equals(inspectSelection())) {
            throw new IllegalStateException("The selected Litematica source or placement transform changed while loading.");
        }
        scan.tick(blockBudget);
    }

    private io.github.schematicsupervisor.core.BlockState readSourceState(int x, int y, int z) {
        BlockState state = selection.container().get(x, y, z);
        if (state.isAir()) { return io.github.schematicsupervisor.core.BlockState.AIR; }
        return mappedStates.computeIfAbsent(state, source -> {
            BlockMirror subMirror = selection.regionMirror();
            if (subMirror != BlockMirror.NONE && (selection.rotation() == BlockRotation.CLOCKWISE_90
                    || selection.rotation() == BlockRotation.COUNTERCLOCKWISE_90)) {
                subMirror = subMirror == BlockMirror.FRONT_BACK ? BlockMirror.LEFT_RIGHT : BlockMirror.FRONT_BACK;
            }
            BlockState transformed = source.mirror(selection.mirror()).mirror(subMirror)
                    .rotate(selection.rotation().rotate(selection.regionRotation()));
            return MinecraftBlockStates.toCore(transformed);
        });
    }

    static SchematicSourceScan.Transform sourceTransform(BlockPos origin, BlockPos regionPosition,
            BlockPos signedSize, BlockMirror mirror, BlockRotation rotation,
            BlockMirror regionMirror, BlockRotation regionRotation) {
        BlockPos relativeEnd = PositionUtils.getRelativeEndPositionFromAreaSize(signedSize);
        BlockPos offset = new BlockPos(Math.min(0, relativeEnd.getX()), Math.min(0, relativeEnd.getY()),
                Math.min(0, relativeEnd.getZ()));
        BlockPos regionOrigin = PositionUtils.getTransformedBlockPos(regionPosition, mirror, rotation).add(origin);
        BlockPos zero = transform(offset, mirror, rotation, regionMirror, regionRotation).add(regionOrigin);
        BlockPos x = transform(offset.add(1, 0, 0), mirror, rotation, regionMirror, regionRotation).add(regionOrigin);
        BlockPos y = transform(offset.add(0, 1, 0), mirror, rotation, regionMirror, regionRotation).add(regionOrigin);
        BlockPos z = transform(offset.add(0, 0, 1), mirror, rotation, regionMirror, regionRotation).add(regionOrigin);
        return new SchematicSourceScan.Transform(toCore(zero), toCore(x.subtract(zero)),
                toCore(y.subtract(zero)), toCore(z.subtract(zero)));
    }

    private static BlockPos transform(BlockPos position, BlockMirror mirror, BlockRotation rotation,
            BlockMirror regionMirror, BlockRotation regionRotation) {
        return PositionUtils.getTransformedBlockPos(PositionUtils.getTransformedBlockPos(position, mirror, rotation),
                regionMirror, regionRotation);
    }

    private static BlockPosition toCore(BlockPos position) {
        return new BlockPosition(position.getX(), position.getY(), position.getZ());
    }

    private static void rejectOverlappingPlacements(SchematicPlacement selected, BuildVolume selectedVolume) {
        for (SchematicPlacement placement : DataManager.getSchematicPlacementManager().getAllSchematicsPlacements()) {
            if (placement == selected || !placement.isEnabled()) { continue; }
            for (Box box : placement.getSubRegionBoxes(RequiredEnabled.PLACEMENT_ENABLED).values()) {
                BlockPos first = box.getPos1();
                BlockPos second = box.getPos2();
                if (first == null || second == null) { continue; }
                BuildVolume other = new BuildVolume(Math.min(first.getX(), second.getX()),
                        Math.min(first.getY(), second.getY()), Math.min(first.getZ(), second.getZ()),
                        Math.max(first.getX(), second.getX()), Math.max(first.getY(), second.getY()),
                        Math.max(first.getZ(), second.getZ()));
                if (intersects(selectedVolume, other)) {
                    throw new IllegalStateException("Another enabled Litematica placement overlaps the selected placement.");
                }
            }
        }
    }

    private static boolean intersects(BuildVolume left, BuildVolume right) {
        return left.minX() <= right.maxX() && left.maxX() >= right.minX()
                && left.minY() <= right.maxY() && left.maxY() >= right.minY()
                && left.minZ() <= right.maxZ() && left.maxZ() >= right.minZ();
    }
}
