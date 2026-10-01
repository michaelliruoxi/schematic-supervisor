package io.github.schematicsupervisor.core;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Objects;
import java.util.Optional;

public final class JsonFileCheckpointStore implements SupervisorPorts.Checkpoints {
    private final Path checkpointPath;
    private final AtomicReplacement replacement;

    @FunctionalInterface
    interface AtomicReplacement {
        void replace(Path source, Path destination) throws IOException;
    }

    public JsonFileCheckpointStore(Path checkpointPath) {
        this(checkpointPath, (source, destination) -> Files.move(source, destination,
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING));
    }

    JsonFileCheckpointStore(Path checkpointPath, AtomicReplacement replacement) {
        this.checkpointPath = Objects.requireNonNull(checkpointPath, "checkpointPath")
                .toAbsolutePath()
                .normalize();
        this.replacement = Objects.requireNonNull(replacement, "replacement");
    }

    @Override
    public Optional<SupervisorCheckpoint> load() {
        if (!Files.exists(checkpointPath)) {
            return Optional.empty();
        }
        try {
            return Optional.of(CheckpointJsonCodec.fromJson(
                    Files.readString(checkpointPath, StandardCharsets.UTF_8)
            ));
        } catch (IOException exception) {
            throw new UncheckedIOException("unable to read checkpoint " + checkpointPath, exception);
        }
    }

    @Override
    public void save(SupervisorCheckpoint checkpoint) {
        Path parent = checkpointPath.getParent();
        Path temporary = null;
        try {
            byte[] encoded = CheckpointJsonCodec.toJson(checkpoint).getBytes(StandardCharsets.UTF_8);
            Files.createDirectories(parent);
            temporary = Files.createTempFile(parent, checkpointPath.getFileName() + ".", ".tmp");
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING)) {
                ByteBuffer bytes = ByteBuffer.wrap(encoded);
                while (bytes.hasRemaining()) { channel.write(bytes); }
                channel.force(true);
            }
            replacement.replace(temporary, checkpointPath);
        } catch (IOException exception) {
            throw new UncheckedIOException("unable to save checkpoint " + checkpointPath, exception);
        } finally {
            if (temporary != null) {
                try { Files.deleteIfExists(temporary); }
                catch (IOException ignored) { /* An orphan temporary file is never treated as a checkpoint. */ }
            }
        }
    }

    @Override
    public void clear() {
        try {
            Files.deleteIfExists(checkpointPath);
        } catch (IOException exception) {
            throw new UncheckedIOException("unable to clear checkpoint " + checkpointPath, exception);
        }
    }
}
