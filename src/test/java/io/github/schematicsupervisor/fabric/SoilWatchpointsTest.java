package io.github.schematicsupervisor.fabric;

import static org.junit.jupiter.api.Assertions.*;

import io.github.schematicsupervisor.core.BlockPosition;
import io.github.schematicsupervisor.core.BlockState;
import io.github.schematicsupervisor.core.BuildVolume;
import io.github.schematicsupervisor.core.SchematicCompiler;
import io.github.schematicsupervisor.core.SchematicPlan;
import io.github.schematicsupervisor.core.TargetBlock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class SoilWatchpointsTest {
    static final Instant NOW = Instant.parse("2026-09-16T20:00:00Z");
    static final RunContext CONTEXT = new RunContext("sha256:" + "1".repeat(64), "minecraft:overworld");
    static final BlockPosition FIRST = new BlockPosition(4, 0, 4);

    @Test void selectsAtMostFourActualBareSourceFarmlandCellsAndNeverRepinsThem() {
        Fixture fixture = new Fixture();
        fixture.source.states.put(FIRST, new BlockState("minecraft:dirt"));
        fixture.source.states.put(new BlockPosition(5, 1, 4), new BlockState("minecraft:wheat"));
        fixture.tick(0);
        var original = fixture.watch.observation();
        assertEquals(4, original.points().size());
        assertEquals(new BlockPosition(6, 0, 4), original.points().getFirst().position());
        assertEquals(fixture.plan.planId(), original.planId());
        assertEquals(CONTEXT, original.context());
        assertEquals("epoch-one", original.worldEpoch());
        for (var point : original.points()) { fixture.source.states.put(point.position(), new BlockState("minecraft:dirt")); }
        fixture.tick(1);
        assertEquals(original.points().stream().map(SoilWatchpointObservation.Point::position).toList(),
                fixture.watch.observation().points().stream().map(SoilWatchpointObservation.Point::position).toList());
        assertEquals("minecraft:dirt", fixture.watch.observation().points().getFirst().lastFresh().actual().blockId());
    }

    @Test void monotonicGateAllowsNoReadsBetweenOneSecondSamplesAndDiscoveryIsBounded() {
        Fixture fixture = new Fixture();
        fixture.source.states.replaceAll((position, ignored) -> new BlockState("minecraft:dirt"));
        fixture.tick(0);
        assertTrue(fixture.source.stateReads <= SoilWatchpoints.DISCOVERY_BUDGET * 2);
        assertEquals(0, fixture.source.waterReads, "Non-candidates do not start environment scans");
        int reads = fixture.source.stateReads;
        fixture.watch.tick(999_999_999, NOW.plusSeconds(100), fixture.source);
        assertEquals(reads, fixture.source.stateReads, "Wall clock movement does not bypass monotonic throttling");
        fixture.tick(1);
        assertTrue(fixture.source.stateReads > reads);
        assertTrue(fixture.watch.observation().points().isEmpty());
    }

    @Test void exactWaterRangeAndRainAreReportedOnlyAfterCompleteReceivedPredictionFreeScan() {
        Fixture fixture = new Fixture();
        fixture.source.water.add(new BlockPosition(0, 1, 0)); // Inclusive -4/-4 and Y+1 boundary.
        fixture.tick(0);
        var sample = fixture.watch.observation().points().getFirst().latest();
        assertTrue(sample.environmentComplete());
        assertEquals(Boolean.TRUE, sample.nearbyWater());
        assertEquals(Boolean.FALSE, sample.rainAtAbove());
        assertEquals(4 * SoilWatchpoints.ENVIRONMENT_CELLS, fixture.source.waterReads);
        fixture.source.water.clear();
        fixture.source.water.add(new BlockPosition(-1, 0, 4)); // Outside the pinned range.
        fixture.tick(1);
        assertEquals(Boolean.FALSE, fixture.watch.observation().points().getFirst().latest().nearbyWater());
        fixture.source.unreceived.add(new BlockPosition(0, 0, 0));
        fixture.tick(2);
        sample = fixture.watch.observation().points().getFirst().latest();
        assertTrue(sample.fresh());
        assertFalse(sample.environmentComplete());
        assertNull(sample.nearbyWater());
        assertNull(sample.rainAtAbove());
        fixture.source.unreceived.clear();
        fixture.source.pending.add(new BlockPosition(0, 0, 0));
        fixture.tick(3);
        assertFalse(fixture.watch.observation().points().getFirst().latest().environmentComplete());
        fixture.source.pending.clear();
        fixture.source.rain = null;
        fixture.tick(4);
        assertFalse(fixture.watch.observation().points().getFirst().latest().environmentComplete());
    }

    @Test void predictedOrUnloadedTargetDoesNotReadBlockStateOrExtendFreshEvidence() {
        Fixture fixture = new Fixture();
        fixture.tick(0);
        var initial = fixture.watch.observation().points().getFirst().firstFresh();
        fixture.source.pending.add(FIRST);
        fixture.source.stateReads = 0;
        fixture.tick(1);
        var point = fixture.watch.observation().points().getFirst();
        assertEquals("PREDICTION_PENDING", point.latest().status());
        assertNull(point.latest().actual());
        assertEquals(initial, point.lastFresh());
        assertEquals(6, fixture.source.stateReads, "Only the three other fixed cells may read soil/above state");
        fixture.source.pending.clear();
        fixture.source.allUnreceived = true;
        fixture.source.stateReads = 0;
        fixture.tick(2);
        point = fixture.watch.observation().points().getFirst();
        assertEquals("UNLOADED", point.latest().status());
        assertNull(point.latest().predictionPending());
        assertEquals(0, fixture.source.stateReads);
        assertEquals(initial, point.lastFresh());
        assertTrue(point.continuityUnknown());
        fixture.source.allUnreceived = false;
        fixture.tick(3);
        assertTrue(fixture.watch.observation().points().getFirst().continuityUnknown());
        assertEquals(NOW.plusSeconds(3), fixture.watch.observation().points().getFirst().lastFresh().at());
    }

    @Test void disconnectionAndMissedSamplingIntervalsRemainUnknownWithoutClaimingRandomTicks() {
        Fixture fixture = new Fixture();
        fixture.tick(0);
        fixture.watch.tick(1_000_000_000L, NOW.plusSeconds(1), null);
        assertFalse(fixture.watch.observation().available());
        assertEquals("CONTEXT_UNAVAILABLE", fixture.watch.observation().points().getFirst().latest().status());
        fixture.tick(2);
        assertTrue(fixture.watch.observation().points().getFirst().continuityUnknown());
        Fixture second = new Fixture();
        second.tick(0);
        second.tick(10);
        assertTrue(second.watch.observation().points().getFirst().continuityUnknown());
        assertEquals(2, second.watch.observation().points().getFirst().freshSamples());
    }

    @Test void historyIsImmutableBoundedAndRetainsFirstAndLatestFreshStatesAcrossDrying() {
        Fixture fixture = new Fixture();
        fixture.tick(0);
        for (int second = 1; second <= 12; second++) {
            fixture.source.states.put(FIRST, soil(second % 8));
            fixture.tick(second);
        }
        fixture.source.states.put(FIRST, new BlockState("minecraft:dirt"));
        fixture.tick(13);
        var observation = fixture.watch.observation();
        var point = observation.points().getFirst();
        assertEquals(7, point.firstFresh().moisture());
        assertEquals("minecraft:dirt", point.lastFresh().actual().blockId());
        assertNull(point.lastFresh().moisture());
        assertEquals(8, point.history().size());
        assertTrue(point.historyTruncated());
        assertEquals(13, point.changes());
        assertThrows(UnsupportedOperationException.class, () -> point.history().clear());
        assertThrows(UnsupportedOperationException.class, () -> observation.points().clear());
        fixture.tick(14);
        assertEquals(14, point.freshSamples(), "Previously published immutable observation does not change");
    }

    @Test void failedOptionalReadPreservesPinnedPositionsAndMarksUnknown() {
        Fixture fixture = new Fixture();
        fixture.tick(0);
        fixture.source.failReads = true;
        fixture.tick(1);
        assertEquals(4, fixture.watch.observation().points().size());
        assertEquals("READ_FAILED", fixture.watch.observation().points().getFirst().latest().status());
        assertEquals(NOW, fixture.watch.observation().points().getFirst().lastFresh().at());
    }

    static BlockState soil(int moisture) {
        return new BlockState("minecraft:farmland", Map.of("moisture", Integer.toString(moisture)));
    }

    static final class Fixture {
        final FakeSource source = new FakeSource();
        final SchematicPlan plan;
        final SoilWatchpoints watch;
        Fixture() {
            List<TargetBlock> targets = new ArrayList<>();
            for (int x = 4; x <= 9; x++) {
                BlockPosition position = new BlockPosition(x, 0, 4);
                targets.add(new TargetBlock(position, soil(7)));
                source.states.put(position, soil(7));
            }
            plan = SchematicCompiler.compile(new BuildVolume(0, 0, 0, 15, 2, 15), targets).withPlantingDeferred(true);
            watch = new SoilWatchpoints(plan, CONTEXT, "epoch-one", null);
        }
        void tick(int second) { watch.tick(second * 1_000_000_000L, NOW.plusSeconds(second), source); }
    }

    static final class FakeSource implements SoilWatchpoints.Source {
        final Map<BlockPosition, BlockState> states = new HashMap<>();
        final Set<BlockPosition> unreceived = new HashSet<>();
        final Set<BlockPosition> pending = new HashSet<>();
        final Set<BlockPosition> water = new HashSet<>();
        boolean allUnreceived;
        boolean failReads;
        Boolean rain = false;
        int stateReads;
        int waterReads;
        @Override public boolean received(BlockPosition position) { return !allUnreceived && !unreceived.contains(position); }
        @Override public boolean predictionPending(BlockPosition position) { return pending.contains(position); }
        @Override public BlockState state(BlockPosition position) {
            stateReads++;
            if (failReads) { throw new IllegalStateException("unavailable"); }
            return states.getOrDefault(position, BlockState.AIR);
        }
        @Override public boolean water(BlockPosition position) { waterReads++; return water.contains(position); }
        @Override public Boolean rainAt(BlockPosition position) { return rain; }
    }
}
