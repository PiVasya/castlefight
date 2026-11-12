package dev.cf;

import net.minestom.server.coordinate.Pos;
import net.minestom.server.entity.Entity;
import net.minestom.server.instance.EntityTracker;
import java.util.function.Consumer;

/** Minestom adapter for the pure policy; one reusable query visitor per unit. */
final class CFTargeting implements CFTargetBrain.World<CFUnit>, Consumer<Entity> {
    static final int MELEE_REVIEW_TICKS = reviewPeriod();
    private final CFUnit unit;
    final CFTargetBrain<CFUnit> brain;
    private final double radius, radiusSquared;
    private final boolean ranged;
    private CFUnit best;
    private Pos origin;
    private double bestSquared;
    private long candidates;

    private static int reviewPeriod() {
        int value = Integer.getInteger("cf.target.meleeReviewTicks", 20);
        if (value < 1 || value > 200)
            throw new IllegalArgumentException("cf.target.meleeReviewTicks must be 1..200");
        return value;
    }

    CFTargeting(CFUnit unit) {
        this.unit = unit;
        this.ranged = "ranged_hold".equals(unit.behaviorId());
        this.radius = ranged ? unit.attackRange() : unit.detectionRange();
        this.radiusSquared = radius * radius;
        this.brain = new CFTargetBrain<>(unit, this, ranged, MELEE_REVIEW_TICKS, unit.getEntityId());
    }
    CFUnit peek() { return brain.peek(); }
    boolean pending() { return brain.pending(); }
    CFUnit resolve() {
        CFMegaPerf.count(CFMegaPerf.Count.TARGET_REQUESTS, 1);
        long prior = brain.cacheHits();
        CFUnit result = brain.resolve(unit.getAliveTicks());
        if (brain.cacheHits() != prior) CFMegaPerf.count(CFMegaPerf.Count.TARGET_CACHE_HITS, 1);
        return result;
    }
    void assign(Entity value) {
        if (value != null && !(value instanceof CFUnit))
            throw new IllegalArgumentException("CF combat targets must be CFUnit enemies");
        brain.assign((CFUnit)value, unit.getAliveTicks());
    }
    void retire(String reason) {
        boolean wasClosed = brain.links.closed();
        int affected = brain.links.retire();
        if (!wasClosed) {
            CFMegaPerf.count(CFMegaPerf.Count.TARGET_INVALIDATIONS, affected);
            if (affected != 0) {
                CFTrace.event("TARGET_INVALIDATE_INBOUND", "unit", unit.getEntityId(),
                        "attackers", affected, "reason", reason);
                CFSkeletonMindLog.event(unit, "TARGET_INVALIDATE_INBOUND",
                        "attackers", affected, "reason", reason);
            }
        }
    }
    void worldChanged() { brain.links.disconnect(); }
    @Override public boolean alive(CFUnit u) { return u.combatAlive() && !u.targeting().brain.links.closed(); }
    @Override public boolean enemy(CFUnit u, CFUnit other) {
        return other != null && other != u && alive(other)
                && other.getInstance() == u.getInstance() && !u.team().equals(other.team());
    }
    @Override public boolean inRange(CFUnit u, CFUnit other) {
        return u.getDistanceSquared(other) <= radiusSquared
                && (ranged || CFCombatHeight.meleeAllowed(u.getPosition().y(), other.getPosition().y()))
                && (!u.isCastleKing() || (other.getPosition().distanceSquared(u.kingThrone()) <= radiusSquared
                && CFKingGuardGoal.reachableFromThrone(u.kingThrone(), other.getPosition(), u.attackRange())));
    }
    @Override public CFTargetLinks<CFUnit> links(CFUnit u) { return u.targeting().brain.links; }

    @Override public CFUnit nearest(CFUnit ignored) {
        long began = CFMegaPerf.begin();
        long perf = CFDuelPerf.enterMethod();
        best = null; origin = unit.getPosition(); bestSquared = radiusSquared; candidates = 0;
        try {
            var instance = unit.getInstance();
            if (instance == null) return null;
            // Same spherical query as Instance.getNearbyEntities, without a
            // temporary ArrayList/stream/comparator or per-search lambda.
            instance.getEntityTracker().nearbyEntities(origin, radius, EntityTracker.Target.ENTITIES, this);
            return best;
        } finally {
            CFMegaPerf.count(CFMegaPerf.Count.TARGET_CANDIDATES, candidates);
            CFMegaPerf.end(CFMegaPerf.Metric.TARGET_FULL_SCAN, began);
            CFDuelPerf.exitMethod("CFTargeting.fullScan", perf);
            best = null; origin = null;
        }
    }
    @Override public void accept(Entity candidate) {
        candidates++;
        if (!(candidate instanceof CFUnit other) || !enemy(unit, other)) return;
        double distance = origin.distanceSquared(other.getPosition());
        if (distance > bestSquared || (unit.isCastleKing() &&
                other.getPosition().distanceSquared(unit.kingThrone()) > radiusSquared)) return;
        if (!ranged && !CFCombatHeight.meleeAllowed(origin.y(), other.getPosition().y())) return;
        // Deterministic tie-break; no invisible-target replacement for archers.
        if (distance == bestSquared && best != null && other.getEntityId() >= best.getEntityId()) return;
        // The king cannot leave his 3x3 to chase an unreachable enemy or fight through walls.
        if (unit.isCastleKing() && (!CFKingGuardGoal.reachableFromThrone(
                unit.kingThrone(), other.getPosition(), unit.attackRange())
                || !unit.hasLineOfSight(other))) return;
        if (ranged && !unit.hasLineOfSight(other)) return;
        best = other; bestSquared = distance;
    }
    @Override public void scanned(CFTargetBrain.Reason reason) {
        CFMegaPerf.count(switch (reason) {
            case MARCH -> CFMegaPerf.Count.TARGET_SCANS_MARCH;
            case MELEE_REVIEW -> CFMegaPerf.Count.TARGET_SCANS_REVIEW;
            default -> CFMegaPerf.Count.TARGET_SCANS_LOST;
        }, 1);
    }
    @Override public void changed(CFUnit old, CFUnit next, CFTargetBrain.Reason reason, long tick) {
        CFMegaPerf.count(CFMegaPerf.Count.TARGET_CHANGES, 1);
        String type = next == null ? "TARGET_RELEASE" : old == null ? "TARGET_LOCK" : "TARGET_RETARGET";
        String decision = next == null ? "NO_ENEMY_RESUME_MARCH" : ranged
                ? "HOLD_POSITION_AND_SHOOT" : "CHASE_OR_MELEE";
        CFTrace.event(type, "unit", unit.getEntityId(), "previousTarget", CFTrace.id(old),
                "target", CFTrace.id(next), "reason", reason.name(), "ageTicks", tick,
                "policy", ranged ? "RANGED_DEATH_OR_RADIUS" : "MELEE_PERIODIC",
                "reviewTicks", ranged ? 0 : MELEE_REVIEW_TICKS, "decision", decision);
        CFSkeletonMindLog.event(unit, type, "previousTarget", CFTrace.id(old),
                "target", CFTrace.id(next), "reason", reason.name(), "decision", decision);
    }
}
