package io.github.schematicsupervisor.fabric;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** Historical facts from a failed clearing check, independent of any sent block interaction. */
record ExecutionObstruction(Instant capturedAt, String mode, String reason,
                            ExecutionObservation.Target target, boolean sourceWorldMatches,
                            Boolean currentWorldMatches, Boolean playerOverlapsTarget,
                            Boolean playerOverlapsSupportBox, Integer totalEntities,
                            List<EntitySample> entities, boolean truncated, String error) {
    static final int MAX_ENTITY_SAMPLES = 8;

    ExecutionObstruction {
        Objects.requireNonNull(capturedAt, "capturedAt");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(entities, "entities");
        mode = bounded(mode, 64);
        reason = bounded(reason, 512);
        error = bounded(error, 256);
        if (totalEntities != null && (totalEntities < 0 || totalEntities < entities.size())) {
            throw new IllegalArgumentException("invalid obstruction entity count");
        }
        if (!sourceWorldMatches) {
            target = new ExecutionObservation.Target(target.position(), target.expectedBlock(), null, null);
            currentWorldMatches = null;
        }
        if (!sourceWorldMatches || !Boolean.TRUE.equals(target.chunkReceived())) {
            playerOverlapsTarget = null;
            playerOverlapsSupportBox = null;
            totalEntities = null;
            entities = List.of();
            truncated = false;
        } else {
            truncated |= entities.size() > MAX_ENTITY_SAMPLES;
            entities = List.copyOf(entities.subList(0, Math.min(entities.size(), MAX_ENTITY_SAMPLES)));
            truncated |= totalEntities != null && totalEntities > entities.size();
        }
    }

    ExecutionObstruction withCurrentWorldMatches(Boolean matches) {
        return new ExecutionObstruction(capturedAt, mode, reason, target, sourceWorldMatches, matches,
                playerOverlapsTarget, playerOverlapsSupportBox, totalEntities, entities, truncated, error);
    }

    record EntitySample(String entityType, boolean living, boolean player, boolean intersectsTarget,
                        boolean intersectsSupportBox, String itemId, Integer itemCount) {
        EntitySample {
            entityType = registryId(entityType);
            if ((itemId == null) != (itemCount == null) || (itemCount != null && itemCount < 0)) {
                throw new IllegalArgumentException("invalid obstruction item facts");
            }
            if (itemId != null) { itemId = registryId(itemId); }
            if (player && !living) { throw new IllegalArgumentException("player entity must be living"); }
        }
    }

    private static String registryId(String value) {
        if (value == null || value.length() > 128 || !value.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) {
            throw new IllegalArgumentException("invalid obstruction registry identifier");
        }
        return value;
    }

    private static String bounded(String value, int limit) {
        String text = value == null ? "" : value.replaceAll("[\\p{Cntrl}]", " ").strip();
        return text.substring(0, Math.min(text.length(), limit));
    }
}
