package io.github.schematicsupervisor.fabric;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import io.github.schematicsupervisor.core.DepotId;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Atomic persistence for operator-approved depot locations.
 *
 * <p>Stock observations deliberately are not persisted: a prior-session chest count is not an
 * exact statement about the current server state.</p>
 */
final class DepotRegistryStore {
    private static final int SCHEMA_VERSION = 2;
    private static final Gson GSON = MaterialGson.register(new GsonBuilder()).setPrettyPrinting().create();

    private final Path path;

    DepotRegistryStore(Path path) {
        this.path = Objects.requireNonNull(path, "path").toAbsolutePath().normalize();
    }

    List<RegisteredDepot> load() throws IOException {
        if (!Files.exists(path)) {
            return List.of();
        }

        RegistryFile file;
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            file = GSON.fromJson(reader, RegistryFile.class);
        } catch (JsonParseException exception) {
            throw new IOException("depot registry is not valid JSON: " + path, exception);
        }
        if (file == null || file.schemaVersion != SCHEMA_VERSION || file.depots == null) {
            throw new IOException("unsupported or incomplete depot registry: " + path);
        }

        List<RegisteredDepot> result = new ArrayList<>(file.depots.size());
        Set<DepotId> ids = new HashSet<>();
        Set<String> locations = new HashSet<>();
        try {
            for (DepotFile depot : file.depots) {
                if (depot == null) {
                    throw new IllegalArgumentException("null depot entry");
                }
                DepotId id = new DepotId(depot.id);
                RegisteredDepot registered = RegisteredDepot.unscanned(
                        id,
                        Objects.requireNonNull(
                                depot.worldIdentityHash,
                                "depot world identity"
                        ),
                        Objects.requireNonNull(depot.dimension, "depot dimension"),
                        depot.x,
                        depot.y,
                        depot.z
                );
                if (!ids.add(id)) {
                    throw new IllegalArgumentException("duplicate depot id: " + id.value());
                }
                String location = locationKey(registered);
                if (!locations.add(location)) {
                    throw new IllegalArgumentException("duplicate depot location");
                }
                result.add(registered);
            }
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw new IOException("invalid depot registry entry: " + path, exception);
        }
        result.sort(Comparator.comparing(RegisteredDepot::id));
        return List.copyOf(result);
    }

    void save(List<RegisteredDepot> depots) throws IOException {
        Objects.requireNonNull(depots, "depots");
        Path parent = path.getParent();
        if (parent == null) {
            throw new IOException("depot registry must have a parent directory");
        }
        Files.createDirectories(parent);

        RegistryFile file = new RegistryFile();
        file.schemaVersion = SCHEMA_VERSION;
        file.depots = depots.stream()
                .sorted(Comparator.comparing(RegisteredDepot::id))
                .map(DepotFile::from)
                .toList();

        Path temporary = Files.createTempFile(parent, "depots-", ".tmp");
        try {
            try (Writer writer = Files.newBufferedWriter(
                    temporary,
                    StandardCharsets.UTF_8,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING
            )) {
                GSON.toJson(file, writer);
            }
            try {
                Files.move(
                        temporary,
                        path,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING
                );
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static String locationKey(RegisteredDepot depot) {
        return depot.worldIdentityHash()
                + '\0'
                + depot.dimension()
                + '\0'
                + depot.x()
                + ','
                + depot.y()
                + ','
                + depot.z();
    }

    private static final class RegistryFile {
        private int schemaVersion;
        private List<DepotFile> depots;
    }

    private static final class DepotFile {
        private String id;
        private String worldIdentityHash;
        private String dimension;
        private int x;
        private int y;
        private int z;

        private static DepotFile from(RegisteredDepot depot) {
            DepotFile file = new DepotFile();
            file.id = depot.id().value();
            file.worldIdentityHash = depot.worldIdentityHash();
            file.dimension = depot.dimension();
            file.x = depot.x();
            file.y = depot.y();
            file.z = depot.z();
            return file;
        }
    }
}
