package io.github.schematicsupervisor.fabric;

import static org.junit.jupiter.api.Assertions.*;

import io.github.schematicsupervisor.core.BlockPosition;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntFunction;
import java.util.function.IntPredicate;
import org.junit.jupiter.api.Test;

class PlantTargetSelectorTest {
    @Test void nearbyRowsShareAStripAndStripsAlternateAtAnyOriginWithStableIndices() {
        for (int origin : new int[]{8250, -18}) {
            var selector = new PlantTargetSelector(List.of(pos(origin, 0), pos(origin, 1),
                    pos(origin + 2, 0), pos(origin + 2, 1), pos(origin + 6, 0), pos(origin + 6, 1)));
            // Rows origin and origin+2 are one strip, interleaved along +z; origin+6 returns along -z.
            assertArrayEquals(new int[]{0, 2, 1, 3, 5, 4}, selector.sweepOrder());
            var done = new BitSet();
            var visited = new ArrayList<Integer>();
            for (int step = 0; step < 6; step++) {
                int index = select(selector, i -> done.get(i) ? PlantTargetSelector.TargetState.COMPLETE
                        : PlantTargetSelector.TargetState.READY, i -> false);
                assertFalse(done.get(index));
                visited.add(index);
                done.set(index);
            }
            // Each first unfinished cell is approached once through the next cell of its own row.
            assertEquals(List.of(1, 0, 3, 2, 4, 5), visited);
            assertEquals(-1, select(selector, i -> PlantTargetSelector.TargetState.COMPLETE, i -> false));
        }
    }

    @Test void denseRowsWithinTheSpanFormOneStripAndWiderGapsStartANewOne() {
        var selector = new PlantTargetSelector(List.of(pos(0, 0), pos(1, 0), pos(2, 0), pos(3, 0),
                pos(4, 0), pos(4, 1), pos(8, 0), pos(8, 1)));
        assertArrayEquals(new int[]{0, 1, 2, 3, 5, 4, 6, 7}, selector.sweepOrder());
    }

    @Test void visiblePatchWinsOverFallbackAndOutsideIsPlantedFirst() {
        var selector = new PlantTargetSelector(List.of(pos(0, 0), pos(0, 1), pos(0, 2)));
        assertEquals(2, select(selector, i -> PlantTargetSelector.TargetState.READY, i -> i > 0));
        var done = new BitSet();
        done.set(2);
        assertEquals(1, select(selector, i -> done.get(i) ? PlantTargetSelector.TargetState.COMPLETE
                : PlantTargetSelector.TargetState.READY, i -> false), "The move aims past the first cell");
        assertEquals(0, select(selector, i -> done.get(i) ? PlantTargetSelector.TargetState.COMPLETE
                : PlantTargetSelector.TargetState.READY, i -> false), "A missed first cell is then approached directly");
    }

    @Test void lookAheadStaysInTheFallbackStripWithinItsDistanceAndPrefersTheSameRow() {
        var cells = new ArrayList<BlockPosition>();
        for (int z = 0; z < 8; z++) { cells.add(pos(0, z)); cells.add(pos(2, z)); }
        cells.add(pos(4, 1));
        var selector = new PlantTargetSelector(cells);
        // Index 0 is the first cell; the farthest cell within four blocks is z=4, and row 0 wins the tie.
        assertEquals(8, select(selector, i -> PlantTargetSelector.TargetState.READY, i -> false));
        assertEquals(cells.get(8), pos(0, 4));
        // Pending, unreceived and invalid cells are never aimed at; the next farthest ready cell is.
        var other = new PlantTargetSelector(cells);
        assertEquals(6, select(other, i -> switch (i) {
            case 8 -> PlantTargetSelector.TargetState.PENDING;
            case 9 -> PlantTargetSelector.TargetState.UNRECEIVED;
            default -> PlantTargetSelector.TargetState.READY;
        }, i -> false));
        assertEquals(cells.get(6), pos(0, 3));
    }

    @Test void noLookAheadFromAnUnreceivedFirstCellOrIntoTheNextStripOrFloor() {
        var selector = new PlantTargetSelector(List.of(pos(0, 0), pos(0, 1)));
        assertEquals(0, select(selector, i -> i == 0 ? PlantTargetSelector.TargetState.UNRECEIVED
                : PlantTargetSelector.TargetState.READY, i -> false));
        var strips = new PlantTargetSelector(List.of(pos(0, 0), pos(8, 1), pos(8, 2)));
        assertEquals(0, select(strips, i -> PlantTargetSelector.TargetState.READY, i -> false));
        var floors = new PlantTargetSelector(List.of(new BlockPosition(0, 8, 0), new BlockPosition(0, 2, 1)));
        assertEquals(0, select(floors, i -> PlantTargetSelector.TargetState.READY, i -> false));
        var far = new PlantTargetSelector(List.of(pos(0, 0), pos(0, 5)));
        assertEquals(0, select(far, i -> PlantTargetSelector.TargetState.READY, i -> false));
    }

    @Test void pendingPredictionOnHighestFloorCannotCompleteOrSkipToLowerFloor() {
        var selector = new PlantTargetSelector(List.of(new BlockPosition(0, 2, 0),
                new BlockPosition(0, 8, 0)));
        assertEquals(-1, select(selector, i -> i == 1 ? PlantTargetSelector.TargetState.PENDING
                : PlantTargetSelector.TargetState.READY, i -> true));
        assertTrue(selector.waitingForPrediction());
        assertEquals(0, select(selector, i -> i == 1 ? PlantTargetSelector.TargetState.COMPLETE
                : PlantTargetSelector.TargetState.READY, i -> true));
        assertFalse(selector.waitingForPrediction());
    }

    @Test void invalidSupportIsNotHiddenByAnotherVisibleTarget() {
        var selector = new PlantTargetSelector(List.of(pos(0, 0), pos(0, 1)));
        assertEquals(1, select(selector, i -> i == 0 ? PlantTargetSelector.TargetState.READY
                : PlantTargetSelector.TargetState.INVALID, i -> true));
        assertEquals(1, selector.invalidIndex());
    }

    @Test void unreceivedAndPendingCellsNeverRunRayTests() {
        var selector = new PlantTargetSelector(List.of(pos(0, 0), pos(0, 1), pos(0, 2)));
        assertEquals(2, select(selector, i -> switch (i) {
            case 0 -> PlantTargetSelector.TargetState.UNRECEIVED;
            case 1 -> PlantTargetSelector.TargetState.PENDING;
            default -> PlantTargetSelector.TargetState.READY;
        }, i -> { assertEquals(2, i); return true; }));
        assertEquals(0, select(selector, i -> PlantTargetSelector.TargetState.UNRECEIVED,
                i -> { fail("Unreceived cells must not query geometry"); return false; }));
    }

    @Test void scanBudgetMustFinishBeforeSelectingAFallbackAndResetsFreshly() {
        var selector = new PlantTargetSelector(List.of(pos(0, 0), pos(0, 1), pos(0, 2)));
        var reads = new AtomicInteger();
        selector.begin();
        IntFunction<PlantTargetSelector.TargetState> state = i -> {
            reads.incrementAndGet(); return PlantTargetSelector.TargetState.READY;
        };
        selector.scan(1, state, i -> i == 2, i -> i);
        assertEquals(1, reads.get());
        assertFalse(selector.complete());
        assertThrows(IllegalStateException.class, selector::selectedIndex);
        selector.scan(1, state, i -> i == 2, i -> i);
        assertFalse(selector.complete());
        selector.scan(1, state, i -> i == 2, i -> i);
        assertEquals(2, selector.selectedIndex());
        assertEquals(0, select(selector, i -> PlantTargetSelector.TargetState.READY, i -> i == 0));
        assertThrows(IllegalArgumentException.class,
                () -> selector.scan(0, state, i -> true, i -> 1));
    }

    @Test void rowDirectionResetsOnEachFloorAndSourceMutationCannotReindexTargets() {
        var source = new ArrayList<>(List.of(new BlockPosition(-2, 2, 0),
                new BlockPosition(-2, 8, 0), new BlockPosition(-2, 8, 1),
                new BlockPosition(0, 8, 0), new BlockPosition(0, 8, 1)));
        var selector = new PlantTargetSelector(source);
        source.clear();
        assertArrayEquals(new int[]{1, 3, 2, 4, 0}, selector.sweepOrder());
        var done = new BitSet();
        var visited = new ArrayList<Integer>();
        for (int n = 0; n < 5; n++) {
            int selected = select(selector, i -> done.get(i) ? PlantTargetSelector.TargetState.COMPLETE
                    : PlantTargetSelector.TargetState.READY, i -> false);
            visited.add(selected);
            done.set(selected);
        }
        assertEquals(List.of(2, 1, 4, 3, 0), visited);
    }

    @Test void emptyOrdersAndAlreadyPlantedCellsFinishWithoutAnyGeometry() {
        assertEquals(-1, select(new PlantTargetSelector(List.of()),
                i -> { throw new AssertionError("empty"); }, i -> true));
        assertEquals(-1, select(new PlantTargetSelector(List.of(pos(0, 0))),
                i -> PlantTargetSelector.TargetState.COMPLETE,
                i -> { throw new AssertionError("completed"); }));
    }

    @Test void invalidDistancesDoNotOverrideDeterministicFallback() {
        for (double distance : new double[]{Double.NaN, Double.POSITIVE_INFINITY, -1}) {
            var selector = new PlantTargetSelector(List.of(pos(0, 0), pos(0, 2)));
            selector.begin();
            selector.scan(256, i -> PlantTargetSelector.TargetState.READY, i -> true, i -> distance);
            // No distance makes a cell "visible"; the sweep's own move toward the first cell applies.
            assertEquals(1, selector.selectedIndex());
            selector.begin();
            selector.scan(256, i -> PlantTargetSelector.TargetState.READY, i -> true, i -> distance);
            assertEquals(0, selector.selectedIndex());
        }
    }

    @Test void fourTickPlantIntervalOverlapsReceiptAndNavigationButNeverBypassesEitherGuard() {
        var cadence = new InteractionDispatchCadence(4);
        var receipt = new FlightInteractionConfirmation(64, true, false, 100);
        cadence.dispatched();
        for (int i = 0; i < 6; i++) {
            cadence.tick();
            assertEquals(FlightInteractionConfirmation.Result.WAITING, receipt.observe(true, true, 63));
            assertEquals(InteractionTickDispatch.Next.WAIT_RECEIPT,
                    InteractionTickDispatch.next(true, InteractionTickDispatch.Kind.CLICK, false));
        }
        assertTrue(cadence.ready());
        assertEquals(FlightInteractionConfirmation.Result.CONFIRMED, receipt.observe(false, true, 63));
        assertEquals(1, receipt.consumed());
        assertEquals(InteractionTickDispatch.Next.WAIT_SCREEN,
                InteractionTickDispatch.next(false, InteractionTickDispatch.Kind.CLICK, true));
        cadence.dispatched();
        assertThrows(IllegalStateException.class, cadence::dispatched);
    }

    private static int select(PlantTargetSelector selector,
                              IntFunction<PlantTargetSelector.TargetState> state, IntPredicate reachable) {
        selector.begin();
        selector.scan(256, state, reachable, i -> i + 1);
        assertTrue(selector.complete());
        return selector.selectedIndex();
    }

    private static BlockPosition pos(int x, int z) { return new BlockPosition(x, 10, z); }
}
