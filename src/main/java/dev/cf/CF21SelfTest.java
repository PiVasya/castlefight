package dev.cf;

import net.minestom.server.collision.*;
import net.minestom.server.coordinate.*;
import net.minestom.server.instance.WorldBorder;
import net.minestom.server.instance.block.Block;
import java.nio.file.*;
import java.util.*;

/** Safe install gate: no MinecraftServer.init, port, real world or navigation file writes. */
public final class CF21SelfTest {
    private static int checks;
    private static void check(boolean v,String why){checks++;if(!v)throw new AssertionError(why);}
    public static void main(String[] args) throws Exception {
        Map<CFPlacementRules.Cell,String> grid=new HashMap<>();
        CFPlacementRules.Lookup lookup=(x,y,z)->grid.get(new CFPlacementRules.Cell(x,y,z));
        for(int x=14;x<=16;x++)for(int z=-1;z<=1;z++)grid.put(new CFPlacementRules.Cell(x,8,z),"blue");
        check(CFPlacementRules.allowed(lookup,"blue",15,8,0),"3x3 across chunk boundaries");
        check(!CFPlacementRules.allowed(lookup,"red",15,8,0),"wrong team rejected");
        check(!CFPlacementRules.allowed(lookup,"blue",15,9,0),"wrong height rejected");
        check(!CFPlacementRules.allowed(lookup,"blue",14,8,0),"edge footprint rejected");
        for(var c:List.copyOf(grid.keySet())){
            grid.remove(c);check(!CFPlacementRules.allowed(lookup,"blue",15,8,0),"every one of 9 cells required");
            grid.put(c,"red");check(!CFPlacementRules.allowed(lookup,"blue",15,8,0),"mixed color rejected");grid.put(c,"blue");
        }
        check(!CFStepPolicy.mayJump(.5,true,true,false,100),"half slab is not a jump");
        check(!CFStepPolicy.mayJump(1,true,true,true,100),"ranged lock forbids jump");
        check(!CFStepPolicy.mayJump(1,false,true,false,100),"jump needs actual obstacle");
        check(!CFStepPolicy.mayJump(1,true,true,false,2),"jump cooldown");
        check(CFStepPolicy.mayJump(1,true,true,false,100),"full step can jump");
        check(!CFStepPolicy.acceptStep(1,0,.1,true),"no wall climbing");
        check(!CFStepPolicy.acceptStep(.5,0,.1,false),"landing required");
        check(!CFStepPolicy.acceptStep(.5,.1,.1,true),"progress required");
        check(!CFStepPolicy.acceptStep(Double.NaN,0,.1,true),"NaN rejected");

        var box=new BoundingBox(.6,1.99,.6);
        Pos p=new Pos(.68,0,.5);
        Vec walk=new Vec(.075,0,0);
        Block.Getter slab=(x,y,z,c)->y==-1?Block.STONE:y==0&&x>=1?Block.OAK_SLAB:Block.AIR;
        var plain=CollisionUtils.handlePhysics(slab,box,p,walk,null,false);
        Pos step=CFStepSolver.attempt(slab,WorldBorder.DEFAULT_BORDER,box,p,walk,plain.newPosition());
        check(plain.collisionX(),"test setup: half slab obstructs horizontal motion");
        check(step!=null&&Math.abs(step.y()-.5)<.002&&step.x()>.74,"half slab sweep climbs .5 without jumping");
        Block.Getter wall=(x,y,z,c)->y==-1||x>=1&&y==0?Block.STONE:Block.AIR;
        var wallHit=CollisionUtils.handlePhysics(wall,box,p,walk,null,false);
        check(CFStepSolver.attempt(wall,WorldBorder.DEFAULT_BORDER,box,p,walk,wallHit.newPosition())==null,
                "0.6 step does not pass full-block wall");
        Block.Getter lowCeiling=(x,y,z,c)->y==2?Block.STONE:slab.getBlock(x,y,z,c);
        var low=CollisionUtils.handlePhysics(lowCeiling,box,p,walk,null,false);
        check(CFStepSolver.attempt(lowCeiling,WorldBorder.DEFAULT_BORDER,box,p,walk,low.newPosition())==null,
                "step does not pass low ceiling");
        Pos onSlab=new Pos(1.68,.5,.5);
        Block.Getter nextStep=(x,y,z,c)->y==-1?Block.STONE:y==0&&x>=2?Block.OAK_PLANKS:
                y==0&&x>=1?Block.OAK_SLAB:Block.AIR;
        var next=CollisionUtils.handlePhysics(nextStep,box,onSlab,walk,null,false);
        Pos ontoFull=CFStepSolver.attempt(nextStep,WorldBorder.DEFAULT_BORDER,box,onSlab,walk,next.newPosition());
        check(ontoFull!=null&&Math.abs(ontoFull.y()-1)<.002,"slab -> full block is another half step");

        List<Pos> points=List.of(new Pos(0,0,0),new Pos(10,0,0),new Pos(20,0,0),new Pos(30,0,0));
        check(CFLaneGeometry.startCheckpoint(points,new Pos(21,0,2))==3,"tower joins forward near third waypoint");
        var reverse=new ArrayList<>(points);Collections.reverse(reverse);
        check(CFLaneGeometry.startCheckpoint(reverse,new Pos(21,0,2))==1,"reverse team joins forward toward other castle");
        Path root=Path.of(args.length==0?"content":args[0]);
        var content=new CFContentRegistry(root);
        check(!content.mobs().isEmpty()&&!content.towers().isEmpty(),"non-empty valid content");
        for(var t:content.towers())for(var entry:t.recipe())check(content.mob(entry.mob())!=null,"all spawn references resolve");
        System.out.println("[CF21 TEST] PASS "+checks+" assertions; content validated. No world files opened.");
    }
}
