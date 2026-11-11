package dev.cf;

import net.minestom.server.collision.*;
import net.minestom.server.coordinate.*;
import net.minestom.server.entity.pathfinding.PNode;
import net.minestom.server.entity.pathfinding.generators.PreciseGroundNodeGenerator;
import net.minestom.server.instance.block.Block;
import java.util.*;

/** Collision-swept ground neighbors, including half-height steps without integer rounding. */
class CFGroundGenerator extends PreciseGroundNodeGenerator {
    @Override public Collection<? extends PNode> getWalkable(Block.Getter w,Set<PNode> visited,PNode current,Point goal,BoundingBox box) {
        var out=new ArrayList<PNode>();
        Pos from=new Pos(current.x(),current.y(),current.z());
        for(int dx=-1;dx<=1;dx++)for(int dz=-1;dz<=1;dz++) {
            if(dx==0&&dz==0)continue;
            Pos top=new Pos(current.blockX()+.5+dx,current.y()+1.01,current.blockZ()+.5+dz);
            var fall=CollisionUtils.handlePhysics(w,box,top,new Vec(0,-2.02,0),null,true);
            if(!fall.collisionY())continue;
            Pos to=fall.newPosition();double rise=to.y()-from.y();
            if(Math.abs(rise)>1.001 || !CFLaneSearch.clear(w,box,to))continue;
            Pos lifted=from.withY(Math.max(from.y(),to.y())+.002);
            Pos across=new Pos(to.x(),lifted.y(),to.z());
            if(!canMoveTowards(w,from.add(0,.002,0),lifted,box) || !canMoveTowards(w,lifted,across,box))continue;
            var n=new PNode(to,current.g()+Math.hypot(dx,dz)+Math.max(0,rise)*.3,heuristic(to,goal),
                    rise>.01?PNode.Type.JUMP:rise<-.01?PNode.Type.FALL:PNode.Type.WALK,current);
            if(!visited.contains(n) && out.stream().noneMatch(n::equals))out.add(n);
        }
        return out;
    }
}
