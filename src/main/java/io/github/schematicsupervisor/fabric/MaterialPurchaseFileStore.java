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

/** A bounded forced journal replacement; unknown writes leave the purchase ineligible for replay. */
public final class MaterialPurchaseFileStore implements MaterialPurchaseController.Store {
    private static final int VERSION = 1;
    public static final int MAXIMUM_BYTES = 65_536;
    private static final Gson GSON = MaterialGson.register(new GsonBuilder()).serializeNulls().create();
    private final Path path;
    private final AtomicReplacement replacement;

    @FunctionalInterface
    interface AtomicReplacement { void move(Path temporary, Path destination) throws IOException; }

    public MaterialPurchaseFileStore(Path path) {
        this(path, (temporary, destination) -> Files.move(temporary, destination,
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING));
    }

    MaterialPurchaseFileStore(Path path, AtomicReplacement replacement) {
        this.path = Objects.requireNonNull(path, "path").toAbsolutePath().normalize();
        this.replacement = Objects.requireNonNull(replacement, "replacement");
    }

    @Override
    public synchronized Optional<MaterialPurchaseJournal> load() throws IOException {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) { return Optional.empty(); }
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) > MAXIMUM_BYTES) {
            throw new IOException("purchase journal must be a bounded regular file");
        }
        byte[] bytes;
        try (var input = Files.newInputStream(path)) {
            bytes = input.readNBytes(MAXIMUM_BYTES + 1);
        }
        if (bytes.length > MAXIMUM_BYTES) { throw new IOException("purchase journal exceeds size limit"); }
        try {
            String json = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            var value = JsonParser.parseString(json).getAsJsonObject();
            if (!value.keySet().equals(Set.of("version", "journal"))
                    || !Integer.toString(VERSION).equals(value.get("version").toString())) {
                throw new IllegalArgumentException("unsupported purchase journal version or fields");
            }
            MaterialPurchaseJournal journal = GSON.fromJson(value.get("journal"), MaterialPurchaseJournal.class);
            if (!GSON.toJsonTree(journal).equals(value.get("journal"))) {
                throw new IllegalArgumentException("unknown or incomplete purchase journal fields");
            }
            return Optional.of(Objects.requireNonNull(journal, "journal"));
        } catch (RuntimeException invalid) {
            throw new IOException("invalid purchase journal; preserve and reconcile existing inventory", invalid);
        }
    }

    @Override
    public synchronized void replace(Optional<MaterialPurchaseJournal> expected, MaterialPurchaseJournal journal) throws IOException {
        Objects.requireNonNull(expected, "expected");
        Objects.requireNonNull(journal, "journal");
        Path parent = path.getParent();
        if (parent == null) { throw new IOException("purchase journal requires a parent directory"); }
        Files.createDirectories(parent);
        Path lockPath = path.resolveSibling(path.getFileName() + ".lock");
        try (FileChannel channel = FileChannel.open(lockPath, StandardOpenOption.CREATE,
                StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
            try (var lock = channel.tryLock()) {
                if (lock == null) { throw new IOException("another writer owns the purchase journal"); }
                replaceLocked(expected, journal, parent);
            }
        } catch (OverlappingFileLockException busy) {
            throw new IOException("another writer owns the purchase journal", busy);
        }
    }

    private void replaceLocked(Optional<MaterialPurchaseJournal> expected, MaterialPurchaseJournal journal,
                               Path parent) throws IOException {
        if (!load().equals(expected)) { throw new IOException("purchase journal changed; reload without clicking"); }
        if (expected.filter(value -> value.stage() == MaterialPurchaseJournal.Stage.PENDING
                && !value.operationId().equals(journal.operationId())).isPresent()) {
            throw new IOException("an unresolved purchase intent cannot be replaced by a new operation");
        }
        if (expected.isPresent() && expected.orElseThrow().operationId().equals(journal.operationId())) {
            MaterialPurchaseJournal prior = expected.orElseThrow();
            if (!prior.before().equals(journal.before()) || !prior.quote().equals(journal.quote())
                    || prior.stage() == MaterialPurchaseJournal.Stage.CONFIRMED && !prior.equals(journal)) {
                throw new IOException("the immutable purchase intent or confirmed receipt changed");
            }
        }
        byte[] encoded = GSON.toJson(new FileRecord(VERSION, journal)).getBytes(StandardCharsets.UTF_8);
        if (encoded.length > MAXIMUM_BYTES) { throw new IOException("purchase journal exceeds size limit"); }
        Path temporary = Files.createTempFile(parent, "material-purchase-", ".tmp");
        try {
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING)) {
                ByteBuffer buffer = ByteBuffer.wrap(encoded);
                while (buffer.hasRemaining()) { channel.write(buffer); }
                channel.force(true);
            }
            replacement.move(temporary, path);
        } finally {
            try { Files.deleteIfExists(temporary); }
            catch (IOException ignored) { /* Orphan temporary files are never read as journals. */ }
        }
    }

    private record FileRecord(int version, MaterialPurchaseJournal journal) { }
}
