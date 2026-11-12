package dev.cf;

/** Combat vertical filtering is independent of route terrain stepping. */
final class CFCombatHeight {
    static final double MELEE_MAX_DELTA_Y = 3.0;
    static boolean meleeAllowed(double sourceY, double targetY) {
        return Double.isFinite(sourceY) && Double.isFinite(targetY)
                && Math.abs(sourceY - targetY) <= MELEE_MAX_DELTA_Y;
    }
    private CFCombatHeight() { }
}
