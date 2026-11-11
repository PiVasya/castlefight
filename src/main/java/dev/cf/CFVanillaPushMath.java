package dev.cf;

/**
 * Minecraft Java Edition's horizontal entity-versus-entity push calculation.
 * This intentionally follows the vanilla normalization, threshold and 0.05F
 * strength instead of an overlap-depth or fixed-distance escape force.
 * The result is in blocks per tick (convert for Minestom velocity at call site).
 */
final class CFVanillaPushMath {
    private static final double MIN_SEPARATION = 0.009999999776482582D;
    private static final double PUSH_STRENGTH = (double) 0.05F;
    private CFVanillaPushMath() { }

    record Impulse(double x, double z) {
        boolean isZero() { return x == 0.0 && z == 0.0; }
    }

    static Impulse fromCenterDelta(double dx, double dz) {
        double maximum = Math.max(Math.abs(dx), Math.abs(dz));
        if (!(maximum >= MIN_SEPARATION)) return new Impulse(0, 0);
        double root = Math.sqrt(maximum);
        double inverse = Math.min(1.0, 1.0 / root);
        return new Impulse(dx / root * inverse * PUSH_STRENGTH,
                dz / root * inverse * PUSH_STRENGTH);
    }
}
