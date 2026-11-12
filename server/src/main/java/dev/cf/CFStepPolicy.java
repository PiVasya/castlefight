package dev.cf;

final class CFStepPolicy {
    static final double MAX_STEP = .6;
    static boolean acceptStep(double rise, double plainProgress, double stepProgress, boolean landed) {
        return landed && Double.isFinite(rise) && rise > .001 && rise <= MAX_STEP + .001 &&
                stepProgress > plainProgress + .000001;
    }
    static boolean mayJump(double rise, boolean blocked, boolean grounded, boolean locked, long ticksSince) {
        return !locked && grounded && blocked && rise > MAX_STEP && rise <= 1.25 && ticksSince >= 12;
    }
    private CFStepPolicy() { }
}
