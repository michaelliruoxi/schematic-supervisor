package io.github.schematicsupervisor.core;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class SchematicVerification {
    private SchematicVerification() {
    }

    public static VerificationResult verify(
            SchematicPlan plan,
            VerificationScope scope,
            BlockObservation observation
    ) {
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(observation, "observation");

        MessageDigest digest = sha256();
        List<BlockMismatch> mismatches = new ArrayList<>();
        boolean allLoaded = true;
        List<ChunkPlan> chunks = scope.kind() == VerificationScope.Kind.FULL_PLAN
                ? plan.chunks()
                : List.of(plan.chunk(scope.chunkIndex()));
        Map<BlockPosition, BlockState> expected = new HashMap<>();
        for (ChunkPlan chunk : chunks) {
            for (TargetBlock target : chunk.expectedBlocks()) {
                expected.put(target.position(), target.state());
            }
        }

        for (ChunkPlan chunk : chunks) {
            boolean loaded = observation.isChunkLoaded(chunk.chunk());
            allLoaded &= loaded;
            update(digest, "chunk:" + chunk.chunk().x() + "," + chunk.chunk().z()
                    + ":loaded=" + loaded + "\n");
            if (!loaded) {
                continue;
            }
            BuildVolume volume = plan.buildVolume();
            int chunkMinimumX = Math.max(volume.minX(), Math.multiplyExact(chunk.chunk().x(), 16));
            int chunkMaximumX = Math.min(volume.maxX(), Math.addExact(Math.multiplyExact(chunk.chunk().x(), 16), 15));
            int chunkMinimumZ = Math.max(volume.minZ(), Math.multiplyExact(chunk.chunk().z(), 16));
            int chunkMaximumZ = Math.min(volume.maxZ(), Math.addExact(Math.multiplyExact(chunk.chunk().z(), 16), 15));
            if (chunkMinimumX > chunkMaximumX || chunkMinimumZ > chunkMaximumZ) {
                continue;
            }
            for (int y = volume.minY(); ; y++) {
                for (int x = chunkMinimumX; ; x++) {
                    for (int z = chunkMinimumZ; ; z++) {
                        BlockPosition position = new BlockPosition(x, y, z);
                        BlockState expectedState = expected.getOrDefault(position, BlockState.AIR);
                        inspectPosition(observation, digest, mismatches, position, expectedState,
                                plan.plantingDeferred());
                        if (z == chunkMaximumZ) {
                            break;
                        }
                    }
                    if (x == chunkMaximumX) {
                        break;
                    }
                }
                if (y == volume.maxY()) {
                    break;
                }
            }
        }

        List<BlockPosition> scaffolding = new ArrayList<>(
                Objects.requireNonNull(observation.temporaryScaffolding(scope), "temporary scaffolding")
        );
        scaffolding.sort(Comparator.naturalOrder());
        for (BlockPosition position : scaffolding) {
            update(digest, "scaffold:" + position.x() + "," + position.y() + "," + position.z() + "\n");
        }
        mismatches.sort(Comparator.comparing(BlockMismatch::position));

        return new VerificationResult(
                allLoaded,
                mismatches,
                scaffolding,
                "sha256:" + java.util.HexFormat.of().formatHex(digest.digest())
        );
    }

    private static void inspectPosition(
            BlockObservation observation,
            MessageDigest digest,
            List<BlockMismatch> mismatches,
            BlockPosition position,
            BlockState expected,
            boolean plantingDeferred
    ) {
        BlockState actual = Objects.requireNonNull(
                observation.blockState(position),
                "block observation returned null at " + position
        );
        BlockState normalizedActual = BlockStateNormalizer.normalize(actual);
        update(digest, position.x() + ","
                + position.y() + ","
                + position.z() + "="
                + normalizedActual.blockId() + normalizedActual.properties() + "\n");
        boolean deferredSeedCell = plantingDeferred && expected.blockId().equals("minecraft:wheat");
        if (!BlockStateNormalizer.equivalent(expected, actual) && !(deferredSeedCell && actual.isAir())) {
            MismatchType type;
            if (expected.isAir()) {
                type = MismatchType.EXTRANEOUS;
            } else if (actual.isAir()) {
                type = MismatchType.MISSING;
            } else {
                type = MismatchType.INCORRECT;
            }
            mismatches.add(new BlockMismatch(
                    position,
                    expected,
                    actual,
                    type
            ));
        }
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static void update(MessageDigest digest, String value) {
        digest.update(value.getBytes(StandardCharsets.UTF_8));
    }
}
