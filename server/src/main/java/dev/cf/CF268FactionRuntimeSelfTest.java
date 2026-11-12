package dev.cf;

import net.minestom.server.MinecraftServer;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.instance.InstanceContainer;
import net.minestom.server.instance.block.Block;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Mandatory real Java25/Minestom smoke test for every new faction mob model. */
public final class CF268FactionRuntimeSelfTest {
    private static int assertions;
    private static void check(boolean good,String why) {
        assertions++;if(!good)throw new AssertionError(why);
    }
    public static void main(String[] args)throws Exception {
        if(args.length!=1)throw new IllegalArgumentException("content directory required");
        MinecraftServer.init();
        var live=new ArrayList<CFUnit>();
        try {
            var root=Path.of(args[0]);
            var content=new CFContentRegistry(root);
            InstanceContainer world=MinecraftServer.getInstanceManager().createInstanceContainer();
            world.setGenerator(chunk->chunk.modifier().fillHeight(0,8,Block.STONE));
            for(int cx=0;cx<2;cx++)for(int cz=0;cz<2;cz++)
                world.loadChunk(cx,cz).get(10,TimeUnit.SECONDS);
            var current=new java.util.HashSet<CFUnit>();
            var abilities=new CFArenaAbilities(world,current,root,new CFArenaAbilities.Summoner(){
                @Override public boolean summon(String team,String kind,int lane,Pos p){return false;}
            });
            int i=0;
            for(var def:content.mobs()) {
                if(!def.id().startsWith("dark_") && !def.id().startsWith("snow_"))continue;
                Pos at=new Pos(2+(i%12)*2,8,2+(i/12)*2);
                CFUnit mob=new CFUnit("blue",def,at,new Pos(30,8,30));
                mob.getAIGroups().clear(); // Avoid advancing real AI during content smoke.
                mob.setInstance(world,at).get(10,TimeUnit.SECONDS);
                mob.markSpawnCompleted();
                live.add(mob);current.add(mob);
                check(mob.health10()==def.health10(),"live HP10 for "+def.id());
                check(mob.attackDamage10()==def.damage10(),"live damage10 for "+def.id());
                check(Math.abs(mob.getHealth()-def.health10()/10f)<.0001f,"Minestom HP bridge for "+def.id());
                check(mob.combatAlive(),"new faction unit alive: "+def.id());
                i++;
            }
            check(i==25,"25 faction mob models actually spawned");
            CFUnit wolf=live.stream().filter(u->u.contentId().equals("snow_wolf_cave_l1"))
                   .findFirst().orElseThrow();
            check(wolf.criticalChancePercent()==25 && wolf.criticalDamage10()==23,
                  "fixed integer wolf bite used at runtime");
            wolf.setHealth(4.5f);
            check(wolf.health10()==45,"setHealth applies exact int10 bridge");
            check(abilities.activeResurrectionBlessings()==0,"no stray resurrection effects");
            System.out.println("[CF FACTIONS RUNTIME] PASS "+assertions+
                    " checks: 25 real Minestom faction entities and int10 health/damage");
        } finally {
            for(var mob:live)if(!mob.isRemoved())mob.remove();
            MinecraftServer.stopCleanly();
        }
    }
}
