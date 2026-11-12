package dev.cf;

import net.minestom.server.coordinate.Pos;
import net.minestom.server.coordinate.Vec;
import net.minestom.server.entity.Entity;
import net.minestom.server.entity.EntityProjectile;
import net.minestom.server.entity.EntityType;

final class CFTraceArrow extends EntityProjectile {
    private final CFUnit source;
    private final Entity targetAtFire;
    CFTraceArrow(CFUnit source, Entity target) {
        super(source, EntityType.ARROW);
        this.source = source;
        this.targetAtFire = target;
        CFTrace.tagArrow(getEntityId(), source);
    }

    @Override
    public void tick(long time) {
        if (!CFTrace.on()) { super.tick(time); return; }
        Pos before = getPosition();
        Vec vBefore = getVelocity();
        super.tick(time);
        Pos after = getPosition();
        Entity target = targetAtFire;
        Pos tPos = target == null ? null : target.getPosition();
        double missBody = -1, sweptMiss = -1;
        if (target != null && !target.isRemoved()) {
            missBody = distanceToBody(after, target);
            sweptMiss = sweepDistance(before, after, target);
        }
        CFTrace.event("ARROW_TICK", "arrow", getEntityId(), "shooter", source.getEntityId(),
                "ageTicks", getAliveTicks(), "targetAtFire", CFTrace.id(target),
                "preX", before.x(), "preY", before.y(), "preZ", before.z(),
                "postX", after.x(), "postY", after.y(), "postZ", after.z(),
                "preVX", vBefore.x(), "preVY", vBefore.y(), "preVZ", vBefore.z(),
                "postVX", getVelocity().x(), "postVY", getVelocity().y(),
                "postVZ", getVelocity().z(),
                "targetPos", CFTrace.xyz(tPos), "targetVelocity", target == null ? "null" : CFTrace.vel(target.getVelocity()),
                "distanceBody", missBody, "sweptDistanceBodyApprox", sweptMiss,
                "targetMinY", target == null ? -1 : target.getPosition().y() + target.getBoundingBox().minY(),
                "targetMaxY", target == null ? -1 : target.getPosition().y() + target.getBoundingBox().maxY(),
                "removed", isRemoved(), "onGround", isOnGround());
    }

    private static double sweepDistance(Pos a, Pos b, Entity target) {
        // Diagnostic approximation: closest point on this tick's segment to target body center.
        Pos p=target.getPosition();
        double centerX=p.x();
        double centerY=p.y()+target.getBoundingBox().height()*.5;
        double centerZ=p.z();
        double dx=b.x()-a.x(), dy=b.y()-a.y(), dz=b.z()-a.z();
        double denom=dx*dx+dy*dy+dz*dz;
        double t=denom<1e-12?0:Math.max(0,Math.min(1,
                ((centerX-a.x())*dx+(centerY-a.y())*dy+(centerZ-a.z())*dz)/denom));
        return distanceToBody(a.x()+t*dx,a.y()+t*dy,a.z()+t*dz,target);
    }
    private static double distanceToBody(Pos p,Entity target) {
        return distanceToBody(p.x(),p.y(),p.z(),target);
    }
    private static double distanceToBody(double x,double y,double z,Entity e) {
        var box=e.getBoundingBox();
        var p=e.getPosition();
        double dx=Math.max(0,Math.max(p.x()+box.minX()-x,x-(p.x()+box.maxX())));
        double dy=Math.max(0,Math.max(p.y()+box.minY()-y,y-(p.y()+box.maxY())));
        double dz=Math.max(0,Math.max(p.z()+box.minZ()-z,z-(p.z()+box.maxZ())));
        return Math.sqrt(dx*dx+dy*dy+dz*dz);
    }
}
