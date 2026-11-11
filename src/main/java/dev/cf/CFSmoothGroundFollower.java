package dev.cf;

import net.minestom.server.collision.CollisionUtils;
import net.minestom.server.coordinate.*;
import net.minestom.server.entity.pathfinding.followers.GroundNodeFollower;

/** Collision-swept 0.6-block stepping; never turns each half slab into a ballistic jump. */
final class CFSmoothGroundFollower extends GroundNodeFollower {
    private final CFUnit unit;
    private long steppedTick = -1000, jumpedTick = -1000, warningTick = -1000;
    private boolean blocked;
    CFSmoothGroundFollower(CFUnit unit) { super(unit); this.unit = unit; }
    @Override public void moveTowards(Point target, double speed, Point lookAt) {
        long _cfMegaCost = CFMegaPerf.begin();
        try {

        blocked = false;
        if (unit.isRangedLocked() || unit.getInstance() == null) return;
        Pos p = unit.getPosition();
        double dx = target.x()-p.x(), dz = target.z()-p.z(), length = Math.hypot(dx,dz);
        if (length < 1.e-7) return;
        double stride = Math.min(Math.max(0,speed),length);
        Vec delta = new Vec(dx/length*stride,0,dz/length*stride);
        long _cfMegaCollision = CFMegaPerf.begin();
        var plain = CollisionUtils.handlePhysics(unit,delta);
        CFMegaPerf.end(CFMegaPerf.Metric.FOLLOWER_BLOCK_COLLISION, _cfMegaCollision);
        Pos chosen = plain.newPosition();
        blocked = plain.collisionX() || plain.collisionZ();
        if (blocked && unit.isOnGround() && unit.getVelocity().y() <= .01) {
            Pos stepped=CFStepSolver.attempt(unit.getInstance(),unit.getInstance().getWorldBorder(),
                    unit.getBoundingBox(),p,delta,chosen);
            if(stepped!=null) {
                chosen=stepped;
                steppedTick=unit.getAliveTicks();
                // Do not mutate position before the body sweep below.
                blocked=false;

            }
        }
        // The road is a wide navigable strip, not a physics wall. Restrict active
        // walking only; external vanilla impulses are still handled by Minestom.
        if (!unit.marchGoal().safeLaneStep(p,chosen)) {
            blocked=true;
            chosen=p;
        }
        Pos view = p.withLookAt(lookAt);
        // Vanilla mobs are not hard body obstacles for the path follower.
        // Contact is handled through the CFUnit velocity push pass instead.
        if (chosen.y() > p.y()+.001 && steppedTick == unit.getAliveTicks()) {
            CFSkeletonMindLog.event(unit,"GROUND_STEP","rise",chosen.y()-p.y(),
                    "from",CFTrace.xyz(p),"to",CFTrace.xyz(chosen),
                    "decision","COLLISION_AND_CORRIDOR_CHECKED_STEP");
            unit.groundStep(chosen.withView(view.yaw(),view.pitch()));
        }
        else unit.refreshPosition(chosen.withView(view.yaw(),view.pitch()));
    
        } finally { CFMegaPerf.end(CFMegaPerf.Metric.FOLLOWER_MOVE, _cfMegaCost); }
    }
    @Override public void jump(Point point, Point target) {
        long tick = unit.getAliveTicks();
        if (tick == steppedTick || unit.isRangedLocked() || point == null) return;
        double rise = point.y() - unit.getPosition().y();
        if (CFStepPolicy.mayJump(rise,blocked,unit.isOnGround(),unit.isRangedLocked(),tick-jumpedTick)) {
            jumpedTick = tick;
            Vec v = unit.getVelocity();
            unit.setVelocity(new Vec(v.x(),8.4,v.z()));
            CFSkeletonMindLog.event(unit,"NAV_JUMP","rise",rise,"impulseY",8.4,
                    "reason","FULL_BLOCK_OBSTACLE_NOT_HALF_STEP");
        } else if (blocked && tick-warningTick >= 20) {
            warningTick=tick;
            CFSkeletonMindLog.event(unit,"STEP_BLOCKED","rise",rise,"ground",unit.isOnGround(),
                    "node",CFTrace.xyz(point),"decision","NO_REPEATED_BLIND_JUMP");
        }
    }
    @Override public boolean isAtPoint(Point point) {
        Pos p=unit.getPosition();
        // Fractional collision surfaces must not require an unreachable integer foot Y.
        return xz2(p,point)<.09 && Math.abs(p.y()-point.y())<.6;
    }
    private static double xz2(Point a,Point b) { double x=a.x()-b.x(),z=a.z()-b.z();return x*x+z*z; }
}
