package io.github.schematicsupervisor.fabric;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParser;
import io.github.schematicsupervisor.core.PlannedConsumptionCredit;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Objects;
import java.util.Optional;

/** Persists each intent before an interaction and each acknowledgement before advancing. */
final class TemporarySupportStore {
    private static final int VERSION = 2;
    private static final int MAXIMUM_BYTES = 32_768;
    private static final Gson GSON = MaterialGson.register(new GsonBuilder()).setPrettyPrinting().create();
    private final Path path;

    TemporarySupportStore(Path path) {
        this.path = Objects.requireNonNull(path, "path").toAbsolutePath().normalize();
    }

    synchronized Optional<TemporarySupportJournal> load() throws IOException {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) { return Optional.empty(); }
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                || Files.size(path) > MAXIMUM_BYTES) {
            throw new IOException("support journal must be a bounded regular file");
        }
        try {
            var parsed = JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8)).getAsJsonObject();
            if (!parsed.has("version") || parsed.get("version").getAsInt() != VERSION) {
                throw new IllegalArgumentException("legacy support journal has no durable credit evidence; preserve and reconcile it");
            }
            JournalFile file = GSON.fromJson(parsed, JournalFile.class);
            if (file == null || file.version() != VERSION || file.journal() == null) {
                throw new IllegalArgumentException("unsupported or incomplete support journal");
            }
            return Optional.of(file.journal());
        } catch (RuntimeException invalid) {
            throw new IOException("invalid support journal; existing ownership must be reconciled", invalid);
        }
    }

    synchronized Optional<TemporarySupportJournal> loadFor(String planId, RunContext context,
                                                           String sliceId) throws IOException {
        Optional<TemporarySupportJournal> loaded = load();
        if (loaded.isPresent() && !loaded.orElseThrow().matches(planId, context, sliceId)) {
            throw new IOException("support journal belongs to another plan, world, dimension, or slice");
        }
        return loaded;
    }

    synchronized TemporarySupportJournal create(TemporarySupportPlanner.Column column,
                                                 RunContext context) throws IOException {
        Optional<TemporarySupportJournal> existing = load();
        if (existing.isPresent() && !existing.orElseThrow().complete()) {
            throw new IOException("an unfinished support journal already owns temporary cells");
        }
        TemporarySupportJournal created = TemporarySupportJournal.begin(column, context);
        write(created);
        return created;
    }

    synchronized TemporarySupportJournal transition(TemporarySupportJournal expected,
                                                     TemporarySupportJournal.Action action,
                                                     int cellIndex) throws IOException {
        Objects.requireNonNull(expected, "expected");
        if (!load().orElseThrow(() -> new IOException("support journal is missing")).equals(expected)) {
            throw new IOException("support journal changed; reload before interacting");
        }
        TemporarySupportJournal updated = expected.transition(action, cellIndex);
        write(updated);
        return updated;
    }

    synchronized TemporarySupportJournal confirmStarter(TemporarySupportJournal expected,
                                                        boolean creative) throws IOException {
        requireCurrent(expected);
        TemporarySupportJournal updated = expected.confirmStarter(creative);
        write(updated);
        return updated;
    }

    synchronized Optional<PlannedConsumptionCredit> pendingPlannedCredit() throws IOException {
        return load().flatMap(TemporarySupportJournal::pendingPlannedCredit);
    }

    synchronized TemporarySupportJournal acknowledgePlannedCredit(TemporarySupportJournal expected,
                                                                  String id) throws IOException {
        requireCurrent(expected);
        TemporarySupportJournal updated = expected.acknowledgePlannedCredit(id);
        if (updated != expected) { write(updated); }
        return updated;
    }

    private void requireCurrent(TemporarySupportJournal expected) throws IOException {
        Objects.requireNonNull(expected, "expected");
        if (!load().orElseThrow(() -> new IOException("support journal is missing")).equals(expected)) {
            throw new IOException("support journal changed; reload before interacting");
        }
    }

    private void write(TemporarySupportJournal journal) throws IOException {
        byte[] encoded = GSON.toJson(new JournalFile(VERSION, journal)).getBytes(StandardCharsets.UTF_8);
        if (encoded.length > MAXIMUM_BYTES) { throw new IOException("support journal exceeds size limit"); }
        Path parent = path.getParent();
        if (parent == null) { throw new IOException("support journal requires a parent directory"); }
        Files.createDirectories(parent);
        Path temporary = Files.createTempFile(parent, "temporary-support-", ".tmp");
        try {
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING)) {
                ByteBuffer bytes = ByteBuffer.wrap(encoded);
                while (bytes.hasRemaining()) { channel.write(bytes); }
                channel.force(true);
            }
            // No non-atomic fallback: an unsupported replacement leaves the old journal intact.
            Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private record JournalFile(int version, TemporarySupportJournal journal) { }
}
