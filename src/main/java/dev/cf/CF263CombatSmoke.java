package dev.cf;

import net.minestom.server.MinecraftServer;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.coordinate.Vec;
import net.minestom.server.entity.ai.GoalSelector;
import net.minestom.server.entity.damage.DamageType;
import net.minestom.server.event.entity.EntityDamageEvent;
import net.minestom.server.event.entity.EntityDeathEvent;
import net.minestom.server.instance.InstanceContainer;
import net.minestom.server.instance.block.Block;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Uses a NEW in-memory world and temporary content; never launches Main or opens live maps. */
public final class CF263CombatSmoke {
    static int checks;
    static final Set<CFUnit> units=new HashSet<>();
    static InstanceContainer world;
    static CFContentRegistry content;
    static CFArenaAbilities abilities;
    static void check(boolean ok,String why){checks++;if(!ok)throw new AssertionError(why);}
    static void hp(CFUnit u,float expected,String why){check(Math.abs(u.getHealth()-expected)<.0011,why+" got="+u.getHealth());}
    static CFUnit unit(String team,String type,double x,double z)throws Exception {
        Pos p=new Pos(x,8,z);
        CFUnit u=new CFUnit(team,content.mob(type),p,new Pos(30,8,z));
        u.getAIGroups().clear();
        u.setInstance(world,p).get(10,TimeUnit.SECONDS);u.markSpawnCompleted();units.add(u);return u;
    }
    static void pump(long ms)throws Exception {
        long end=System.nanoTime()+ms*1000000;
        while(System.nanoTime()<end) {
            world.scheduler().processTick();world.scheduler().process();
            // Entity scheduler owns death removal independently of CFUnit.update.
            for(CFUnit u:List.copyOf(units))if(!u.isRemoved())u.tick(System.currentTimeMillis());
            Thread.sleep(10);
        }
    }
    public static void main(String[] args)throws Exception {
        if(args.length!=1)throw new IllegalArgumentException("content directory required");
        content=new CFContentRegistry(Path.of(args[0]));
        MinecraftServer.init();
        try {
            world=MinecraftServer.getInstanceManager().createInstanceContainer();
            world.setGenerator(u->u.modifier().fillHeight(0,8,Block.STONE));
            for(int x=-1;x<4;x++)for(int z=-1;z<3;z++)world.loadChunk(x,z).get(10,TimeUnit.SECONDS);
            abilities=new CFArenaAbilities(world,units,Path.of(args[0]),(t,m,l,p)->false);
            MinecraftServer.getGlobalEventHandler().addListener(EntityDamageEvent.class,CFCombatAudit::damageEvent);
            MinecraftServer.getGlobalEventHandler().addListener(EntityDeathEvent.class,e->{
                if(e.getEntity() instanceof CFUnit u){CFCombatAudit.death(u);abilities.onDeath(u);}
            });
            CFTrace.startCompact(60);
            CFUnit zombie=unit("blue","grave_zombie",1,1), victim=unit("red","mutated_zombie",2,1);
            float start=victim.getHealth();
            check(CFMeleeCombat.attack(zombie,victim),"living melee accepted");
            hp(victim,start-zombie.attackDamage(),"primary hit exact BEFORE scheduled poison");
            check(abilities.activePoisonStacks()==1,"successful hit applied one poison stack");
            abilities.clearUnits();
            zombie.kill();float saved=victim.getHealth();
            check(!CFMeleeCombat.attack(zombie,victim),"dead melee rejected independently of AI");
            hp(victim,saved,"dead melee cannot damage");
            int[] ticks={0};
            zombie.addAIGroup(List.of(new GoalSelector(zombie){
                public boolean shouldStart(){return true;}public void start(){}public void tick(long t){ticks[0]++;}
                public boolean shouldEnd(){return false;}public void end(){}
            }),List.of());
            zombie.update(System.currentTimeMillis());check(ticks[0]==0,"dead update never enters inherited AI");
            check(!zombie.isCustomNameVisible(),"death hides HP label");
            long shots=CFClientShot.shots();CFClientShot.shoot(zombie,victim);
            check(CFClientShot.shots()==shots,"dead source cannot launch arrow");

            CFUnit arson=unit("blue","risen_arsonist",5,5), burnVictim=unit("red","mutated_zombie",6,5);
            check(CFMeleeCombat.attack(arson,burnVictim),"arsonist melee accepted");
            check(abilities.activeBurnStacks()==1,"arsonist actually applies burn");
            check(burnVictim.getFireTicks()==0,"no hidden vanilla fire timer");
            abilities.clearUnits();burnVictim.remove();arson.remove();

            CFUnit dotVictim=unit("red","mutated_zombie",10,2), source=unit("blue","grave_zombie",12,2);
            abilities.dot(CFArenaAbilities.DotKind.POISON,dotVictim,.6f,50,3,source,101,-1,"blue");
            abilities.dot(CFArenaAbilities.DotKind.POISON,dotVictim,.6f,50,3,source,102,-1,"blue");
            abilities.dot(CFArenaAbilities.DotKind.BURN,dotVictim,.8f,50,3,source,103,-1,"blue");
            check(abilities.activePoisonStacks()==2 && abilities.activeBurnStacks()==1,"independent simultaneous stacks");
            source.kill();pump(1150);
            hp(dotVictim,2f,"8 - 2 poison stacks (3.6) - burn stack (2.4) = 2; source death allowed");
            check(dotVictim.getFireTicks()==0,"DoT does not set vanilla fire ticks");
            check(abilities.activePoisonStacks()==0 && abilities.activeBurnStacks()==0,"all stacks finish");
            check(zombie.isRemoved(),"corpse removal scheduler still runs");

            CFUnit immune=unit("red","risen",14,2);
            abilities.dot(CFArenaAbilities.DotKind.POISON,immune,.6f,50,3,null,-1,9,"blue");
            abilities.dot(CFArenaAbilities.DotKind.BURN,immune,.6f,50,3,null,-1,9,"blue");
            check(abilities.activePoisonStacks()==0 && abilities.activeBurnStacks()==0,"negative immunity rejects both DoTs");
            CFUnit tiny=unit("red","grave_zombie",16,2);
            for(int i=0;i<10;i++)tiny.damage(DamageType.MAGIC,.6f);
            check(tiny.isDead() && tiny.getHealth()==0,"ten .6 damage calls kill exact 6 HP");

            CFUnit arrow=unit("blue","risen",1,10), arrowVictim=unit("red","mutated_zombie",4,10);
            CFClientShot.shoot(arrow,arrowVictim);arrow.kill();pump(500);
            hp(arrowVictim,8-arrow.attackDamage(),"arrow launched alive still hits after shooter death");
            CFUnit resetVictim=unit("red","mutated_zombie",9,10);
            abilities.dot(CFArenaAbilities.DotKind.POISON,resetVictim,.6f,50,3,null,-1,-1,"blue");
            abilities.dot(CFArenaAbilities.DotKind.BURN,resetVictim,.6f,50,3,null,-1,-1,"blue");
            abilities.clearUnits();pump(250);hp(resetVictim,8,"old round cannot leak DoTs into next round");
            check(abilities.activePoisonStacks()==0 && abilities.activeBurnStacks()==0,"reset counts stay nonnegative");

            CFUnit holdA=unit("blue","risen",20,10), holdB=unit("blue","banshee",20.25,10);
            holdA.setRangedLock(true);holdB.setRangedLock(true);pump(700);
            check(Math.hypot(holdA.getPosition().x()-holdB.getPosition().x(),holdA.getPosition().z()-holdB.getPosition().z())>.6,
                    "ranged locked units are pushed apart by vanilla velocity impulses");
            check(CFVanillaPushMath.fromCenterDelta(0,0).isZero(),
                    "vanilla does not invent a direction for identical centers");
            CFTrace.stop();check(CFTrace.awaitFinished(5000),"forensic trace flushed");
            System.out.println("[CF263 COMBAT] PASS "+checks+" actual Minestom checks; temporary world only");
        } finally { if(CFTrace.on())CFTrace.stop(); MinecraftServer.stopCleanly(); }
    }
}
