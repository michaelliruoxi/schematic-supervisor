package io.github.schematicsupervisor.fabric;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Forced atomic repair intent; an uncertain command survives executor resets and process restarts. */
final class HoeRepairFileStore implements HoeRepairSession.Store {
    private static final Gson GSON = MaterialGson.register(new GsonBuilder()).serializeNulls().create();
    private static final int MAX_BYTES = 4096;
    private final Path path;

    HoeRepairFileStore(Path path) { this.path = Objects.requireNonNull(path).toAbsolutePath().normalize(); }

    @Override
    public synchronized Optional<HoeRepairSession.Journal> load() throws IOException {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) { return Optional.empty(); }
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) > MAX_BYTES) {
            throw new IOException("Repair journal must be a bounded regular file");
        }
        try (var input = Files.newInputStream(path)) {
            byte[] bytes = input.readNBytes(MAX_BYTES + 1);
            if (bytes.length > MAX_BYTES) { throw new IOException("Repair journal exceeds its limit"); }
            var root = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
            if (!root.keySet().equals(Set.of("version", "journal")) || !"1".equals(root.get("version").toString())) {
                throw new IllegalArgumentException("Unsupported repair journal");
            }
            var value = GSON.fromJson(root.get("journal"), HoeRepairSession.Journal.class);
            if (value == null || !GSON.toJsonTree(value).equals(root.get("journal"))) {
                throw new IllegalArgumentException("Incomplete repair journal");
            }
            return Optional.of(value);
        } catch (RuntimeException invalid) { throw new IOException("Invalid repair journal; preserve it without repeating /fix", invalid); }
    }

    @Override
    public synchronized void replace(Optional<HoeRepairSession.Journal> expected, HoeRepairSession.Journal next) throws IOException {
        Path parent = path.getParent();
        Files.createDirectories(parent);
        try (FileChannel channel = FileChannel.open(path.resolveSibling(path.getFileName() + ".lock"),
                StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
                var lock = channel.tryLock()) {
            if (lock == null || !load().equals(expected)) { throw new IOException("Repair journal changed or is locked"); }
            if (expected.filter(value -> !value.confirmed() && !value.confirm().equals(next)).isPresent()) {
                throw new IOException("A pending repair may only become its exact confirmed receipt");
            }
            byte[] bytes = GSON.toJson(new Record(1, next)).getBytes(StandardCharsets.UTF_8);
            if (bytes.length > MAX_BYTES) { throw new IOException("Repair journal exceeds its limit"); }
            Path temporary = Files.createTempFile(parent, "hoe-repair-", ".tmp");
            try {
                try (FileChannel output = FileChannel.open(temporary, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                    ByteBuffer buffer = ByteBuffer.wrap(bytes);
                    while (buffer.hasRemaining()) { output.write(buffer); }
                    output.force(true);
                }
                Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } finally { Files.deleteIfExists(temporary); }
        } catch (OverlappingFileLockException busy) { throw new IOException("Repair journal is locked", busy); }
    }

    private record Record(int version, HoeRepairSession.Journal journal) { }
}
