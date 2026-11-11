package dev.cf;

/** Effect stacking rules. Evaluated only when a buff is applied, never per mob tick. */
enum CFBuffPolicy {
    POISON(Mode.STACKABLE),
    BURN(Mode.STACKABLE),
    SLOW(Mode.STACKABLE),
    RESURRECTION(Mode.SINGLE_REFRESH);

    enum Mode { STACKABLE, SINGLE_REFRESH }
    private final Mode mode;
    CFBuffPolicy(Mode mode) { this.mode=mode; }
    Mode mode() { return mode; }
    boolean stackable() { return mode==Mode.STACKABLE; }

    /** Recast of a unique effect extends its deadline but never creates another stack. */
    static long mergeUniqueExpiry(long oldDeadlineNs,long requestedDeadlineNs) {
        return Math.max(oldDeadlineNs,requestedDeadlineNs);
    }
}
