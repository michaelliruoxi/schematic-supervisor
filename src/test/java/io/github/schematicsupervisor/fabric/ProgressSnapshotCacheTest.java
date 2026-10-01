package io.github.schematicsupervisor.fabric;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.schematicsupervisor.core.BlockPosition;
import io.github.schematicsupervisor.core.BlockState;
import io.github.schematicsupervisor.core.ChunkCoordinate;
import io.github.schematicsupervisor.core.LayerBuildSchedule;
import io.github.schematicsupervisor.core.ScheduleProgress;
import io.github.schematicsupervisor.core.SchematicCompiler;
import io.github.schematicsupervisor.core.SchematicPlan;
import io.github.schematicsupervisor.core.TargetBlock;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

final class ProgressSnapshotCacheTest {
    @Test
    void rebuildsOnlyWhenTheInputsChange() {
        List<RuntimeException> failures = new ArrayList<>();
        ProgressSnapshotCache cache = new ProgressSnapshotCache(failures::add);
        assertNull(cache.snapshot());
        AtomicInteger builds = new AtomicInteger();
        ScheduleProgress.Key first = new ScheduleProgress.Key("plan", "layers-v1", 0, -1);
        Supplier<ScheduleProgress> atStart = () -> {
            builds.incrementAndGet();
            return progress(0);
        };
        cache.updateAvailable(first, atStart);
        cache.updateAvailable(first, atStart);
        assertEquals(1, builds.get());
        assertEquals(1, cache.revision());
        assertEquals(1, json(cache).get("revision").getAsLong());
        cache.updateAvailable(new ScheduleProgress.Key("plan", "layers-v1", 1, -1), () -> {
            builds.incrementAndGet();
            return progress(1);
        });
        assertEquals(2, builds.get());
        assertEquals(2, cache.revision());
        assertEquals(1, json(cache).getAsJsonObject("totals").get("done").getAsLong());
        assertTrue(failures.isEmpty());
    }

    @Test
    void unavailableReasonsBumpTheRevisionOncePerChange() {
        ProgressSnapshotCache cache = new ProgressSnapshotCache(failure -> { });
        cache.updateUnavailable("No plan is loaded.");
        cache.updateUnavailable("No plan is loaded.");
        assertEquals(1, cache.revision());
        assertEquals("No plan is loaded.", json(cache).get("reason").getAsString());
        cache.updateUnavailable("The plan is loading.");
        assertEquals(2, cache.revision());
    }

    @Test
    void failedBuildKeepsThePreviousBodyAndIsNotRetriedForTheSameInputs() {
        List<RuntimeException> failures = new ArrayList<>();
        ProgressSnapshotCache cache = new ProgressSnapshotCache(failures::add);
        cache.updateAvailable(new ScheduleProgress.Key("plan", "layers-v1", 0, -1), () -> progress(0));
        byte[] before = cache.snapshot();
        AtomicInteger attempts = new AtomicInteger();
        ScheduleProgress.Key broken = new ScheduleProgress.Key("plan", "layers-v1", 1, -1);
        Supplier<ScheduleProgress> failing = () -> {
            attempts.incrementAndGet();
            throw new IllegalStateException("broken");
        };
        cache.updateAvailable(broken, failing);
        cache.updateAvailable(broken, failing);
        assertEquals(1, attempts.get());
        assertEquals(1, failures.size());
        assertEquals(1, cache.revision());
        assertArrayEquals(before, cache.snapshot());
    }

    @Test
    void snapshotsAreDefensiveCopies() {
        ProgressSnapshotCache cache = new ProgressSnapshotCache(failure -> { });
        cache.updateUnavailable("No plan is loaded.");
        byte[] copy = cache.snapshot();
        copy[0] = 'x';
        assertEquals((byte) '{', cache.snapshot()[0]);
    }

    private static ScheduleProgress progress(int cursor) {
        SchematicPlan plan = SchematicCompiler.compile7x7(new ChunkCoordinate(0, 0), List.of(
                new TargetBlock(new BlockPosition(0, 0, 0), new BlockState("minecraft:dirt")),
                new TargetBlock(new BlockPosition(16, 0, 0), new BlockState("minecraft:dirt"))));
        return ScheduleProgress.of(ScheduleProgress.index(plan, new LayerBuildSchedule(plan)), cursor, -1);
    }

    private static JsonObject json(ProgressSnapshotCache cache) {
        return JsonParser.parseString(new String(cache.snapshot(), StandardCharsets.UTF_8)).getAsJsonObject();
    }
}
