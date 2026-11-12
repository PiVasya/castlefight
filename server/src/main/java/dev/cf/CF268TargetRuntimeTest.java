package dev.cf;

import net.minestom.server.MinecraftServer;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.coordinate.Vec;
import net.minestom.server.entity.Entity;
import net.minestom.server.event.entity.EntityAttackEvent;
import net.minestom.server.instance.InstanceContainer;
import net.minestom.server.instance.block.Block;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Real Minestom integration, mandatory in the Java 25 installer. Temporary
 * in-memory instances only: no saved arena, network listener or player data.
 * Controls entity age only in this test, so exact scheduling can be asserted
 * without sleeping for minutes. Uses the real AI group, query index and LOS.
 */
public final class CF268TargetRuntimeTest {
    private static int checks;
    private static Field age;
    private static final List<CFUnit> all = new ArrayList<>();
    private static void check(boolean value, String message) {
        checks++; if (!value) throw new AssertionError(message);
    }
    private static InstanceContainer world() throws Exception {
        InstanceContainer w=MinecraftServer.getInstanceManager().createInstanceContainer();
        w.setGenerator(chunk->chunk.modifier().fillHeight(0,8,Block.STONE));
        for(int x=0;x<3;x++)for(int z=0;z<2;z++)w.loadChunk(x,z).get(10,TimeUnit.SECONDS);
        return w;
    }
    private static CFUnit mob(InstanceContainer w,CFContentRegistry registry,String team,String kind,Pos p) throws Exception {
        CFUnit m=new CFUnit(team,registry.mob(kind),p,new Pos(30,8,12));
        m.setInstance(w,p).get(10,TimeUnit.SECONDS);m.markSpawnCompleted();all.add(m);return m;
    }
    private static void tick(CFUnit u,long tick,long time) throws Exception {
        age.setLong(u,tick);u.aiTick(time);
    }
    private static boolean meleeGoal(CFUnit u) {
        return u.getAIGroups().iterator().next().getCurrentGoalSelector() instanceof CFTraceMeleeGoal;
    }
    private static boolean rangedGoal(CFUnit u) {
        return u.getAIGroups().iterator().next().getCurrentGoalSelector() instanceof CFHoldRangedGoal;
    }
    public static void main(String[] args) throws Exception {
        if(args.length!=1)throw new IllegalArgumentException("content directory required");
        age=Entity.class.getDeclaredField("ticks");age.setAccessible(true);
        MinecraftServer.init();
        try {
            CFContentRegistry registry=new CFContentRegistry(Path.of(args[0]));
            InstanceContainer w=world();
            CFUnit a=mob(w,registry,"blue","grave_zombie",new Pos(4,8,4));
            CFUnit x=mob(w,registry,"red","grave_zombie",new Pos(6,8,4));
            CFUnit y=mob(w,registry,"red","grave_zombie",new Pos(10,8,4));
            AtomicInteger attacks=new AtomicInteger();
            MinecraftServer.getGlobalEventHandler().addListener(EntityAttackEvent.class,e->{
                if(e.getEntity()==a)attacks.incrementAndGet();
            });
            tick(a,0,100_000);
            check(a.getTarget()==x && a.traceTarget()==x,"one target authority: gameplay and trace");
            check(meleeGoal(a),"melee goal active instead of march");
            check(attacks.get()==1,"one initial attack");
            check(x.targeting().brain.links.attackers()==1,"real reverse edge");
            y.teleport(new Pos(4.75,8,4)).get(10,TimeUnit.SECONDS);
            long due=a.targeting().brain.nextReviewTick();
            for(long t=1;t<due;t++){tick(a,t,100_000+t*50);check(a.getTarget()==x,"hold until planned review");}
            tick(a,due,100_000+due*50);
            check(a.getTarget()==y,"periodic full search can replace a live enemy");
            check(attacks.get()==1,"review does not reset melee attack cooldown");
            check(x.targeting().brain.links.attackers()==0 && y.targeting().brain.links.attackers()==1,
                    "both sides of link updated");
            y.kill();
            check(a.getTarget()==null,"kill synchronously invalidates incoming links");
            tick(a,due,100_001+due*50);
            check(a.getTarget()==x && meleeGoal(a),"same-tick death retarget without a march goal");
            check(attacks.get()==1,"death retarget does not grant a free attack");
            tick(a,due+1,100_000+a.attackCooldownMs()+1);
            check(attacks.get()==2,"original melee cooldown still expires normally");
            x.remove();
            check(a.getTarget()==null,"remove without death invalidates links");
            tick(a,due+2,110_000);
            check(!meleeGoal(a),"no enemy permits leaving melee goal");

            InstanceContainer rw=world();
            CFUnit r=mob(rw,registry,"blue","risen",new Pos(4,8,4));
            CFUnit rx=mob(rw,registry,"red","grave_zombie",new Pos(8,8,4));
            CFUnit ry=mob(rw,registry,"red","grave_zombie",new Pos(13,8,4));
            tick(r,0,100_000);
            check(r.getTarget()==rx && rangedGoal(r) && r.isRangedLocked(),"real ranged lock");
            long shots=CFClientShot.shots(), scans=r.targeting().brain.scans();
            check(shots>0,"ranged fired initial shot");
            ry.teleport(new Pos(5,8,4)).get(10,TimeUnit.SECONDS);
            for(int yy=8;yy<13;yy++)for(int zz=1;zz<=7;zz++)rw.setBlock(6,yy,zz,Block.STONE);
            check(!r.hasLineOfSight(rx),"wall really occludes selected target");
            r.setVelocity(new Vec(1,0,.5));
            Pos held=r.getPosition();
            for(int t=1;t<=60;t++)tick(r,t,100_000+t*50);
            Thread.sleep(900); // explicitly exceed the removed 800ms sight grace
            tick(r,60,103_000);
            check(r.getTarget()==null && !rangedGoal(r) && !r.isRangedLocked(),
                    "occluded target is released so the march goal can resume");
            check(r.targeting().brain.scans()>scans,"archer rechecks visibility after releasing target");
            check(CFClientShot.shots()==shots,"no shots through wall");
            check(r.getPosition().samePoint(held),"AI-only test does not advance physics");
            check(r.getVelocity().x()==1 && r.getVelocity().z()==.5,"resumed march preserves knockback velocity");
            for(int yy=8;yy<13;yy++)for(int zz=1;zz<=7;zz++)rw.setBlock(6,yy,zz,Block.AIR);
            ry.teleport(new Pos(30,8,4)).get(10,TimeUnit.SECONDS);
            rx.teleport(new Pos(4+r.attackRange(),8,4)).get(10,TimeUnit.SECONDS);
            tick(r,61,104_000);
            check(r.getTarget()==rx && rangedGoal(r),"inclusive ranged boundary reacquires enemy");
            CFHoldRangedGoal activeRanged=(CFHoldRangedGoal)r.getAIGroups().iterator().next().getCurrentGoalSelector();
            Field shotClock=CFHoldRangedGoal.class.getDeclaredField("lastShotNs");shotClock.setAccessible(true);
            long clockBefore=shotClock.getLong(activeRanged);
            rx.teleport(new Pos(4+r.attackRange()+.01,8,4)).get(10,TimeUnit.SECONDS);
            ry.teleport(new Pos(8,8,4)).get(10,TimeUnit.SECONDS);
            check(!activeRanged.shouldEnd(),"range replacement retains active goal");
            check(r.getTarget()==ry && rangedGoal(r),"out of radius replaces target without marching");
            check(shotClock.getLong(activeRanged)==clockBefore,"range retarget preserves shot cooldown");
            rx.teleport(new Pos(9,8,4)).get(10,TimeUnit.SECONDS);ry.kill();
            check(!activeRanged.shouldEnd(),"death replacement retains active goal");
            check(r.getTarget()==rx && rangedGoal(r),"ranged same-tick death retarget");
            check(shotClock.getLong(activeRanged)==clockBefore,"death retarget preserves shot cooldown");
            rx.kill();tick(r,63,104_100);
            check(!rangedGoal(r) && !r.isRangedLocked(),"empty ranged fight releases goal");

            InstanceContainer mw=world();
            CFUnit target=mob(mw,registry,"red","grave_zombie",new Pos(9,8,4));
            List<CFUnit> army=new ArrayList<>();
            for(int i=0;i<40;i++){
                CFUnit m=mob(mw,registry,"blue","grave_zombie",new Pos(4,8,4));
                army.add(m);m.targeting().resolve();
            }
            check(target.targeting().brain.links.attackers()==40,"40 attackers recorded");
            target.kill();
            for(CFUnit m:army)check(m.getTarget()==null,"mass death immediate invalidation");
            CFUnit revived=mob(mw,registry,"red","grave_zombie",new Pos(9,8,4));
            for(CFUnit m:army)check(m.targeting().resolve()==revived,"new entity can be acquired same tick");
            check(target.targeting().brain.links.attackers()==0,"corpse receives no revived links");
            InstanceContainer otherWorld=world();
            revived.setInstance(otherWorld,new Pos(9,8,4)).get(10,TimeUnit.SECONDS);
            for(CFUnit m:army)check(m.getTarget()==null,"world transfer clears incoming edges");
            for(CFUnit m:army){m.remove();check(m.targeting().brain.links.consistent(),"cleanup links consistent");}
            for(CFUnit m:all)check(m.targeting().brain.links.consistent(),"all real nodes consistent");
            System.out.println("[CF268 RUNTIME] PASS "+checks+" checks: real AI goals, LOS, radius, cooldowns, death, removal and transfer");
        } finally {
            for(CFUnit m:all)if(!m.isRemoved())m.remove();
            MinecraftServer.stopCleanly();
        }
    }
}
