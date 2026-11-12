package dev.cf;

import net.minestom.server.collision.BoundingBox;
import net.minestom.server.coordinate.*;
import net.minestom.server.entity.pathfinding.PNode;
import net.minestom.server.entity.pathfinding.generators.PreciseGroundNodeGenerator;
import net.minestom.server.instance.block.Block;
import java.util.*;

/** March constraints refer to this unit's lane only. Combat continues using the normal AI. */
final class CFLaneRuntimeGenerator extends CFGroundGenerator {
    private final CFUnit unit;
    CFLaneRuntimeGenerator(CFUnit unit){this.unit=unit;}
    @Override public Collection<? extends PNode> getWalkable(Block.Getter w,Set<PNode> visited,PNode current,Point goal,BoundingBox box) {
        // Terrain path candidates must not depend on the particular mob's body size.
        var candidates=super.getWalkable(w,visited,current,goal,CFThreeLanes.terrainPoint());
        if(!unit.marchGoal().constrainLane())return candidates;
        return candidates.stream().filter(n->{Pos p=new Pos(n.x(),n.y(),n.z());
            return CFVerticalTravel.nodeHeightAllowed(current.y(),n.y()) &&unit.marchGoal().allowsLaneBody(p)&&CFLaneSearch.clear(w,CFThreeLanes.terrainPoint(),p);
        }).toList();
    }
}
