package io.github.schematicsupervisor.fabric;

import java.io.IOException;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** One fixed repair command, held until a later applied server packet proves the same tool repaired. */
final class HoeRepairSession {
    static final int REPAIR_RESERVE = 64;
    static final int ACKNOWLEDGEMENT_TICKS = 100;
    enum Status { READY, WAITING, BLOCKED }

    record Tool(int slot, String itemId, String identity, int count, int damage, int maximum, boolean unbreakable) {
        Tool {
            Objects.requireNonNull(itemId, "itemId");
            Objects.requireNonNull(identity, "identity");
            if (slot < 0 || slot >= 36 || !itemId.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")
                    || itemId.length() > 128 || !identity.matches("[0-9a-f]{64}")
                    || count != 1 || damage < 0 || maximum < 1 || damage > maximum) {
                throw new IllegalArgumentException("Invalid bounded repair tool facts");
            }
        }
        boolean needsRepair() {
            int reserve = Math.min(REPAIR_RESERVE, Math.max(1, maximum / 10));
            return !unbreakable && damage > 0 && maximum - damage <= reserve && damage < maximum;
        }
        boolean sameTool(Tool other) {
            return other != null && slot == other.slot && itemId.equals(other.itemId)
                    && identity.equals(other.identity) && count == other.count && maximum == other.maximum
                    && unbreakable == other.unbreakable;
        }
    }

    record Receipt(String epoch, long sequence, Tool tool) {
        Receipt {
            UUID.fromString(epoch);
            Objects.requireNonNull(tool, "tool");
            if (sequence < 1) { throw new IllegalArgumentException("Applied server receipt sequence must be positive"); }
        }
    }

    record Journal(String operationId, RunContext context, Tool before, String epoch, long afterSequence, boolean confirmed) {
        Journal {
            UUID.fromString(operationId);
            Objects.requireNonNull(context, "context");
            Objects.requireNonNull(before, "before");
            UUID.fromString(epoch);
            if (afterSequence < 0 || before.slot() >= 9 || !before.needsRepair()) {
                throw new IllegalArgumentException("Repair intent requires a selected usable hoe inside its repair reserve");
            }
        }
        Journal confirm() { return new Journal(operationId, context, before, epoch, afterSequence, true); }
    }

    interface Store {
        Optional<Journal> load() throws IOException;
        void replace(Optional<Journal> expected, Journal replacement) throws IOException;
    }

    private final Store store;
    private Optional<Journal> journal;
    private int waitingTicks;
    private String failure = "";

    HoeRepairSession(Store store) throws IOException {
        this.store = Objects.requireNonNull(store, "store");
        journal = store.load();
    }

    boolean pending() { return journal.filter(value -> !value.confirmed()).isPresent(); }
    Optional<Journal> journal() { return journal; }
    String detail() {
        return !failure.isEmpty() ? failure : pending()
                ? "Waiting for an applied server inventory packet confirming the selected hoe repaired."
                : "Hoe repair is ready.";
    }

    /** Returns after durable intent; the caller rechecks context and sends exactly one fixed command. */
    void begin(RunContext context, Tool tool, String epoch, long sequence) throws IOException {
        if (pending() || !failure.isEmpty()) { throw new IllegalStateException("An unresolved repair must never be repeated"); }
        if (!tool.needsRepair()) { throw new IllegalArgumentException("The selected hoe does not need repair"); }
        Journal intent = new Journal(UUID.randomUUID().toString(), context, tool, epoch, sequence, false);
        try { store.replace(journal, intent); }
        catch (IOException | RuntimeException uncertain) {
            failure = "Repair intent could not be saved; preserve the journal and inspect it before retrying.";
            throw uncertain;
        }
        journal = Optional.of(intent);
        waitingTicks = 0;
    }

    Status observe(RunContext context, Tool current, Receipt receipt, boolean advanceDeadline) throws IOException {
        if (!pending()) { return failure.isEmpty() ? Status.READY : Status.BLOCKED; }
        Journal intent = journal.orElseThrow();
        if (!intent.context().equals(context)) {
            block("Hoe repair belongs to another world or profile; restore its original context without repeating /fix.");
            return Status.BLOCKED;
        }
        boolean later = receipt != null && (!intent.epoch().equals(receipt.epoch())
                || receipt.sequence() > intent.afterSequence());
        if (later && intent.before().sameTool(receipt.tool()) && receipt.tool().damage() == 0
                && receipt.tool().sameTool(current) && current.damage() == 0) {
            Journal confirmed = intent.confirm();
            try { store.replace(journal, confirmed); }
            catch (IOException | RuntimeException uncertain) {
                block("The repair packet arrived but its receipt could not be saved; preserve the journal.");
                throw uncertain;
            }
            journal = Optional.of(confirmed);
            failure = "";
            return Status.READY;
        }
        if (!intent.before().sameTool(current)) {
            block("The pending repair tool or slot changed; restore it and inspect the receipt without repeating /fix.");
        } else if (failure.isEmpty() && advanceDeadline && ++waitingTicks >= ACKNOWLEDGEMENT_TICKS) {
            block("No server-confirmed hoe repair after 100 ticks; check /fix permission or cooldown. The command will not repeat.");
        }
        return failure.isEmpty() ? Status.WAITING : Status.BLOCKED;
    }

    void block(String detail) { if (pending() && failure.isEmpty()) { failure = detail; } }
    void cancel() { block("Hoe repair was cancelled with a pending command; only its later server receipt can release it."); }
}
