package dev.cf;

import net.minestom.server.collision.BoundingBox;
import net.minestom.server.coordinate.Point;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.entity.pathfinding.PNode;
import net.minestom.server.instance.block.Block;

import java.util.Collection;
import java.util.Set;

/** A castle king is allowed to walk ONLY over his own 3x3 ceramic floor. */
final class CFKingThroneGenerator extends CFGroundGenerator {
    private final int cx,cz;
    private final Pos throne;
    CFKingThroneGenerator(Pos throne) {
        this.throne=throne;
        cx=throne.blockX();cz=throne.blockZ();
    }
    @Override public Collection<? extends PNode> getWalkable(Block.Getter world,
            Set<PNode> visited,PNode current,Point goal,BoundingBox box) {
        return super.getWalkable(world,visited,current,goal,box).stream().filter(node->
                Math.abs((int)Math.floor(node.x())-cx)<=1 &&
                Math.abs((int)Math.floor(node.z())-cz)<=1 &&
                Math.abs(node.y()-throne.y())<.51).toList();
    }
}
