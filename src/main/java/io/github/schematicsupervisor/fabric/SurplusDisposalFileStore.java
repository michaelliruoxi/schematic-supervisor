package io.github.schematicsupervisor.fabric;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Forced atomic compare-and-replace; no non-atomic fallback and no unresolved-intent overwrite. */
final class SurplusDisposalFileStore implements SurplusDisposalController.Store {
    static final int MAXIMUM_BYTES = 262_144;
    private static final Gson GSON = MaterialGson.register(new GsonBuilder()).serializeNulls().create();
    private final Path path;
    private final AtomicReplacement replacement;
    @FunctionalInterface interface AtomicReplacement { void move(Path from, Path to) throws IOException; }

    SurplusDisposalFileStore(Path path) {
        this(path, (from, to) -> Files.move(from, to, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING));
    }
    SurplusDisposalFileStore(Path path, AtomicReplacement replacement) {
        this.path = Objects.requireNonNull(path).toAbsolutePath().normalize();
        this.replacement = Objects.requireNonNull(replacement);
    }
    @Override public synchronized Optional<SurplusDisposalJournal> load() throws IOException {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) { return Optional.empty(); }
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) > MAXIMUM_BYTES) {
            throw new IOException("Disposal journal must be a bounded regular file");
        }
        try (var input = Files.newInputStream(path)) {
            byte[] bytes = input.readNBytes(MAXIMUM_BYTES + 1);
            if (bytes.length > MAXIMUM_BYTES) { throw new IOException("Disposal journal exceeds its bound"); }
            String json = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            var root = JsonParser.parseString(json).getAsJsonObject();
            if (!root.keySet().equals(Set.of("version", "journal"))
                    || !Set.of("1", "2", "3", "4", "5").contains(root.get("version").toString())) {
                throw new IllegalArgumentException("Unsupported disposal journal version or fields");
            }
            if ("1".equals(root.get("version").toString())) {
                var storage = root.getAsJsonObject("journal").getAsJsonObject("storage");
                if (storage.has("mode")) { throw new IllegalArgumentException("Legacy storage proof cannot specify a disposal mode"); }
                storage.addProperty("mode", SurplusDisposalAuthorization.Mode.STORAGE_FULL.name());
            }
            var encodedJournal = root.getAsJsonObject("journal");
            boolean inventoryRecovery = "5".equals(root.get("version").toString());
            if (!inventoryRecovery) {
                if (encodedJournal.has("inventoryRecovery")) { throw new IllegalArgumentException("Legacy journal cannot authorize inventory recovery"); }
                encodedJournal.add("inventoryRecovery", com.google.gson.JsonNull.INSTANCE);
            } else if (!encodedJournal.has("inventoryRecovery") || encodedJournal.get("inventoryRecovery").isJsonNull()) {
                throw new IllegalArgumentException("Inventory recovery version requires explicit evidence");
            }
            if (!"3".equals(root.get("version").toString())) {
                if (encodedJournal.has("operatorRecovery")) { throw new IllegalArgumentException("Legacy journal cannot authorize operator recovery"); }
                encodedJournal.add("operatorRecovery", com.google.gson.JsonNull.INSTANCE);
            } else if (!encodedJournal.has("operatorRecovery") || encodedJournal.get("operatorRecovery").isJsonNull()) {
                throw new IllegalArgumentException("Operator recovery version requires explicit evidence");
            }
            SurplusDisposalJournal journal = GSON.fromJson(root.get("journal"), SurplusDisposalJournal.class);
            if (journal == null || !GSON.toJsonTree(journal).equals(root.get("journal"))) {
                throw new IllegalArgumentException("Incomplete disposal journal");
            }
            if (("4".equals(root.get("version").toString()) || inventoryRecovery)
                    != (journal.storage().mode() == SurplusDisposalAuthorization.Mode.IN_PLACE_SHOP)) {
                throw new IllegalArgumentException("In-place disposal requires its distinct journal version");
            }
            return Optional.of(journal);
        } catch (RuntimeException invalid) { throw new IOException("Invalid disposal journal; preserve without another THROW", invalid); }
    }
    @Override public synchronized void replace(Optional<SurplusDisposalJournal> expected,
                                                SurplusDisposalJournal next) throws IOException {
        Path parent = path.getParent();
        if (parent == null) { throw new IOException("Disposal journal requires a parent directory"); }
        Files.createDirectories(parent);
        try (FileChannel channel = FileChannel.open(path.resolveSibling(path.getFileName() + ".lock"),
                StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
             var lock = channel.tryLock()) {
            if (lock == null || !load().equals(expected)) { throw new IOException("Disposal journal changed or is locked"); }
            if (expected.isPresent()) {
                var prior = expected.orElseThrow();
                if (prior.stage() == SurplusDisposalJournal.Stage.PENDING && !prior.operationId().equals(next.operationId())
                        || prior.operationId().equals(next.operationId())
                        && (!prior.playerUuid().equals(next.playerUuid()) || !prior.before().equals(next.before()) || prior.sourceMainIndex() != next.sourceMainIndex()
                        || !Objects.equals(prior.site(), next.site()) || !prior.storage().equals(next.storage())
                        || prior.stage() != SurplusDisposalJournal.Stage.PENDING && !prior.equals(next))) {
                    throw new IOException("Unresolved or confirmed disposal evidence cannot be replaced");
                }
            }
            if (expected.filter(prior -> prior.stage() == SurplusDisposalJournal.Stage.INVENTORY_RECONCILED
                    && !prior.operationId().equals(next.operationId())).isPresent()) {
                archiveRecoveredIntent(expected.orElseThrow());
            }
            int version = next.inventoryRecovery() != null ? 5 : next.storage().mode() == SurplusDisposalAuthorization.Mode.IN_PLACE_SHOP ? 4
                    : next.operatorRecovery() == null ? 2 : 3;
            var encoded = GSON.toJsonTree(new FileRecord(version, next)).getAsJsonObject();
            if (next.operatorRecovery() == null) { encoded.getAsJsonObject("journal").remove("operatorRecovery"); }
            if (next.inventoryRecovery() == null) { encoded.getAsJsonObject("journal").remove("inventoryRecovery"); }
            byte[] bytes = GSON.toJson(encoded).getBytes(StandardCharsets.UTF_8);
            if (bytes.length > MAXIMUM_BYTES) { throw new IOException("Disposal journal exceeds its bound"); }
            Path temporary = Files.createTempFile(parent, "surplus-disposal-", ".tmp");
            try {
                try (FileChannel output = FileChannel.open(temporary, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                    ByteBuffer buffer = ByteBuffer.wrap(bytes);
                    while (buffer.hasRemaining()) { output.write(buffer); }
                    output.force(true);
                }
                replacement.move(temporary, path);
            } finally {
                try { Files.deleteIfExists(temporary); }
                catch (IOException ignored) { /* Orphan temporary files never authorize input. */ }
            }
        } catch (OverlappingFileLockException busy) { throw new IOException("Disposal journal is locked", busy); }
    }
    private void archiveRecoveredIntent(SurplusDisposalJournal prior) throws IOException {
        Path directory = path.resolveSibling("surplus-disposal-history");
        Files.createDirectories(directory);
        Path archive = directory.resolve(prior.operationId() + ".json");
        byte[] original = Files.readAllBytes(path);
        if (Files.exists(archive, LinkOption.NOFOLLOW_LINKS)) {
            if (!Files.isRegularFile(archive, LinkOption.NOFOLLOW_LINKS) || Files.size(archive) > MAXIMUM_BYTES
                    || !java.util.Arrays.equals(original, Files.readAllBytes(archive))) {
                throw new IOException("Recovered intent archive differs; preserve original journal");
            }
        } else {
            try (FileChannel output = FileChannel.open(archive, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                ByteBuffer buffer = ByteBuffer.wrap(original);
                while (buffer.hasRemaining()) { output.write(buffer); }
                output.force(true);
            }
        }
    }
    private record FileRecord(int version, SurplusDisposalJournal journal) { }
}
