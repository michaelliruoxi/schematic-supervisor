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

/** A bounded forced journal replacement; unknown writes leave the transfer ineligible for replay. */
public final class MossDepositFileStore implements MossDepositController.Store {
    private static final int VERSION = 2;
    public static final int MAXIMUM_BYTES = 196_608;
    private static final Gson GSON = MaterialGson.register(new GsonBuilder()).serializeNulls().create();
    private final Path path;
    private final AtomicReplacement replacement;

    @FunctionalInterface
    interface AtomicReplacement { void move(Path temporary, Path destination) throws IOException; }

    public MossDepositFileStore(Path path) {
        this(path, (temporary, destination) -> Files.move(temporary, destination,
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING));
    }

    MossDepositFileStore(Path path, AtomicReplacement replacement) {
        this.path = Objects.requireNonNull(path, "path").toAbsolutePath().normalize();
        this.replacement = Objects.requireNonNull(replacement, "replacement");
    }

    @Override
    public synchronized Optional<MossDepositJournal> load() throws IOException {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) { return Optional.empty(); }
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) > MAXIMUM_BYTES) {
            throw new IOException("deposit journal must be a bounded regular file");
        }
        byte[] bytes;
        try (var input = Files.newInputStream(path)) {
            bytes = input.readNBytes(MAXIMUM_BYTES + 1);
        }
        if (bytes.length > MAXIMUM_BYTES) { throw new IOException("deposit journal exceeds size limit"); }
        try {
            String json = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            var value = JsonParser.parseString(json).getAsJsonObject();
            String version = value.get("version").toString();
            if (!value.keySet().equals(Set.of("version", "journal"))
                    || !(Integer.toString(VERSION).equals(version) || "1".equals(version))) {
                throw new IllegalArgumentException("unsupported deposit journal version or fields");
            }
            if ("1".equals(version)) { migrateLegacyJournal(value.getAsJsonObject("journal")); }
            MossDepositJournal journal = GSON.fromJson(value.get("journal"), MossDepositJournal.class);
            return Optional.of(Objects.requireNonNull(journal, "journal"));
        } catch (RuntimeException invalid) {
            throw new IOException("invalid deposit journal; preserve and reconcile existing inventory", invalid);
        }
    }

    /** In-memory migration preserves every original stack fingerprint, amount, identity and packet stamp. */
    private static void migrateLegacyJournal(com.google.gson.JsonObject journal) {
        migrateLegacyObservation(journal.getAsJsonObject("before"));
        if (!journal.get("receipt").isJsonNull()) { migrateLegacyObservation(journal.getAsJsonObject("receipt")); }
    }

    private static void migrateLegacyObservation(com.google.gson.JsonObject observation) {
        var slots = observation.getAsJsonObject("slots");
        for (String group : java.util.List.of("chest", "main")) {
            for (var element : slots.getAsJsonArray(group)) {
                var slot = element.getAsJsonObject();
                if (slot.has("insertion") || !slot.has("canInsertMoss") || !slot.has("mossSlotLimit")) {
                    throw new IllegalArgumentException("legacy slot facts must retain their Moss-only capacity");
                }
                var insertion = new com.google.gson.JsonObject();
                var moss = new com.google.gson.JsonObject();
                moss.add("allowed", slot.remove("canInsertMoss"));
                moss.add("limit", slot.remove("mossSlotLimit"));
                insertion.add(MossDepositFacts.MOSS, moss);
                slot.add("insertion", insertion);
                migrateLegacyStack(slot.getAsJsonObject("stack"));
            }
        }
        migrateLegacyStack(slots.getAsJsonObject("cursor"));
        migrateLegacyStack(slots.getAsJsonObject("offhand"));
        for (var armor : slots.getAsJsonArray("armor")) { migrateLegacyStack(armor.getAsJsonObject()); }
    }

    private static void migrateLegacyStack(com.google.gson.JsonObject stack) {
        if (stack.has("plainPickup") || !stack.has("plainMoss")
                || !stack.get("plainMoss").isJsonPrimitive()
                || !stack.getAsJsonPrimitive("plainMoss").isBoolean()
                || stack.get("plainMoss").getAsBoolean()
                && !MossDepositFacts.MOSS.equals(stack.get("itemId").getAsString())) {
            throw new IllegalArgumentException("legacy eligibility proves plain Moss only");
        }
        stack.add("plainPickup", stack.remove("plainMoss"));
    }

    @Override
    public synchronized void replace(Optional<MossDepositJournal> expected, MossDepositJournal journal) throws IOException {
        Objects.requireNonNull(expected, "expected");
        Objects.requireNonNull(journal, "journal");
        Path parent = path.getParent();
        if (parent == null) { throw new IOException("deposit journal requires a parent directory"); }
        Files.createDirectories(parent);
        Path lockPath = path.resolveSibling(path.getFileName() + ".lock");
        try (FileChannel channel = FileChannel.open(lockPath, StandardOpenOption.CREATE,
                StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
            try (var lock = channel.tryLock()) {
                if (lock == null) { throw new IOException("another writer owns the deposit journal"); }
                replaceLocked(expected, journal, parent);
            }
        } catch (OverlappingFileLockException busy) {
            throw new IOException("another writer owns the deposit journal", busy);
        }
    }

    private void replaceLocked(Optional<MossDepositJournal> expected, MossDepositJournal journal,
                               Path parent) throws IOException {
        if (!load().equals(expected)) { throw new IOException("deposit journal changed; reload without clicking"); }
        if (expected.filter(value -> value.stage() == MossDepositJournal.Stage.PENDING
                && !value.operationId().equals(journal.operationId())).isPresent()) {
            throw new IOException("an unresolved deposit intent cannot be replaced by a new operation");
        }
        if (expected.isPresent() && expected.orElseThrow().operationId().equals(journal.operationId())) {
            MossDepositJournal prior = expected.orElseThrow();
            if (!prior.before().equals(journal.before()) || !prior.plan().equals(journal.plan())
                    || prior.stage() == MossDepositJournal.Stage.CONFIRMED && !prior.equals(journal)) {
                throw new IOException("the immutable deposit intent or confirmed receipt changed");
            }
        }
        byte[] encoded = GSON.toJson(new FileRecord(VERSION, journal)).getBytes(StandardCharsets.UTF_8);
        if (encoded.length > MAXIMUM_BYTES) { throw new IOException("deposit journal exceeds size limit"); }
        Path temporary = Files.createTempFile(parent, "moss-deposit-", ".tmp");
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

    private record FileRecord(int version, MossDepositJournal journal) { }
}
