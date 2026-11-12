package dev.cf;

import net.minestom.server.collision.*;
import net.minestom.server.coordinate.*;
import net.minestom.server.entity.pathfinding.*;
import net.minestom.server.entity.pathfinding.generators.PreciseGroundNodeGenerator;
import net.minestom.server.instance.Instance;
import net.minestom.server.instance.block.Block;
import java.util.*;
import java.util.function.Predicate;

/** Collision-backed bounded local planning. No partial/BEST_EFFORT path is certified. */
final class CFLaneSearch {
    static Block.Getter terrain(Instance world) {
        return (x,y,z,c)->{
            if(world.getChunk(Math.floorDiv(x,16),Math.floorDiv(z,16))==null)return Block.BARRIER;
            Block b=world.getBlock(x,y,z,c);
            return CFMarkerPalette.of(b)!=null?Block.AIR:b; // Carpet markers are map metadata, not physical steps
        };
    }
    static boolean clear(Block.Getter w,BoundingBox box,Pos p) {
        var it=box.getBlocks(p.add(0,.002,0));
        while(it.hasNext()) {var c=it.next();Block b=w.getBlock(c.blockX(),c.blockY(),c.blockZ());
            if(b.liquid() || b.collisionShape().intersectBox(p.add(0,.002,0).sub(c.blockX(),c.blockY(),c.blockZ()),box))return false;
        }
        return CollisionUtils.handlePhysics(w,box,p.add(0,.002,0),new Vec(0,-.08,0),null,true).collisionY();
    }
    static Pos surface(Block.Getter w,BoundingBox box,Pos p) {
        var r=CollisionUtils.handlePhysics(w,box,p.add(0,.05,0),new Vec(0,-1.15,0),null,true);
        Pos q=r.newPosition();
        if(!r.collisionY()||!clear(w,box,q))throw new IllegalArgumentException("No dry walkable floor/headroom at "+p);
        return new Pos(q.x(),q.y(),q.z());
    }
    static Predicate<Point> tube(Pos a,Pos b,double radius) {
        return p->segmentDistanceSq(a,b,p)<=radius*radius &&
            p.y()>=Math.min(a.y(),b.y())-2 && p.y()<=Math.max(a.y(),b.y())+2;
    }
    static double segmentDistanceSq(Point a,Point b,Point p) {
        double dx=b.x()-a.x(),dz=b.z()-a.z(),len=dx*dx+dz*dz;
        double t=len<1e-8?0:Math.clamp(((p.x()-a.x())*dx+(p.z()-a.z())*dz)/len,0,1);
        double x=p.x()-a.x()-dx*t,z=p.z()-a.z()-dz*t;return x*x+z*z;
    }
    /**
     * Certify a flat, straight connector without trusting a pathfinder's
     * BEST_EFFORT result. Even a fully open synthetic road can make the
     * native search exhaust its heuristic budget; that is not a blocked road.
     *
     * This is a strict collision-backed shortcut, NOT a line through blocks:
     * every quarter-block sample must be inside the allowed corridor, have
     * solid walkable support, have clear headroom, and be swept-reachable from
     * the previous sample. A blocked/sloped connector falls back to the
     * normal ground pathfinder (or fails the compiler if no full path exists).
     */
    static List<Pos> certifiedFlatConnector(Block.Getter w,BoundingBox box,Pos a,Pos b,Predicate<Point> domain) {
        if(Math.abs(a.y()-b.y())>.01 || !domain.test(a) || !domain.test(b))return null;
        int steps=Math.max(1,(int)Math.ceil(a.distance(b)*4));
        if(steps>384)return null; // the 96-block link limit is enforced by find()
        var path=new ArrayList<Pos>(steps+1);
        var ground=new CFGroundGenerator();
        Pos previous=a;
        path.add(a);
        for(int i=1;i<=steps;i++) {
            double t=(double)i/steps;
            Pos next=i==steps ? b : new Pos(
                    a.x()+(b.x()-a.x())*t,
                    a.y()+(b.y()-a.y())*t,
                    a.z()+(b.z()-a.z())*t);
            if(!domain.test(next) || !clear(w,box,next) ||
                    !ground.canMoveTowards(w,previous.add(0,.002,0),next.add(0,.002,0),box))
                return null;
            path.add(next);
            previous=next;
        }
        return List.copyOf(path);
    }

    static List<Pos> find(Block.Getter w,BoundingBox box,Pos a,Pos b,Predicate<Point> domain) {
        if(!clear(w,box,a)||!clear(w,box,b))throw new IllegalArgumentException("Blocked start/end: "+a+" -> "+b);
        if(a.distanceSquared(b)<.0001)return List.of(a);
        if(a.distance(b)>96)throw new IllegalArgumentException("Connection >96 blocks; extend road dots nearer the castle/tower");
        // Fully certify a simple level segment before invoking heuristic A*.
        // This avoids false BEST_EFFORT failures on open, flat castle links.
        List<Pos> straight=certifiedFlatConnector(w,box,a,b,domain);
        if(straight!=null)return straight;
        var path=PathGenerator.generate(w,a,b,.35,Math.min(220,Math.max(32,a.distance(b)*2+12)),32,
                box,true,new Bounded(domain),null);
        var nodes=path.getNodes();
        if(nodes.isEmpty())throw new IllegalArgumentException("No path: "+a+" -> "+b);
        var last=nodes.getLast();
        if(new Pos(last.x(),last.y(),last.z()).distanceSquared(b)>.0025)
            throw new IllegalArgumentException("Incomplete path (not certified): "+a+" -> "+b);
        var result=new ArrayList<Pos>();result.add(a);
        for(var n:nodes) {
            Pos p=new Pos(n.x(),n.y(),n.z());
            if(n.getType()==PNode.Type.REPATH || !domain.test(p)||!clear(w,box,p))
                throw new IllegalArgumentException("Unsafe/incomplete path node "+p);
            if(p.distanceSquared(result.getLast())>.0001)result.add(p);
        }
        return List.copyOf(result);
    }
    static final class Bounded extends CFGroundGenerator {
        private final Predicate<Point> domain;private int expanded;
        Bounded(Predicate<Point> domain){this.domain=domain;}
        @Override public Collection<? extends PNode> getWalkable(Block.Getter w,Set<PNode> visited,PNode current,Point goal,BoundingBox box) {
            if(++expanded>4000)throw new IllegalArgumentException("Path search budget exceeded; put road dots closer");
            return super.getWalkable(w,visited,current,goal,box).stream().filter(n->{
                Pos p=new Pos(n.x(),n.y(),n.z());
                return CFVerticalTravel.nodeHeightAllowed(current.y(),n.y()) && domain.test(p)&&clear(w,box,p);
            }).toList();
        }
    }
    private CFLaneSearch() { }
}
