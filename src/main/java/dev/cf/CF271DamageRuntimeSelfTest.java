package dev.cf;

import net.minestom.server.MinecraftServer;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.entity.EntityType;
import net.minestom.server.entity.damage.DamageType;
import net.minestom.server.event.entity.EntityDamageEvent;
import net.minestom.server.instance.InstanceContainer;
import net.minestom.server.instance.block.Block;
import net.minestom.server.item.Material;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.concurrent.TimeUnit;

/** Mandatory installer test on real Java25/Minestom, in-memory world only.
 * Independent fixtures: no probabilistic poison/dodge, no saved arena edited.
 */
public final class CF271DamageRuntimeSelfTest {
    private static int checks;
    private static final ArrayList<CFUnit> live=new ArrayList<>();
    private static void check(boolean ok,String why) {
        checks++;if(!ok)throw new AssertionError(why);
    }
    private static CFUnit mob(InstanceContainer w,boolean ranged,String team,
            CFDamageTable.Type type,CFDamageTable.Armor armor,int damage,int crit,int chance,Pos pos)throws Exception {
        var def=new CFContentRegistry.Mob("damage_fixture","Damage fixture",EntityType.ZOMBIE,
                ranged?"ranged_hold":"melee",10000,.1,damage,crit,chance,
                ranged?10:2,12,2000,Material.AIR,new CFDamageTable.Profile(type,armor,damage,crit));
        CFUnit m=new CFUnit(team,def,pos,new Pos(20,8,8));
        m.getAIGroups().clear();m.setInstance(w,pos).get(10,TimeUnit.SECONDS);
        m.markSpawnCompleted();live.add(m);return m;
    }
    private static void reset(CFUnit target){target.setHealth(1000f);}
    public static void main(String[] args)throws Exception {
        if(args.length!=1)throw new IllegalArgumentException("content root required");
        MinecraftServer.init();
        try {
            InstanceContainer w=MinecraftServer.getInstanceManager().createInstanceContainer();
            w.setGenerator(c->c.modifier().fillHeight(0,8,Block.STONE));
            w.loadChunk(0,0).get(10,TimeUnit.SECONDS);w.loadChunk(1,0).get(10,TimeUnit.SECONDS);
            CFArenaAbilities effects=new CFArenaAbilities(w,new HashSet<>(),Path.of(args[0]),(t,m,l,p)->false);
            int[][] expected={{105,55,190,90,50},{105,190,90,55,40},
                              {105,90,55,190,40},{105,55,55,55,190},{105,100,100,100,100}};
            var types=CFDamageTable.Type.values();var armors=CFDamageTable.Armor.values();
            for(int t=0;t<5;t++)for(int a=0;a<5;a++) {
                CFUnit src=mob(w,false,"blue",types[t],armors[a],100,100,0,new Pos(4,8,4));
                CFUnit dst=mob(w,false,"red",types[t],armors[a],100,100,0,new Pos(5,8,4));
                check(CFMeleeCombat.attack(src,dst),"actual melee accepted");
                check(dst.health10()==10000-expected[t][a],"actual melee table cell "+t+"/"+a);
                reset(dst);effects.grantIceShield(dst,60000);
                CFTypedDamage hit=new CFTypedDamage(src,src.normalHit());
                check(dst.damage(hit),"shielded hit accepted");
                check(dst.health10()==10000-(expected[t][a]*65/100),"shield only once");
                reset(dst);check(dst.damage(hit),"same object reuse");
                check(dst.health10()==10000-(expected[t][a]*65/100),"no compounded percentages");
                src.remove();dst.remove();
            }
            CFUnit wolf=mob(w,false,"blue",CFDamageTable.Type.PIERCING,CFDamageTable.Armor.NONE,
                    18,23,100,new Pos(4,8,4));
            CFUnit light=mob(w,false,"red",CFDamageTable.Type.CHAOS,CFDamageTable.Armor.LIGHT,
                    1,1,0,new Pos(5,8,4));
            check(CFMeleeCombat.attack(wolf,light),"forced critical bite applied");
            check(light.health10()==9957,"fixed 23 crit x190% =43, no double multiplier");
            wolf.remove();light.remove();
            CFUnit archer=mob(w,true,"blue",CFDamageTable.Type.MAGIC,CFDamageTable.Armor.NONE,
                    30,30,0,new Pos(4,8,4));
            CFUnit heavy=mob(w,false,"red",CFDamageTable.Type.CHAOS,CFDamageTable.Armor.HEAVY,
                    1,1,0,new Pos(8,8,4));
            CFClientShot.shoot(archer,heavy);
            archer.kill(); // Already-launched shots keep their captured profile.
            effects.grantIceShield(heavy,60000); // Protection evaluated at IMPACT, not launch.
            for(int i=0;i<40;i++) {w.scheduler().processTick();w.scheduler().process();}
            check(heavy.health10()==10000-37,"real delayed arrow: 30 x190% =57, shield ->37, dead shooter allowed");
            heavy.remove();
            CFUnit src=mob(w,false,"blue",CFDamageTable.Type.SLASHING,CFDamageTable.Armor.NONE,
                    100,100,0,new Pos(4,8,4));
            CFUnit dst=mob(w,false,"red",CFDamageTable.Type.CHAOS,CFDamageTable.Armor.MEDIUM,
                    1,1,0,new Pos(5,8,4));
            final boolean[] cancel={false},modify={false};
            final int[] observed={0};
            MinecraftServer.getGlobalEventHandler().addListener(EntityDamageEvent.class,e->{
                if(e.getEntity()!=dst)return;
                observed[0]++;
                if(cancel[0])e.setCancelled(true);
                if(modify[0])e.getDamage().setAmount(1.2f);
            });
            cancel[0]=true;
            check(!dst.damage(new CFTypedDamage(src,src.normalHit())) && dst.health10()==10000,"cancellation preserved");
            cancel[0]=false;modify[0]=true;
            check(dst.damage(new CFTypedDamage(src,src.normalHit())) && dst.health10()==9988,"event override not scaled twice");
            modify[0]=false;reset(dst);dst.setInvulnerable(true);
            check(!dst.damage(new CFTypedDamage(src,src.normalHit())) && dst.health10()==10000,"invulnerability preserved");
            dst.setInvulnerable(false);
            check(dst.damage(DamageType.MAGIC,1f) && dst.health10()==9990,"native MAGIC alone does not silently classify DoT");
            reset(dst);int n=observed[0];
            var zero=new CFDamageTable.Hit(CFDamageTable.Type.MAGIC,1);
            check(!dst.damage(new CFTypedDamage(src,zero)) && dst.health10()==10000,"zero hit rejected");
            check(observed[0]==n,"zero hit creates no damage event");
            var content=new CFContentRegistry(Path.of(args[0]));
            var specs=new CFAbilitySpecs(Path.of(args[0]).resolve("mobs"));
            for(var mob:content.mobs()) {
                check(mob.combat().type.id.equals(specs.get(mob.id()).attackType()),"shared damage type "+mob.id());
                check(mob.combat().armor.id.equals(specs.get(mob.id()).armorType()),"shared armor type "+mob.id());
            }
            System.out.println("[CF DAMAGE RUNTIME] PASS "+checks+" checks: matrix, melee, fixed crit, real delayed arrow, shield, cancellation, native boundary");
        } finally {
            for(CFUnit u:live)if(!u.isRemoved())u.remove();
            MinecraftServer.stopCleanly();
        }
    }
}
