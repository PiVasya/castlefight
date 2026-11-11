package dev.cf;

import net.minestom.server.collision.BoundingBox;
import net.minestom.server.collision.CollisionUtils;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.coordinate.Vec;
import net.minestom.server.entity.Entity;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Local body collision. Every real movement is block-physics checked and lane-confined.
 * Recovery decreases TOTAL overlap across neighbours; it cannot enter a new body.
 * All searches are bounded to the moving mob's immediate surroundings.
 */
final class CFCrowdPhysics {
    private static final double GAP = CFCrowdGeometry.GAP;
    private static final double LOOKAHEAD = 1.10;
    private static final int RECOVERY_DIRECTIONS = 8;
    private CFCrowdPhysics() { }

    static boolean intersects(BoundingBox a, Pos pa, BoundingBox b, Pos pb, double gap) {
        return pa.x() + a.maxX() + gap > pb.x() + b.minX()
                && pb.x() + b.maxX() + gap > pa.x() + a.minX()
                && pa.z() + a.maxZ() + gap > pb.z() + b.minZ()
                && pb.z() + b.maxZ() + gap > pa.z() + a.minZ()
                && pa.y() + a.maxY() > pb.y() + b.minY() + 0.035
                && pb.y() + b.maxY() > pa.y() + a.minY() + 0.035;
    }

    private static CFCrowdGeometry.Body body(BoundingBox bb, Pos p) {
        return new CFCrowdGeometry.Body(p.x(),p.y(),p.z(),
                bb.minX(),bb.maxX(),bb.minY(),bb.maxY(),bb.minZ(),bb.maxZ());
    }

    private static ArrayList<CFCrowdGeometry.Body> bodies(ArrayList<CFUnit> neighbors) {
        ArrayList<CFCrowdGeometry.Body> out = new ArrayList<>(neighbors.size());
        for(CFUnit u:neighbors)out.add(body(u.getBoundingBox(),u.getPosition()));
        return out;
    }

    private static ArrayList<CFUnit> neighbors(CFUnit mover, Pos at, double radius) {
        ArrayList<CFUnit> out = new ArrayList<>(6);
        if(mover.getInstance()==null)return out;
        for(Entity e:mover.getInstance().getNearbyEntities(at,radius)) {
            if(e instanceof CFUnit other && other!=mover && other.combatAlive()
                    && Math.abs(other.getPosition().y()-at.y())<=2.1)out.add(other);
        }
        return out;
    }

    private static boolean pathClear(CFUnit mover, Pos from, Pos to,
                                     List<CFCrowdGeometry.Body> local) {
        if(local.isEmpty())return true;
        return CFCrowdGeometry.assess(body(mover.getBoundingBox(),from),
                to.x(),to.y(),to.z(),local).allowed();
    }

    /**
     * Steer around local bodies. If already overlapping, gradual moves are allowed
     * only if they reduce the sum of penetrations, rather than improving EVERY pair.
     */
    static Pos avoid(CFUnit mover, Pos from, Pos proposed) {
        if (mover.getInstance() == null) return proposed;
        double dx = proposed.x() - from.x(), dz = proposed.z() - from.z();
        double length = Math.hypot(dx, dz);
        if (length < 1.0e-6) return proposed;
        ArrayList<CFUnit> nearby = neighbors(mover,from,Math.max(2.2,length+1.6));
        if(nearby.isEmpty())return proposed;
        List<CFCrowdGeometry.Body> local=bodies(nearby);
        double ux=dx/length,uz=dz/length;
        boolean blocked=!pathClear(mover,from,proposed,local);
        Pos ahead=from.add(ux*LOOKAHEAD,proposed.y()-from.y(),uz*LOOKAHEAD);
        boolean incoming=!pathClear(mover,from,ahead,local);
        if(!blocked && !incoming)return proposed;

        CFCrowdGeometry.Body self=body(mover.getBoundingBox(),from);
        double overlap=totalOverlap(self,from,local);
        double requestedStep=Math.min(overlap>1e-8?.12:.36,length);
        int preferred=(mover.getEntityId()&1)==0?1:-1;
        Pos best=null;double bestScore=-Double.MAX_VALUE;
        for(int turn:new int[]{preferred,-preferred}) {
            double sideX=-uz*turn,sideZ=ux*turn;
            for(int variant=0;variant<3;variant++) {
                double forward=variant==0?.32:variant==1?.08:.58;
                double sideways=variant==0?.95:variant==1?1.0:.82;
                double norm=Math.hypot(forward,sideways);
                Vec delta=new Vec(requestedStep*(ux*forward+sideX*sideways)/norm,0,
                        requestedStep*(uz*forward+sideZ*sideways)/norm);
                Pos candidate=CollisionUtils.handlePhysics(mover,delta).newPosition();
                double moved=Math.hypot(candidate.x()-from.x(),candidate.z()-from.z());
                if(moved<requestedStep*.45)continue;
                if(!mover.marchGoal().allowsLane(candidate))continue;
                if(mover.isOnGround()&&!supported(mover,candidate))continue;
                if(!pathClear(mover,from,candidate,local))continue;
                double clearance=nearestClearance(candidate,nearby);
                double score=Math.min(clearance,2.0)*2.0+
                        (candidate.x()-from.x())*ux+(candidate.z()-from.z())*uz;
                if(score>bestScore){bestScore=score;best=candidate;}
            }
        }
        if(best!=null){
            if(mover.getAliveTicks()%20==0)CFTrace.event("CROWD_DETOUR","unit",mover.getEntityId(),
                    "otherCount",nearby.size(),"from",CFTrace.xyz(from),"to",CFTrace.xyz(best),
                    "blocked",blocked,"lookahead",incoming);
            return best.withView(proposed.yaw(),proposed.pitch());
        }
        if(!blocked)return proposed;
        if(mover.getAliveTicks()%20==0)CFTrace.event("CROWD_WAIT","unit",mover.getEntityId(),
                "otherCount",nearby.size(),"position",CFTrace.xyz(from));
        return from.withView(proposed.yaw(),proposed.pitch());
    }

    /** Local check for Minestom velocity movement, not a second navigation pass. */
    static boolean bodyStepClear(CFUnit mover,Pos from,Pos to) {
        if(mover.getInstance()==null)return false;
        if(Math.hypot(to.x()-from.x(),to.z()-from.z())<1e-8)return true;
        ArrayList<CFUnit> local=neighbors(mover,from,
                Math.max(2.2,Math.hypot(to.x()-from.x(),to.z()-from.z())+1.6));
        return local.isEmpty()||pathClear(mover,from,to,bodies(local));
    }

    /** Only friendly bodies in front of an actively marching unit count as a crowd stall. */
    static boolean friendlyCrowdAhead(CFUnit unit,Pos target) {
        if(target==null||unit.getInstance()==null)return false;
        Pos here=unit.getPosition();
        double dx=target.x()-here.x(),dz=target.z()-here.z(),d=Math.hypot(dx,dz);
        if(d<.05) {
            // The waypoint can coincide with the trapped unit; a true overlap is
            // still a crowd obstruction even without a usable forward vector.
            for(CFUnit other:neighbors(unit,here,1.0))
                if(unit.team().equals(other.team()) &&
                        intersects(unit.getBoundingBox(),here,other.getBoundingBox(),
                                other.getPosition(),GAP)) return true;
            return false;
        }
        double ux=dx/d,uz=dz/d;
        for(CFUnit other:neighbors(unit,here,1.75)) {
            if(!unit.team().equals(other.team()))continue;
            Pos p=other.getPosition();
            double rx=p.x()-here.x(),rz=p.z()-here.z();
            double forward=rx*ux+rz*uz;
            double side=Math.abs(rx*uz-rz*ux);
            if(forward>=-.28&&forward<1.5&&side<.85)return true;
        }
        return false;
    }

    static boolean supported(CFUnit unit,Pos at) {
        return CollisionUtils.handlePhysics(unit.getInstance(),unit.getInstance().getWorldBorder(),
                unit.getBoundingBox(),at.add(0,.01,0),new Vec(0,-.65,0),null,false).collisionY();
    }

    record Recovery(Pos position,double before,double after,int nearby,int candidates,
                    int terrainRejected,int laneRejected,int unsupported,int bodyRejected) {
        boolean moved(Pos from) {return position!=null&&position.distanceSquared(from)>1.0e-5;}
    }

    /**
     * Bounded local escape (max 16 tiny block-physics queries) for an actually
     * trapped/overlapping unit; no teleport, no map edits and no spawn changes.
     * The normal AI remains the only source of forward route progression.
     */
    static Recovery recover(CFUnit unit,double pushX,double pushZ,boolean forceDetour) {
        Pos here=unit.getPosition();
        var nearby=neighbors(unit,here,1.85);
        if(nearby.isEmpty())return new Recovery(null,0,0,0,0,0,0,0,0);
        var local=bodies(nearby);
        var self=body(unit.getBoundingBox(),here);
        double before=totalOverlap(self,here,local);
        if(before<1e-8 && !forceDetour)return new Recovery(null,0,0,nearby.size(),0,0,0,0,0);
        Pos destination=unit.getNavigator().getPathPosition() instanceof Pos node ? node : unit.marchGoal().checkpoint();
        if(destination==null)destination=here;
        double fx=destination.x()-here.x(),fz=destination.z()-here.z();
        double f=Math.hypot(fx,fz);
        if(f<.05){fx=0;fz=1;}else{fx/=f;fz/=f;}
        double pushLen=Math.hypot(pushX,pushZ);
        // Stable side choice avoids changing direction on every tick in a tight corridor.
        double side=(unit.getEntityId()&1)==0?1:-1;
        double baseX=before>0&&pushLen>.001?pushX/pushLen:-fz*side;
        double baseZ=before>0&&pushLen>.001?pushZ/pushLen:fx*side;
        double baseAngle=Math.atan2(baseZ,baseX);
        Pos best=null;double bestAfter=before,bestScore=-Double.MAX_VALUE;
        int attempts=0,terrain=0,lane=0,unsupported=0,bodyReject=0;
        for(double step:new double[]{.085,.045}) {
            for(int i=0;i<RECOVERY_DIRECTIONS;i++) {
                // Alternate left and right around preferred escape, then widen search.
                int turn=i==0?0:(i%2==1?(i+1)/2:-(i/2));
                double angle=baseAngle+turn*(Math.PI/4);
                Vec delta=new Vec(Math.cos(angle)*step,0,Math.sin(angle)*step);
                attempts++;
                Pos candidate=CollisionUtils.handlePhysics(unit,delta).newPosition();
                double moved=Math.hypot(candidate.x()-here.x(),candidate.z()-here.z());
                if(moved<step*.7){terrain++;continue;}
                if(!unit.marchGoal().allowsLane(candidate)){lane++;continue;}
                if(unit.isOnGround()&&!supported(unit,candidate)){unsupported++;continue;}
                var assessment=CFCrowdGeometry.assess(self,candidate.x(),candidate.y(),candidate.z(),local);
                if(!assessment.allowed()){bodyReject++;continue;}
                double forward=(candidate.x()-here.x())*fx+(candidate.z()-here.z())*fz;
                // For an overlap, reducing occupied area outweighs forward preference.
                double score=before>1e-8?(before-assessment.after())*10+forward*.002:
                        forward*.5+((candidate.x()-here.x())*baseX+(candidate.z()-here.z())*baseZ)*.15;
                if(score>bestScore){bestScore=score;best=candidate;bestAfter=assessment.after();}
            }
            if (best != null) break; // second radius only if the first yielded no safe step
        }
        return new Recovery(best,before,bestAfter,nearby.size(),attempts,
                terrain,lane,unsupported,bodyReject);
    }

    private static double nearestClearance(Pos at,Collection<CFUnit> neighbors) {
        double best=Double.MAX_VALUE;
        for(CFUnit other:neighbors) {
            Pos p=other.getPosition();double dx=at.x()-p.x(),dz=at.z()-p.z();
            best=Math.min(best,dx*dx+dz*dz);
        }
        return best;
    }

    private static double totalOverlap(CFCrowdGeometry.Body self,Pos p,
                                       List<CFCrowdGeometry.Body> neighbors) {
        double sum=0;
        for(var other:neighbors)sum+=CFCrowdGeometry.overlapArea(self,p.x(),p.y(),p.z(),other);
        return sum;
    }

    /** Legacy pairwise swept-rectangle test retained for CF262 regression coverage. */
    static boolean sweeps(BoundingBox moving,Pos from,Pos to,BoundingBox stationary,Pos at) {
        if(Math.max(from.y()+moving.maxY(),to.y()+moving.maxY())<=at.y()+stationary.minY()
                ||Math.min(from.y()+moving.minY(),to.y()+moving.minY())>=at.y()+stationary.maxY())return false;
        double minX=at.x()+stationary.minX()-moving.maxX()-GAP;
        double maxX=at.x()+stationary.maxX()-moving.minX()+GAP;
        double minZ=at.z()+stationary.minZ()-moving.maxZ()-GAP;
        double maxZ=at.z()+stationary.maxZ()-moving.minZ()+GAP;
        if(from.x()>minX&&from.x()<maxX&&from.z()>minZ&&from.z()<maxZ) {
            double oldDepth=Math.min(from.x()-minX,maxX-from.x())*
                    Math.min(from.z()-minZ,maxZ-from.z());
            double newDepth=to.x()>minX&&to.x()<maxX&&to.z()>minZ&&to.z()<maxZ
                    ? Math.min(to.x()-minX,maxX-to.x())*Math.min(to.z()-minZ,maxZ-to.z()):0;
            return newDepth+1.0e-5>=oldDepth;
        }
        double dx=to.x()-from.x(),dz=to.z()-from.z();
        double enter=0,exit=1;
        if(Math.abs(dx)<1.0e-10){if(from.x()<=minX||from.x()>=maxX)return false;}
        else{double a=(minX-from.x())/dx,b=(maxX-from.x())/dx;
            enter=Math.max(enter,Math.min(a,b));exit=Math.min(exit,Math.max(a,b));}
        if(Math.abs(dz)<1.0e-10){if(from.z()<=minZ||from.z()>=maxZ)return false;}
        else{double a=(minZ-from.z())/dz,b=(maxZ-from.z())/dz;
            enter=Math.max(enter,Math.min(a,b));exit=Math.min(exit,Math.max(a,b));}
        return enter<exit&&enter<1.0&&exit>0;
    }
}
