package io.github.schematicsupervisor.fabric;

import java.util.Objects;
import java.util.function.BiFunction;
import java.util.function.BiPredicate;
import java.util.function.Supplier;
import net.minecraft.block.ShapeContext;
import net.minecraft.block.Blocks;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.registry.Registries;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.world.RaycastContext;

/** One read-only interaction test shared by route selection and the final click guard. */
final class ExactInteractionRay {
    private static final double BOTTOM_FACE_INSET = 0.001;
    private static final double TILL_TOP_FACE_INSET = 0.001;

    private ExactInteractionRay() { }

    /** Tilling dirt must aim above adjacent soil instead of through the target's center. */
    static Result traceTillTopFace(ClientWorld world, ClientPlayerEntity player, Vec3d eye,
                                   BlockPos target, double reach) {
        return traceTillTopFace(eye, target, reach,
                (x, z) -> ClientChunkAvailability.isLoaded(world, x, z),
                (from, to) -> world.raycast(new RaycastContext(from, to,
                        RaycastContext.ShapeType.OUTLINE, RaycastContext.FluidHandling.NONE, player)));
    }

    static Result traceTillTopFace(Vec3d eye, BlockPos target, double reach,
                                   BiPredicate<Integer, Integer> received,
                                   BiFunction<Vec3d, Vec3d, BlockHitResult> raycast) {
        return trace(eye, target, Direction.UP, reach, received, raycast,
                () -> new Vec3d(target.getX() + 0.5, target.getY() + 1.0 - TILL_TOP_FACE_INSET,
                        target.getZ() + 0.5));
    }

    static Result trace(ClientWorld world, ClientPlayerEntity player, Vec3d eye,
                        BlockPos target, Direction requiredFace, double reach) {
        return trace(eye, target, requiredFace, reach,
                (x, z) -> ClientChunkAvailability.isLoaded(world, x, z),
                (from, to) -> world.raycast(new RaycastContext(from, to,
                        RaycastContext.ShapeType.OUTLINE, RaycastContext.FluidHandling.NONE, player)), () -> {
                    // Read the target outline only after the common received-chunk and reach checks.
                    var state = world.getBlockState(target);
                    if (requiredFace == Direction.UP && state.isOf(Blocks.FARMLAND)) {
                        return farmlandTopEndpoint(target, state.getOutlineShape(world, target, ShapeContext.of(player)));
                    }
                    if (requiredFace != null) { return defaultEndpoint(target, requiredFace); }
                    String blockId = Registries.BLOCK.getId(state.getBlock()).toString();
                    return StemClearingSweep.isStem(blockId)
                            ? stemEndpoint(target, blockId, state.getOutlineShape(world, target, ShapeContext.of(player)))
                            : defaultEndpoint(target, null);
                });
    }

    static Result trace(Vec3d eye, BlockPos target, Direction requiredFace, double reach,
                        BiPredicate<Integer, Integer> received,
                        BiFunction<Vec3d, Vec3d, BlockHitResult> raycast) {
        return trace(eye, target, requiredFace, reach, received, raycast,
                () -> defaultEndpoint(target, requiredFace));
    }

    static Result trace(Vec3d eye, BlockPos target, Direction requiredFace, double reach,
                        BiPredicate<Integer, Integer> received,
                        BiFunction<Vec3d, Vec3d, BlockHitResult> raycast, Supplier<Vec3d> endpoint) {
        Objects.requireNonNull(eye, "eye");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(received, "received");
        Objects.requireNonNull(raycast, "raycast");
        Objects.requireNonNull(endpoint, "endpoint");
        if (!Double.isFinite(reach) || reach <= 0 || reach > 6
                || !Double.isFinite(eye.x) || !Double.isFinite(eye.y) || !Double.isFinite(eye.z)) {
            throw new IllegalArgumentException("interaction ray requires finite coordinates and bounded reach");
        }
        // Reject distant targets before inspecting a potentially large rectangle of chunks.
        Vec3d nearest = new Vec3d(Math.clamp(eye.x, target.getX(), target.getX() + 1.0),
                Math.clamp(eye.y, target.getY(), target.getY() + 1.0),
                Math.clamp(eye.z, target.getZ(), target.getZ() + 1.0));
        if (eye.squaredDistanceTo(nearest) > reach * reach) {
            return rejected("target is outside interaction reach", eye, target, requiredFace, reach, null);
        }
        int eyeChunkX = ((int) Math.floor(eye.x)) >> 4;
        int eyeChunkZ = ((int) Math.floor(eye.z)) >> 4;
        for (int x = Math.min(eyeChunkX, target.getX() >> 4);
             x <= Math.max(eyeChunkX, target.getX() >> 4); x++) {
            for (int z = Math.min(eyeChunkZ, target.getZ() >> 4);
                 z <= Math.max(eyeChunkZ, target.getZ() >> 4); z++) {
                if (!received.test(x, z)) {
                    return rejected("ray crosses unreceived chunk (" + x + "," + z + ")",
                            eye, target, requiredFace, reach, null);
                }
            }
        }
        BlockHitResult hit = Objects.requireNonNull(raycast.apply(eye, endpoint.get()), "ray result");
        String problem = hit.getType() != HitResult.Type.BLOCK ? "outline ray missed the target"
                : !hit.getBlockPos().equals(target) ? "outline ray hit another block"
                : requiredFace != null && hit.getSide() != requiredFace ? "outline ray reached a different face"
                : eye.squaredDistanceTo(hit.getPos()) > reach * reach ? "outline hit exceeds interaction reach"
                : "";
        return problem.isEmpty() ? new Result(hit, "")
                : rejected(problem, eye, target, requiredFace, reach, hit);
    }

    private static Vec3d defaultEndpoint(BlockPos target, Direction requiredFace) {
        // A center ray from a low corridor can enter an adjacent ceiling block first.
        return requiredFace == Direction.DOWN
                ? new Vec3d(target.getX() + 0.5, target.getY() + BOTTOM_FACE_INSET, target.getZ() + 0.5)
                : Vec3d.ofCenter(target);
    }

    static Vec3d farmlandTopEndpoint(BlockPos target, VoxelShape outline) {
        if (outline.isEmpty()) { return Vec3d.ofCenter(target); }
        return new Vec3d(target.getX() + 0.5,
                target.getY() + outline.getMax(Direction.Axis.Y) - TILL_TOP_FACE_INSET,
                target.getZ() + 0.5);
    }

    static Vec3d stemEndpoint(BlockPos target, String blockId, VoxelShape outline) {
        if (!StemClearingSweep.isStem(blockId) || outline.isEmpty()) { return Vec3d.ofCenter(target); }
        // Young stems are below block center. An actual cuboid center also stays inside
        // a multipart attached stem, whose union bounding-box center can be empty space.
        return outline.getBoundingBoxes().getFirst().getCenter().add(target.getX(), target.getY(), target.getZ());
    }

    private static Result rejected(String problem, Vec3d eye, BlockPos target,
                                   Direction requiredFace, double reach, BlockHitResult hit) {
        String detail = problem + "; target=" + target.toShortString() + "; eye=("
                + rounded(eye.x) + "," + rounded(eye.y) + "," + rounded(eye.z) + "); face="
                + (requiredFace == null ? "any" : requiredFace) + "; reach=" + rounded(reach)
                + (hit == null ? "" : "; hit=" + hit.getType() + "@" + hit.getBlockPos().toShortString()
                + "/" + hit.getSide() + "; hitDistance=" + rounded(eye.distanceTo(hit.getPos())));
        return new Result(null, detail.substring(0, Math.min(detail.length(), 384)));
    }

    private static double rounded(double value) { return Math.round(value * 100.0) / 100.0; }

    record Result(BlockHitResult hit, String detail) {
        boolean accepted() { return hit != null; }
    }
}
