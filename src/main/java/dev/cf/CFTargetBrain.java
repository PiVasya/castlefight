package dev.cf;

/** Pure targeting policy, also exercised without a Minecraft server. */
final class CFTargetBrain<T> {
    enum Reason { MARCH, MELEE_REVIEW, TARGET_LOST, OUT_OF_RANGE, INVALID, EXTERNAL }
    interface World<T> {
        boolean alive(T unit);
        boolean enemy(T unit, T other);
        boolean inRange(T unit, T other);
        T nearest(T unit);
        CFTargetLinks<T> links(T unit);
        void changed(T old, T next, Reason reason, long tick);
        void scanned(Reason reason);
    }
    private final T owner;
    private final World<T> world;
    private final boolean ranged;
    private final int period, phase;
    final CFTargetLinks<T> links;
    private long lastScanTick = Long.MIN_VALUE;
    private long lastScanRevision = Long.MIN_VALUE;
    private long nextReviewTick;
    private T observed;
    private boolean pending;
    private long scans, cacheHits;

    CFTargetBrain(T owner, World<T> world, boolean ranged, int period, int phase) {
        if (period < 1) throw new IllegalArgumentException("review period must be positive");
        this.owner = owner; this.world = world; this.ranged = ranged;
        this.period = period; this.phase = Math.floorMod(phase, period);
        this.links = new CFTargetLinks<>(owner);
    }
    T peek() { return links.target(); }
    long scans() { return scans; }
    long cacheHits() { return cacheHits; }
    long nextReviewTick() { return nextReviewTick; }
    boolean pending() { return pending; }
    boolean ranged() { return ranged; }

    T resolve(long tick) {
        if (links.closed() || !world.alive(owner)) return null;
        T current = peek();
        Reason reason = current == null && observed != null ? Reason.TARGET_LOST : Reason.MARCH;
        if (current != null) {
            if (!world.enemy(owner, current)) {
                links.bind(null); current = null; reason = Reason.INVALID;
            } else if (ranged && !world.inRange(owner, current)) {
                links.bind(null); current = null; reason = Reason.OUT_OF_RANGE;
            } else if (ranged || tick < nextReviewTick) {
                cacheHits++;
                // The reverse edge may have been invalidated on another worker.
                return peek();
            } else {
                reason = Reason.MELEE_REVIEW;
            }
        }
        long revision = links.revision();
        // shouldEnd, shouldStart and tick can all ask in one entity tick. A death
        // changes revision and therefore bypasses even a same-tick cached miss.
        if (lastScanTick == tick && lastScanRevision == revision) {
            cacheHits++;
            return peek();
        }
        pending = false;
        T selected = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            scans++;
            world.scanned(reason);
            selected = world.nearest(owner);
            if (selected == null) { pending = false; links.bind(null); break; }
            if (world.enemy(owner, selected) && world.inRange(owner, selected)
                    && links.bind(world.links(selected))) break;
            selected = null;
            pending = true;
        }
        if (selected != null) pending = false;
        if (selected == null) links.bind(null);
        lastScanTick = tick;
        lastScanRevision = links.revision();
        nextReviewTick = nextPhaseTick(tick, phase, period);
        T result = peek();
        // Failed concurrent acquisition holds the combat goal for this tick,
        // rather than handing a one-frame opening to the march goal.
        if (selected != null && result == null && world.alive(owner)) pending = true;
        if (observed != result) {
            T old = observed; observed = result;
            world.changed(old, result, reason, tick);
        }
        return result;
    }

    static long nextPhaseTick(long tick, int phase, int period) {
        long first = tick + 1;
        return first + Math.floorMod((long) phase - Math.floorMod(first, period), period);
    }

    /** Used by EntityCreature.setTarget, never an independent target cache. */
    void assign(T selected, long tick) {
        if (selected != null && (!world.enemy(owner, selected) || !world.inRange(owner, selected)))
            throw new IllegalArgumentException("Not a living enemy inside acquisition radius");
        if (!links.bind(selected == null ? null : world.links(selected))) return;
        T old = observed; observed = peek();
        nextReviewTick = nextPhaseTick(tick, phase, period);
        lastScanTick = Long.MIN_VALUE;
        pending = false;
        if (old != observed) world.changed(old, observed, Reason.EXTERNAL, tick);
    }
}
