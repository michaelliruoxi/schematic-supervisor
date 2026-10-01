package io.github.schematicsupervisor.fabric;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Objects;
import java.util.Optional;

final class RunContextStore {
    private static final int SCHEMA_VERSION = 1;
    private static final Gson GSON = MaterialGson.register(new GsonBuilder()).setPrettyPrinting().create();
    private final Path path;

    RunContextStore(Path path) {
        this.path = Objects.requireNonNull(path, "path").toAbsolutePath().normalize();
    }

    Optional<RunContext> load() throws IOException {
        if (!Files.exists(path)) {
            return Optional.empty();
        }
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            ContextFile file = GSON.fromJson(reader, ContextFile.class);
            if (file == null || file.schemaVersion != SCHEMA_VERSION) {
                throw new IOException("unsupported run-context file");
            }
            return Optional.of(new RunContext(file.worldIdentityHash, file.dimension));
        } catch (JsonParseException | IllegalArgumentException | NullPointerException exception) {
            throw new IOException("invalid run-context file", exception);
        }
    }

    void save(RunContext context) throws IOException {
        Objects.requireNonNull(context, "context");
        Path parent = path.getParent();
        if (parent == null) {
            throw new IOException("run-context file must have a parent directory");
        }
        Files.createDirectories(parent);
        Path temporary = Files.createTempFile(parent, "run-context-", ".tmp");
        ContextFile file = new ContextFile();
        file.schemaVersion = SCHEMA_VERSION;
        file.worldIdentityHash = context.worldIdentityHash();
        file.dimension = context.dimension();
        try {
            try (Writer writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
                GSON.toJson(file, writer);
            }
            try {
                Files.move(
                        temporary,
                        path,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING
                );
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    void clear() throws IOException {
        Files.deleteIfExists(path);
    }

    private static final class ContextFile {
        private int schemaVersion;
        private String worldIdentityHash;
        private String dimension;
    }
}
