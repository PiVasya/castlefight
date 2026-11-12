package dev.cf;

import net.minestom.server.coordinate.Pos;
import net.minestom.server.entity.EntityCreature;
import net.minestom.server.entity.ai.GoalSelector;
import net.minestom.server.entity.pathfinding.PPath;
import net.minestom.server.instance.InstanceContainer;
import net.minestom.server.instance.anvil.AnvilLoader;

import java.util.ArrayList;
import java.util.List;

/**
 * Legacy duel units keep their old straight-line routing.
 * Tower units receive ONE polyline/corridor; spawn joins the nearest forward segment.
 * A checkpoint is a soft progress marker, not a required pixel-perfect destination.
 */
final class MarchGoal extends GoalSelector {
    private List<Pos> checkpoints = new ArrayList<>();
    private int next = 0;
    private CFLanePath verifiedLane;
    private boolean laneMarching;
    private CFWideMarch wide;
    String movementMode(){return wide==null?"LEGACY_NAV":wide.mode();}
    double lateralPosition(){return wide==null?Double.NaN:wide.lateral();}
    double roadProgress(){return wide==null?Double.NaN:wide.progress();}
    boolean safeLaneStep(Pos from,Pos to){return wide==null || !laneMarching || wide.safeStep(from,to);}
    private Pos recoveryFrom, recoveryTo;
    int laneId() { return verifiedLane == null ? (assignedLane ? 1 : 0) : verifiedLane.id; }
    boolean constrainLane() { return verifiedLane != null && laneMarching; }
    boolean allowsLaneBody(Pos p){return wide!=null?wide.allowsBody(p):allowsLane(p);}
    boolean allowsLane(net.minestom.server.coordinate.Point p) {
        if (wide != null) return wide.allows(p);
        if (verifiedLane == null) return true;
        if (verifiedLane.contains(p)) return true;
        // A short tower exit / return-after-combat connection leads only to this lane.
        return recoveryFrom != null && recoveryTo != null &&
                CFLaneSearch.segmentDistanceSq(recoveryFrom,recoveryTo,p) <= 16 &&
                p.y() >= Math.min(recoveryFrom.y(),recoveryTo.y())-2 &&
                p.y() <= Math.max(recoveryFrom.y(),recoveryTo.y())+2;
    }
    void assignValidatedLane(CFLanePath lane,List<Pos> journey) {
        if (verifiedLane != null) throw new IllegalStateException("A unit cannot change its lane");
        if (journey.size() < 2) throw new IllegalArgumentException("Empty tower journey");
        verifiedLane=lane;checkpoints=journey;next=0;assignedLane=true;laneWidth=9;needsNewPath=true;
        if(entityCreature instanceof CFUnit u)wide=new CFWideMarch(u,lane,journey);
        resetProgress();
    }

    private long lastRequest = 0;
    private boolean needsNewPath;
    private boolean assignedLane;
    private int laneWidth = 0;
    private long lastProgressNs;
    private Pos lastProgressPos;
    private long nextStallWarningNs;
    private int stalledRecoveries;

    void assignLane(List<Pos> points, int width, Pos actualSpawn) {
        if (points == null || points.size() < 2) throw new IllegalArgumentException("Need 2+ points");
        checkpoints.clear();
        checkpoints.addAll(List.copyOf(points));
        laneWidth = Math.max(3, Math.min(21, width));
        next = CFLaneGeometry.startCheckpoint(checkpoints, actualSpawn);
        lastRequest = 0;
        needsNewPath = true;
        assignedLane = true;
        resetProgress();
        if (entityCreature instanceof CFUnit u)
            CFSkeletonMindLog.event(u, "LANE_JOIN", "lane", 1, "width", laneWidth,
                    "nearestForwardStep", next + 1, "total", checkpoints.size(),
                    "spawn", CFTrace.xyz(actualSpawn),
                    "nextGoal", CFTrace.xyz(checkpoint()),
                    "reason", "NEAREST_SEGMENT_NOT_POINT_ONE");
    }

    boolean hasAssignedLane() { return assignedLane; }
    int laneWidth() { return laneWidth; }
    int checkpointIndex() { return next; }
    int checkpointCount() { return checkpoints.size(); }
    Pos checkpoint() { return wide!=null && wide.aim()!=null && !wide.finished()?wide.aim():next < checkpoints.size() ? checkpoints.get(next) : null; }
    boolean routeFinished() { return next >= checkpoints.size(); }

    MarchGoal(EntityCreature mob, Pos from, Pos to) {
        super(mob);
        buildCheckpoints(from, to);
    }

    void retarget(Pos to) {
        if (assignedLane) return;
        if (CFTrace.detailed()) CFTrace.event("MARCH_RETARGET", "unit", entityCreature.getEntityId(),
                "from", CFTrace.xyz(entityCreature.getPosition()), "to", CFTrace.xyz(to));
        buildCheckpoints(entityCreature.getPosition(), to);
        needsNewPath = true;
    }

    private void buildCheckpoints(Pos from, Pos to) {
        checkpoints.clear();
        next = 0;
        int steps = Math.max(1, (int) Math.ceil(from.distance(to) / 12.0));
        for (int i = 1; i <= steps; i++) {
            double t = i / (double) steps;
            checkpoints.add(new Pos(from.x() + (to.x() - from.x()) * t,
                    from.y() + (to.y() - from.y()) * t,
                    from.z() + (to.z() - from.z()) * t));
        }
    }

    @Override
    public boolean shouldStart() {
        boolean yes = next < checkpoints.size() && !entityCreature.isDead() && !entityCreature.isRemoved();
        if (CFTrace.detailed()) CFTrace.event("MARCH_SHOULD_START", "unit", entityCreature.getEntityId(),
                "yes", yes, "step", next, "total", checkpoints.size());
        return yes;
    }

    @Override
    public void start() {
        if (wide != null) {
            laneMarching=true;wide.start();next=wide.nextIndex();needsNewPath=false;return;
        }
        if (assignedLane) {
            recoverForwardProgress("MARCH_RESTART_AFTER_COMBAT");
            resetProgress();
        }
        if (CFTrace.detailed()) CFTrace.event("MARCH_START", "unit", entityCreature.getEntityId(), "step", next);
        needsNewPath = false;
        setNextPath();
    }

    @Override
    public void tick(long time) {
        long perf = CFDuelPerf.enterMethod();
        try {
            if (entityCreature.isDead() || next >= checkpoints.size()) return;
            if (verifiedLane != null) { tickVerified(time); return; }
            if (needsNewPath) { needsNewPath = false; setNextPath(); }
            if (assignedLane) {
                // Corridor projection is geometry-only, but still stagger checks to
                // avoid scanning all segments for every mob on every tick.
                if ((entityCreature.getAliveTicks() & 3L) == 0L &&
                        recoverForwardProgress("PASSED_CORRIDOR_SEGMENT")) return;
                if (next >= checkpoints.size()) return;
                if (CFLaneGeometry.reached(entityCreature.getPosition(), checkpoints.get(next),
                        Math.max(2.0, Math.min(4.0, laneWidth * 0.34)))) {
                    advance("WITHIN_ROAD_RADIUS");
                    return;
                }
                watchForStall();
            } else if (entityCreature.getPosition().distanceSquared(checkpoints.get(next)) < 2.8) {
                if (CFTrace.detailed()) CFTrace.event("MARCH_CHECKPOINT", "unit", entityCreature.getEntityId(),
                        "completed", next, "pos", CFTrace.xyz(entityCreature.getPosition()));
                next++;
                if (next < checkpoints.size()) setNextPath();
                return;
            }
            if (CFTrace.detailed()) CFTrace.event("MARCH_TICK", "unit", entityCreature.getEntityId(),
                    "step", next, "dest", CFTrace.xyz(checkpoint()),
                    "distance", entityCreature.getPosition().distance(checkpoint()),
                    "nav", entityCreature.getNavigator().getState().name());
            long now = assignedLane ? System.currentTimeMillis() : time;
            if (now - lastRequest > (assignedLane ? 2300 : 1500) &&
                    entityCreature.getNavigator().getState() == PPath.State.INVALID &&
                    !(entityCreature instanceof CFUnit u && u.crowdRecoveryActive())) {
                if (assignedLane) log("NAV_INVALID_RETRY", "step", next + 1, "reason", "INVALID_STATE");
                setNextPath();
                if (!assignedLane) lastRequest = time;
            }
        } finally { CFDuelPerf.exitMethod("MarchGoal.tick", perf); }
    }

    @Override
    public boolean shouldEnd() {
        return entityCreature.isDead() || entityCreature.isRemoved() || next >= checkpoints.size();
    }

    @Override
    public void end() {
        laneMarching=false;
        if(wide!=null)wide.pause();
        if (CFTrace.detailed()) CFTrace.event("MARCH_END", "unit", entityCreature.getEntityId(),
                "step", next, "remaining", checkpoints.size() - next);
        if (assignedLane) log("MARCH_PAUSE", "step", next + 1,
                "reason", routeFinished() ? "LANE_COMPLETED" : "COMBAT_OR_DEATH");
        entityCreature.getNavigator().reset();
    }

    private void tickVerified(long time) {
        if(wide==null)return;
        wide.tick();next=wide.nextIndex();
    }

    private boolean recoverForwardProgress(String reason) {
        if (!assignedLane || next >= checkpoints.size()) return false;
        CFLaneGeometry.Nearest projection = CFLaneGeometry.nearest(checkpoints,
                entityCreature.getPosition(), Math.max(0, next - 1));
        double allowed = laneWidth / 2.0 + 0.5;
        if (projection.distanceSq() > allowed * allowed) return false;
        // Even when off the centreline the mob may cut a corner, but never move backwards.
        int candidate = projection.segment() + 1;
        if (projection.t() > .94 && candidate < checkpoints.size() - 1) candidate++;
        if (candidate > next) {
            int before = next;
            next = Math.min(checkpoints.size() - 1, candidate);
            log("WAYPOINTS_SKIPPED", "fromStep", before + 1, "toStep", next + 1,
                    "distanceToRoad", Math.sqrt(projection.distanceSq()), "reason", reason);
            resetProgress();
            // start() already requests a path; avoid duplicate A* work.
            if (!"MARCH_RESTART_AFTER_COMBAT".equals(reason)) setNextPath();
            return true;
        }
        return false;
    }

    private void advance(String reason) {
        int before = next;
        next++;
        log("ROAD_PROGRESS", "completedStep", before + 1, "nextStep", next + 1,
                "reason", reason, "position", CFTrace.xyz(entityCreature.getPosition()));
        resetProgress();
        if (next < checkpoints.size()) setNextPath();
        else log("LANE_FINISHED", "totalPoints", checkpoints.size());
    }

    private void watchForStall() {
        long now = System.nanoTime();
        Pos p = entityCreature.getPosition();
        if (lastProgressPos == null || Math.hypot(p.x() - lastProgressPos.x(), p.z() - lastProgressPos.z()) > .30) {
            lastProgressPos = p;
            lastProgressNs = now;
            stalledRecoveries = 0;
            if (entityCreature instanceof CFUnit u) u.finishCrowdRecovery("MOVING_AGAIN");
            return;
        }
        if (now - lastProgressNs < 3_000_000_000L || now < nextStallWarningNs) return;
        nextStallWarningNs = now + 3_000_000_000L;
        stalledRecoveries++;
        String floor = "unknown", feet = "unknown", forwardFloor = "unknown", forwardFeet = "unknown";
        try {
            floor = entityCreature.getInstance().getBlock(p.blockX(), p.blockY() - 1, p.blockZ()).name();
            feet = entityCreature.getInstance().getBlock(p.blockX(), p.blockY(), p.blockZ()).name();
            Pos navNext = entityCreature.getNavigator().getPathPosition() instanceof Pos node
                    ? node : checkpoint();
            if (navNext != null) {
                double dx = navNext.x() - p.x(), dz = navNext.z() - p.z();
                double len = Math.hypot(dx, dz);
                if (len > .01) {
                    int ax = (int) Math.floor(p.x() + dx / len * .95);
                    int az = (int) Math.floor(p.z() + dz / len * .95);
                    forwardFloor = entityCreature.getInstance().getBlock(ax, p.blockY() - 1, az).name();
                    forwardFeet = entityCreature.getInstance().getBlock(ax, p.blockY(), az).name();
                }
            }
        } catch (Exception ignored) { }
        Pos obstacleTarget = entityCreature.getNavigator().getPathPosition() instanceof Pos node
                ? node : checkpoint();
        CFUnit unit = entityCreature instanceof CFUnit u ? u : null;
        boolean crowded = unit != null && CFCrowdPhysics.friendlyCrowdAhead(unit,obstacleTarget);
        String decision = crowded ? "CROWD_RECOVERY_NO_REPATH" : "RETRY_LOCAL_PATH_NO_TELEPORT";
        log("STALLED_ROAD", "durationMs", (now - lastProgressNs) / 1_000_000L,
                "attempt", stalledRecoveries, "step", next + 1,
                "nav", entityCreature.getNavigator().getState().name(),
                "ground", entityCreature.isOnGround(), "floor", floor, "feet", feet,
                "forwardFloor", forwardFloor, "forwardFeet", forwardFeet,
                "goal", CFTrace.xyz(checkpoint()), "decision", decision);
        if(unit != null) CFTrace.event("CROWD_STALL_CLASSIFIED",
                "unit",unit.getEntityId(),"lane",unit.laneId(),"crowd",crowded,
                "attempt",stalledRecoveries,"step",next+1,
                "nav",entityCreature.getNavigator().getState().name(),
                "pos",CFTrace.xyz(p),"target",CFTrace.xyz(obstacleTarget),
                "decision",decision);
        if(crowded) {
            // A* cannot fix a living, physically blocked neighbour. Request
            // local small-step escape instead of creating an identical path.
            unit.requestCrowdRecovery("STALL_FRIENDLY_BODY");
        } else {
            if(unit!=null)unit.finishCrowdRecovery("PATH_OBSTRUCTION");
            setNextPath();
        }
    }

    private void resetProgress() {
        if(entityCreature instanceof CFUnit u)u.finishCrowdRecovery("MARCH_PROGRESS");
        lastProgressNs = System.nanoTime();
        lastProgressPos = entityCreature.getPosition();
        nextStallWarningNs = 0;
        stalledRecoveries = 0;
    }

    private void setNextPath() {
        if(wide!=null){wide.requestRepath();return;}
        long perf = CFDuelPerf.enterMethod();
        try {
            if (next >= checkpoints.size()) return;
            Pos target = checkpoints.get(next);
            if (verifiedLane != null) {
                // A small look-ahead on the verified polyline; native A* remains lane-confined.
                int goal=next;double length=0;
                while(goal+1<checkpoints.size() && length+checkpoints.get(goal).distance(checkpoints.get(goal+1))<=4) {
                    length+=checkpoints.get(goal).distance(checkpoints.get(goal+1));goal++;
                }
                target=checkpoints.get(goal);
                recoveryFrom=entityCreature.getPosition();recoveryTo=target;
                if (recoveryFrom.distance(target)>32) {
                    log("LANE_RETURN_TOO_FAR", "lane",laneId(),"distance",recoveryFrom.distance(target),"decision","WAIT_NOT_SWITCH_LANE");
                    lastRequest=System.currentTimeMillis();return;
                }
            }
            if (!assignedLane && entityCreature.getInstance() instanceof InstanceContainer instance
                    && instance.getChunkLoader() instanceof AnvilLoader) {
                // Legacy duels stay unchanged. For the manually painted road, never
                // overwrite its saved waypoint Y with a different full-block floor.
                Pos surface = MapSurface.find(instance, target.blockX(), target.blockZ(),
                        entityCreature.getPosition().y(), 6);
                if (surface != null) { target = surface; checkpoints.set(next, target); }
            }
            boolean accepted = entityCreature.getNavigator().setPathTo(target,
                    verifiedLane != null ? .8 : assignedLane ? 2.4 : 1.4, 48, 16, null);
            if (assignedLane) lastRequest = System.currentTimeMillis();
            if (assignedLane) log("NAV_PATH_REQUEST", "step", next + 1,
                    "goal", CFTrace.xyz(target), "accepted", accepted,
                    "navState", entityCreature.getNavigator().getState().name(),
                    "origin", CFTrace.xyz(entityCreature.getPosition()));
            if (CFTrace.detailed()) CFTrace.event("MARCH_PATH_REQUEST", "unit", entityCreature.getEntityId(),
                    "step", next, "goal", CFTrace.xyz(checkpoints.get(next)),
                    "accepted", accepted, "navState", entityCreature.getNavigator().getState().name());
        } finally { CFDuelPerf.exitMethod("MarchGoal.setNextPath", perf); }
    }

    private void log(String event, Object... pairs) {
        if (entityCreature instanceof CFUnit u) CFSkeletonMindLog.event(u, event, pairs);
    }
}
