package io.github.schematicsupervisor.fabric;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.schematicsupervisor.core.BuildPhase;
import io.github.schematicsupervisor.core.JsonFileCheckpointStore;
import io.github.schematicsupervisor.core.LayerBuildSchedule;
import io.github.schematicsupervisor.core.MaterialQuantities;
import io.github.schematicsupervisor.core.RecoveryStage;
import io.github.schematicsupervisor.core.SupervisorCheckpoint;
import io.github.schematicsupervisor.core.SupervisorState;
import io.github.schematicsupervisor.core.VerificationStage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class BuildStateRepositoryTest {
    private static final RunContext WORLD = new RunContext("sha256:" + "ab".repeat(32), "minecraft:overworld");
    @TempDir Path directory;

    @Test
    void newResolutionCreatesNoFilesAndUsesAStableOpaqueContainedNamespace() throws IOException {
        BuildStateRepository repository = new BuildStateRepository(directory);
        BuildStateRepository.Handle selected = repository.resolve("../farm/name", WORLD);
        assertFalse(selected.legacy());
        assertEquals(selected.checkpointPath(), repository.resolve("../farm/name", WORLD).checkpointPath());
        Path relative = directory.relativize(selected.checkpointPath());
        assertEquals(3, relative.getNameCount());
        assertEquals("builds", relative.getName(0).toString());
        assertTrue(relative.getName(1).toString().matches("[0-9a-f]{64}"));
        assertEquals("checkpoint.json", relative.getName(2).toString());
        assertFalse(Files.exists(directory.resolve("builds")));
        assertTrue(selected.checkpointStore().load().isEmpty());
        assertTrue(selected.contextStore().load().isEmpty());
    }

    @Test
    void planWorldAndDimensionAllSeparateState() throws IOException {
        BuildStateRepository repository = new BuildStateRepository(directory);
        Path first = repository.resolve("farm", WORLD).checkpointPath();
        assertNotEquals(first, repository.resolve("house", WORLD).checkpointPath());
        assertNotEquals(first, repository.resolve("farm", new RunContext("sha256:" + "cd".repeat(32),
                WORLD.dimension())).checkpointPath());
        assertNotEquals(first, repository.resolve("farm", new RunContext(WORLD.worldIdentityHash(),
                "minecraft:the_nether")).checkpointPath());
        assertNotEquals(repository.resolve("c", new RunContext(WORLD.worldIdentityHash(), "a\u0000b"))
                        .checkpointPath(),
                repository.resolve("b\u0000c", new RunContext(WORLD.worldIdentityHash(), "a")).checkpointPath());
    }

    @Test
    void matchingLegacyPairIsReusedWithoutChangingAnyByte() throws IOException {
        saveLegacy(checkpoint("farm", false, false));
        byte[] checkpointBytes = Files.readAllBytes(directory.resolve("checkpoint.json"));
        byte[] contextBytes = Files.readAllBytes(directory.resolve("run-context.json"));
        BuildStateRepository.Handle selected = new BuildStateRepository(directory).resolve("farm", WORLD);
        assertTrue(selected.legacy());
        assertEquals(directory.resolve("checkpoint.json"), selected.checkpointPath());
        assertEquals("farm", selected.checkpointStore().load().orElseThrow().planId());
        assertArrayEquals(checkpointBytes, Files.readAllBytes(directory.resolve("checkpoint.json")));
        assertArrayEquals(contextBytes, Files.readAllBytes(directory.resolve("run-context.json")));
        assertFalse(Files.exists(directory.resolve("builds")));
    }

    @Test
    void unrelatedSettledLegacyPairIsPreservedWhenAnotherBuildIsSelectedAndCleared() throws IOException {
        saveLegacy(checkpoint("farm", false, false));
        byte[] checkpointBytes = Files.readAllBytes(directory.resolve("checkpoint.json"));
        byte[] contextBytes = Files.readAllBytes(directory.resolve("run-context.json"));
        BuildStateRepository repository = new BuildStateRepository(directory);
        BuildStateRepository.Handle other = repository.resolve("house", WORLD);
        assertFalse(other.legacy());
        other.contextStore().save(WORLD);
        other.checkpointStore().save(checkpoint("house", false, false));
        assertEquals("house", repository.resolve("house", WORLD).checkpointStore().load().orElseThrow().planId());
        other.checkpointStore().clear();
        other.contextStore().clear();
        assertArrayEquals(checkpointBytes, Files.readAllBytes(directory.resolve("checkpoint.json")));
        assertArrayEquals(contextBytes, Files.readAllBytes(directory.resolve("run-context.json")));
        assertTrue(repository.resolve("farm", WORLD).legacy());
    }

    @Test
    void eitherUnsettledLegacyFlagBlocksSwitchingButKeepsOriginalBuildLoadable() throws IOException {
        for (int mode = 0; mode < 2; mode++) {
            saveLegacy(checkpoint("farm", mode == 0, mode == 1));
            BuildStateRepository repository = new BuildStateRepository(directory);
            byte[] saved = Files.readAllBytes(directory.resolve("checkpoint.json"));
            assertTrue(repository.resolve("farm", WORLD).legacy());
            assertThrows(IOException.class, () -> repository.resolve("house", WORLD));
            assertThrows(IOException.class, () -> repository.resolve("farm",
                    new RunContext(WORLD.worldIdentityHash(), "minecraft:the_nether")));
            assertArrayEquals(saved, Files.readAllBytes(directory.resolve("checkpoint.json")));
            assertFalse(Files.exists(directory.resolve("builds")));
        }
    }

    @Test
    void eitherUnsettledNamespacedFlagBlocksSwitchingAfterRestartAndPreservesOriginal() throws IOException {
        for (int mode = 0; mode < 2; mode++) {
            Path profile = directory.resolve("mode-" + mode);
            BuildStateRepository repository = new BuildStateRepository(profile);
            BuildStateRepository.Handle original = repository.resolve("farm", WORLD);
            original.contextStore().save(WORLD);
            original.checkpointStore().save(checkpoint("farm", mode == 0, mode == 1));
            byte[] checkpointBytes = Files.readAllBytes(original.checkpointPath());
            Path contextPath = original.checkpointPath().resolveSibling("run-context.json");
            byte[] contextBytes = Files.readAllBytes(contextPath);

            BuildStateRepository restarted = new BuildStateRepository(profile);
            assertThrows(IOException.class, () -> restarted.resolve("house", WORLD));
            assertThrows(IOException.class, () -> restarted.resolve("farm",
                    new RunContext(WORLD.worldIdentityHash(), "minecraft:the_nether")));
            assertEquals(original.checkpointPath(), restarted.resolve("farm", WORLD).checkpointPath());
            assertArrayEquals(checkpointBytes, Files.readAllBytes(original.checkpointPath()));
            assertArrayEquals(contextBytes, Files.readAllBytes(contextPath));
        }
    }

    @Test
    void matchingSettledLegacyBuildCannotBypassUnsettledNamespace() throws IOException {
        saveLegacy(checkpoint("legacy", false, false));
        byte[] legacyBytes = Files.readAllBytes(directory.resolve("checkpoint.json"));
        BuildStateRepository.Handle original = new BuildStateRepository(directory).resolve("farm", WORLD);
        original.contextStore().save(WORLD);
        original.checkpointStore().save(checkpoint("farm", false, true));
        byte[] originalBytes = Files.readAllBytes(original.checkpointPath());

        BuildStateRepository restarted = new BuildStateRepository(directory);
        assertThrows(IOException.class, () -> restarted.resolve("legacy", WORLD));
        assertEquals(original.checkpointPath(), restarted.resolve("farm", WORLD).checkpointPath());
        assertArrayEquals(originalBytes, Files.readAllBytes(original.checkpointPath()));
        assertArrayEquals(legacyBytes, Files.readAllBytes(directory.resolve("checkpoint.json")));
    }

    @Test
    void severalExistingUnsettledBuildsRemainIndividuallyLoadableForRecovery() throws IOException {
        BuildStateRepository repository = new BuildStateRepository(directory);
        BuildStateRepository.Handle first = repository.resolve("first", WORLD);
        BuildStateRepository.Handle second = repository.resolve("second", WORLD);
        first.contextStore().save(WORLD);
        first.checkpointStore().save(checkpoint("first", true, false));
        second.contextStore().save(WORLD);
        second.checkpointStore().save(checkpoint("second", false, true));
        byte[] firstBytes = Files.readAllBytes(first.checkpointPath());
        byte[] secondBytes = Files.readAllBytes(second.checkpointPath());

        BuildStateRepository restarted = new BuildStateRepository(directory);
        assertEquals(first.checkpointPath(), restarted.resolve("first", WORLD).checkpointPath());
        assertEquals(second.checkpointPath(), restarted.resolve("second", WORLD).checkpointPath());
        assertThrows(IOException.class, () -> restarted.resolve("third", WORLD));
        // A legacy unresolved build left by an older version must not deadlock either recovery path.
        saveLegacy(checkpoint("legacy", true, true));
        assertTrue(restarted.resolve("legacy", WORLD).legacy());
        assertEquals(first.checkpointPath(), restarted.resolve("first", WORLD).checkpointPath());
        assertEquals(second.checkpointPath(), restarted.resolve("second", WORLD).checkpointPath());
        assertThrows(IOException.class, () -> restarted.resolve("third", WORLD));
        assertArrayEquals(firstBytes, Files.readAllBytes(first.checkpointPath()));
        assertArrayEquals(secondBytes, Files.readAllBytes(second.checkpointPath()));
    }

    @Test
    void settledOtherBuildsContextOnlyAndEmptyTeardownNamespacesPermitSwitching() throws IOException {
        BuildStateRepository repository = new BuildStateRepository(directory);
        BuildStateRepository.Handle settled = repository.resolve("settled", WORLD);
        settled.contextStore().save(WORLD);
        settled.checkpointStore().save(checkpoint("settled", false, false));
        byte[] settledBytes = Files.readAllBytes(settled.checkpointPath());
        BuildStateRepository.Handle contextOnly = repository.resolve("context-only", WORLD);
        contextOnly.contextStore().save(WORLD);
        byte[] contextBytes = Files.readAllBytes(contextOnly.checkpointPath().resolveSibling("run-context.json"));
        BuildStateRepository.Handle emptied = repository.resolve("empty", WORLD);
        Files.createDirectories(emptied.checkpointPath().getParent());

        BuildStateRepository restarted = new BuildStateRepository(directory);
        assertTrue(restarted.resolve("fresh", WORLD).checkpointStore().load().isEmpty());
        saveLegacy(checkpoint("legacy", false, false));
        assertTrue(restarted.resolve("legacy", WORLD).legacy());
        assertArrayEquals(settledBytes, Files.readAllBytes(settled.checkpointPath()));
        assertArrayEquals(contextBytes,
                Files.readAllBytes(contextOnly.checkpointPath().resolveSibling("run-context.json")));
    }

    @Test
    void malformedOrIncompleteOtherNamespaceCannotBecomeAFreshBuild() throws IOException {
        for (int mode = 0; mode < 3; mode++) {
            Path profile = directory.resolve("mode-" + mode);
            BuildStateRepository repository = new BuildStateRepository(profile);
            BuildStateRepository.Handle other = repository.resolve("other", WORLD);
            other.contextStore().save(WORLD);
            other.checkpointStore().save(checkpoint("other", false, false));
            Path contextPath = other.checkpointPath().resolveSibling("run-context.json");
            if (mode == 0) { Files.writeString(other.checkpointPath(), "{broken checkpoint"); }
            if (mode == 1) { Files.delete(contextPath); }
            if (mode == 2) { Files.writeString(contextPath, "{broken context"); }
            byte[] checkpointBytes = Files.readAllBytes(other.checkpointPath());
            byte[] contextBytes = Files.exists(contextPath) ? Files.readAllBytes(contextPath) : null;

            BuildStateRepository restarted = new BuildStateRepository(profile);
            assertThrows(IOException.class, () -> restarted.resolve("fresh", WORLD));
            assertArrayEquals(checkpointBytes, Files.readAllBytes(other.checkpointPath()));
            if (contextBytes == null) { assertFalse(Files.exists(contextPath)); }
            else { assertArrayEquals(contextBytes, Files.readAllBytes(contextPath)); }
        }
    }

    @Test
    void otherNamespaceMustMatchItsSavedPlanAndContext() throws IOException {
        BuildStateRepository repository = new BuildStateRepository(directory);
        BuildStateRepository.Handle other = repository.resolve("other", WORLD);
        other.contextStore().save(WORLD);
        other.checkpointStore().save(checkpoint("different-plan", false, false));
        byte[] saved = Files.readAllBytes(other.checkpointPath());
        assertThrows(IOException.class, () -> repository.resolve("fresh", WORLD));
        assertArrayEquals(saved, Files.readAllBytes(other.checkpointPath()));

        other.checkpointStore().save(checkpoint("other", false, false));
        other.contextStore().save(new RunContext(WORLD.worldIdentityHash(), "minecraft:the_nether"));
        assertThrows(IOException.class, () -> repository.resolve("fresh", WORLD));
    }

    @Test
    void namespaceEnumerationIsCappedIncludingEmptyDirectories() throws IOException {
        Path builds = directory.resolve("builds");
        for (int index = 0; index < BuildStateRepository.MAXIMUM_NAMESPACES; index++) {
            Files.createDirectories(builds.resolve(String.format("%064x", index)));
        }
        BuildStateRepository repository = new BuildStateRepository(directory);
        assertTrue(repository.resolve("fresh", WORLD).checkpointStore().load().isEmpty());
        Files.createDirectory(builds.resolve("f".repeat(64)));
        IOException failure = assertThrows(IOException.class, () -> repository.resolve("fresh", WORLD));
        assertTrue(failure.getMessage().contains("more than " + BuildStateRepository.MAXIMUM_NAMESPACES));
        try (var entries = Files.list(builds)) {
            assertEquals(BuildStateRepository.MAXIMUM_NAMESPACES + 1L, entries.count());
        }
    }

    @Test
    void namespaceEntriesMustBeOpaqueDirectoriesAndStateFilesMustBeBounded() throws IOException {
        Path builds = directory.resolve("builds");
        Files.createDirectories(builds);
        Path invalidName = Files.createDirectory(builds.resolve("unexpected-directory"));
        BuildStateRepository repository = new BuildStateRepository(directory);
        assertThrows(IOException.class, () -> repository.resolve("fresh", WORLD));
        Files.delete(invalidName);
        Path notDirectory = builds.resolve("f".repeat(64));
        Files.writeString(notDirectory, "not a directory");
        assertThrows(IOException.class, () -> repository.resolve("fresh", WORLD));
        assertEquals("not a directory", Files.readString(notDirectory));
        Files.delete(notDirectory);

        BuildStateRepository.Handle other = repository.resolve("other", WORLD);
        other.contextStore().save(WORLD);
        Files.write(other.checkpointPath(), new byte[1_048_577]);
        assertThrows(IOException.class, () -> repository.resolve("fresh", WORLD));
        assertEquals(1_048_577L, Files.size(other.checkpointPath()));
    }

    @Test
    void partialLegacyStateCannotBeHiddenBySelectingAnotherBuild() throws IOException {
        JsonFileCheckpointStore checkpoints = new JsonFileCheckpointStore(directory.resolve("checkpoint.json"));
        checkpoints.save(checkpoint("farm", false, false));
        BuildStateRepository repository = new BuildStateRepository(directory);
        assertThrows(IOException.class, () -> repository.resolve("house", WORLD));
        checkpoints.clear();
        new RunContextStore(directory.resolve("run-context.json")).save(WORLD);
        assertThrows(IOException.class, () -> repository.resolve("house", WORLD));
        assertFalse(Files.exists(directory.resolve("builds")));
    }

    @Test
    void malformedLegacyCheckpointAndContextArePreservedAndBlockResolution() throws IOException {
        saveLegacy(checkpoint("farm", false, false));
        Path checkpointPath = directory.resolve("checkpoint.json");
        Files.writeString(checkpointPath, "{broken checkpoint");
        BuildStateRepository repository = new BuildStateRepository(directory);
        assertThrows(IOException.class, () -> repository.resolve("house", WORLD));
        assertEquals("{broken checkpoint", Files.readString(checkpointPath));
        saveLegacy(checkpoint("farm", false, false));
        Path contextPath = directory.resolve("run-context.json");
        Files.writeString(contextPath, "{broken context");
        assertThrows(IOException.class, () -> repository.resolve("house", WORLD));
        assertEquals("{broken context", Files.readString(contextPath));
        assertFalse(Files.exists(directory.resolve("builds")));
    }

    @Test
    void namespacedStoresRejectWrongIdentityAndMissingContext() throws IOException {
        BuildStateRepository repository = new BuildStateRepository(directory);
        BuildStateRepository.Handle selected = repository.resolve("farm", WORLD);
        selected.checkpointStore().save(checkpoint("farm", false, false));
        assertThrows(IOException.class, () -> repository.resolve("farm", WORLD));
        selected.contextStore().save(new RunContext(WORLD.worldIdentityHash(), "minecraft:the_nether"));
        assertThrows(IOException.class, () -> repository.resolve("farm", WORLD));
        selected.contextStore().save(WORLD);
        selected.checkpointStore().save(checkpoint("another-plan", false, false));
        assertThrows(IOException.class, () -> repository.resolve("farm", WORLD));
        assertEquals("another-plan", selected.checkpointStore().load().orElseThrow().planId());
    }

    @Test
    void namespacedContextMayBeBoundBeforeFirstCheckpointAndUnsettledMatchingStateRemainsLoadable()
            throws IOException {
        BuildStateRepository repository = new BuildStateRepository(directory);
        BuildStateRepository.Handle selected = repository.resolve("farm", WORLD);
        selected.contextStore().save(WORLD);
        assertEquals(selected.checkpointPath(), repository.resolve("farm", WORLD).checkpointPath());
        selected.checkpointStore().save(checkpoint("farm", true, true));
        SupervisorCheckpoint restored = repository.resolve("farm", WORLD).checkpointStore().load().orElseThrow();
        assertTrue(restored.withdrawalInFlight());
        assertTrue(restored.reconciliationRequired());
    }

    @Test
    void invalidFilesystemEntriesDoNotFallBackToFreshState() throws IOException {
        Files.createDirectory(directory.resolve("checkpoint.json"));
        BuildStateRepository repository = new BuildStateRepository(directory);
        assertThrows(IOException.class, () -> repository.resolve("farm", WORLD));
        Files.delete(directory.resolve("checkpoint.json"));
        Files.writeString(directory.resolve("builds"), "occupied namespace root");
        assertThrows(IOException.class, () -> repository.resolve("farm", WORLD));
        assertEquals("occupied namespace root", Files.readString(directory.resolve("builds")));
    }

    @Test
    void resetRetryRemainsBoundToItsOriginalHandleAfterAnotherBuildIsResolved() throws IOException {
        BuildStateRepository repository = new BuildStateRepository(directory);
        BuildStateRepository.Handle first = repository.resolve("first", WORLD);
        first.contextStore().save(WORLD);
        first.checkpointStore().save(checkpoint("first", false, false));
        java.util.concurrent.atomic.AtomicBoolean failOnce = new java.util.concurrent.atomic.AtomicBoolean(true);
        ResetTeardownCoordinator reset = new ResetTeardownCoordinator(first.checkpointStore()::clear, () -> {
            if (failOnce.getAndSet(false)) { throw new IOException("context temporarily locked"); }
            first.contextStore().clear();
        });
        reset.begin();
        assertFalse(reset.clearStores().complete());

        BuildStateRepository.Handle second = repository.resolve("second", WORLD);
        second.contextStore().save(WORLD);
        second.checkpointStore().save(checkpoint("second", false, false));
        byte[] secondCheckpoint = Files.readAllBytes(second.checkpointPath());
        assertTrue(reset.clearStores().complete());
        assertFalse(Files.exists(first.checkpointPath()));
        assertTrue(first.contextStore().load().isEmpty());
        assertArrayEquals(secondCheckpoint, Files.readAllBytes(second.checkpointPath()));
        assertEquals(WORLD, second.contextStore().load().orElseThrow());
    }

    @Test
    void failedCheckpointDeletionBlocksSwitchingUntilOriginalCleanupFinishes() throws IOException {
        BuildStateRepository repository = new BuildStateRepository(directory);
        BuildStateRepository.Handle first = repository.resolve("first", WORLD);
        first.contextStore().save(WORLD);
        first.checkpointStore().save(checkpoint("first", false, false));
        byte[] checkpointBytes = Files.readAllBytes(first.checkpointPath());
        java.util.concurrent.atomic.AtomicBoolean failOnce = new java.util.concurrent.atomic.AtomicBoolean(true);
        ResetTeardownCoordinator reset = new ResetTeardownCoordinator(() -> {
            if (failOnce.getAndSet(false)) { throw new IOException("checkpoint temporarily locked"); }
            first.checkpointStore().clear();
        }, first.contextStore()::clear);
        reset.begin();
        assertFalse(reset.clearStores().complete());
        assertThrows(IOException.class, () -> new BuildStateRepository(directory).resolve("second", WORLD));
        assertArrayEquals(checkpointBytes, Files.readAllBytes(first.checkpointPath()));
        assertTrue(reset.clearStores().complete());
        assertTrue(repository.resolve("second", WORLD).checkpointStore().load().isEmpty());
    }

    private void saveLegacy(SupervisorCheckpoint checkpoint) throws IOException {
        new JsonFileCheckpointStore(directory.resolve("checkpoint.json")).save(checkpoint);
        new RunContextStore(directory.resolve("run-context.json")).save(WORLD);
    }

    private static SupervisorCheckpoint checkpoint(String plan, boolean inFlight, boolean reconciliation) {
        MaterialQuantities empty = MaterialQuantities.empty();
        return new SupervisorCheckpoint(SupervisorCheckpoint.CURRENT_VERSION, plan, SupervisorState.PAUSED,
                SupervisorState.RESTOCKING, SupervisorState.BUILDING, 0, BuildPhase.ORDINARY_BLOCKS,
                VerificationStage.CHUNK, RecoveryStage.NONE, 0, "", empty, empty, empty, empty,
                "", 0, false, false, false, inFlight, reconciliation,
                reconciliation ? "Unsettled depot transfer." : "", LayerBuildSchedule.ID, 0, -1);
    }
}
