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
    /** Same nominal visual speed as the server, computed locally from entity positions. */
    public static int ticksForDistance(double distance) {
        if (!Double.isFinite(distance) || distance < 0) throw new IllegalArgumentException("Invalid distance");
        return Math.min(21,Math.max(1,(int)Math.ceil(distance/1.5)));
    }
    private CFArrowMotion() { }
}
