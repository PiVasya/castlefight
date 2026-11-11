package dev.cf;

import net.minestom.server.coordinate.Pos;
import net.minestom.server.entity.Entity;
import net.minestom.server.entity.attribute.Attribute;
import net.minestom.server.entity.damage.DamageType;
import net.minestom.server.instance.InstanceContainer;
import net.minestom.server.timer.Task;
import net.minestom.server.timer.TaskSchedule;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Combat hooks are called ONLY after an actual attack/impact, an entity spawn/death or a scheduled
 * ability deadline. Nothing scans the abilities of every mob each tick. All ability timers share
 * one earliest-deadline task, so 100 unit types do not imply 100 scheduler loops.
 */
final class CFArenaAbilities {
    @FunctionalInterface interface Summoner {
        boolean summon(String team, String mob, int lane, Pos near);
        default boolean summonAhead(CFUnit source, String mob) {
            return summon(source.team(), mob, source.laneId(), source.getPosition());
        }
        /** Death procs MUST keep the exact position, not snap to the closest road node. */
        default boolean summonAtDeath(String team, String mob, int lane, Pos deathPos) {
            return summon(team, mob, lane, deathPos);
        }
    }
    private record Due(long deadline, long seq, Runnable action) { }
    private record Building(long id, String team, String ability, int lane, Pos origin) { }
    private static final class SlowLayer {
        final double multiplier;
        SlowLayer(double multiplier) { this.multiplier = multiplier; }
    }
    private static final class SlowState {
        final double base;
        final List<SlowLayer> layers = new ArrayList<>();
        SlowState(double base) { this.base = base; }
    }
    private static final class UnitTimers {
        long waveReady, splashReady, iceReady;
    }
    /** One active blessing per unit, refreshed in-place without another timer per cast. */
    private static final class ResurrectionBless {
        long expiresNs;
        long altarId;
        ResurrectionBless(long expiresNs, long altarId) {
            this.expiresNs=expiresNs; this.altarId=altarId;
        }
    }
    enum DotKind { POISON, BURN }
    private static final class DamageStack {
        final DotKind kind;
        final CFUnit target;
        final float damage;
        final long intervalMs;
        final long stackId, originActionId, altarId, createdNs;
        final int sourceUnitId, totalPulses;
        final String sourceMob, sourceTeam;
        int pulsesLeft;
        DamageStack(DotKind kind, CFUnit target, float damage, long intervalMs, int pulses,
                    long stackId, CFUnit source, long originActionId, long altarId, String altarTeam) {
            this.kind=kind;this.target=target;this.damage=damage;this.intervalMs=intervalMs;this.pulsesLeft=pulses;
            this.totalPulses=pulses;this.stackId=stackId;
            this.sourceUnitId=source==null?-1:source.getEntityId();
            this.sourceMob=source==null?"building":source.contentId();
            this.sourceTeam=source==null?altarTeam:source.team();
            this.originActionId=originActionId;this.altarId=altarId;
            this.createdNs=System.nanoTime();
        }
    }
    private final InstanceContainer world;
    private final Set<CFUnit> units;
    private final Summoner summoner;
    private final CFAbilitySpecs specs;
    private final CFFactionSpecs factionSpecs;
    private final IdentityHashMap<CFUnit,Long> frozenUntil = new IdentityHashMap<>();
    private final IdentityHashMap<CFUnit,Long> shieldUntil = new IdentityHashMap<>();
    private final IdentityHashMap<CFUnit,Long> attackSlowedUntil = new IdentityHashMap<>();
    private final IdentityHashMap<CFUnit,Integer> attackSlowPct = new IdentityHashMap<>();
    private final Map<Long,Long> buildingFrozenUntil = new HashMap<>();
    private final Map<Long,Building> buildings = new LinkedHashMap<>();
    private final IdentityHashMap<CFUnit,UnitTimers> unitTimers = new IdentityHashMap<>();
    private final IdentityHashMap<CFUnit,SlowState> slowed = new IdentityHashMap<>();
    private final IdentityHashMap<CFUnit,ResurrectionBless> resurrection = new IdentityHashMap<>();
    private final PriorityQueue<Due> pending = new PriorityQueue<>(Comparator.comparingLong(Due::deadline)
            .thenComparingLong(Due::seq));
    private Task armed;
    private long armedDeadline = Long.MAX_VALUE;
    private long sequence, virtualBuildingId=-1, poisonStackSerial;
    private boolean draining;
    private long hits, dodges, poisons, poisonTicks, heals, waves, splashes, summons, revivals, mutations, necromancies, toxicCasts;
    private int activePoisonStacks, activeBurnStacks;
    private long burns, burnTicks;
    private long unitEpoch;
    private static CFArenaAbilities ACTIVE;

    CFArenaAbilities(InstanceContainer world,Set<CFUnit> units,Path content, Summoner summoner) throws IOException {
        this.world=world;this.units=units;this.summoner=summoner;
        this.specs=new CFAbilitySpecs(content.resolve("mobs"));
        this.factionSpecs=new CFFactionSpecs(content.resolve("mobs"));
        ACTIVE=this;
    }
    static CFArenaAbilities active() { return ACTIVE; }
    boolean isFrozen(CFUnit unit) {
        Long until=frozenUntil.get(unit);
        if(until==null)return false;
        if(until<=System.nanoTime() || !unit.combatAlive()) {frozenUntil.remove(unit);return false;}
        return true;
    }
    long adjustAttackCooldown(CFUnit unit,long baseMs) {
        Long until=attackSlowedUntil.get(unit);
        if(until==null)return baseMs;
        if(until<=System.nanoTime()) {
            attackSlowedUntil.remove(unit);attackSlowPct.remove(unit);return baseMs;
        }
        int pct=attackSlowPct.getOrDefault(unit,0);
        return pct>0?(baseMs*100L+(99-pct))/(100-pct):baseMs;
    }
    private void slowAttack(CFUnit target,int pct,long ms) {
        if(!alive(target)||pct<=0||ms<=0||spec(target).negativeImmune())return;
        long newUntil=System.nanoTime()+ms*1_000_000L;
        attackSlowedUntil.put(target,Math.max(newUntil,attackSlowedUntil.getOrDefault(target,0L)));
        attackSlowPct.put(target,Math.max(pct,attackSlowPct.getOrDefault(target,0)));
        CFTrace.event("ATTACK_SPEED_SLOW","unit",target.getEntityId(),"percent",pct,"durationMs",ms);
    }
    private void freeze(CFUnit target,int ms,int damage10,int intervalMs) {
        if(!alive(target)||ms<=0||spec(target).negativeImmune())return;
        long expires=System.nanoTime()+ms*1_000_000L;
        frozenUntil.put(target,Math.max(expires,frozenUntil.getOrDefault(target,0L)));
        target.getNavigator().reset();
        target.setVelocity(net.minestom.server.coordinate.Vec.ZERO);
        CFTrace.event("FROST_FREEZE","unit",target.getEntityId(),"durationMs",ms);
        if(damage10>0 && intervalMs>0) {
            long epoch=unitEpoch;
            for(int elapsed=intervalMs;elapsed<=ms;elapsed+=intervalMs) {
                schedule(elapsed,()->{
                    if(epoch!=unitEpoch || !alive(target))return;
                    target.damage(DamageType.MAGIC,damage10/10f);
                    CFTrace.event("FROST_DAMAGE","unit",target.getEntityId(),"damage10",damage10);
                });
            }
        }
    }
    private void factionDamage(CFUnit source,CFUnit target,int damage10,String reason) {
        if(!validEnemy(source.team(),target)||damage10<=0)return;
        boolean applied=target.damage(DamageType.MAGIC,damage10/10f);
        CFTrace.event("FACTION_DAMAGE","attacker",source.getEntityId(),"target",target.getEntityId(),
                "reason",reason,"damage10",damage10,"applied",applied);
    }
    private void factionSlowMove(CFUnit target,int percent,long durationMs) {
        if(percent>0 && durationMs>0)slow(target,(100-percent)/100.0,durationMs);
    }
    private void factionAfterHit(CFUnit source,CFUnit primary,boolean ranged) {
        CFFactionSpecs.Spec s=factionSpecs.get(source.contentId());
        var rng=ThreadLocalRandom.current();
        if(s.frostChance()>0 && rng.nextInt(100)<s.frostChance())
            freeze(primary,s.frostMs(),s.frostDamage10(),s.frostIntervalMs());
        if(s.iceChance()>0) {
            UnitTimers t=unitTimers.computeIfAbsent(source,k->new UnitTimers());
            long now=System.nanoTime();
            if(now>=t.iceReady && rng.nextInt(100)<s.iceChance()) {
                t.iceReady=now+s.iceCooldownMs()*1_000_000L;
                for(Entity entity:world.getNearbyEntities(primary.getPosition(),s.iceRadius()))
                    if(entity instanceof CFUnit enemy && validEnemy(source.team(),enemy)) {
                        factionDamage(source,enemy,s.iceDamage10(),"ICE_BURST");
                        slowAttack(enemy,s.iceAttackSlowPct(),s.iceSlowMs());
                    }
                CFTrace.event("ICE_BURST","source",source.getEntityId(),"radius",s.iceRadius());
            }
        }
        if(s.cleaveDamage10()>0)
            for(Entity entity:world.getNearbyEntities(primary.getPosition(),s.cleaveRadius()))
                if(entity instanceof CFUnit enemy && validEnemy(source.team(),enemy))
                    factionDamage(source,enemy,s.cleaveDamage10(),"CLEAVE");
        if(s.blizzardDamage10()>0 && ranged) {
            for(Entity entity:world.getNearbyEntities(primary.getPosition(),s.blizzardRadius()))
                if(entity instanceof CFUnit enemy && validEnemy(source.team(),enemy)) {
                    factionDamage(source,enemy,s.blizzardDamage10(),"BLIZZARD");
                    slowAttack(enemy,s.blizzardAttackSlowPct(),s.blizzardSlowMs());
                    factionSlowMove(enemy,s.blizzardMoveSlowPct(),s.blizzardSlowMs());
                }
            factionSlowMove(primary,s.blizzardPrimarySlowPct(),s.blizzardSlowMs());
        }
        if(s.killSummonChance()>0 && !primary.combatAlive() &&
                rng.nextInt(100)<s.killSummonChance() && source.combatAlive()) {
            boolean ok=summoner.summonAhead(source,s.killSummonMob());
            CFTrace.event("LORD_KILL_SUMMON","source",source.getEntityId(),"mob",s.killSummonMob(),"success",ok);
            if(ok)summons++;
        }
    }
    void onDamaged(CFUnit victim,CFUnit attacker) {
        if(!alive(attacker) || victim==attacker || victim.team().equals(attacker.team()))return;
        CFFactionSpecs.Spec s=factionSpecs.get(victim.contentId());
        if(s.counterPoisonDamage10()>0) {
            int pulses=Math.max(1,s.counterPoisonDurationMs()/1000);
            dot(DotKind.POISON,attacker,s.counterPoisonDamage10()/10f,1000,pulses,victim,-1,-1,victim.team());
        }
        Long until=shieldUntil.get(victim);
        if(until!=null && until>System.nanoTime())slowAttack(attacker,30,10000);
    }
    void grantIceShield(CFUnit target,long durationMs) {
        if(!alive(target)||durationMs<=0)return;
        shieldUntil.put(target,Math.max(shieldUntil.getOrDefault(target,0L),
                System.nanoTime()+durationMs*1_000_000L));
    }
    boolean hasIceShield(CFUnit victim) {
        Long until=shieldUntil.get(victim);
        if(until==null)return false;
        if(until<=System.nanoTime()) {shieldUntil.remove(victim);return false;}
        return true;
    }
    /** AFTER armor, BEFORE HP loss: the single extension point for live defensive effects.
     * New shields belong here (or in the status-effect registry this delegates to),
     * never in CFDamageTable.Hit. Each step is int10 with floor/truncation.
     * No lists or allocations on a normal unshielded hit.
     */
    int postArmorDamage10(CFUnit victim,int afterArmor10) {
        int value=afterArmor10;
        if(value<=0)return 0;
        if(hasIceShield(victim)) {
            int before=value;
            value=CFDamageTable.scale10(value,65);
            CFTrace.event("ICE_SHIELD_REDUCE","target",victim.getEntityId(),
                    "before10",before,"after10",value);
        }
        // Future protection effects are applied in a defined order, right here:
        // value=applyOtherShield(victim,value);
        return value;
    }
    void modifyIncomingDamage(CFUnit victim,net.minestom.server.entity.damage.Damage damage) {
        // Untyped effects bypass the armor matrix, but still obey active shields.
        int amount10=Math.max(0,Math.round(damage.getAmount()*10f));
        int result10=postArmorDamage10(victim,amount10);
        if(result10!=amount10)damage.setAmount(result10/10f);
    }
    boolean towerFrozen(long buildingId) {
        Long until=buildingFrozenUntil.get(buildingId);
        if(until==null)return false;
        if(until<=System.nanoTime()){buildingFrozenUntil.remove(buildingId);return false;}
        return true;
    }
    void onTowerRemoved(long id) {buildingFrozenUntil.remove(id);}

    CFAbilitySpecs.Spec spec(CFUnit u) { return specs.get(u.contentId()); }
    boolean dodge(CFUnit defender) {
        if (defender.isDead() || defender.isRemoved()) return false;
        double probability=spec(defender).dodge();
        if (probability<=0) return false;
        double roll=ThreadLocalRandom.current().nextDouble();
        boolean evaded=roll<probability;
        CFTrace.event("DODGE_ROLL","defender",defender.getEntityId(),
                "mob",defender.contentId(),"chance",probability,"roll",roll,"evaded",evaded);
        if(!evaded)return false;
        dodges++;
        return true;
    }
    void afterSuccessfulHit(CFUnit attacker, CFUnit target, boolean ranged, long damageActionId) {
        if (attacker.isRemoved() || attacker.isDead()) return;
        hits++;
        CFAbilitySpecs.Spec s=spec(attacker);
        var rng=ThreadLocalRandom.current();
        CFTrace.event("HIT_CONFIRMED","attacker",attacker.getEntityId(),"defender",target.getEntityId(),
                "damageActionId",damageActionId,
                "attackerMob",attacker.contentId(),"defenderMob",target.contentId(),"ranged",ranged);
        if(s.poisonPulses()>0) {
            double roll=rng.nextDouble();
            boolean applies=roll<s.poisonChance();
            CFTrace.event("POISON_ROLL","attacker",attacker.getEntityId(),"defender",target.getEntityId(),
                    "damageActionId",damageActionId,
                    "chance",s.poisonChance(),"roll",roll,"success",applies);
            if(applies)dot(DotKind.POISON,target,s.poisonDamage(),s.poisonIntervalMs(),s.poisonPulses(),
                    attacker,damageActionId,-1,attacker.team());
        }
        CFAbilitySpecs.Dot burn=s.burn();
        if(burn.pulses()>0) {
            double roll=rng.nextDouble();
            boolean applies=roll<burn.chance();
            CFTrace.event("BURN_ROLL","attacker",attacker.getEntityId(),"defender",target.getEntityId(),
                    "damageActionId",damageActionId,"chance",burn.chance(),"roll",roll,"success",applies);
            if(applies)dot(DotKind.BURN,target,burn.damage(),burn.intervalMs(),burn.pulses(),
                    attacker,damageActionId,-1,attacker.team());
        }
        if(s.lifestealChance()>0) {
            double roll=rng.nextDouble();
            boolean activated=roll<s.lifestealChance();
            CFTrace.event("LIFESTEAL_ROLL","unit",attacker.getEntityId(),
                    "originActionId",damageActionId,"chance",s.lifestealChance(),"roll",roll,"activated",activated);
            if(activated) {
                float max=(float)attacker.getAttribute(Attribute.MAX_HEALTH).getValue();
                float before=attacker.getHealth();
                if(before>0 && before<max) {
                    attacker.setHealth(Math.min(max,before+s.lifestealAmount()));
                    heals++;
                    CFTrace.event("LIFESTEAL_HEAL","unit",attacker.getEntityId(),
                            "originActionId",damageActionId,"hpBefore",before,"hpAfter",attacker.getHealth());
                } else CFTrace.event("LIFESTEAL_NO_HEAL","unit",attacker.getEntityId(),"originActionId",damageActionId,"hp",before,"maxHp",max);
            }
        }
        if (s.waveChance()>0) {
            UnitTimers state=unitTimers.computeIfAbsent(attacker,k->new UnitTimers());
            long now=System.nanoTime();
            if(now>=state.waveReady) {
                double roll=rng.nextDouble();
                boolean activated=roll<s.waveChance();
                CFTrace.event("WAVE_ROLL","unit",attacker.getEntityId(),"originActionId",damageActionId,"chance",s.waveChance(),
                        "roll",roll,"activated",activated);
                if(activated){
                state.waveReady=now+s.waveCooldownMs()*1_000_000L;
                waves++;
                CFTrace.event("WAVE_TRIGGER","unit",attacker.getEntityId(),"originActionId",damageActionId,"target",target.getEntityId(),
                        "cooldownMs",s.waveCooldownMs(),"radius",s.waveRadius());
                Pos from=attacker.getPosition(), aim=target.getPosition();
                // Screenshot: a 5-block wave IN FRONT of the attacker (not a spherical aura).
                double dx=aim.x()-from.x(),dz=aim.z()-from.z(),length=Math.hypot(dx,dz);
                if(length>.001) {
                    Pos center=from.add(dx/length*s.waveRadius()*.5,0,dz/length*s.waveRadius()*.5);
                    for(Entity other:world.getNearbyEntities(center,s.waveRadius())) {
                        if(other instanceof CFUnit enemy && validEnemy(attacker.team(),enemy) &&
                                inForwardWave(from,aim,enemy.getPosition(),s.waveRadius(),2.0)) {
                            CFTrace.event("WAVE_AFFECT","attacker",attacker.getEntityId(),"originActionId",damageActionId,"target",enemy.getEntityId(),
                                    "slowFactor",s.waveSlowMultiplier(),"durationMs",s.waveDurationMs());
                            if(s.waveSlowMultiplier()<1)slow(enemy,s.waveSlowMultiplier(),s.waveDurationMs());
                            CFFactionSpecs.Spec extra=factionSpecs.get(attacker.contentId());
                            if(extra.witherDamage10()>0) {
                                long gen=unitEpoch;
                                for(int ms=extra.witherIntervalMs();ms<=extra.witherDurationMs();ms+=extra.witherIntervalMs())
                                    schedule(ms,()->{
                                        if(gen==unitEpoch && alive(enemy))enemy.damage(DamageType.MAGIC,extra.witherDamage10()/10f);
                                    });
                            }
                        }
                    }
                }
                }
            }
        }
        // Faction abilities run only on a confirmed hit, never as per-tick scans.
        factionAfterHit(attacker,target,ranged);
        if (ranged && s.splashChance()>0) {
            UnitTimers state=unitTimers.computeIfAbsent(attacker,k->new UnitTimers());
            long now=System.nanoTime();
            if (now>=state.splashReady) {
                double roll=rng.nextDouble();
                boolean activated=roll<s.splashChance();
                CFTrace.event("SPLASH_ROLL","unit",attacker.getEntityId(),"originActionId",damageActionId,"chance",s.splashChance(),
                        "roll",roll,"activated",activated);
                if(activated){
                state.splashReady=now+s.splashCooldownMs()*1_000_000L;
                splashes++;
                CFTrace.event("SPLASH_TRIGGER","attacker",attacker.getEntityId(),
                        "originActionId",damageActionId,"primaryTarget",target.getEntityId(),"radius",s.splashRadius(),"damage",s.splashDamage());
                Pos impact=target.getPosition();
                for (Entity other:world.getNearbyEntities(impact,s.splashRadius())) {
                    if (other instanceof CFUnit enemy && validEnemy(attacker.team(),enemy)) {
                        float before=enemy.getHealth();
                        boolean applied;
                        long splashActionId;
                        try (var audit = CFCombatAudit.begin("BANSHEE_SPLASH", attacker, enemy,
                                s.splashDamage(), damageActionId, -1)) {
                            splashActionId = audit.id();
                            applied=enemy.damage(DamageType.MAGIC,s.splashDamage());
                            audit.finish(applied);
                        }
                        CFTrace.event("SPLASH_HIT","source",attacker.getEntityId(),"victim",enemy.getEntityId(),
                                "damageActionId",splashActionId,"originActionId",damageActionId,
                                "damage",s.splashDamage(),"hpBefore",before,"hpAfter",enemy.getHealth(),
                                "applied",applied);
                    }
                }
                }
            }
        }
    }
    /** Pure geometry test: a forward wave crosses the lane but never affects mobs behind. */
    static boolean inForwardWave(Pos from,Pos toward,Pos candidate,double maxDistance,double halfWidth) {
        double dx=toward.x()-from.x(),dz=toward.z()-from.z();
        double length=Math.hypot(dx,dz);
        if(length<.001)return false;
        double ox=candidate.x()-from.x(),oz=candidate.z()-from.z();
        double forward=(ox*dx+oz*dz)/length;
        double sideways=Math.abs(ox*dz-oz*dx)/length;
        return forward>=0 && forward<=maxDistance && sideways<=halfWidth &&
                Math.abs(candidate.y()-from.y())<=2.5;
    }
    void dot(DotKind kind,CFUnit target,float damage,long intervalMs,int pulses,
                        CFUnit source,long originActionId,long altarId,String altarTeam) {
        if(!alive(target)||pulses<=0)return;
        if(!Float.isFinite(damage)||damage<.001f||intervalMs<1) throw new IllegalArgumentException("Invalid DoT");
        if(spec(target).negativeImmune()){
            CFTrace.event(kind.name()+"_IMMUNE","target",target.getEntityId(),"mob",target.contentId(),
                    "sourceUnit",source==null?-1:source.getEntityId(),
                    "sourceAltar",altarId,"originActionId",originActionId);return;
        }
        DamageStack stack=new DamageStack(kind,target,damage,intervalMs,pulses,
                ++poisonStackSerial,source,originActionId,altarId,altarTeam);
        CFTrace.event(kind.name()+"_APPLY","target",target.getEntityId(),"mob",target.contentId(),
                "stackId",stack.stackId,"sourceUnit",stack.sourceUnitId,
                "sourceMob",stack.sourceMob,"sourceTeam",stack.sourceTeam,
                "sourceAltar",stack.altarId,"originActionId",stack.originActionId,
                "createdNs",stack.createdNs,"hpAtApply",target.getHealth(),
                "damagePerPulse",damage,"intervalMs",intervalMs,"pulses",pulses);
        if(kind==DotKind.POISON){activePoisonStacks++;poisons++;}
        else {activeBurnStacks++;burns++;}
        long generation=unitEpoch;
        long dueNs=System.nanoTime()+intervalMs*1_000_000L;
        schedule(intervalMs,()->pulse(stack,generation,dueNs));
    }
    private void pulse(DamageStack stack,long epoch,long scheduledNs) {
        if (epoch!=unitEpoch) {
            CFTrace.event(stack.kind.name()+"_STACK_STOP", "stackId",stack.stackId,
                    "target",stack.target.getEntityId(),"reason","round-or-unit-reset",
                    "pulsesCompleted",stack.totalPulses-stack.pulsesLeft);
            return;
        }
        if (!alive(stack.target)) {
            stackFinished(stack.kind);
            CFTrace.event(stack.kind.name()+"_STACK_STOP", "stackId",stack.stackId,
                    "target",stack.target.getEntityId(),"reason","target-not-alive",
                    "pulsesCompleted",stack.totalPulses-stack.pulsesLeft);
            return;
        }
        long firedNs=System.nanoTime();
        float before=stack.target.getHealth();
        boolean applied;
        long actionId;
        try (var audit = CFCombatAudit.beginDot(stack.kind.name()+"_PULSE",stack.target,
                stack.damage,stack.originActionId,stack.stackId,stack.sourceUnitId,
                stack.sourceMob,stack.sourceTeam,stack.altarId)) {
            actionId=audit.id();
            applied=stack.target.damage(stack.kind==DotKind.POISON ? DamageType.MAGIC : DamageType.ON_FIRE,stack.damage);
            audit.finish(applied);
        }
        int pulseNumber=stack.totalPulses-stack.pulsesLeft+1;
        CFTrace.event(stack.kind.name()+"_PULSE","target",stack.target.getEntityId(),
                "stackId",stack.stackId,"damageActionId",actionId,
                "originActionId",stack.originActionId,
                "sourceUnit",stack.sourceUnitId,"sourceMob",stack.sourceMob,
                "sourceTeam",stack.sourceTeam,"sourceAltar",stack.altarId,
                "pulse",pulseNumber,"totalPulses",stack.totalPulses,
                "scheduledNs",scheduledNs,"firedNs",firedNs,
                "latenessMs",(firedNs-scheduledNs)/1_000_000.0,
                "damage",stack.damage,"effect",stack.kind.name(),"damageType",stack.kind==DotKind.POISON?"magic":"on_fire","hpBefore",before,"hpAfter",stack.target.getHealth(),
                "hpLost",Math.max(0f,before-stack.target.getHealth()),
                "applied",applied,"pulsesRemainingBefore",stack.pulsesLeft,
                "targetDeadAfter",stack.target.isDead());
        if(stack.kind==DotKind.POISON)poisonTicks++;else burnTicks++;
        if (--stack.pulsesLeft<=0 || !alive(stack.target)) {
            stackFinished(stack.kind);
            CFTrace.event(stack.kind.name()+"_STACK_STOP", "stackId",stack.stackId,
                    "target",stack.target.getEntityId(),
                    "reason",stack.pulsesLeft<=0?"expired":"target-died",
                    "pulsesCompleted",stack.totalPulses-stack.pulsesLeft);
            return;
        }
        long dueNs=System.nanoTime()+stack.intervalMs*1_000_000L;
        schedule(stack.intervalMs,()->pulse(stack,epoch,dueNs));
    }
    private void stackFinished(DotKind kind) {
        if(kind==DotKind.POISON)activePoisonStacks--;else activeBurnStacks--;
    }
    private void slow(CFUnit target,double factor,long ms) {
        if (!alive(target))return;
        if(spec(target).negativeImmune()){
            CFTrace.event("SLOW_IMMUNE","target",target.getEntityId(),"mob",target.contentId());return;
        }
        SlowState state=slowed.computeIfAbsent(target,
                k->new SlowState(target.getAttribute(Attribute.MOVEMENT_SPEED).getBaseValue()));
        SlowLayer layer=new SlowLayer(factor);
        state.layers.add(layer);
        recalcSpeed(target,state);
        CFTrace.event("SLOW_APPLY","target",target.getEntityId(),"factor",factor,
                "durationMs",ms,"layers",state.layers.size());
        schedule(ms,()->{
            SlowState current=slowed.get(target);
            if (current!=state) return;
            state.layers.remove(layer);
            CFTrace.event("SLOW_EXPIRE","target",target.getEntityId(),"layers",state.layers.size());
            if (state.layers.isEmpty()) {
                slowed.remove(target);
                if (alive(target)) target.getAttribute(Attribute.MOVEMENT_SPEED).setBaseValue(state.base);
            } else recalcSpeed(target,state);
        });
    }
    private static void recalcSpeed(CFUnit target,SlowState state) {
        if (!alive(target)) return;
        double multiplier=1;
        for (SlowLayer layer:state.layers) multiplier*=layer.multiplier;
        // No zero or negative movement speed: even many independent slow stacks remain finite.
        target.getAttribute(Attribute.MOVEMENT_SPEED).setBaseValue(Math.max(.005,state.base*multiplier));
    }
    void onUnitSpawn(CFUnit u) {
        CFAbilitySpecs.Spec s=spec(u);
        if (s.summonIntervalMs()<=0 || s.summonMob().isEmpty()) return;
        CFTrace.event("SUMMON_TIMER_INITIAL","unit",u.getEntityId(),"mob",s.summonMob(),
                "intervalMs",s.summonIntervalMs(),"lane",u.laneId());
        long epoch=unitEpoch;
        schedule(s.summonIntervalMs(),()->summonPeriodically(u,s,epoch));
    }
    private void summonPeriodically(CFUnit u,CFAbilitySpecs.Spec s,long epoch) {
        if(epoch!=unitEpoch || !alive(u) || u.laneId()==0)return;
        boolean ok=summoner.summonAhead(u,s.summonMob());
        CFTrace.event("SUMMON_TRIGGER","source",u.getEntityId(),"mob",s.summonMob(),
                "lane",u.laneId(),"team",u.team(),"success",ok);
        if(ok)summons++;
        schedule(s.summonIntervalMs(),()->summonPeriodically(u,s,epoch));
    }
    void onDeath(CFUnit dead) {
        if (!dead.claimDeathAbilityEvent()) {
            CFTrace.event("DEATH_ABILITIES_DUPLICATE", "unit", dead.getEntityId(),
                    "decision", "IGNORED");
            return;
        }
        unitTimers.remove(dead);
        slowed.remove(dead);
        frozenUntil.remove(dead);shieldUntil.remove(dead);
        attackSlowedUntil.remove(dead);attackSlowPct.remove(dead);
        CFTrace.event("DEATH_ABILITIES","unit",dead.getEntityId(),"mob",dead.contentId(),
                "team",dead.team(),"lane",dead.laneId());
        final long now=System.nanoTime();
        final int lane=dead.laneId();
        final Pos at=dead.getPosition();
        final String type=dead.contentId();
        final String team=dead.team();
        final long epoch=unitEpoch;

        // Consuming a blessing is atomic with respect to this single death event.
        // Ten refreshed blessings still mean ONE resurrection, not ten units.
        ResurrectionBless blessing=resurrection.remove(dead);
        boolean revive=blessing!=null && blessing.expiresNs>now && lane>0;
        if(revive) {
            CFTrace.event("REVIVE_SCHEDULE","unit",dead.getEntityId(),"mob",type,
                    "team",team,"lane",lane,"sourceAltar",blessing.altarId,
                    "deathPosition",CFTrace.xyz(at),"delayMs",2000);
            schedule(2000,()->{
                if(epoch!=unitEpoch)return;
                boolean ok=summoner.summonAtDeath(team,type,lane,at);
                CFTrace.event("REVIVE_TRIGGER","unit",dead.getEntityId(),"mob",type,"team",team,
                        "lane",lane,"sourceAltar",blessing.altarId,
                        "deathPosition",CFTrace.xyz(at),"success",ok);
                if(ok)revivals++;
            });
        }

        // At most ONE death-trigger tower reacts per enemy death. The legacy
        // mutation mechanic stays unchanged; Necromancy always produces a grave zombie.
        // Each tower has its own 17s cooldown, never a periodic servant spawn.
        Building selected=null;
        long earliestReady=Long.MAX_VALUE;
        int readyCount=0;
        if(lane>0)for(Building b:buildings.values()) {
            if(CFSoakTest.isRunning() && b.id>=0)continue;
            if((!b.ability.equals("mutation") && !b.ability.equals("necromancy")) || b.team.equals(team))continue;
            long ready=mutationReady.getOrDefault(b.id,0L);
            if(now<ready)continue;
            readyCount++;
            if(ready<earliestReady){earliestReady=ready;selected=b;}
        }
        CFTrace.event("DEATH_PROC_DECISION","unit",dead.getEntityId(),"lane",lane,
                "resurrection",revive,"mutationReadyAltars",readyCount,
                "mutationChosenAltar",selected==null?-1:selected.id,
                "chosenAbility",selected==null?"none":selected.ability);
        if(selected!=null) {
            Building chosen=selected;
            boolean necromancy=chosen.ability.equals("necromancy");
            mutationReady.put(chosen.id,now+17_000_000_000L);
            String summoned=necromancy?"grave_zombie":switch(ThreadLocalRandom.current().nextInt(3)) {
                case 0 -> "mutated_zombie";
                case 1 -> "risen";
                default -> "risen_arsonist";
            };
            String event=necromancy?"NECROMANCY":"MUTATION";
            CFTrace.event(event+"_SCHEDULE","building",chosen.id,"dead",dead.getEntityId(),
                    "spawnMob",summoned,"team",chosen.team,"lane",lane,
                    "deathPosition",CFTrace.xyz(at),"delayMs",50);
            schedule(50,()->{
                if(epoch!=unitEpoch || !buildings.containsKey(chosen.id))return;
                boolean ok=summoner.summonAtDeath(chosen.team,summoned,lane,at);
                CFTrace.event(event+"_TRIGGER","building",chosen.id,"dead",dead.getEntityId(),
                        "mob",summoned,"team",chosen.team,"lane",lane,
                        "deathPosition",CFTrace.xyz(at),"success",ok);
                if(ok) {
                    if(necromancy)necromancies++;
                    else mutations++;
                }
            });
        }
    }
    private final Map<Long,Long> mutationReady=new HashMap<>();
    void addBuilding(long id,String team,String ability,int lane) {addBuilding(id,team,ability,lane,null);}
    void addBuilding(long id,String team,String ability,int lane,Pos origin) {
        if (!Set.of("mutation","necromancy","toxic","resurrection","snow_cannon","snow_shield","snow_obelisk").contains(ability))
            throw new IllegalArgumentException("Unknown support building: "+ability);
        Building b=new Building(id,team,ability,lane,origin);
        buildings.put(id,b);
        if(!ability.equals("mutation") && !ability.equals("necromancy")) schedule(1000,()->buildingCycle(b));
    }
    void removeBuilding(long id) {
        Building removed=buildings.remove(id);mutationReady.remove(id);buildingFrozenUntil.remove(id);
        if(removed!=null)CFTrace.event("BUILDING_ABILITY_REMOVED","id",id,"ability",removed.ability,
                "team",removed.team,"virtual",id<0);
    }
    long addVirtualBuilding(String team,String ability) {
        long id=virtualBuildingId--;
        addBuilding(id,team,ability,0);
        return id;
    }
    void clearVirtualBuildings() {
        for(Long id:List.copyOf(buildings.keySet()))if(id<0)removeBuilding(id);
    }
    private void buildingCycle(Building b) {
        if(buildings.get(b.id)!=b)return;
        if(towerFrozen(b.id)) {
            schedule(1000,()->buildingCycle(b));return;
        }
        if(CFSoakTest.isRunning() && b.id>=0){
            // Real user buildings are not part of test battles. Keep their timer alive so
            // they automatically resume after a long soak, without interacting with its units.
            schedule(b.ability.equals("toxic")?6000:17000,()->buildingCycle(b));
            return;
        }
        if(b.ability.equals("toxic")) {
            CFUnit target=randomUnit(b.team,false);
            CFTrace.event("TOXIC_ALTAR_TICK","building",b.id,"team",b.team,
                    "target",target==null?-1:target.getEntityId(),"selected",target!=null);
            if(target!=null) {
                toxicCasts++;
                dot(DotKind.POISON,target,.6f,2000,6,null,-1,b.id,b.team); // 0.6 HP every 2s for 12s, stacks.
            }
            schedule(6000,()->buildingCycle(b));
        } else if (b.ability.equals("resurrection")) {
            CFUnit target=randomUnit(b.team,true);
            if(target!=null) {
                blessResurrection(target,b.id,b.team,42000);
            } else CFTrace.event("RESURRECTION_ALTAR_NO_TARGET","building",b.id,"team",b.team);
            schedule(17000,()->buildingCycle(b));
        } else if(b.ability.equals("snow_shield")) {
            CFUnit target=randomUnit(b.team,true);
            if(target!=null) {
                grantIceShield(target,80000);
                CFTrace.event("ICE_SHIELD_APPLY","tower",b.id,"unit",target.getEntityId(),"durationMs",80000);
            }
            schedule(10000,()->buildingCycle(b));
        } else if(b.ability.equals("snow_cannon")) {
            CFUnit target=randomUnit(b.team,false);
            if(target!=null) {
                Pos from=b.origin==null?target.getPosition().add(-8,0,0):b.origin;
                Pos to=target.getPosition();
                double dx=to.x()-from.x(),dy=to.y()-from.y(),dz=to.z()-from.z();
                double total=dx*dx+dy*dy+dz*dz;
                if(total>0.01)for(CFUnit enemy:List.copyOf(units))if(alive(enemy)&&!enemy.team().equals(b.team)) {
                    Pos p=enemy.getPosition();
                    double px=p.x()-from.x(),py=p.y()-from.y(),pz=p.z()-from.z();
                    double t=Math.max(0,Math.min(1,(px*dx+py*dy+pz*dz)/total));
                    double ox=px-t*dx,oy=py-t*dy,oz=pz-t*dz;
                    if(ox*ox+oy*oy+oz*oz<=1.0) {
                        enemy.damage(DamageType.MAGIC,3.3f);
                        factionSlowMove(enemy,50,5000); // Tuning: UI slow III has no numeric conversion.
                    }
                }
                if(alive(target))target.kill(); // Source card: endpoint target dies.
                CFTrace.event("SNOW_CANNON_FIRE","tower",b.id,"target",target.getEntityId(),"beamDamage10",33);
            }
            schedule(46000,()->buildingCycle(b));
        } else if(b.ability.equals("snow_obelisk")) {
            long selected=-1;int n=0;var rng=ThreadLocalRandom.current();
            for(Building other:buildings.values())if(other.id>=0 && !other.team.equals(b.team) &&
                    !other.ability.equals("snow_obelisk") && rng.nextInt(++n)==0) selected=other.id;
            if(selected>=0) {
                buildingFrozenUntil.put(selected,System.nanoTime()+31_000_000_000L);
                CFTrace.event("SNOW_OBELISK_FREEZE","tower",b.id,"targetTower",selected,"durationMs",31000);
            }
            schedule(26000,()->buildingCycle(b));
        }
    }
    /** Refresh an existing SINGLE buff without allocating another expiry task. */
    void blessResurrection(CFUnit target,long altarId,String team,long durationMs) {
        if(!alive(target) || durationMs<=0)return;
        long now=System.nanoTime();
        long desired=now+durationMs*1_000_000L;
        ResurrectionBless current=resurrection.get(target);
        boolean refreshing=current!=null && current.expiresNs>now;
        if (current==null) {
            current=new ResurrectionBless(desired,altarId);
            resurrection.put(target,current);
            ResurrectionBless state=current;
            long epoch=unitEpoch;
            schedule(durationMs,()->expireResurrection(target,state,epoch));
        } else {
            current.expiresNs=CFBuffPolicy.mergeUniqueExpiry(current.expiresNs,desired);
            current.altarId=altarId;
        }
        CFTrace.event("RESURRECTION_BLESS","building",altarId,"target",target.getEntityId(),
                "team",team,"expiresInMs",Math.max(0,(current.expiresNs-now)/1_000_000L),
                "refreshed",refreshing,"stacks",1,
                "policy",CFBuffPolicy.RESURRECTION.mode().name());
    }

    private void expireResurrection(CFUnit target,ResurrectionBless state,long epoch) {
        if(epoch!=unitEpoch || resurrection.get(target)!=state)return;
        long remainingNs=state.expiresNs-System.nanoTime();
        if(remainingNs>0) {
            long remainingMs=Math.max(1,(remainingNs+999_999L)/1_000_000L);
            schedule(remainingMs,()->expireResurrection(target,state,epoch));
            return;
        }
        resurrection.remove(target);
        CFTrace.event("RESURRECTION_EXPIRE","building",state.altarId,
                "target",target.getEntityId());
    }
    int activeResurrectionBlessings(){return resurrection.size();}

    private CFUnit randomUnit(String team,boolean ally) {
        int n=0;CFUnit chosen=null;
        var rng=ThreadLocalRandom.current();
        for(CFUnit u:units)if(alive(u) && u.team().equals(team)==ally){
            if(rng.nextInt(++n)==0)chosen=u;
        }
        return chosen;
    }
    private static boolean alive(CFUnit u) {
        return u!=null && u.combatAlive();
    }
    private static boolean validEnemy(String team,CFUnit unit) {
        return alive(unit) && !unit.team().equals(team);
    }
    void clearUnits() {
        unitEpoch++;
        unitTimers.clear();slowed.clear();resurrection.clear();
        frozenUntil.clear();shieldUntil.clear();attackSlowedUntil.clear();attackSlowPct.clear();
        // Poison timers already in the queue become no-ops when their generation expires.
        // Buildings continue operating; /cf clear must not disable placed support buildings.
        activePoisonStacks=0;activeBurnStacks=0;
    }
    int pendingCount(){return pending.size();}
    int activePoisonStacks(){return activePoisonStacks;}
    int activeBurnStacks(){return activeBurnStacks;}
    Map<String,Long> counters(){
        return Map.ofEntries(
                Map.entry("hits",hits),Map.entry("dodges",dodges),
                Map.entry("poisonStacks",poisons),Map.entry("poisonPulses",poisonTicks),
                Map.entry("burnStacks",burns),Map.entry("burnPulses",burnTicks),
                Map.entry("lifesteals",heals),Map.entry("waves",waves),
                Map.entry("splashes",splashes),Map.entry("summons",summons),
                Map.entry("revivals",revivals),Map.entry("mutations",mutations),Map.entry("necromancies",necromancies),
                Map.entry("toxicCasts",toxicCasts));
    }
    String status() {
        return "[CF ABILITIES] hits="+hits+" dodges="+dodges+" poisonStacks="+activePoisonStacks+
                " burnStacks="+activeBurnStacks+" burnTicks="+burnTicks+" poisonTicks="+poisonTicks+" lifesteals="+heals+" waves="+waves+" splash="+splashes+
                " summons="+summons+" mutations="+mutations+" necromancies="+necromancies+" toxicCasts="+toxicCasts+
                " revivals="+revivals+" resurrectionBlessings="+resurrection.size()+
                " supportBuildings="+buildings.size()+" scheduled="+pending.size();
    }
    // Exactly one sleeping timer for the earliest deadline; 0 events = 0 tasks.
    private void schedule(long delayMs,Runnable action) {
        long id=++sequence;
        pending.add(new Due(System.nanoTime()+Math.max(1,delayMs)*1_000_000L,id,action));
        CFTrace.event("ABILITY_TIMER_SCHEDULE","timer",id,"delayMs",delayMs,
                "pending",pending.size());
        if(!draining)arm();
    }
    private void arm() {
        Due soon=pending.peek();
        if(soon==null){if(armed!=null){armed.cancel();armed=null;}armedDeadline=Long.MAX_VALUE;return;}
        if(armed!=null && armedDeadline<=soon.deadline())return;
        if(armed!=null){armed.cancel();armed=null;}
        armedDeadline=soon.deadline();
        long nanos=Math.max(0,soon.deadline()-System.nanoTime());
        int delayTicks=(int)Math.max(1,Math.min(Integer.MAX_VALUE,(nanos+49_999_999L)/50_000_000L));
        armed=world.scheduler().buildTask(this::wake).delay(TaskSchedule.tick(delayTicks)).schedule();
    }
    private void wake() {
        armed=null;armedDeadline=Long.MAX_VALUE;
        draining=true;
        try {
            int budget=256; // Avoid event storms in a single tick.
            while(budget-->0 && !pending.isEmpty() && pending.peek().deadline()<=System.nanoTime()) {
                Due due=pending.poll();
                CFTrace.event("ABILITY_TIMER_FIRE","timer",due.seq(),"pending",pending.size());
                try{due.action().run();}catch(Exception e){
                    CFTrace.event("ABILITY_TIMER_ERROR","timer",due.seq(),"error",e.toString());
                    System.err.println("[CF ABILITY] "+e);e.printStackTrace();
                }
            }
        } finally {draining=false;arm();}
    }
}
