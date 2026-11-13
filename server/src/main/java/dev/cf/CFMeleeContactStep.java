package dev.cf;

/**
 * Two tiny walking ticks after each melee swing. No miss detection, new target
 * search or pathfinding: the existing ground follower performs block collisions.
 */
final class CFMeleeContactStep {
    static final double STRIDE_PER_TICK = 0.15;
    private static final int TICKS_PER_SWING = 2;
    private int remaining;

    void swing() { remaining = TICKS_PER_SWING; }
    boolean takeStep() {
        if (remaining == 0) return false;
        remaining--;
        return true;
    }
    void clear() { remaining = 0; }
}
