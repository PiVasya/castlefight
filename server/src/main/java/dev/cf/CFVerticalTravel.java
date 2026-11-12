package dev.cf;

/**
 * One policy for short terrain changes along an already validated CF lane.
 * This does not relax beacon placement, allow flight, or certify a new route.
 * Every actual step still needs a dry landing and collision-free headroom.
 */
final class CFVerticalTravel {
    /** A walker may step off a ledge with a landing at most two blocks below. */
    static final double MAX_DROP = 2.0;
    /** Full-block ascent needs a normal jump or a sequence of smaller steps. */
    static final double MAX_RISE = 1.0;
    /** Route height is a guide; feet may lag slightly during a short fall/jump. */
    static final double ROUTE_HEIGHT_TOLERANCE = 2.25;
    private static final double EPSILON = .011;
    private static final double AIRBORNE_JUMP_MARGIN = 1.15;

    /** Probe slightly past a possible two-block landing surface for robust collision. */
    static double downwardProbe(boolean airborne) {
        return MAX_DROP + (airborne ? AIRBORNE_JUMP_MARGIN : 0) + .10;
    }

    /** Prevent a descending walker from treating a deep pit as a safe lane step. */
    static boolean landingWithinDrop(double feetY, double landingY, boolean airborne) {
        if (!Double.isFinite(feetY) || !Double.isFinite(landingY)) return false;
        double drop = feetY - landingY;
        return drop >= -.05 && drop <= MAX_DROP + (airborne ? AIRBORNE_JUMP_MARGIN : 0) + EPSILON;
    }

    /** Node generator: up one (jump), down two (gravity); never climb two straight up. */
    static boolean nodeHeightAllowed(double fromY, double toY) {
        if (!Double.isFinite(fromY) || !Double.isFinite(toY)) return false;
        double change = toY - fromY;
        return change <= MAX_RISE + EPSILON && change >= -MAX_DROP - EPSILON;
    }

    private CFVerticalTravel() { }
}
