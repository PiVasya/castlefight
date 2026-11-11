package dev.cf;

/**
 * One outgoing edge and an intrusive reverse list per entity. No global entity
 * registry, per-link allocation, HashSet, or full-army scan on a death.
 * Entity ticks may run on different Minestom workers: mutate links under one
 * short monitor, read the current edge without locking. Never invoke AI or
 * user callbacks while holding the monitor. A retired node cannot be rebound.
 */
final class CFTargetLinks<T> {
    private static final Object MUTEX = new Object();
    final T owner;
    private volatile CFTargetLinks<T> target;
    private volatile boolean closed;
    private volatile long revision;
    private volatile int inboundCount;
    private CFTargetLinks<T> firstAttacker, previous, next;

    CFTargetLinks(T owner) { this.owner = owner; }
    CFTargetLinks<T> targetNode() { return target; }
    T target() { var n = target; return n == null ? null : n.owner; }
    boolean closed() { return closed; }
    long revision() { return revision; }
    int attackers() { return inboundCount; }

    boolean bind(CFTargetLinks<T> destination) {
        synchronized (MUTEX) {
            if (closed || destination == this || (destination != null && destination.closed)) return false;
            if (target == destination) return true;
            unlink();
            if (destination != null) {
                previous = null;
                next = destination.firstAttacker;
                if (next != null) next.previous = this;
                destination.firstAttacker = this;
                destination.inboundCount++;
                target = destination;
                revision++;
            }
            return true;
        }
    }

    private void unlink() {
        CFTargetLinks<T> old = target;
        if (old == null) return;
        if (previous == null) old.firstAttacker = next;
        else previous.next = next;
        if (next != null) next.previous = previous;
        previous = null;
        next = null;
        old.inboundCount--;
        target = null;
        revision++;
    }

    /** Immediate invalidation only. Attackers re-acquire in their OWN AI tick. */
    int retire() {
        synchronized (MUTEX) {
            if (closed) return 0;
            closed = true;
            unlink();
            int count = inboundCount;
            while (firstAttacker != null) firstAttacker.unlink();
            return count;
        }
    }

    /** World transfer: invalidate both directions without killing the entity. */
    int disconnect() {
        synchronized (MUTEX) {
            unlink();
            int count = inboundCount;
            while (firstAttacker != null) firstAttacker.unlink();
            return count;
        }
    }

    // Used only by invariant tests, not by gameplay or diagnostic sampling.
    boolean consistent() {
        synchronized (MUTEX) {
            int count = 0;
            CFTargetLinks<T> prior = null;
            for (var n = firstAttacker; n != null; n = n.next) {
                if (n.target != this || n.previous != prior || n.closed) return false;
                prior = n;
                if (++count > inboundCount) return false;
            }
            return count == inboundCount && (!closed || (count == 0 && target == null))
                    && (target == null || (!target.closed && !closed));
        }
    }
}
