package io.github.schematicsupervisor.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SchematicVerificationTest {
    @Test
    void treatsAllVanillaAirVariantsAsEquivalent() {
        assertTrue(BlockStateNormalizer.equivalent(
                BlockState.AIR,
                new BlockState("minecraft:cave_air")
        ));
        assertTrue(BlockStateNormalizer.equivalent(
                BlockState.AIR,
                new BlockState("minecraft:void_air")
        ));
    }

    @Test
    void ignoresFarmlandMoistureAndWheatAgeInComparisonAndFingerprint() {
        SchematicPlan plan = SchematicCompiler.compile7x7(
                new ChunkCoordinate(0, 0),
                List.of(
                        target(0, 0, 0, "minecraft:farmland", Map.of("moisture", "7")),
                        target(0, 1, 0, "minecraft:wheat", Map.of("age", "0"))
                )
        );
        FakeObservation first = new FakeObservation(Map.of(
                new BlockPosition(0, 0, 0),
                new BlockState("minecraft:farmland", Map.of("moisture", "0")),
                new BlockPosition(0, 1, 0),
                new BlockState("minecraft:wheat", Map.of("age", "3"))
        ));
        FakeObservation second = new FakeObservation(Map.of(
                new BlockPosition(0, 0, 0),
                new BlockState("minecraft:farmland", Map.of("moisture", "6")),
                new BlockPosition(0, 1, 0),
                new BlockState("minecraft:wheat", Map.of("age", "7"))
        ));

        VerificationResult firstResult = SchematicVerification.verify(
                plan,
                VerificationScope.fullPlan(),
                first
        );
        VerificationResult secondResult = SchematicVerification.verify(
                plan,
                VerificationScope.fullPlan(),
                second
        );

        assertTrue(firstResult.clean());
        assertTrue(secondResult.clean());
        assertEquals(firstResult.normalizedFingerprint(), secondResult.normalizedFingerprint());
    }

    @Test
    void doesNotIgnoreOtherPropertiesOrGrowthNamesOnOtherBlocks() {
        assertFalse(BlockStateNormalizer.equivalent(
                new BlockState("minecraft:birch_planks", Map.of("axis", "x")),
                new BlockState("minecraft:birch_planks", Map.of("axis", "z"))
        ));
        assertFalse(BlockStateNormalizer.equivalent(
                new BlockState("minecraft:dirt", Map.of("moisture", "0")),
                new BlockState("minecraft:dirt", Map.of("moisture", "7"))
        ));
        assertFalse(BlockStateNormalizer.equivalent(
                new BlockState("minecraft:glowstone", Map.of("age", "0")),
                new BlockState("minecraft:glowstone", Map.of("age", "1"))
        ));
    }

    @Test
    void reportsMissingIncorrectUnloadedAndTemporaryScaffolding() {
        SchematicPlan plan = SchematicCompiler.compile7x7(
                new ChunkCoordinate(0, 0),
                List.of(
                        target(0, 0, 0, "minecraft:dirt", Map.of()),
                        target(1, 0, 0, "minecraft:glowstone", Map.of())
                )
        );
        FakeObservation observation = new FakeObservation(Map.of(
                new BlockPosition(0, 0, 0), BlockState.AIR,
                new BlockPosition(1, 0, 0), new BlockState("minecraft:dirt"),
                new BlockPosition(2, 0, 0), new BlockState("minecraft:birch_planks")
        ));
        observation.scaffolding = List.of(new BlockPosition(3, 0, 0));

        VerificationResult result = SchematicVerification.verify(
                plan,
                VerificationScope.chunk(0),
                observation
        );

        assertFalse(result.clean());
        assertEquals(1, result.missingCount());
        assertEquals(1, result.incorrectCount());
        assertEquals(1, result.extraneousCount());
        assertEquals(1, result.temporaryScaffolding().size());

        observation.loaded = false;
        VerificationResult unloaded = SchematicVerification.verify(
                plan,
                VerificationScope.chunk(0),
                observation
        );
        assertFalse(unloaded.allRequestedChunksLoaded());
        assertNotEquals(result.normalizedFingerprint(), unloaded.normalizedFingerprint());
    }

    @Test
    void significantWorldChangeChangesFingerprint() {
        SchematicPlan plan = SchematicCompiler.compile7x7(
                new ChunkCoordinate(0, 0),
                List.of(target(0, 0, 0, "minecraft:dirt", Map.of()))
        );
        VerificationResult correct = SchematicVerification.verify(
                plan,
                VerificationScope.chunk(0),
                new FakeObservation(Map.of(
                        new BlockPosition(0, 0, 0), new BlockState("minecraft:dirt")
                ))
        );
        VerificationResult missing = SchematicVerification.verify(
                plan,
                VerificationScope.chunk(0),
                new FakeObservation(Map.of(new BlockPosition(0, 0, 0), BlockState.AIR))
        );

        assertNotEquals(correct.normalizedFingerprint(), missing.normalizedFingerprint());
    }

    @Test
    void explicitBuildVolumeDetectsExpectedAirWithoutStoringAirTargets() {
        BuildVolume volume = new BuildVolume(0, 0, 0, 1, 0, 0);
        SchematicPlan plan = SchematicCompiler.compile7x7(
                new ChunkCoordinate(0, 0),
                volume,
                List.of(target(0, 0, 0, "minecraft:dirt", Map.of()))
        );
        FakeObservation observation = new FakeObservation(Map.of(
                new BlockPosition(0, 0, 0), new BlockState("minecraft:dirt"),
                new BlockPosition(1, 0, 0), new BlockState("minecraft:glowstone")
        ));

        VerificationResult result = SchematicVerification.verify(
                plan,
                VerificationScope.chunk(0),
                observation
        );

        assertEquals(1, plan.chunk(0).expectedBlocks().size());
        assertEquals(2, plan.buildVolume().blockCount());
        assertEquals(1, result.extraneousCount());
        assertEquals(BlockState.AIR, result.mismatches().getFirst().expected());
        assertFalse(result.clean());
    }

    private static TargetBlock target(
            int x,
            int y,
            int z,
            String id,
            Map<String, String> properties
    ) {
        return new TargetBlock(new BlockPosition(x, y, z), new BlockState(id, properties));
    }

    private static final class FakeObservation implements BlockObservation {
        private final Map<BlockPosition, BlockState> states = new HashMap<>();
        private boolean loaded = true;
        private List<BlockPosition> scaffolding = List.of();

        private FakeObservation(Map<BlockPosition, BlockState> states) {
            this.states.putAll(states);
        }

        @Override
        public boolean isChunkLoaded(ChunkCoordinate chunk) {
            return loaded;
        }

        @Override
        public BlockState blockState(BlockPosition position) {
            return states.getOrDefault(position, BlockState.AIR);
        }

        @Override
        public List<BlockPosition> temporaryScaffolding(VerificationScope scope) {
            return scaffolding;
        }
    }
}
