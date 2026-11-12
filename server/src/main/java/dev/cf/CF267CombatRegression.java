package dev.cf;

import net.minestom.server.MinecraftServer;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.instance.InstanceContainer;
import net.minestom.server.instance.block.Block;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/** Only an in-memory world. Checks 10 altars, exact double positions and instant target re-lock. */
public final class CF267CombatRegression {
    private record Spawn(String team,String mob,int lane,Pos at,boolean exact) { }
    private static int checks;
    private static void check(boolean condition,String problem) {
        checks++;
        if(!condition)throw new AssertionError(problem);
    }
    private static void samePos(Pos actual,Pos expected,String issue) {
        check(Double.doubleToLongBits(actual.x())==Double.doubleToLongBits(expected.x())
                && Double.doubleToLongBits(actual.y())==Double.doubleToLongBits(expected.y())
                && Double.doubleToLongBits(actual.z())==Double.doubleToLongBits(expected.z()),issue+
                " actual="+actual+" expected="+expected);
    }
    private static CFUnit make(InstanceContainer world, CFContentRegistry content,Set<CFUnit> units,
                               String team,String kind,Pos p) throws Exception {
        CFUnit mob=new CFUnit(team,content.mob(kind),p,new Pos(30,8,p.z()));
        mob.getAIGroups().clear();
        mob.assignLane(List.of(p,p.add(12,0,0)),9);
        mob.setInstance(world,p).get(10,TimeUnit.SECONDS);
        mob.markSpawnCompleted();
        units.add(mob);
        return mob;
    }
    private static void pump(InstanceContainer world,long ms) throws Exception {
        long until=System.nanoTime()+ms*1_000_000L;
        while(System.nanoTime()<until) {
            world.scheduler().processTick();
            world.scheduler().process();
            Thread.sleep(10);
        }
    }
    public static void main(String[] args) throws Exception {
        if(args.length!=1)throw new IllegalArgumentException("content root expected");
        MinecraftServer.init();
        try {
            Path contentRoot=Path.of(args[0]);
            CFContentRegistry content=new CFContentRegistry(contentRoot);
            InstanceContainer world=MinecraftServer.getInstanceManager().createInstanceContainer();
            world.setGenerator(chunk->chunk.modifier().fillHeight(0,8,Block.STONE));
            world.loadChunk(0,0).get(10,TimeUnit.SECONDS);
            world.loadChunk(1,0).get(10,TimeUnit.SECONDS);
            Set<CFUnit> units=ConcurrentHashMap.newKeySet();
            List<Spawn> spawns=new ArrayList<>();
            CFArenaAbilities.Summoner summoner=new CFArenaAbilities.Summoner() {
                @Override public boolean summon(String team,String mob,int lane,Pos near) {
                    spawns.add(new Spawn(team,mob,lane,near,false));return true;
                }
                @Override public boolean summonAtDeath(String team,String mob,int lane,Pos at) {
                    spawns.add(new Spawn(team,mob,lane,at,true));return true;
                }
            };
            CFArenaAbilities buffs=new CFArenaAbilities(world,units,contentRoot,summoner);
            for(int i=0;i<10;i++)buffs.addBuilding(100+i,"blue","mutation",0);
            Pos original=new Pos(10.3125,8,10.8125);
            CFUnit victim=make(world,content,units,"red","grave_zombie",original);
            check(victim.laneId()>0,"victim has lane");
            for(int i=0;i<10;i++)buffs.blessResurrection(victim,200+i,"red",5000);
            check(buffs.activeResurrectionBlessings()==1,"10 blessings = 1 active state");
            check(buffs.pendingCount()==1,"10 refreshes = 1 expiry timer");
            victim.kill();
            buffs.onDeath(victim);
            buffs.onDeath(victim); // artificially repeated event
            pump(world,2250);
            check(spawns.size()==2,"one revive plus one mutation, no extra results");
            check(spawns.stream().filter(s->s.team().equals("red")).count()==1,"one resurrection");
            check(spawns.stream().filter(s->s.team().equals("blue")).count()==1,"one mutation");
            for(Spawn record:spawns) {
                check(record.exact(),"death proc must use exact-spawn path");
                samePos(record.at(),original,"fractional X/Y/Z must survive death proc");
            }
            check(buffs.activeResurrectionBlessings()==0,"death consumed unique buff");
            check(buffs.counters().get("revivals")==1,"one revival counter");
            check(buffs.counters().get("mutations")==1,"one mutation counter");
            check(buffs.pendingCount()<=11,"no timer explosion");

            // Expiration of a short initial cast must NOT cancel a longer refresh.
            CFUnit second=make(world,content,units,"red","grave_zombie",new Pos(13.125,8,11.875));
            buffs.blessResurrection(second,500,"red",50);
            buffs.blessResurrection(second,501,"red",1000);
            pump(world,160);
            check(buffs.activeResurrectionBlessings()==1,"unique buff remains after first deadline");
            buffs.clearUnits();
            check(buffs.activeResurrectionBlessings()==0,"round cleanup clears all effects");
            // This mob belongs to the previous buff test. It is still a REAL, visible
            // red entity until killed. The earlier test accidentally treated it as
            // absent and blamed the archer for correctly targeting it.
            second.kill();
            check(second.isDead(),"remove unrelated enemy before archer regression");

            // Directly seed a ranged lock and destroy its target. Next evaluation
            // must retain the combat goal and switch to another visible enemy.
            CFUnit archer=make(world,content,units,"blue","risen",new Pos(4.25,8,4.25));
            CFUnit first=make(world,content,units,"red","grave_zombie",new Pos(8.25,8,4.25));
            CFUnit other=make(world,content,units,"red","grave_zombie",new Pos(9.75,8,4.25));
            CFHoldRangedGoal ranged=new CFHoldRangedGoal(archer);
            check(ranged.shouldStart(),"archer acquires an actual visible enemy");
            check(archer.traceTarget()==first,"nearest enemy acquired");
            ranged.start();
            Pos before=archer.getPosition();
            first.kill();
            check(!ranged.shouldEnd(),"re-lock same frame instead of handing control to march");
            check(archer.traceTarget()==other,"instant retarget selects living visible enemy");
            check(archer.isRangedLocked(),"combat hold continues after retarget");
            samePos(archer.getPosition(),before,"re-target must not move archer forward");
            other.kill();
            check(ranged.shouldEnd(),"release combat goal when truly no visible enemy remains");
            ranged.end();
            check(!archer.isRangedLocked(),"normal march can resume without a target");
            System.out.println("[CF 2.6.7 COMBAT] PASS "+checks+
                    " assertions: 10 altars, one death proc, exact positions, instant ranged re-lock");
        } finally {
            MinecraftServer.stopCleanly();
        }
    }
}
