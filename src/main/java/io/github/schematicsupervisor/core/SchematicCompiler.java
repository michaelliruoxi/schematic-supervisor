package io.github.schematicsupervisor.core;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Compiles absolute schematic targets into a bounded row-major rectangular execution plan.
 */
public final class SchematicCompiler {
    private static final Comparator<BlockPosition> TOP_DOWN = Comparator
            .comparingInt(BlockPosition::y)
            .reversed()
            .thenComparingInt(BlockPosition::x)
            .thenComparingInt(BlockPosition::z);

    private SchematicCompiler() {
    }

    public static SchematicPlan compile7x7(
            ChunkCoordinate originChunk,
            Collection<TargetBlock> targets
    ) {
        Objects.requireNonNull(originChunk, "originChunk");
        Objects.requireNonNull(targets, "targets");
        int minimumY = targets.stream().mapToInt(target -> target.position().y()).min().orElse(0);
        int maximumY = targets.stream().mapToInt(target -> target.position().y()).max().orElse(0);
        int minimumX = Math.multiplyExact(originChunk.x(), 16);
        int minimumZ = Math.multiplyExact(originChunk.z(), 16);
        BuildVolume inferredVolume = new BuildVolume(
                minimumX,
                minimumY,
                minimumZ,
                Math.addExact(minimumX, SchematicPlan.GRID_SIZE * 16 - 1),
                maximumY,
                Math.addExact(minimumZ, SchematicPlan.GRID_SIZE * 16 - 1)
        );
        return compile7x7(originChunk, inferredVolume, targets);
    }

    public static SchematicPlan compile7x7(
            ChunkCoordinate originChunk,
            BuildVolume buildVolume,
            Collection<TargetBlock> targets
    ) {
        return compile(new ChunkLayout(originChunk, SchematicPlan.GRID_SIZE, SchematicPlan.GRID_SIZE),
                buildVolume, targets);
    }

    public static SchematicPlan compile(BuildVolume buildVolume, Collection<TargetBlock> targets) {
        return compile(ChunkLayout.covering(buildVolume), buildVolume, targets);
    }

    private static SchematicPlan compile(ChunkLayout layout, BuildVolume buildVolume,
                                         Collection<TargetBlock> targets) {
        Objects.requireNonNull(buildVolume, "buildVolume");
        Objects.requireNonNull(targets, "targets");
        PlanLimits.requireVolume(buildVolume);
        PlanLimits.requireTargetCount(targets.size());

        List<MutableChunk> chunks = new ArrayList<>(layout.chunkCount());
        Map<ChunkCoordinate, Integer> indexes = new HashMap<>();
        for (int index = 0; index < layout.chunkCount(); index++) {
            ChunkCoordinate chunk = layout.chunk(index);
            indexes.put(chunk, index);
            chunks.add(new MutableChunk(chunk));
        }

        Map<BlockPosition, TargetBlock> uniqueTargets = new HashMap<>();
        for (TargetBlock target : targets) {
            if (!buildVolume.contains(target.position())) {
                throw new IllegalArgumentException(
                        "target outside the declared build volume: " + target.position()
                );
            }
            Integer chunkIndex = indexes.get(ChunkCoordinate.containing(target.position()));
            if (chunkIndex == null) {
                throw new IllegalArgumentException("target outside the chunk layout: " + target.position());
            }
            if (uniqueTargets.putIfAbsent(target.position(), target) != null) {
                throw new IllegalArgumentException("duplicate target position " + target.position());
            }
            compileTarget(chunks.get(chunkIndex), target);
        }

        List<ChunkPlan> compiled = chunks.stream().map(MutableChunk::freeze).toList();
        String planId = fingerprint(layout, buildVolume, uniqueTargets.values());
        return new SchematicPlan(planId, layout, buildVolume, compiled);
    }

    private static void compileTarget(MutableChunk chunk, TargetBlock target) {
        chunk.expected.add(target);
        switch (target.state().blockId()) {
            case "minecraft:farmland" -> {
                chunk.ordinary.add(new OrdinaryPlacement(
                        target.position(),
                        new BlockState("minecraft:dirt"),
                        Material.DIRT
                ));
                chunk.till.add(target.position());
            }
            case "minecraft:wheat" -> chunk.plant.add(target.position());
            case "minecraft:dirt" -> chunk.ordinary.add(new OrdinaryPlacement(
                    target.position(),
                    target.state(),
                    Material.DIRT
            ));
            case "minecraft:glowstone" -> chunk.ordinary.add(new OrdinaryPlacement(
                    target.position(),
                    target.state(),
                    Material.GLOWSTONE
            ));
            case "minecraft:birch_planks" -> chunk.ordinary.add(new OrdinaryPlacement(
                    target.position(),
                    target.state(),
                    Material.BIRCH_PLANKS
            ));
            default -> {
                // Any other block is placed like dirt when it has no properties to orient or set; the
                // fabric scan has already checked that it is a full, placeable cube.
                if (!target.state().properties().isEmpty()) {
                    throw new IllegalArgumentException(
                            "Unsupported block " + target.state().blockId() + target.state().properties()
                                    + " at " + target.position() + "; only full blocks without properties"
                                    + " (such as stone or planks), farmland, and wheat are supported."
                    );
                }
                chunk.ordinary.add(new OrdinaryPlacement(
                        target.position(),
                        target.state(),
                        Material.block(target.state().blockId())
                ));
            }
        }
    }

    private static String fingerprint(
            ChunkLayout layout,
            BuildVolume volume,
            Collection<TargetBlock> targets
    ) {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
        // Smaller layouts can collide with the legacy padded 7x7 compiler for identical input.
        // Larger tight layouts are already distinguished by the origin and volume below.
        if (layout.columns() <= SchematicPlan.GRID_SIZE && layout.rows() <= SchematicPlan.GRID_SIZE
                && (layout.columns() != SchematicPlan.GRID_SIZE || layout.rows() != SchematicPlan.GRID_SIZE)) {
            update(digest, "layout:" + layout.columns() + "," + layout.rows() + "\n");
        }
        update(digest, layout.origin().x() + "," + layout.origin().z() + "\n");
        update(digest, "volume:"
                + volume.minX() + "," + volume.minY() + "," + volume.minZ() + ":"
                + volume.maxX() + "," + volume.maxY() + "," + volume.maxZ() + "\n");
        targets.stream()
                .sorted(Comparator.comparing(TargetBlock::position))
                .forEach(target -> {
                    BlockState normalized = BlockStateNormalizer.normalize(target.state());
                    update(digest, target.position().x() + ","
                            + target.position().y() + ","
                            + target.position().z() + "="
                            + normalized.blockId() + normalized.properties() + "\n");
                });
        return "sha256:" + java.util.HexFormat.of().formatHex(digest.digest());
    }

    private static void update(MessageDigest digest, String value) {
        digest.update(value.getBytes(StandardCharsets.UTF_8));
    }

    private static final class MutableChunk {
        private final ChunkCoordinate chunk;
        private final List<TargetBlock> expected = new ArrayList<>();
        private final List<OrdinaryPlacement> ordinary = new ArrayList<>();
        private final List<BlockPosition> till = new ArrayList<>();
        private final List<BlockPosition> plant = new ArrayList<>();

        private MutableChunk(ChunkCoordinate chunk) {
            this.chunk = chunk;
        }

        private ChunkPlan freeze() {
            expected.sort(Comparator.comparing(TargetBlock::position));
            ordinary.sort(Comparator.comparing(OrdinaryPlacement::position));
            till.sort(TOP_DOWN);
            plant.sort(TOP_DOWN);
            return new ChunkPlan(chunk, expected, ordinary, till, plant);
        }
    }
}
