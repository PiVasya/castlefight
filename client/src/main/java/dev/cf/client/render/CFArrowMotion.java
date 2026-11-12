package dev.cf.client.render;

/** Pure, deterministic movement of a client-only decorative projectile. */
public final class CFArrowMotion {
    public static final long NANOSECONDS_PER_TICK = 50_000_000L;
    public static double progress(long elapsedNanos, int flightTicks) {
        if (flightTicks < 1 || flightTicks > 60)
            throw new IllegalArgumentException("Invalid CF arrow duration");
        if (elapsedNanos <= 0) return 0;
        return Math.min(1, (double) elapsedNanos / (flightTicks * (double) NANOSECONDS_PER_TICK));
    }
    private CFArrowMotion() { }
}
