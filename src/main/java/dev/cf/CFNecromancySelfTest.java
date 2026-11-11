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

/**
 * Live Minestom regression. Death procs keep the victim's validated lane (1, 2, 3).
 * Necromancy changes the team to the necromancer's team; resurrection retains the victim's team.
 */
public final class CFNecromancySelfTest {
    private record Spawn(String team,String mob,int lane,Pos at,boolean exact) { }
    private static int checks;
    private static void check(boolean ok,String message) {
        checks++;
        if(!ok)throw new AssertionError(message);
    }
    private static void pump(InstanceContainer world,long ms) throws Exception {
        long until=System.nanoTime()+ms*1_000_000L;
        while(System.nanoTime()<until) {
            world.scheduler().processTick();
            world.scheduler().process();
            Thread.sleep(10);
        }
    }
    /** Assign a REAL, immutable CFLanePath, not the legacy assignLane(width) helper. */
    private static CFUnit mob(InstanceContainer world,CFContentRegistry content,Set<CFUnit> units,
                              String team,int laneId,Pos pos) throws Exception {
        check(laneId>=1 && laneId<=3,"test uses real lane IDs only");
        CFUnit u=new CFUnit(team,content.mob("grave_zombie"),pos,
                pos.add("blue".equals(team)?12:-12,0,0));
        u.getAIGroups().clear();
        CFLanePath lane=new CFLanePath(laneId,
                List.of(pos,pos.add(12,0,0)),
                List.of(pos,pos.add(-12,0,0)));
        u.assignValidatedLane(lane,lane.forTeam(team));
        check(u.laneId()==laneId,"validated lane matches the victim's lane");
        u.setInstance(world,pos).get(10,TimeUnit.SECONDS);
        u.markSpawnCompleted();
        units.add(u);
        return u;
    }
    private static Spawn result(List<Spawn> results,int start,String team,int lane,Pos at) {
        List<Spawn> matches=results.subList(start,results.size()).stream()
                .filter(s->s.team().equals(team) && s.lane()==lane && s.at().equals(at))
                .toList();
        check(matches.size()==1,"exactly one spawn for team="+team+" lane="+lane+" at="+at+
                " (got "+matches.size()+")");
        Spawn found=matches.getFirst();
        check(found.exact(),"death spawn uses exact-position path");
        return found;
    }
    public static void main(String[] args) throws Exception {
        if(args.length!=1)throw new IllegalArgumentException("content root required");
        Path path=Path.of(args[0]);
        CFContentRegistry registry=new CFContentRegistry(path);
        var necro=registry.tower("dark_necromancer_tower");
        check(necro!=null && necro.ability().equals("necromancy") && necro.recipe().isEmpty(),
                "necromancer reacts to enemy death, NOT a periodic servant spawn");
        check(registry.tower("dark_death_altar").ability().equals("resurrection"),
                "death altar retains existing resurrection");
        check(registry.tower("dark_toxic_factory").ability().equals("toxic"),
                "toxic factory retains existing poison");
        MinecraftServer.init();
        try {
            InstanceContainer world=MinecraftServer.getInstanceManager().createInstanceContainer();
            world.setGenerator(chunk->chunk.modifier().fillHeight(0,8,Block.STONE));
            world.loadChunk(0,0).get(10,TimeUnit.SECONDS);
            world.loadChunk(1,0).get(10,TimeUnit.SECONDS);
            Set<CFUnit> units=ConcurrentHashMap.newKeySet();
            List<Spawn> results=new ArrayList<>();
            CFArenaAbilities.Summoner summoner=new CFArenaAbilities.Summoner() {
                @Override public boolean summon(String team,String kind,int lane,Pos at) {
                    results.add(new Spawn(team,kind,lane,at,false));return true;
                }
                @Override public boolean summonAtDeath(String team,String kind,int lane,Pos at) {
                    results.add(new Spawn(team,kind,lane,at,true));return true;
                }
            };
            CFArenaAbilities abilities=new CFArenaAbilities(world,units,path,summoner);
            // Necromancer on lane 1: killing a RED unit on lane 2 MUST inherit lane 2.
            abilities.addBuilding(871,"blue","necromancy",1);
            Pos death2=new Pos(10.3125,8,10.8125);
            CFUnit red2=mob(world,registry,units,"red",2,death2);
            abilities.blessResurrection(red2,900,"red",5000);
            red2.kill();abilities.onDeath(red2);abilities.onDeath(red2);
            pump(world,2250);
            check(results.size()==2,"one necromancy + one resurrection after duplicate death callback");
            Spawn zombie2=result(results,0,"blue",2,death2);
            check(zombie2.mob().equals("grave_zombie"),"necromancy summons zombie, not acolyte");
            Spawn revive2=result(results,0,"red",2,death2);
            check(revive2.mob().equals(red2.contentId()),"red resurrection keeps the dead unit type");
            check(abilities.counters().get("necromancies")==1,"one necromancy proc");
            check(abilities.counters().get("revivals")==1,"one revival proc");
            check(abilities.counters().get("mutations")==0,"legacy mutation was not triggered");

            // BLUE resurrection is independent of necromancy and preserves lane 1.
            Pos death1=new Pos(11.25,8,11.75);
            CFUnit blue1=mob(world,registry,units,"blue",1,death1);
            abilities.blessResurrection(blue1,901,"blue",5000);
            int before=results.size();
            blue1.kill();abilities.onDeath(blue1);
            pump(world,2250);
            check(results.size()==before+1,"friendly death revives but does not invoke friendly necromancy");
            Spawn revive1=result(results,before,"blue",1,death1);
            check(revive1.mob().equals(blue1.contentId()),"blue resurrection keeps original mob type");

            // The 17s cooldown must remain, regardless of which lane dies.
            CFUnit red3=mob(world,registry,units,"red",3,new Pos(12.25,8,12.75));
            before=results.size();
            red3.kill();abilities.onDeath(red3);pump(world,150);
            check(results.size()==before,"cooldown blocks the next enemy death on lane 3");
            abilities.removeBuilding(871);
            CFUnit noTower=mob(world,registry,units,"red",3,new Pos(13.25,8,13.75));
            noTower.kill();abilities.onDeath(noTower);pump(world,150);
            check(results.size()==before,"removed necromancer cannot summon");

            // Necromancer on lane 2: killing an enemy on lane 3 MUST inherit lane 3.
            abilities.addBuilding(872,"blue","necromancy",2);
            Pos death3=new Pos(14.25,8,13.25);
            CFUnit red3b=mob(world,registry,units,"red",3,death3);
            red3b.kill();abilities.onDeath(red3b);pump(world,180);
            check(results.size()==before+1,"one zombie from the new necromancer");
            Spawn zombie3=result(results,before,"blue",3,death3);
            check(zombie3.mob().equals("grave_zombie"),"lane 3 necromancy produces grave zombie");
            check(results.stream().noneMatch(s->s.mob().equals("acolyte")),
                    "necromancy never summons an acolyte");
            check(abilities.counters().get("necromancies")==2,"two distinct towers each proc once");
            check(abilities.counters().get("revivals")==2,"red and blue resurrection both proc once");
            System.out.println("[CF NECROMANCY + LANES] PASS "+checks+
                    " checks: lane 2 enemy -> blue zombie + red revival, lane 1 blue revival, "+
                    "lane 3 enemy -> blue zombie, exact position, duplicate safety, cooldown, removal");
        } finally {
            MinecraftServer.stopCleanly();
        }
    }
}
