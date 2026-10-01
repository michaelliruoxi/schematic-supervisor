package io.github.schematicsupervisor.fabric;

import io.github.schematicsupervisor.core.BlockPosition;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

/** Collision-checked movement using only the player's already-active, server-granted flight. */
final class MinecraftFlightNavigation {
    private static final int SEARCH_BUDGET_PER_TICK = 128;
    private static final int MAXIMUM_SEARCH_NODES = 60_000;
    private static final int MAXIMUM_EXCLUDED_ARRIVALS = 4;
    static final int MAXIMUM_ROUTE_DISTANCE = 384;
    static final int MAXIMUM_ACTIVE_TICKS = 20 * 180;
    private static final int MAXIMUM_STALLED_TICKS = 20 * 5;
    private static final int MAXIMUM_STREAM_WAIT_TICKS = 20 * 12;
    private static final int DESCENT_PROBE_BUDGET = 32;
    private static final int DESCENT_SEARCH_BUDGET = 16;
    private static final int MINIMUM_STAGED_DESCENT = 16;
    private static final double STREAM_STAGE_PROGRESS = 12.0;
    // Vanilla flight without sprinting settles near 0.5 blocks per tick horizontally and 0.375 when
    // rising or sinking; each step stays within both.
    static final double HORIZONTAL_SPEED_PER_TICK = 0.5;
    static final double VERTICAL_SPEED_PER_TICK = 0.375;
    private static final double RELEASE_TOLERANCE = 0.07;
    private static final double BODY_HALF_WIDTH = 0.3;
    private static final double BODY_HEIGHT = 1.8;
    private final MinecraftClient client;
    private final Predicate<Box> movementAllowed;
    private ClientPlayerEntity player;
    private ClientWorld world;
    private BlockPos target;
    private Direction requiredFace;
    private boolean tillTopFaceAim;
    private BlockPos reservedPlacementCell;
    private boolean feetGoal;
    private boolean chunkReceiptGoal;
    private boolean streamingLeg;
    private boolean horizontalLeg;
    private boolean horizontalStagePending;
    private double reachRadius;
    private double eyeHeight;
    private FlightRoutePlanner planner;
    private FlightDescentProbe descentProbe;
    private FlightDescentSearch descentSearch;
    private FlightDescentEscape descentEscape;
    private DescentLeg descentLeg = DescentLeg.NONE;
    private int descentExitY;
    private BlockPosition descentFrontierRequest;
    private final Set<Long> descentFrontiers = new HashSet<>();
    private BlockPosition routeOrigin;
    private final FlightStreamingProgress streamingProgress =
            new FlightStreamingProgress(MAXIMUM_STREAM_WAIT_TICKS);
    private List<BlockPosition> route = List.of();
    private int waypoint;
    private FlightRouteAttempt attempt;
    private int stalledTicks;
    private double bestWaypointDistance = Double.POSITIVE_INFINITY;
    private Vec3d lastPosition;
    private Vec3d ownedVelocity;
    private State state = State.IDLE;
    private String detail = "";
    private String lastArrivalRejection = "";
    private long progressMarker;
    // The failure was a bounded search that found no usable route, not interference or lost flight.
    private boolean routeUnavailable;

    MinecraftFlightNavigation(MinecraftClient client) {
        this(client, box -> true);
    }

    MinecraftFlightNavigation(MinecraftClient client, Predicate<Box> movementAllowed) {
        this.client = Objects.requireNonNull(client, "client");
        this.movementAllowed = Objects.requireNonNull(movementAllowed, "movementAllowed");
    }

    void begin(BlockPos target, double reachRadius) {
        begin(target, reachRadius, null);
    }

    void begin(BlockPos target, double reachRadius, Direction requiredFace) {
        begin(target, reachRadius, requiredFace, null);
    }

    void begin(BlockPos target, double reachRadius, Direction requiredFace,
               BlockPos reservedPlacementCell) {
        begin(target, reachRadius, requiredFace, reservedPlacementCell, false);
    }

    void begin(BlockPos target, double reachRadius, Direction requiredFace,
               BlockPos reservedPlacementCell, boolean tillTopFaceAim) {
        beginRoute(target, reachRadius, requiredFace, reservedPlacementCell, false, false, tillTopFaceAim);
    }

    /** Tests a fresh interaction route without changing the existing route or owned movement. */
    boolean canBeginAtCurrentPosition(BlockPos target, double reachRadius, Direction requiredFace,
                                      BlockPos reservedPlacementCell) {
        return canBeginAtCurrentPosition(target, reachRadius, requiredFace, reservedPlacementCell, false);
    }

    boolean canBeginAtCurrentPosition(BlockPos target, double reachRadius, Direction requiredFace,
                                      BlockPos reservedPlacementCell, boolean tillTopFaceAim) {
        Objects.requireNonNull(target, "target");
        if (tillTopFaceAim && requiredFace != Direction.UP) {
            throw new IllegalArgumentException("tilling requires an upper-face interaction");
        }
        ClientPlayerEntity currentPlayer = client.player;
        ClientWorld currentWorld = client.world;
        if (currentPlayer == null || currentWorld == null
                || !currentPlayer.getAbilities().allowFlying || !currentPlayer.getAbilities().flying
                || target.getY() < currentWorld.getBottomY()
                || target.getY() > currentWorld.getTopYInclusive()) {
            return false;
        }
        Vec3d feet = currentPlayer.getPos();
        Vec3d eye = currentPlayer.getEyePos();
        return interactionArrival(feet, eye, target, reachRadius, reservedPlacementCell,
                ClientChunkAvailability.isLoaded(currentWorld, target),
                box -> receivedBox(currentWorld, box) && currentWorld.isSpaceEmpty(currentPlayer, box),
                () -> (tillTopFaceAim
                        ? ExactInteractionRay.traceTillTopFace(currentWorld, currentPlayer, eye, target, reachRadius)
                        : ExactInteractionRay.trace(currentWorld, currentPlayer, eye,
                                target, requiredFace, reachRadius)).accepted());
    }

    void beginReturnTo(BlockPos feetTarget, double radius) {
        beginReturnTo(feetTarget, radius, null);
    }

    void beginReturnTo(BlockPos feetTarget, double radius, BlockPos reservedPlacementCell) {
        beginRoute(feetTarget, radius, null, reservedPlacementCell, true, false, false);
    }

    void beginApproachChunk(int chunkX, int chunkZ) {
        beginApproachChunk(chunkX, chunkZ, null);
    }

    void beginApproachChunk(int chunkX, int chunkZ, BlockPos reservedPlacementCell) {
        int feetY = client.player == null ? 0 : (int) Math.floor(client.player.getY());
        BlockPos chunkCenter = new BlockPos(Math.addExact(Math.multiplyExact(chunkX, 16), 8),
                feetY, Math.addExact(Math.multiplyExact(chunkZ, 16), 8));
        beginRoute(chunkCenter, 1.0, null, reservedPlacementCell, true, true, false);
    }

    private void beginRoute(BlockPos target, double reachRadius, Direction requiredFace,
                            BlockPos reservedPlacementCell, boolean feetGoal, boolean chunkReceiptGoal,
                            boolean tillTopFaceAim) {
        stop();
        Objects.requireNonNull(target, "target");
        if (tillTopFaceAim && requiredFace != Direction.UP) {
            throw new IllegalArgumentException("tilling requires an upper-face interaction");
        }
        if (!Double.isFinite(reachRadius) || reachRadius <= 0 || reachRadius > 6) {
            throw new IllegalArgumentException("flight interaction radius must be between zero and six");
        }
        this.target = target.toImmutable();
        this.reachRadius = reachRadius;
        this.requiredFace = requiredFace;
        this.tillTopFaceAim = tillTopFaceAim;
        this.feetGoal = feetGoal;
        this.chunkReceiptGoal = chunkReceiptGoal;
        this.reservedPlacementCell = reservedPlacementCell == null ? null
                : reservedPlacementCell.toImmutable();
        player = client.player;
        world = client.world;
        attempt = new FlightRouteAttempt(MAXIMUM_SEARCH_NODES, MAXIMUM_ACTIVE_TICKS,
                MAXIMUM_EXCLUDED_ARRIVALS);
        if (!validIdentityAndFlight()) { return; }
        eyeHeight = player.getEyePos().y - player.getY();
        lastPosition = player.getPos();
        routeOrigin = cell(lastPosition);
        horizontalStagePending = FlightAltitudeStaging.required(routeOrigin,
                new BlockPosition(target.getX(), target.getY(), target.getZ()));
        streamingProgress.reset();
        if (target.getY() < world.getBottomY() || target.getY() > world.getTopYInclusive()) {
            failUnreachable("The flight target is outside the current world's vertical bounds.");
            return;
        }
        double allowableDistance = MAXIMUM_ROUTE_DISTANCE + reachRadius + (feetGoal ? 0.2 : eyeHeight);
        if (center(routeOrigin).squaredDistanceTo(Vec3d.ofCenter(target)) > allowableDistance * allowableDistance) {
            failUnreachable("Flight target exceeds the bounded route distance.");
            return;
        }
        if (goal(lastPosition, reachRadius)) {
            arrive();
            return;
        }
        prepareNextLeg(lastPosition);
    }

    private void prepareNextLeg(Vec3d current) {
        releaseOwnedVelocity();
        route = List.of();
        planner = null;
        descentProbe = null;
        descentSearch = null;
        descentEscape = null;
        descentLeg = DescentLeg.NONE;
        BlockPosition start = cell(current);
        BlockPosition destination = new BlockPosition(target.getX(), target.getY(), target.getZ());
        streamingLeg = !loaded(target.getX() >> 4, target.getZ() >> 4);
        if (FlightAltitudeStaging.aboveDestination(start, destination)) { horizontalStagePending = false; }
        horizontalLeg = !streamingLeg && horizontalStagePending;
        if (streamingLeg && FlightStreamingProgress.atFrontier(start, destination, this::receivedBody)) {
            state = State.WAITING_FOR_CHUNKS;
            detail = "Waiting for received chunks at the flight frontier; target=" + target + ".";
            return;
        }
        streamingProgress.reset();
        if (attempt.remainingNodes() < 1) {
            failUnreachable("Flight searches exhausted their shared bounded node budget.");
            return;
        }
        if (streamingLeg) {
            // Stay near the current altitude until the destination's actual blocks are available.
            BlockPosition horizontalTarget = new BlockPosition(target.getX(), start.y(), target.getZ());
            planner = new FlightRoutePlanner(start, horizontalTarget, 0,
                    MAXIMUM_ROUTE_DISTANCE, attempt.remainingNodes(),
                    position -> clearBody(center(position)),
                    position -> clearsReservedPlacement(center(position), reservedPlacementCell)
                            && FlightStreamingProgress.reachedStage(start, position, horizontalTarget,
                                    STREAM_STAGE_PROGRESS, this::receivedBody),
                    (from, to) -> clearSegment(center(from), center(to)));
        } else if (horizontalLeg) {
            BlockPosition horizontalTarget = new BlockPosition(target.getX(), start.y(), target.getZ());
            planner = new FlightRoutePlanner(start, horizontalTarget, 8,
                    MAXIMUM_ROUTE_DISTANCE, FlightAltitudeStaging.nodeBudget(attempt.remainingNodes()),
                    position -> FlightAltitudeStaging.withinAltitudeBand(start, position)
                            && clearBody(center(position)),
                    position -> FlightAltitudeStaging.aboveDestination(position, horizontalTarget)
                            && clearsReservedPlacement(center(position), reservedPlacementCell),
                    (from, to) -> clearSegment(center(from), center(to)));
        } else if (!chunkReceiptGoal && start.y() - descentApproachY() >= MINIMUM_STAGED_DESCENT) {
            descentExitY = descentApproachY();
            descentProbe = new FlightDescentProbe(start, descentExitY,
                    (from, to) -> clearSegment(center(from), center(to)));
            state = State.PROBING_DESCENT;
            detail = descentProbe.detail() + targetDescription();
            return;
        } else {
            planner = new FlightRoutePlanner(start, destination,
                    reachRadius + (feetGoal ? 0.2 : eyeHeight),
                    MAXIMUM_ROUTE_DISTANCE, attempt.remainingNodes(),
                    position -> clearBody(center(position)),
                    position -> goal(center(position), Math.max(0.1, reachRadius - 0.2)),
                    (from, to) -> clearSegment(center(from), center(to)));
        }
        attempt.accountNodes(planner.discoveredNodes());
        if (planner.startObstructed()) {
            // Not a missing route to this target: every other target would fail from here as well.
            fail(planner.detail());
            return;
        }
        if (planner.failed()) {
            if (horizontalLeg) { settleFailedStaging(current); return; }
            failUnreachable(planner.detail());
            return;
        }
        state = State.SEARCHING;
        detail = planningDetail();
    }

    /**
     * The destination's column can be walled off at this altitude, as below a chest standing on a
     * roof over stacked farm planes. Stage beside it when the search came that close; otherwise plan
     * the rest directly from here with the nodes the staging search left.
     */
    private void settleFailedStaging(Vec3d current) {
        horizontalStagePending = false;
        List<BlockPosition> partial = FlightAltitudeStaging.fallbackStage(planner.pathToClosest(),
                new BlockPosition(target.getX(), target.getY(), target.getZ()));
        if (partial.isEmpty()) {
            prepareNextLeg(current);
            return;
        }
        // Following it keeps horizontalLeg, so its end plans the next leg without staging.
        followRoute(partial);
    }

    void tick() {
        if (!active()) { return; }
        if (!validIdentityAndFlight()) { return; }
        if (!MinecraftBackgroundBuildAccess.allowsWorldActions(client)) {
            waitForScreen();
            return;
        }
        if (manualMovementPressed()) {
            fail("Manual movement input interrupted the flight route.");
            return;
        }
        if (!attempt.tick()) {
            fail("Flight route exceeded its bounded duration.");
            return;
        }
        Vec3d current = player.getPos();
        if (lastPosition != null && current.squaredDistanceTo(lastPosition) > 4.0) {
            fail("The server moved the player outside the expected flight segment; route stopped.");
            return;
        }
        if (lastPosition != null && current.squaredDistanceTo(lastPosition) > 1.0e-4) { progressMarker++; }
        lastPosition = current;
        if (goal(current, reachRadius)) {
            if (streamingLeg) { progressMarker++; }
            arrive();
            return;
        }
        if (state == State.PROBING_DESCENT) {
            releaseOwnedVelocity();
            int inspectedBefore = descentProbe.inspected();
            descentProbe.tick(DESCENT_PROBE_BUDGET);
            if (descentProbe.inspected() > inspectedBefore) { progressMarker++; }
            detail = descentProbe.detail() + targetDescription();
            if (descentProbe.complete()) {
                descentLeg = descentProbe.blocked() ? DescentLeg.TO_BARRIER : DescentLeg.TO_APPROACH;
                if (descentProbe.blocked()) {
                    descentEscape = new FlightDescentEscape(descentProbe.path().getLast().y(),
                            world.getTopYInclusive() - 1);
                }
                followRoute(descentProbe.path());
            }
            return;
        }
        if (state == State.SEARCHING_DESCENT) {
            tickDescentSearch();
            return;
        }
        if (state == State.WAITING_FOR_DESCENT_CHUNKS) {
            releaseOwnedVelocity();
            FlightStreamingProgress.WaitState wait = streamingProgress.await(
                    receivedBody(descentFrontierRequest));
            if (wait == FlightStreamingProgress.WaitState.TIMED_OUT) {
                fail("The server did not stream the adjacent descent-search chunk within the bounded wait.");
            } else if (wait == FlightStreamingProgress.WaitState.READY) {
                progressMarker++;
                beginDescentSearch(current);
            } else {
                detail = "Waiting for an adjacent descent-search chunk: " + streamingProgress.waitTicks()
                        + "/" + MAXIMUM_STREAM_WAIT_TICKS + " ticks; frontier=" + descentFrontierRequest + ".";
            }
            return;
        }
        if (state == State.WAITING_FOR_CHUNKS) {
            releaseOwnedVelocity();
            boolean continuation = loaded(target.getX() >> 4, target.getZ() >> 4)
                    || !FlightStreamingProgress.atFrontier(cell(current),
                    new BlockPosition(target.getX(), target.getY(), target.getZ()), this::receivedBody);
            FlightStreamingProgress.WaitState wait = streamingProgress.await(continuation);
            if (wait == FlightStreamingProgress.WaitState.TIMED_OUT) {
                fail("The server did not stream the next flight chunks within the bounded wait.");
            } else if (wait == FlightStreamingProgress.WaitState.READY) {
                progressMarker++;
                prepareNextLeg(current);
            } else {
                detail = "Waiting for received chunks: " + streamingProgress.waitTicks() + "/"
                        + MAXIMUM_STREAM_WAIT_TICKS + " ticks; target=" + target + ".";
            }
            return;
        }
        if (streamingLeg && loaded(target.getX() >> 4, target.getZ() >> 4)) {
            progressMarker++;
            prepareNextLeg(current);
            return;
        }
        if (state == State.SEARCHING) {
            releaseOwnedVelocity();
            int expandedBefore = planner.expandedNodes();
            int discoveredBefore = planner.discoveredNodes();
            planner.tick(SEARCH_BUDGET_PER_TICK);
            attempt.accountNodes(planner.discoveredNodes() - discoveredBefore);
            if (planner.expandedNodes() > expandedBefore) { progressMarker++; }
            detail = planningDetail();
            if (planner.failed()) {
                if (horizontalLeg) { settleFailedStaging(current); }
                else { failUnreachable(planner.detail()); }
                return;
            }
            if (!planner.complete()) { return; }
            followRoute(planner.path());
        }
        follow(current);
    }

    void waitForScreen() {
        releaseOwnedVelocity();
        if (active()) { detail = "Flight route is waiting for the inventory cursor or container to clear."; }
    }

    void stop() {
        releaseOwnedVelocity();
        state = State.IDLE;
        planner = null;
        descentProbe = null;
        descentSearch = null;
        descentEscape = null;
        descentLeg = DescentLeg.NONE;
        descentFrontierRequest = null;
        descentFrontiers.clear();
        route = List.of();
        detail = "";
        lastArrivalRejection = "";
        attempt = null;
        player = null;
        world = null;
        lastPosition = null;
        routeOrigin = null;
        streamingLeg = false;
        horizontalLeg = false;
        horizontalStagePending = false;
        tillTopFaceAim = false;
        routeUnavailable = false;
        streamingProgress.reset();
    }

    boolean active() {
        return state == State.SEARCHING || state == State.FOLLOWING || state == State.WAITING_FOR_CHUNKS
                || state == State.PROBING_DESCENT || state == State.SEARCHING_DESCENT
                || state == State.WAITING_FOR_DESCENT_CHUNKS;
    }
    boolean arrived() { return state == State.ARRIVED; }
    boolean failed() { return state == State.FAILED; }
    /** The route failed because its bounded searches found no usable path to the target. */
    boolean failedForLackOfRoute() { return state == State.FAILED && routeUnavailable; }
    boolean activeInteractionRoute() {
        return activeInteractionRoute(active(), feetGoal, chunkReceiptGoal);
    }
    static boolean activeInteractionRoute(boolean active, boolean feetGoal, boolean chunkReceiptGoal) {
        return active && !feetGoal && !chunkReceiptGoal;
    }
    boolean followingInteractionRoute() {
        return state == State.FOLLOWING && !feetGoal && !chunkReceiptGoal && waypoint < route.size();
    }
    String detail() { return detail; }
    long progressMarker() { return progressMarker; }

    boolean retryInteractionArrival(String rejection) {
        if (state != State.ARRIVED || feetGoal || chunkReceiptGoal) {
            fail("An alternate interaction arrival requires a reached interaction route.");
            return false;
        }
        return retryArrival(rejection);
    }

    private boolean retryArrival(String rejection) {
        lastArrivalRejection = rejection == null || rejection.isBlank()
                ? "The arrival no longer satisfies the clear-body, reserved-cell and exact-ray guards" :
                rejection.substring(0, Math.min(rejection.length(), 384));
        if (!validIdentityAndFlight()) { return false; }
        if (manualMovementPressed()) {
            fail("Manual movement input interrupted the alternate flight approach.");
            return false;
        }
        Vec3d current = player.getPos();
        if (lastPosition != null && current.squaredDistanceTo(lastPosition) > 4.0) {
            fail("The server moved the player outside the expected flight segment; route stopped.");
            return false;
        }
        if (!attempt.durationAvailable() || attempt.remainingNodes() < 1) {
            failUnreachable("The interaction approach exhausted its original route budget.");
            return false;
        }
        if (!attempt.excludeArrival(cell(current))) {
            failUnreachable("No exact interaction ray after bounded alternate flight arrivals.");
            return false;
        }
        // Retain the original position, tick/node budgets, identity and failed arrival cells.
        lastPosition = current;
        prepareNextLeg(current);
        return !failed();
    }

    private int descentApproachY() {
        return feetGoal ? target.getY() : Math.min(world.getTopYInclusive() - 1, target.getY() + 2);
    }

    private void followRoute(List<BlockPosition> plannedRoute) {
        route = plannedRoute;
        waypoint = 0;
        stalledTicks = 0;
        bestWaypointDistance = Double.POSITIVE_INFINITY;
        state = State.FOLLOWING;
    }

    private void beginDescentSearch(Vec3d current) {
        releaseOwnedVelocity();
        route = List.of();
        int remainingNodes = attempt.remainingNodes();
        if (remainingNodes < 1) {
            failUnreachable("Descent-column searches exhausted their shared bounded node budget.");
            return;
        }
        descentSearch = new FlightDescentSearch(cell(current), descentExitY, remainingNodes,
                position -> withinRouteBound(center(position)), this::receivedBody,
                position -> clearBody(center(position)),
                (from, to) -> clearsReservedPlacement(center(to), reservedPlacementCell)
                        && clearSegment(center(from), center(to)),
                position -> !descentFrontiers.contains(chunkKey(position)));
        attempt.accountNodes(descentSearch.discoveredNodes());
        if (descentSearch.failed()) {
            failUnreachable(descentSearch.detail());
            return;
        }
        descentLeg = DescentLeg.NONE;
        state = State.SEARCHING_DESCENT;
        detail = descentSearch.detail() + targetDescription();
    }

    private void tickDescentSearch() {
        releaseOwnedVelocity();
        int inspectedBefore = descentSearch.inspected();
        int discoveredBefore = descentSearch.discoveredNodes();
        descentSearch.tick(DESCENT_SEARCH_BUDGET);
        attempt.accountNodes(descentSearch.discoveredNodes() - discoveredBefore);
        if (descentSearch.inspected() > inspectedBefore) { progressMarker++; }
        detail = descentSearch.detail() + targetDescription();
        if (descentSearch.failed()) {
            boolean exhaustedPlane = descentSearch.failureReason() == FlightDescentSearch.FailureReason.NO_ROUTE;
            if (exhaustedPlane && tryHigherDescentPlane()) { return; }
            failUnreachable(descentSearch.detail() + (exhaustedPlane && descentEscape != null
                    ? " " + descentEscape.detail() : ""));
            return;
        }
        if (!descentSearch.complete()) { return; }
        if (descentSearch.needsChunks()) {
            descentFrontierRequest = descentSearch.requestedFrontier();
            descentFrontiers.add(chunkKey(descentFrontierRequest));
            descentLeg = DescentLeg.TO_RECEIVED_FRONTIER;
        } else {
            descentLeg = DescentLeg.TO_APPROACH;
        }
        followRoute(descentSearch.path());
    }

    private boolean tryHigherDescentPlane() {
        if (descentEscape == null || attempt.remainingNodes() < 1) { return false; }
        Vec3d current = player.getPos();
        List<BlockPosition> escape = descentEscape.nextPath(cell(current),
                (from, to) -> (reservedPlacementCell == null
                        || !sweptBody(current, center(to)).intersects(new Box(reservedPlacementCell)))
                        && clearSegment(current, center(to)));
        if (escape.isEmpty()) { return false; }
        descentLeg = DescentLeg.TO_HIGHER_CLEARANCE;
        detail = descentEscape.detail();
        followRoute(escape);
        return true;
    }

    private static long chunkKey(BlockPosition position) {
        return ((long) Math.floorDiv(position.x(), 16) << 32)
                ^ (Math.floorDiv(position.z(), 16) & 0xffffffffL);
    }

    private void follow(Vec3d current) {
        while (waypoint < route.size()
                && current.squaredDistanceTo(center(route.get(waypoint))) < 0.01) {
            waypoint++;
            stalledTicks = 0;
            bestWaypointDistance = Double.POSITIVE_INFINITY;
        }
        if (waypoint >= route.size()) {
            if (goal(current, reachRadius)) { arrive(); }
            else if (descentLeg == DescentLeg.TO_BARRIER) { beginDescentSearch(current); }
            else if (descentLeg == DescentLeg.TO_HIGHER_CLEARANCE) { beginDescentSearch(current); }
            else if (descentLeg == DescentLeg.TO_RECEIVED_FRONTIER) {
                releaseOwnedVelocity();
                streamingProgress.reset();
                state = State.WAITING_FOR_DESCENT_CHUNKS;
                detail = "Waiting for received data beyond the reached descent-search boundary.";
            }
            else if (descentLeg == DescentLeg.TO_APPROACH) { prepareNextLeg(current); }
            else if (horizontalLeg) {
                horizontalStagePending = false;
                prepareNextLeg(current);
            }
            else if (streamingLeg) { prepareNextLeg(current); }
            else if (!feetGoal && !chunkReceiptGoal) {
                retryArrival(interactionRay(current, reachRadius).detail());
            }
            else { fail("Flight endpoint no longer provides a clear interaction with the target."); }
            return;
        }
        int advanced = FlightRouteLookahead.select(waypoint, route.size(),
                index -> clearSegment(current, center(route.get(index))));
        if (advanced > waypoint) {
            waypoint = advanced;
            stalledTicks = 0;
            bestWaypointDistance = Double.POSITIVE_INFINITY;
        }
        Vec3d destination = center(route.get(waypoint));
        if (!clearSegment(current, destination)) {
            fail("A flight segment became obstructed or unloaded; route stopped before movement.");
            return;
        }
        double distance = current.distanceTo(destination);
        if (distance + 0.02 < bestWaypointDistance) {
            bestWaypointDistance = distance;
            stalledTicks = 0;
        } else if (++stalledTicks > MAXIMUM_STALLED_TICKS) {
            fail("No confirmed flight progress; the server may be rejecting or correcting movement.");
            return;
        }
        Vec3d velocity = nextVelocity(current, destination);
        if (!clearSegment(current, current.add(velocity))) {
            fail("The next flight movement is not collision-free.");
            return;
        }
        player.setVelocity(velocity);
        ownedVelocity = velocity;
        detail = (descentLeg == DescentLeg.TO_BARRIER ? "Descending to the verified clearance above an obstacle"
                : descentLeg == DescentLeg.TO_HIGHER_CLEARANCE ? "Flying to a verified higher descent-search plane"
                : descentLeg == DescentLeg.TO_RECEIVED_FRONTIER ? "Flying to a received descent-search boundary"
                : descentLeg == DescentLeg.TO_APPROACH ? "Following the verified descent approach"
                : streamingLeg ? "Flying through received chunks"
                : horizontalLeg ? "Flying above the destination before changing altitude" : "Flying to target")
                + ": waypoint " + (waypoint + 1) + "/" + route.size() + ".";
    }

    static Vec3d nextVelocity(Vec3d current, Vec3d destination) {
        Vec3d delta = destination.subtract(current);
        double horizontal = Math.sqrt(delta.x * delta.x + delta.z * delta.z);
        double vertical = Math.abs(delta.y);
        // One factor keeps the step on the straight segment and never passes the destination.
        double scale = Math.min(1.0, Math.min(
                horizontal > 0 ? HORIZONTAL_SPEED_PER_TICK / horizontal : Double.POSITIVE_INFINITY,
                vertical > 0 ? VERTICAL_SPEED_PER_TICK / vertical : Double.POSITIVE_INFINITY));
        return delta.multiply(scale);
    }

    private boolean validIdentityAndFlight() {
        if (player == null || world == null || client.player != player || client.world != world) {
            fail("Player or world identity changed during flight navigation.");
            return false;
        }
        if (!player.getAbilities().allowFlying || !player.getAbilities().flying) {
            fail("Flight must remain enabled and active; this route cannot grant or toggle flight.");
            return false;
        }
        if (!movementAllowed.test(player.getBoundingBox())) {
            fail("The current position is outside this operation's permitted flight space.");
            return false;
        }
        return true;
    }

    private boolean goal(Vec3d feet, double radius) {
        boolean targetReceived = loaded(target.getX() >> 4, target.getZ() >> 4);
        if (chunkReceiptGoal || feetGoal) {
            return targetReceived && clearsReservedPlacement(feet, reservedPlacementCell)
                    && (chunkReceiptGoal || withinFeetRadius(feet, target, radius)) && clearBody(feet);
        }
        return interactionArrival(feet, feet.add(0, eyeHeight, 0), target, radius,
                reservedPlacementCell, targetReceived,
                box -> attempt.permitsArrival(cell(feet)) && withinRouteBound(feet) && clearBox(box),
                () -> interactionRay(feet, radius).accepted());
    }

    static boolean interactionArrival(Vec3d feet, Vec3d eye, BlockPos target, double radius,
                                      BlockPos reservedPlacementCell, boolean targetReceived,
                                      Predicate<Box> clearBody, BooleanSupplier exactRay) {
        if (!Double.isFinite(radius) || radius <= 0 || radius > 6) { return false; }
        // Match the exact ray's cheap range rejection before querying body collisions for each face.
        Vec3d nearest = new Vec3d(Math.clamp(eye.x, target.getX(), target.getX() + 1.0),
                Math.clamp(eye.y, target.getY(), target.getY() + 1.0),
                Math.clamp(eye.z, target.getZ(), target.getZ() + 1.0));
        return eye.squaredDistanceTo(nearest) <= radius * radius && targetReceived
                && clearsReservedPlacement(feet, reservedPlacementCell)
                && clearBody.test(body(feet)) && exactRay.getAsBoolean();
    }

    private ExactInteractionRay.Result interactionRay(Vec3d feet, double radius) {
        Vec3d eye = feet.add(0, eyeHeight, 0);
        return tillTopFaceAim
                ? ExactInteractionRay.traceTillTopFace(world, player, eye, target, radius)
                : ExactInteractionRay.trace(world, player, eye, target, requiredFace, radius);
    }

    private boolean clearBody(Vec3d feet) {
        return withinRouteBound(feet) && clearBox(body(feet));
    }

    private boolean clearSegment(Vec3d from, Vec3d to) {
        return withinRouteBound(from) && withinRouteBound(to) && clearBox(sweptBody(from, to));
    }

    static Box sweptBody(Vec3d from, Vec3d to) {
        Box first = body(from);
        Box second = body(to);
        return new Box(Math.min(first.minX, second.minX), Math.min(first.minY, second.minY),
                Math.min(first.minZ, second.minZ), Math.max(first.maxX, second.maxX),
                Math.max(first.maxY, second.maxY), Math.max(first.maxZ, second.maxZ));
    }

    private boolean clearBox(Box box) {
        return movementAllowed.test(box) && receivedBox(box) && world.isSpaceEmpty(player, box);
    }

    private boolean receivedBody(BlockPosition position) { return receivedBox(body(center(position))); }

    private boolean receivedBox(Box box) {
        return receivedBox(world, box);
    }

    private static boolean receivedBox(ClientWorld world, Box box) {
        if (box.minY < world.getBottomY() || box.maxY > world.getTopYInclusive() + 1) { return false; }
        int minChunkX = ((int) Math.floor(box.minX)) >> 4;
        int maxChunkX = ((int) Math.floor(box.maxX - 1.0e-7)) >> 4;
        int minChunkZ = ((int) Math.floor(box.minZ)) >> 4;
        int maxChunkZ = ((int) Math.floor(box.maxZ - 1.0e-7)) >> 4;
        for (int x = minChunkX; x <= maxChunkX; x++) {
            for (int z = minChunkZ; z <= maxChunkZ; z++) {
                if (!ClientChunkAvailability.isLoaded(world, x, z)) { return false; }
            }
        }
        return true;
    }

    private boolean loaded(int chunkX, int chunkZ) {
        return ClientChunkAvailability.isLoaded(world, chunkX, chunkZ);
    }

    private boolean withinRouteBound(Vec3d feet) {
        return routeOrigin == null || center(routeOrigin).squaredDistanceTo(feet)
                <= (double) MAXIMUM_ROUTE_DISTANCE * MAXIMUM_ROUTE_DISTANCE;
    }

    private String planningDetail() {
        String phase = streamingLeg ? "Staging within received chunks. "
                : horizontalLeg ? "Planning the horizontal approach before changing altitude. " : "";
        return phase + planner.detail() + targetDescription();
    }

    private String targetDescription() {
        String targetState = loaded(target.getX() >> 4, target.getZ() >> 4)
                ? world.getBlockState(target).toString() : "not received";
        return " Target=" + target + ", state=" + targetState + ".";
    }

    private boolean manualMovementPressed() {
        return client.options.forwardKey.isPressed() || client.options.backKey.isPressed()
                || client.options.leftKey.isPressed() || client.options.rightKey.isPressed()
                || client.options.jumpKey.isPressed() || client.options.sneakKey.isPressed();
    }

    private void releaseOwnedVelocity() {
        if (ownedVelocity == null) { return; }
        // Do not erase a different entity's motion or an unrelated external impulse.
        if (player != null && player == client.player && world == client.world
                && remainderOfCommand(player.getVelocity(), ownedVelocity)) {
            player.setVelocity(Vec3d.ZERO);
        }
        ownedVelocity = null;
    }

    /**
     * Whether a velocity is what vanilla flight leaves of a commanded step: each tick keeps 91% of
     * the horizontal and 60% of the vertical speed, and a collision zeroes an axis. A released
     * 0.375 descent still sinks 0.225 the next tick and over half a block in all, which lands the
     * player from a hover cell 0.1 above a floor, and landing switches flight off.
     */
    static boolean remainderOfCommand(Vec3d velocity, Vec3d commanded) {
        double x = beyondRemainder(velocity.x, commanded.x);
        double y = beyondRemainder(velocity.y, commanded.y);
        double z = beyondRemainder(velocity.z, commanded.z);
        return x * x + y * y + z * z <= RELEASE_TOLERANCE * RELEASE_TOLERANCE;
    }

    /** How far a component lies outside the range from zero to its commanded value. */
    private static double beyondRemainder(double component, double commanded) {
        return Math.max(0, Math.max(Math.min(0, commanded) - component, component - Math.max(0, commanded)));
    }

    private void arrive() {
        releaseOwnedVelocity();
        state = State.ARRIVED;
        detail = chunkReceiptGoal ? "The requested world chunk has been received; flight approach stopped."
                : feetGoal ? "Returned within the saved safe-position feet radius."
                : "Reached the target's clear interaction range using existing flight.";
    }

    private void failUnreachable(String problem) {
        fail(problem);
        routeUnavailable = true;
    }

    private void fail(String problem) {
        releaseOwnedVelocity();
        state = State.FAILED;
        routeUnavailable = false;
        if (lastArrivalRejection.isEmpty() && !feetGoal && !chunkReceiptGoal && target != null
                && player != null && world != null && player == client.player && world == client.world) {
            lastArrivalRejection = interactionRay(player.getPos(), reachRadius).detail();
        }
        detail = problem + (lastArrivalRejection.isEmpty() ? "" : " " + lastArrivalRejection)
                + (attempt == null ? "" : " Excluded arrivals=" + attempt.excludedCount()
                + "/" + MAXIMUM_EXCLUDED_ARRIVALS + ", nodes=" + attempt.nodesUsed()
                + "/" + MAXIMUM_SEARCH_NODES + ", activeTicks=" + attempt.activeTicks()
                + "/" + MAXIMUM_ACTIVE_TICKS + ".") + routeContext();
    }

    private String routeContext() {
        if (target == null) { return ""; }
        StringBuilder context = new StringBuilder(" Target=(")
                .append(target.getX()).append(',').append(target.getY()).append(',')
                .append(target.getZ()).append(')');
        try {
            if (player != null && player == client.player) {
                context.append(", player=(").append(Math.round(player.getX() * 10.0) / 10.0)
                        .append(',').append(Math.round(player.getY() * 10.0) / 10.0)
                        .append(',').append(Math.round(player.getZ() * 10.0) / 10.0).append(')');
            }
            if (world != null && world == client.world) {
                context.append(", worldY=").append(world.getBottomY()).append("..")
                        .append(world.getTopYInclusive());
                context.append(", targetState=").append(loaded(target.getX() >> 4, target.getZ() >> 4)
                        ? world.getBlockState(target) : "unloaded");
            }
        } catch (RuntimeException unavailable) {
            context.append(", world inspection unavailable");
        }
        return context.append('.').toString();
    }

    static boolean withinFeetRadius(Vec3d feet, BlockPos target, double radius) {
        Vec3d hover = center(new BlockPosition(target.getX(), target.getY(), target.getZ()));
        return feet.squaredDistanceTo(hover) <= radius * radius;
    }

    static boolean clearsReservedPlacement(Vec3d feet, BlockPos reserved) {
        return reserved == null || !body(feet).intersects(new Box(reserved));
    }

    static Box body(Vec3d feet) {
        return new Box(feet.x - BODY_HALF_WIDTH, feet.y, feet.z - BODY_HALF_WIDTH,
                feet.x + BODY_HALF_WIDTH, feet.y + BODY_HEIGHT, feet.z + BODY_HALF_WIDTH);
    }

    static Vec3d center(BlockPosition position) {
        // Keep feet above solid floors so following a grid cell never lands and disables flight.
        return new Vec3d(position.x() + 0.5, position.y() + 0.1, position.z() + 0.5);
    }

    private static BlockPosition cell(Vec3d feet) {
        return new BlockPosition((int) Math.floor(feet.x), (int) Math.floor(feet.y),
                (int) Math.floor(feet.z));
    }

    private enum DescentLeg { NONE, TO_BARRIER, TO_APPROACH, TO_RECEIVED_FRONTIER, TO_HIGHER_CLEARANCE }
    private enum State {
        IDLE, SEARCHING, FOLLOWING, WAITING_FOR_CHUNKS, PROBING_DESCENT, SEARCHING_DESCENT,
        WAITING_FOR_DESCENT_CHUNKS, ARRIVED, FAILED
    }
}
