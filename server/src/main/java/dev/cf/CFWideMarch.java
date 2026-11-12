package dev.cf;

import net.minestom.server.collision.CollisionUtils;
import net.minestom.server.coordinate.Point;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.coordinate.Vec;
import net.minestom.server.entity.pathfinding.PPath;
import java.util.List;

/** March-only corridor steering. Combat AI and external vanilla push velocity are untouched.
 * Open ground: walk parallel to the road from the current lateral position.
 * Terrain obstruction: bounded native A* to an offset, dry target on the SAME journey.
 */
final class CFWideMarch {
    private final CFUnit unit;
    private final CFWideLaneGeometry road;
    private final CFSmoothGroundFollower follower;
    private final double usableRadius;
    private CFWideLaneGeometry.Projection projection;
    private Pos aim, lastPosition, lastProgressPosition, rejoinFrom, rejoinTo;
    private double cursor;
    private boolean initial=true, finished, fallback, pendingFallback;
    private long nextRequestTick, nextDirectProbeTick, lastProgressTick, nextStallLogTick;
    private int blockedTicks, fallbackAttempt, nextIndex;
    private String mode="WIDE_IDLE";

    CFWideMarch(CFUnit unit,CFLanePath lane,List<Pos> journey) {
        this.unit=unit; this.road=lane.wideGeometry(journey);
        this.follower=new CFSmoothGroundFollower(unit);
        // Fixed lane steering width: no shrinking the road for bigger mob hitboxes.
        usableRadius=CFWideLaneGeometry.RADIUS-.35;
    }
    int nextIndex(){return finished?road.nodeCount():nextIndex;}
    Pos aim(){return aim;}
    String mode(){return mode;}
    double lateral(){return projection==null?Double.NaN:projection.lateral();}
    double progress(){return cursor;}
    boolean finished(){return finished;}

    void start() {
        unit.getNavigator().reset(); fallback=false;pendingFallback=false;blockedTicks=0;
        nextRequestTick=unit.getAliveTicks();
        updateProjection(true);
        lastProgressPosition=unit.getPosition();lastProgressTick=unit.getAliveTicks();
        mode=finished?"WIDE_FINISHED":"WIDE_DIRECT";
    }
    void pause(){mode=finished?"WIDE_FINISHED":"WIDE_PAUSED_COMBAT";}
    void requestRepath(){pendingFallback=true;}

    private static CFWideLaneGeometry.Node node(Point p) {
        return new CFWideLaneGeometry.Node(p.x(),p.y(),p.z());
    }
    private static Pos pos(CFWideLaneGeometry.Node p){return new Pos(p.x(),p.y(),p.z());}

    private boolean updateProjection(boolean restarting) {
        long _cfMegaCost = CFMegaPerf.begin();
        try {

        Pos here=unit.getPosition();
        double displacement=lastPosition==null?0:Math.hypot(here.x()-lastPosition.x(),here.z()-lastPosition.z());
        var q=initial?road.nearest(node(here)):
                road.nearest(node(here),Math.max(0,cursor-4-displacement),
                        cursor+12+displacement,CFVerticalTravel.ROUTE_HEIGHT_TOLERANCE);
        if(q==null){mode="WIDE_WAIT_HEIGHT";return false;}
        initial=false;projection=q;cursor=q.progress();
        nextIndex=q.nextIndex();lastPosition=here;
        aim=pos(road.steeringTarget(node(here),q,4.0,usableRadius));
        if(q.distanceSq()>CFWideLaneGeometry.RADIUS*CFWideLaneGeometry.RADIUS) {
            if(rejoinFrom==null||restarting){rejoinFrom=here;rejoinTo=pos(road.aim(q,2.0,usableRadius));}
        } else {rejoinFrom=null;rejoinTo=null;}
        if(road.finished(q,node(here))) {
            finished=true;mode="WIDE_FINISHED";unit.getNavigator().reset();
            CFSkeletonMindLog.event(unit,"CASTLE_REACHED","lane",unit.laneId(),
                    "decision","ROUTE_CROSS_SECTION_FINISHED_NO_CASTLE_DAMAGE_CHANGE");
        }
        return true;
    
        } finally { CFMegaPerf.end(CFMegaPerf.Metric.WIDE_PROJECTION, _cfMegaCost); }
    }

    /** Geometry domain only. Solid blocks, dry support and hitbox are checked separately. */
    boolean allows(Point p) {
        if(road.contains(node(p),Math.max(0,cursor-12),cursor+24,CFWideLaneGeometry.RADIUS))return true;
        // A knocked/chasing unit may rejoin its OWN road through a narrow connection.
        // This is not permission to switch lanes or search arbitrary distant route nodes.
        return rejoinFrom!=null && rejoinTo!=null && rejoinFrom.distance(rejoinTo)<=32
                && CFLaneSearch.segmentDistanceSq(rejoinFrom,rejoinTo,p)<=1.0
                && p.y()>=Math.min(rejoinFrom.y(),rejoinTo.y())-2
                && p.y()<=Math.max(rejoinFrom.y(),rejoinTo.y())+2;
    }

    /** Route bounds are checked at the entity's position, NOT at hitbox corners. */
    boolean allowsBody(Pos p) { return allows(p); }

    /** Invoked by the shared follower, for direct motion AND terrain detours.
     * Does not write positions or velocities; never treats other units as solid blocks.
     */
    boolean safeStep(Pos from,Pos to) {
        long _cfMegaCost = CFMegaPerf.begin();
        try {

        if(unit.getInstance()==null)return false;
        // Terrain support probe is independent of any mob size.
        var box=CFThreeLanes.terrainPoint();
        // If external physics pushed us over the corridor edge, allow gradual return.
        var old=road.nearest(node(from),Math.max(0,cursor-12),cursor+24,CFVerticalTravel.ROUTE_HEIGHT_TOLERANCE);
        var now=road.nearest(node(to),Math.max(0,cursor-12),cursor+24,CFVerticalTravel.ROUTE_HEIGHT_TOLERANCE);
        boolean returning=old!=null && now!=null && old.distanceSq()>usableRadius*usableRadius
                && now.distanceSq()<old.distanceSq()-1.e-10;
        if(!returning && !allowsBody(to)) {
            CFMegaPerf.count(CFMegaPerf.Count.WIDE_SAFE_DENIED, 1);
            return false;
        }
        // Let gravity carry the mob down short ledges instead of repeatedly
        // cancelling its horizontal movement until it finds a 1-block floor.
        // The collision probe must still find a real, DRY landing <= 2 blocks
        // below the feet (plus the height of an ongoing native jump).
        boolean airborne=!unit.isOnGround();
        var floor=CollisionUtils.handlePhysics(unit.getInstance(),box,to.add(0,.01,0),
                new Vec(0,-CFVerticalTravel.downwardProbe(airborne),0),null,true);
        return floor.collisionY()
                && CFVerticalTravel.landingWithinDrop(to.y(),floor.newPosition().y(),airborne)
                // Enforce the same route-height window at the *landing* too:
                // an airborne jump must not turn a 3+ block pit into a shortcut.
                && (returning || allowsBody(floor.newPosition()))
                && CFLaneSearch.clear(unit.getInstance(),box,floor.newPosition());
    
        } finally { CFMegaPerf.end(CFMegaPerf.Metric.WIDE_SAFE_STEP, _cfMegaCost); }
    }

    void tick() {
        long _cfMegaCost = CFMegaPerf.begin();
        try {

        if(finished||!unit.combatAlive()||unit.isRangedLocked())return;
        if(!updateProjection(false)){
            unit.getNavigator().reset(); sample();return;
        }
        if(finished)return;
        long tick=unit.getAliveTicks();
        if(pendingFallback && tick>=nextRequestTick) {
            pendingFallback=false;requestTerrainPath();sample();return;
        }
        if(fallback) {
            CFMegaPerf.count(CFMegaPerf.Count.WIDE_FALLBACK_TICKS, 1);
            // Never cancel an ongoing jump to begin an additional direct step.
            if(tick>=nextDirectProbeTick && unit.isOnGround() && clearAhead()) {
                unit.getNavigator().reset();fallback=false;blockedTicks=0;
            } else {
                if(tick>=nextDirectProbeTick)nextDirectProbeTick=tick+10;
                PPath.State state=unit.getNavigator().getState();
                if((state==PPath.State.INVALID || unit.getNavigator().isComplete()) && tick>=nextRequestTick)
                    requestTerrainPath();
                mode="WIDE_TERRAIN_PATH"; watchProgress();sample();return;
            }
        }
        // At most one locomotion owner per tick. An empty navigator cannot also walk a grid node.
        if(unit.getNavigator().getGoalPosition()!=null)unit.getNavigator().reset();
        CFMegaPerf.count(CFMegaPerf.Count.WIDE_DIRECT_TICKS, 1);
        Pos before=unit.getPosition();
        follower.moveTowards(aim,follower.movementSpeed(),aim);
        Pos after=unit.getPosition();
        double moved=Math.hypot(after.x()-before.x(),after.z()-before.z());
        // Gravity already owns the vertical part of a short descent/jump.
        // Do not label a brief airborne pause as a terrain stall and reset A*.
        boolean airborne=!unit.isOnGround();
        blockedTicks=airborne?0:moved<Math.min(.015,follower.movementSpeed()*.12)?blockedTicks+1:0;
        pendingFallback=!airborne && blockedTicks>=2;
        if (pendingFallback) CFMegaPerf.count(CFMegaPerf.Count.WIDE_BLOCKED_STEPS, 1);
        mode=airborne?"WIDE_AIRBORNE":blockedTicks>=2?"WIDE_BLOCKED_TERRAIN":
                rejoinFrom!=null?"WIDE_REJOIN":"WIDE_DIRECT";
        watchProgress();sample();
    
        } finally { CFMegaPerf.end(CFMegaPerf.Metric.WIDE_MARCH_TICK, _cfMegaCost); }
    }

    private boolean clearAhead() {
        long _cfMegaCost = CFMegaPerf.begin();
        try {

        Pos from=unit.getPosition();double dx=aim.x()-from.x(),dz=aim.z()-from.z();
        double len=Math.hypot(dx,dz);if(len<.05)return false;
        double distance=Math.min(4.0,len);
        var swept=CollisionUtils.handlePhysics(unit,new Vec(dx/len*distance,0,dz/len*distance));
        if(swept.collisionX()||swept.collisionZ())return false;
        for(int i=1;i<=4;i++)if(!safeStep(from,from.add(dx/len*distance*i/4,0,dz/len*distance*i/4)))return false;
        return true;
    
        } finally { CFMegaPerf.end(CFMegaPerf.Metric.WIDE_CLEAR_AHEAD, _cfMegaCost); }
    }

    private void requestTerrainPath() {
        long _cfMegaCost = CFMegaPerf.begin();
        try {

        long tick=unit.getAliveTicks();
        nextRequestTick=tick+40+Math.floorMod(unit.getEntityId(),17);
        nextDirectProbeTick=tick+10;
        double side=CFWideLaneGeometry.clamp(projection.lateral(),-usableRadius,usableRadius);
        double[] offsets={side,side+.8,side-.8,side+1.6,side-1.6,0,-usableRadius,usableRadius};
        Pos chosen=null;int start=fallbackAttempt++%offsets.length;
        for(int i=0;i<offsets.length;i++) {
            double offset=CFWideLaneGeometry.clamp(offsets[(start+i)%offsets.length],-usableRadius,usableRadius);
            Pos raw=pos(road.offsetAt(Math.min(road.length(),cursor+4),offset));
            try {
                Pos floor=CFLaneSearch.surface(unit.getInstance(),CFThreeLanes.terrainPoint(),raw.add(0,.55,0));
                if(!allows(floor)||!safeStep(floor,floor))continue;
                chosen=floor;break;
            } catch(IllegalArgumentException blocked) { /* Try a nearby lateral target, not a new lane. */ }
        }
        if(chosen==null) {
            fallback=false;mode="WIDE_WAIT_NO_DRY_TARGET";
            CFSkeletonMindLog.event(unit,"WIDE_PATH_WAIT","progress",cursor,
                    "side",projection.lateral(),"reason","NO_DRY_TARGET_ON_OWN_LANE");return;
        }
        CFMegaPerf.count(CFMegaPerf.Count.WIDE_PATH_REQUESTS, 1);
        long _cfMegaNativeAstar = CFMegaPerf.begin();
        boolean accepted;
        try { accepted=unit.getNavigator().setPathTo(chosen,.45,32,16,null); }
        finally { CFMegaPerf.end(CFMegaPerf.Metric.NATIVE_ASTAR_REQUEST, _cfMegaNativeAstar); }
        CFMegaPerf.count(accepted ? CFMegaPerf.Count.WIDE_PATH_ACCEPTED
                : CFMegaPerf.Count.WIDE_PATH_REJECTED, 1);
        fallback=accepted;
        if(accepted)aim=chosen;
        mode=accepted?"WIDE_TERRAIN_PATH":"WIDE_PATH_REJECTED";
        CFSkeletonMindLog.event(unit,"WIDE_PATH_REQUEST","goal",CFTrace.xyz(chosen),
                "accepted",accepted,"progress",cursor,"side",projection.lateral(),
                "reason","TERRAIN_NOT_FRIENDLY_BODIES");
    
        } finally { CFMegaPerf.end(CFMegaPerf.Metric.WIDE_TERRAIN_PATH, _cfMegaCost); }
    }

    private void watchProgress() {
        long tick=unit.getAliveTicks();Pos here=unit.getPosition();
        if(lastProgressPosition==null || Math.hypot(here.x()-lastProgressPosition.x(),here.z()-lastProgressPosition.z())>.3) {
            lastProgressPosition=here;lastProgressTick=tick;
            unit.finishCrowdRecovery("WIDE_MARCH_MOVING");return;
        }
        if(tick-lastProgressTick>=60 && tick>=nextStallLogTick) {
            nextStallLogTick=tick+60;pendingFallback=true;
            CFSkeletonMindLog.event(unit,"WIDE_MARCH_STALL","ticks",tick-lastProgressTick,
                    "mode",mode,"side",lateral(),"goal",CFTrace.xyz(aim),
                    "decision","BOUNDED_TERRAIN_REPATH_NO_SEPARATION_FORCE");
        }
    }
    private void sample() {
        if(Math.floorMod(unit.getAliveTicks()+(long)unit.getEntityId(),20L)!=0)return;
        CFSkeletonMindLog.event(unit,"WIDE_MARCH_SAMPLE","mode",mode,
                "progress",cursor,"length",road.length(),"side",lateral(),
                "radius",CFWideLaneGeometry.RADIUS,"usableRadius",usableRadius,
                "goal",CFTrace.xyz(aim),"pos",CFTrace.xyz(unit.getPosition()),
                "nav",unit.getNavigator().getState().name());
    }
}
