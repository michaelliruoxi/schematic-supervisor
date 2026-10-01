package io.github.schematicsupervisor.fabric;

import io.github.schematicsupervisor.core.JsonFileCheckpointStore;
import io.github.schematicsupervisor.core.SupervisorCheckpoint;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;

/** Resolves one build's stores without moving, rewriting, or deleting saved state. */
final class BuildStateRepository {
    private static final long MAXIMUM_STATE_BYTES = 1_048_576;
    static final int MAXIMUM_NAMESPACES = 128;
    private final Path stateDirectory;

    BuildStateRepository(Path stateDirectory) {
        this.stateDirectory = Objects.requireNonNull(stateDirectory, "stateDirectory")
                .toAbsolutePath().normalize();
    }

    Handle resolve(String planId, RunContext context) throws IOException {
        Objects.requireNonNull(planId, "planId");
        Objects.requireNonNull(context, "context");
        if (planId.isBlank()) { throw new IllegalArgumentException("plan id must not be blank"); }
        requireDirectoryIfPresent(stateDirectory);

        Handle legacy = handle(stateDirectory, planId, context, true);
        SavedState savedLegacy = inspect(stateDirectory, true);
        boolean legacyMatches = savedLegacy.checkpoint().isPresent()
                && savedLegacy.checkpoint().orElseThrow().planId().equals(planId)
                && savedLegacy.context().orElseThrow().equals(context);
        if (legacyMatches && unsettled(savedLegacy)) {
            return legacy;
        }

        Path builds = contained(stateDirectory.resolve("builds"));
        requireDirectoryIfPresent(builds);
        Handle selected = legacy;
        if (!legacyMatches) {
            Path directory = contained(builds.resolve(namespace(planId, context)));
            requireDirectoryIfPresent(directory);
            selected = handle(directory, planId, context, false);
            SavedState saved = inspect(directory, false);
            if (saved.context().isPresent() && !saved.context().orElseThrow().equals(context)) {
                throw new IOException("The selected build namespace contains a different world context.");
            }
            if (saved.checkpoint().isPresent() && !saved.checkpoint().orElseThrow().planId().equals(planId)) {
                throw new IOException("The selected build namespace contains a different plan checkpoint.");
            }
            // Recovery must remain possible even if an earlier version left several unsettled builds.
            if (unsettled(saved)) { return selected; }
        }
        if (unsettled(savedLegacy)) {
            throw new IOException("The existing root checkpoint has an unsettled outcome; "
                    + "reconcile its original build before selecting another build.");
        }
        requireOtherBuildsSettled(builds);
        return selected;
    }

    private void requireOtherBuildsSettled(Path builds) throws IOException {
        DirectoryStream<Path> entries;
        try {
            entries = Files.newDirectoryStream(builds);
        } catch (NoSuchFileException absent) {
            return;
        }
        try (entries) {
            int count = 0;
            for (Path entry : entries) {
                if (++count > MAXIMUM_NAMESPACES) {
                    throw new IOException("Build state has more than " + MAXIMUM_NAMESPACES
                            + " namespaces; preserve the saved builds and resolve this limit before switching.");
                }
                Path directory = contained(entry);
                if (!directory.getFileName().toString().matches("[0-9a-f]{64}")) {
                    throw new IOException("Saved build namespace must have its original opaque identity.");
                }
                requireDirectory(directory);
                SavedState saved = inspect(directory, false);
                if (saved.checkpoint().isPresent()) {
                    SupervisorCheckpoint checkpoint = saved.checkpoint().orElseThrow();
                    RunContext context = saved.context().orElseThrow();
                    if (!directory.getFileName().toString().equals(namespace(checkpoint.planId(), context))) {
                        throw new IOException("Saved build namespace does not match its checkpoint and context.");
                    }
                    if (unsettled(saved)) {
                        throw new IOException("Another saved build has an unsettled outcome; "
                                + "reconcile its original build before selecting another build.");
                    }
                }
            }
        } catch (DirectoryIteratorException invalid) {
            throw new IOException("Saved build namespaces could not be read; existing files were preserved.", invalid);
        }
    }

    private static boolean unsettled(SavedState state) {
        return state.checkpoint().filter(checkpoint -> checkpoint.withdrawalInFlight()
                || checkpoint.reconciliationRequired()).isPresent();
    }

    private Handle handle(Path directory, String planId, RunContext context, boolean legacy) throws IOException {
        Path checkpoint = contained(directory.resolve("checkpoint.json"));
        Path contextPath = contained(directory.resolve("run-context.json"));
        return new Handle(planId, context, checkpoint, new JsonFileCheckpointStore(checkpoint),
                new RunContextStore(contextPath), legacy);
    }

    private SavedState inspect(Path directory, boolean requireCompletePair) throws IOException {
        Path checkpointPath = contained(directory.resolve("checkpoint.json"));
        Path contextPath = contained(directory.resolve("run-context.json"));
        boolean checkpointPresent = regularFilePresent(checkpointPath);
        boolean contextPresent = regularFilePresent(contextPath);
        if ((checkpointPresent && !contextPresent)
                || (requireCompletePair && contextPresent && !checkpointPresent)) {
            throw new IOException((requireCompletePair ? "The existing root state" : "The saved build state")
                    + " is incomplete; preserve and reconcile its checkpoint and run context.");
        }
        try {
            Optional<SupervisorCheckpoint> checkpoint = checkpointPresent
                    ? new JsonFileCheckpointStore(checkpointPath).load() : Optional.empty();
            Optional<RunContext> context = contextPresent
                    ? new RunContextStore(contextPath).load() : Optional.empty();
            if ((checkpointPresent && checkpoint.isEmpty()) || (contextPresent && context.isEmpty())) {
                throw new IOException("Saved build state changed while its identity was being read.");
            }
            return new SavedState(checkpoint, context);
        } catch (RuntimeException invalid) {
            throw new IOException("Saved build state could not be validated; existing files were preserved.", invalid);
        }
    }

    private Path contained(Path path) throws IOException {
        Path normalized = path.toAbsolutePath().normalize();
        if (normalized.equals(stateDirectory) || !normalized.startsWith(stateDirectory)) {
            throw new IOException("Build state path must remain inside the configured state directory.");
        }
        return normalized;
    }

    private static boolean regularFilePresent(Path path) throws IOException {
        BasicFileAttributes attributes;
        try {
            attributes = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        } catch (NoSuchFileException absent) {
            return false;
        }
        if (!attributes.isRegularFile() || attributes.isSymbolicLink() || attributes.isOther()
                || attributes.size() > MAXIMUM_STATE_BYTES) {
            throw new IOException("Saved build state must be a bounded regular file: " + path.getFileName());
        }
        return true;
    }

    private static void requireDirectoryIfPresent(Path path) throws IOException {
        try {
            requireDirectory(path);
        } catch (NoSuchFileException absent) {
            // Resolution is read-only; the stores create the directory when the caller saves.
        }
    }

    private static void requireDirectory(Path path) throws IOException {
        BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class,
                LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isDirectory() || attributes.isSymbolicLink() || attributes.isOther()) {
            throw new IOException("Build state namespace must be a directory without a symbolic link or reparse entry.");
        }
    }

    private static String namespace(String planId, RunContext context) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String field : new String[] {"build-state-v1", context.worldIdentityHash(),
                    context.dimension(), planId}) {
                byte[] bytes = field.getBytes(StandardCharsets.UTF_8);
                digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
                digest.update(bytes);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 is unavailable", unavailable);
        }
    }

    record Handle(String planId, RunContext context, Path checkpointPath,
                  JsonFileCheckpointStore checkpointStore, RunContextStore contextStore, boolean legacy) {
        Handle {
            Objects.requireNonNull(planId, "planId");
            Objects.requireNonNull(context, "context");
            Objects.requireNonNull(checkpointPath, "checkpointPath");
            Objects.requireNonNull(checkpointStore, "checkpointStore");
            Objects.requireNonNull(contextStore, "contextStore");
        }
    }

    private record SavedState(Optional<SupervisorCheckpoint> checkpoint, Optional<RunContext> context) { }
}
