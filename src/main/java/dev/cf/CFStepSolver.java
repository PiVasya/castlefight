package dev.cf;

import net.minestom.server.collision.*;
import net.minestom.server.coordinate.*;
import net.minestom.server.instance.WorldBorder;
import net.minestom.server.instance.block.Block;

/** Small, testable collision sweep. No entity mutation or path search. */
final class CFStepSolver {
    static Pos attempt(Block.Getter world, WorldBorder border, BoundingBox box, Pos p,
                       Vec delta, Pos plain) {
        var up=CollisionUtils.handlePhysics(world,border,box,p,
                new Vec(0,CFStepPolicy.MAX_STEP,0),null,false);
        double raised=up.newPosition().y()-p.y();
        if(raised<=.001)return null;
        var across=CollisionUtils.handlePhysics(world,border,box,up.newPosition(),delta,null,false);
        var down=CollisionUtils.handlePhysics(world,border,box,across.newPosition(),
                new Vec(0,-raised-.02,0),null,false);
        Pos result=down.newPosition();
        return CFStepPolicy.acceptStep(result.y()-p.y(),xz2(plain,p),xz2(result,p),down.isOnGround())
                ? result : null;
    }
    private static double xz2(Point a,Point b){double dx=a.x()-b.x(),dz=a.z()-b.z();return dx*dx+dz*dz;}
    private CFStepSolver() { }
}
