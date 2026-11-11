package dev.cf;

import net.minestom.server.MinecraftServer;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.coordinate.Vec;
import net.minestom.server.entity.EntityType;
import net.minestom.server.instance.InstanceContainer;
import net.minestom.server.instance.block.Block;
import net.minestom.server.item.Material;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Isolated live-engine regression in a NEW in-memory world. No saved map, socket or Main.
 * Deterministic assertions, NOT a TPS/p99 benchmark or a substitute for a real arena run.
 */
public final class CFWideLaneWorldSmoke {
    private static int checks;
    private static InstanceContainer world;
    private static CFLanePath lane;
    private static long clock=System.currentTimeMillis();
    private static final CFContentRegistry.Mob ZOMBIE=new CFContentRegistry.Mob(
            "grave_zombie","Test zombie",EntityType.ZOMBIE,"melee",6,.1,1,1.5,0,1000,Material.AIR);
    static void check(boolean ok,String why){checks++;if(!ok)throw new AssertionError(why);}
    static CFUnit unit(double x,double z)throws Exception {
        Pos p=new Pos(x,8,z);CFUnit u=new CFUnit("blue",ZOMBIE,p,lane.blue.getLast());
        u.assignValidatedLane(lane,lane.blue);u.setInstance(world,p).get(10,TimeUnit.SECONDS);
        u.markSpawnCompleted();return u;
    }
    static void tick(List<CFUnit> units,boolean async)throws Exception {
        clock+=50;world.scheduler().processTick();world.scheduler().process();
        for(CFUnit u:units)u.tick(clock);
        if(async)Thread.sleep(2);
    }
    static CFUnit probe(double x,double z)throws Exception {
        CFUnit u=unit(x,z);u.getAIGroups().clear();u.marchGoal().start();return u;
    }
    public static void main(String[] args)throws Exception {
        MinecraftServer.init();
        try {
            world=MinecraftServer.getInstanceManager().createInstanceContainer();
            world.setGenerator(u->u.modifier().fillHeight(0,8,Block.STONE));
            for(int x=-1;x<=8;x++)for(int z=-1;z<=1;z++)world.loadChunk(x,z).get(10,TimeUnit.SECONDS);
            var forward=new ArrayList<Pos>();for(int x=0;x<=120;x++)forward.add(new Pos(x+.5,8,.5));
            var reverse=new ArrayList<>(forward);Collections.reverse(reverse);
            lane=new CFLanePath(1,forward,reverse);
            var group=new ArrayList<CFUnit>();double[] initial=new double[40];
            for(int row=0;row<5;row++)for(int col=0;col<8;col++) {
                double z=.5-3.15+col*.9;initial[group.size()]=z;
                group.add(unit(2.5+row*.9,z));
            }
            for(int i=0;i<200;i++)tick(group,false);
            double minZ=Double.POSITIVE_INFINITY,maxZ=Double.NEGATIVE_INFINITY;
            for(int i=0;i<group.size();i++) {
                CFUnit u=group.get(i);Pos p=u.getPosition();
                check(p.x()>20,"open-road progress: "+p);
                check(Math.abs(p.z()-initial[i])<.12,"no centre pull: "+initial[i]+" -> "+p);
                check(u.marchGoal().movementMode().equals("WIDE_DIRECT"),"direct mode on open road");
                minZ=Math.min(minZ,p.z());maxZ=Math.max(maxZ,p.z());
            }
            check(maxZ-minZ>6.0,"40-unit front retains six-block width");
            for(CFUnit u:group)u.remove();
            CFUnit pushed=unit(10,.5);tick(List.of(pushed),false);
            pushed.setVelocity(new Vec(0,0,2));
            for(int i=0;i<15;i++)tick(List.of(pushed),false);
            double movedZ=pushed.getPosition().z();
            check(movedZ>.6,"external impulse still moves marching unit");
            for(int i=0;i<80;i++)tick(List.of(pushed),false);
            check(pushed.getPosition().z()>=movedZ-.02,"does not return to old lateral slot");
            pushed.remove();

            for(int y=8;y<=11;y++)for(int z=-4;z<=4;z++)world.setBlock(10,y,z,Block.STONE);
            CFUnit blocked=probe(9,.5);var walker=new CFSmoothGroundFollower(blocked);
            for(int i=0;i<30;i++)walker.moveTowards(new Pos(12,8,.5),.1,new Pos(12,8,.5));
            check(blocked.getPosition().x()<9.71,"cannot walk through solid wall");blocked.remove();
            for(int y=8;y<=11;y++)for(int z=-4;z<=4;z++)world.setBlock(10,y,z,Block.AIR);

            world.setBlock(10,8,0,Block.WATER);
            CFUnit water=probe(9,.5);walker=new CFSmoothGroundFollower(water);
            for(int i=0;i<30;i++)walker.moveTowards(new Pos(12,8,.5),.1,new Pos(12,8,.5));
            check(water.getPosition().x()<9.71,"walking does not enter water");water.remove();
            world.setBlock(10,8,0,Block.AIR);
            for(int y=0;y<8;y++)world.setBlock(10,y,0,Block.AIR);
            CFUnit pit=probe(9,.5);walker=new CFSmoothGroundFollower(pit);
            for(int i=0;i<30;i++)walker.moveTowards(new Pos(12,8,.5),.1,new Pos(12,8,.5));
            check(pit.getPosition().x()<10.31,"walking does not cross an unsupported pit");pit.remove();
            for(int y=0;y<8;y++)world.setBlock(10,y,0,Block.STONE);

            // Real native A* fallback around a three-block wide obstacle inside the road.
            for(int x=15;x<=17;x++)for(int z=-1;z<=1;z++)for(int y=8;y<=10;y++)world.setBlock(x,y,z,Block.STONE);
            CFUnit detour=unit(11,.5);boolean sawPath=false;
            for(int i=0;i<700 && detour.getPosition().x()<22;i++) {
                tick(List.of(detour),true);
                if(detour.marchGoal().movementMode().equals("WIDE_TERRAIN_PATH"))sawPath=true;
                check(detour.laneId()==1,"detour never changes lane id");
            }
            check(sawPath,"obstacle uses native terrain fallback");
            check(detour.getPosition().x()>21.5,"detour passed obstacle: "+detour.getPosition());
            detour.remove();
            System.out.println("[CF266 WIDE WORLD] PASS "+checks+" assertions; frontWidth="+(maxZ-minZ)+
                    "; temporary world, no production map or performance claim");
        } finally {MinecraftServer.stopCleanly();}
    }
}
