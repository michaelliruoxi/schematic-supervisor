package io.github.schematicsupervisor.fabric;

import io.github.schematicsupervisor.core.DepotId;
import io.github.schematicsupervisor.core.MaterialQuantities;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/** Current-context, session-local chest observations without persisted private world identities. */
record DepotObservation(boolean available, String operation, String stage, String activeDepotId,
                        List<String> queuedDepotIds, boolean automaticScansPaused, boolean blocked,
                        String detail, int registeredCount, List<Entry> entries, boolean truncated) {
    static final int MAXIMUM_ENTRIES = 32;

    DepotObservation {
        queuedDepotIds = List.copyOf(queuedDepotIds);
        entries = List.copyOf(entries);
        detail = bounded(detail, 512);
        if (entries.size() > MAXIMUM_ENTRIES || queuedDepotIds.size() > MAXIMUM_ENTRIES
                || registeredCount < entries.size()) {
            throw new IllegalArgumentException("depot telemetry must be bounded");
        }
    }

    static DepotObservation unavailable(String detail) {
        return new DepotObservation(false, "NONE", "IDLE", null, List.of(), false, false,
                detail, 0, List.of(), false);
    }

    static DepotObservation capture(RunContext context, Collection<RegisteredDepot> registered,
                                    DepotId activeId, Collection<DepotId> queue, String operation,
                                    String stage, boolean scansPaused, boolean blocked, String detail) {
        if (context == null) { return unavailable("Current world context is unavailable."); }
        List<RegisteredDepot> current = registered.stream().filter(depot -> depot.matches(context)).toList();
        Set<DepotId> currentIds = current.stream().map(RegisteredDepot::id).collect(Collectors.toSet());
        Set<DepotId> queued = queue.stream().filter(currentIds::contains).collect(Collectors.toSet());
        List<Entry> entries = current.stream()
                .sorted(Comparator.<RegisteredDepot>comparingInt(depot ->
                        depot.id().equals(activeId) ? 0 : !depot.lastError().isBlank() ? 1
                                : queued.contains(depot.id()) ? 2 : 3)
                        .thenComparing(RegisteredDepot::id))
                .limit(MAXIMUM_ENTRIES)
                .map(depot -> new Entry(bounded(depot.id().value(), 64), depot.x(), depot.y(), depot.z(),
                        depot.scanned(), depot.scanned() ? depot.cachedStock() : null,
                        bounded(depot.lastError(), 256), depot.id().equals(activeId), queued.contains(depot.id())))
                .toList();
        List<String> queuedIds = queue.stream().filter(currentIds::contains)
                .limit(MAXIMUM_ENTRIES).map(id -> bounded(id.value(), 64)).toList();
        return new DepotObservation(true, operation, stage,
                activeId != null && currentIds.contains(activeId) ? bounded(activeId.value(), 64) : null,
                queuedIds, scansPaused, blocked, detail, current.size(), entries,
                current.size() > MAXIMUM_ENTRIES || queued.size() > MAXIMUM_ENTRIES);
    }

    private static String bounded(String value, int maximum) {
        String clean = value == null ? "" : value.replaceAll("sha256:[0-9a-fA-F]{64}", "[world identity]");
        return clean.length() <= maximum ? clean : clean.substring(0, maximum);
    }

    record Entry(String id, int x, int y, int z, boolean scanned, MaterialQuantities observedStock,
                 String lastError, boolean active, boolean queued) {
        Entry {
            Objects.requireNonNull(id, "id");
            lastError = bounded(lastError, 256);
            if (scanned != (observedStock != null)) {
                throw new IllegalArgumentException("unscanned depot stock must be unknown, not zero");
            }
        }
    }
}
