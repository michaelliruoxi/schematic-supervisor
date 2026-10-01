package io.github.schematicsupervisor.fabric;

import net.minecraft.util.math.Vec3d;

/**
 * Test model of Minecraft 1.21.8 creative-style flight for one client tick with no movement keys held
 * (PlayerEntity.travel and LivingEntity.travelMidAir, checked against the bytecode): the player moves
 * by its velocity, then flight keeps 91% of the horizontal and 60% of the vertical speed. Touching a
 * floor while flying switches flight off (ClientPlayerEntity.tickMovement). The mod ticks after this.
 */
final class VanillaFlight {
    static final double HORIZONTAL_DRAG = 0.91;
    static final double VERTICAL_DRAG = 0.6;

    record State(Vec3d feet, Vec3d velocity, boolean flying) { }

    private VanillaFlight() {
    }

    static Vec3d drag(Vec3d velocity) {
        return new Vec3d(velocity.x * HORIZONTAL_DRAG, velocity.y * VERTICAL_DRAG, velocity.z * HORIZONTAL_DRAG);
    }

    /** One tick over a flat floor whose top is at {@code floorY}. */
    static State tick(State state, double floorY) {
        Vec3d moved = state.feet().add(state.velocity());
        boolean landed = moved.y <= floorY;
        Vec3d feet = landed ? new Vec3d(moved.x, floorY, moved.z) : moved;
        Vec3d velocity = landed ? new Vec3d(state.velocity().x, 0, state.velocity().z) : state.velocity();
        return new State(feet, drag(velocity), state.flying() && !landed);
    }
}
