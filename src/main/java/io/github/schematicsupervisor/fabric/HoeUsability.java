package io.github.schematicsupervisor.fabric;

/** Shared tool rules over observed item identity and optional durability components. */
final class HoeUsability {
    private HoeUsability() { }

    static boolean usable(boolean hoe, boolean unbreakable, Integer damage, Integer maximum) {
        // Missing damage components and UNBREAKABLE make an ItemStack non-damageable.
        // A retained damageable stack at its limit must not count as a replacement tool.
        return hoe && (unbreakable || damage == null || maximum == null || damage < maximum);
    }

    static Integer remaining(boolean hoe, boolean unbreakable, Integer damage, Integer maximum) {
        return !hoe || unbreakable || damage == null || maximum == null || maximum == 0
                ? null : Math.max(0, maximum - damage);
    }
}
