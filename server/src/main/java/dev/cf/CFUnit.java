package dev.cf;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.coordinate.Vec;
import net.minestom.server.entity.Entity;
import net.minestom.server.collision.BoundingBox;
import net.minestom.server.entity.EntityCreature;
import net.minestom.server.entity.attribute.Attribute;
import net.minestom.server.entity.ai.GoalSelector;
import net.minestom.server.item.ItemStack;
import net.minestom.server.entity.damage.Damage;

import java.util.List;

final class CFUnit extends EntityCreature {
    // CF_ENGINE_09_STRAIGHT_ARROWS: fixed aim + no gravity + no spread.
    private final String team;
    private final String kind;
    private final MarchGoal march;
    private final boolean towerSkeleton;
    private final boolean towerUnit;
    private final boolean castleKing;
    private final Pos kingThrone;
    boolean isCastleKing() { return castleKing; }
    Pos kingThrone() { return kingThrone; }
    private final CFContentRegistry.Mob definition;
    boolean isTowerUnit() { return towerUnit; }
    /** Authoritative server stats are integer tenths; Minestom receives a float API bridge. */
    int attackDamage10() { return definition.damage10(); }
    int criticalDamage10() { return definition.critDamage10(); }
    int criticalChancePercent() { return definition.critChancePercent(); }
    float attackDamage() { return attackDamage10() / 10f; }
    CFDamageTable.Hit normalHit() { return definition.combat().normal; }
    CFDamageTable.Hit criticalHit() { return definition.combat().critical; }
    CFDamageTable.Armor armorType() { return definition.combat().armor; }
    int maxHealth10() { return definition.health10(); }
    int health10() { return hp10; }
    private int hp10 = -1;
    double attackRange() { return definition.range(); }
    double detectionRange() { return definition.detection(); }
    private final CFTargeting targeting;
    CFTargeting targeting() { return targeting; }
    long attackCooldownMs() {
        CFArenaAbilities a=CFArenaAbilities.active();
        return a==null?definition.cooldownMs():a.adjustAttackCooldown(this,definition.cooldownMs());
    }
    String contentId() { return definition.id(); }
    String displayName() { return definition.name(); }
    double movementSpeed() { return definition.speed(); }
    String behaviorId() { return definition.behavior(); }
    void groundStep(Pos p) {
        refreshPosition(p);
        onGround = true;
        Vec v = getVelocity();
        setVelocity(new Vec(v.x(), 0, v.z()));
    }
    private volatile boolean rangedLock;
    private Pos lastMindSamplePos;
    private boolean mindDeathLogged;
    private boolean deathAbilityEventClaimed;
    /** Only one set of death procs is permitted per entity lifetime, including duplicate callbacks. */
    boolean claimDeathAbilityEvent() {
        if (deathAbilityEventClaimed) return false;
        deathAbilityEventClaimed = true;
        return true;
    }
    boolean isTowerSkeleton() { return towerSkeleton; }
    boolean isRangedLocked() { return rangedLock; }
    void setRangedLock(boolean locked) { rangedLock = locked; }
    MarchGoal marchGoal() { return march; }
    private volatile java.util.concurrent.atomic.AtomicBoolean benchmarkGate;
    private volatile boolean spawnCompleted;
    void markSpawnCompleted() { spawnCompleted = true; }
    boolean wasSpawnCompleted() { return spawnCompleted; }
    void prepareBenchmark(java.util.concurrent.atomic.AtomicBoolean gate) {
        // Every participant shares the same release gate, so none can start early.
        benchmarkGate = gate;
        setInvulnerable(true);
        setNoGravity(true);
    }
    void releaseBenchmarkPhysics() {
        setNoGravity(false);
        setInvulnerable(false);
    }
    // The combat graph is the only target authority, including diagnostic readers.
    // Lightweight, observation-only movement audit. Fields are not used to control AI.
    private Pos motionPreviousPosition;
    private final Pos motionSpawnPosition;
    Pos motionSpawnPosition() { return motionSpawnPosition; }
    private int motionPushCalls;
    private double motionPushX, motionPushZ;
    private int motionMaxOverlaps;
    // Local crowd recovery is intentionally independent from route completion and combat.
    private long crowdRecoveryUntilTick = -1;
    private Pos crowdProgressPosition;
    private long crowdProgressTick = -1;
    boolean crowdRecoveryActive() { return getAliveTicks() <= crowdRecoveryUntilTick; }
    void requestCrowdRecovery(String reason) {
        boolean entering = !crowdRecoveryActive();
        crowdRecoveryUntilTick = getAliveTicks() + 80; // 4 seconds; renewed only by a new stall.
        if (entering) CFTrace.event("CROWD_RECOVERY_BEGIN", "unit", getEntityId(),
                "lane", laneId(), "reason", reason, "pos", CFTrace.xyz(getPosition()),
                "nav", getNavigator().getState().name());
    }
    void finishCrowdRecovery(String reason) {
        if (!crowdRecoveryActive()) return;
        crowdRecoveryUntilTick = -1;
        CFTrace.event("CROWD_RECOVERY_END", "unit", getEntityId(),
                "lane", laneId(), "reason", reason, "pos", CFTrace.xyz(getPosition()));
    }
    Entity traceTarget() { return targeting == null ? null : targeting.peek(); }
    void traceTarget(Entity target) { setTarget(target); }
    @Override public Entity getTarget() { return traceTarget(); }
    @Override public void setTarget(Entity target) {
        if (targeting != null) targeting.assign(target);
    }
    // Visual-only HP bar. The authoritative CF health is hp10 (int). Minestom mirrors it as float.
    private boolean healthBarVisible = true;
    private int lastDisplayedHealth10 = Integer.MIN_VALUE;
    private int lastDisplayedMaxHealth10 = Integer.MIN_VALUE;
    private static final int HEALTH_CELLS = 10;

    /** Only registered mob definitions can become runtime CF units. */
    CFUnit(String team, CFContentRegistry.Mob definition, Pos spawn, Pos enemyCastle) {
        this(team,definition,spawn,enemyCastle,false);
    }

    CFUnit(String team, CFContentRegistry.Mob definition, Pos spawn, Pos enemyCastle, boolean castleKing) {
        super(definition.entity());
        this.definition = definition;
        this.castleKing = castleKing;
        this.kingThrone = castleKing ? spawn : null;
        this.towerUnit = !castleKing;
        this.towerSkeleton = definition.behavior().equals("ranged_hold");
        this.lastMindSamplePos = spawn;
        this.team = team;
        this.kind = definition.id();
        this.motionPreviousPosition = spawn;
        this.motionSpawnPosition = spawn;

        NamedTextColor color = team.equals("red") ? NamedTextColor.RED : NamedTextColor.BLUE;
        setCustomName(Component.text(team.toUpperCase() + " " + definition.name(), color));
        setCustomNameVisible(true);
        setHelmet(ItemStack.AIR);
        setItemInMainHand(ItemStack.of(definition.weapon()));
        int maxHp = definition.health10();
        // Vanilla's max_health attribute is capped (normally 1024); the
        // authoritative Castle Fight HP stays in hp10, so a king can have 4000.
        getAttribute(Attribute.MAX_HEALTH).setBaseValue(
                Math.min(maxHp / 10.0,Attribute.MAX_HEALTH.maxValue()));
        setHealth(maxHp / 10f);
        getAttribute(Attribute.MOVEMENT_SPEED).setBaseValue(definition.speed());
        getNavigator().setNodeFollower(() -> new CFSmoothGroundFollower(this));
        if(castleKing) getNavigator().setNodeGenerator(() -> new CFKingThroneGenerator(spawn));
        refreshHealthName();

        targeting = new CFTargeting(this);
        march = new MarchGoal(this, spawn, enemyCastle);
        GoalSelector combat = CFCombatBehaviors.create(definition.behavior(), this);
        addAIGroup(castleKing ? List.of(combat, new CFKingGuardGoal(this,spawn))
                : List.of(combat, march), List.of(
                new CFTraceTarget(this)
        ));
    }

    /** One alive predicate used at every NEW attack boundary, never for in-flight impacts. */
    boolean combatAlive() { return !isDead() && !isRemoved() && getHealth() > 0 && getInstance() != null; }

    @Override public float getHealth() {
        return definition==null || hp10<0 ? super.getHealth() : hp10 / 10f;
    }
    @Override public void setHealth(float value) {
        if(definition==null) { super.setHealth(value); return; }
        if(!Float.isFinite(value))throw new IllegalArgumentException("Non-finite health");
        hp10=Math.max(0,Math.min(definition.health10(),Math.round(value*10f)));
        super.setHealth(hp10 / 10f); // Minestom network/engine compatibility; int remains authoritative.
        if (!isDead() && !isRemoved()) refreshHealthName();
    }

    @Override public boolean damage(Damage damage) {
        CFArenaAbilities a=CFArenaAbilities.active();
        float before=getHealth();
        CFUnit source=damage.getAttacker() instanceof CFUnit mob ? mob : null;
        if(damage instanceof CFTypedDamage hit) {
            hit.resolve(armorType(),a,this);
            if(CFCombatAudit.enabled()) CFTrace.event("DAMAGE_MATRIX",
                    "actionId",CFCombatAudit.currentActionId(),"source",source==null?-1:source.getEntityId(),"target",getEntityId(),
                    "damageType",hit.hit.type.id,"armorType",armorType().id,
                    "raw10",hit.hit.raw10,"percent",CFDamageTable.percent(hit.hit.type,armorType()),
                    "afterArmor10",hit.armorDamage10,"iceShield",hit.iceShield,
                    "final10",hit.resolvedDamage10);
            if(hit.resolvedDamage10==0)return false; // No phantom on-hit procs on integer zero.
        } else if(a!=null) a.modifyIncomingDamage(this,damage);
        boolean applied=super.damage(damage);
        if(a!=null && source!=null && applied && getHealth()<before)
            a.onDamaged(this,source);
        return applied;
    }

    @Override public void kill() {
        if (isDead() || isRemoved()) return;
        if (targeting != null) targeting.retire("DEATH");
        // Preserve Minestom's death animation/removal scheduler; stop ONLY new AI work.
        getNavigator().reset();
        setTarget(null);
        traceTarget(null);
        setRangedLock(false);
        setCustomNameVisible(false);
        super.kill();
        if (!mindDeathLogged) {
            mindDeathLogged = true;
            CFSkeletonMindLog.event(this, "UNIT_END", "dead", isDead(),
                    "removed", isRemoved(), "hp", getHealth(), "decision", "AI_STOPPED_DEATH_ANIMATION_ONLY");
        }
    }

    @Override protected void remove(boolean permanent) {
        if (targeting != null) {
            if (permanent) targeting.retire("REMOVED");
            else targeting.worldChanged();
        }
        super.remove(permanent);
    }

    @Override public java.util.concurrent.CompletableFuture<Void> setInstance(
            net.minestom.server.instance.Instance instance, Pos position) {
        if (targeting != null && getInstance() != null && getInstance() != instance)
            targeting.worldChanged();
        return super.setInstance(instance, position);
    }

    // Vanilla Java entities have no hard body-blocking movement constraint:
    // their bodies can overlap and are separated by horizontal velocity impulses.
    // In particular, do NOT cancel collision velocity or snap mobs to empty spots.
    // Minestom's own movementTick() remains responsible for block/world physics.
    @Override
    public void update(long time) {
        var gate = benchmarkGate;
        if (gate != null && !gate.get()) return;
        long _cfPerfBegin = CFDuelPerf.enterMethod();
        long _cfMegaUnit = CFMegaPerf.begin();
        try {
        if (CFTrace.detailed()) CFTrace.event("UNIT_UPDATE_ENTER", "unit", getEntityId(),
                "ageTicks", getAliveTicks(), "pos", CFTrace.xyz(getPosition()));
        if (CFCombatAudit.enabled() && (isDead() || isRemoved()))
            CFTrace.event("DEAD_UNIT_UPDATE_ENTER", "unit", getEntityId(),
                    "kind", contentId(), "hp", getHealth(),
                    "dead", isDead(), "removed", isRemoved(),
                    "ageTicks", getAliveTicks());
        // Entity.tick() still processes the entity removal scheduler before update().
        // EntityCreature.update() would run aiTick() even for a dying entity: do not enter it.
        if (!combatAlive()) return;
        CFArenaAbilities a = CFArenaAbilities.active();
        if (a!=null && a.isFrozen(this)) {
            setVelocity(Vec.ZERO);
            return; // Frozen mobs cannot walk, retarget, attack or shoot.
        }
        super.update(time);
        if (CFCombatAudit.enabled() && (isDead() || isRemoved()))
            CFTrace.event("DEAD_UNIT_UPDATE_AFTER_SUPER", "unit", getEntityId(),
                    "kind", contentId(), "hp", getHealth(),
                    "dead", isDead(), "removed", isRemoved(),
                    "ageTicks", getAliveTicks());
        if (isTowerUnit() && getInstance() != null) {
            Pos here = getPosition();
            double movedSinceSample = Math.hypot(here.x() - lastMindSamplePos.x(),
                    here.z() - lastMindSamplePos.z());
            if (CFSkeletonMindLog.sample(this, movedSinceSample)) lastMindSamplePos = here;
        }
        if (isTowerUnit() && (isDead() || isRemoved()) && !mindDeathLogged) {
            mindDeathLogged = true;
            CFSkeletonMindLog.event(this, "UNIT_END", "dead", isDead(),
                    "removed", isRemoved(), "lastPosition", CFTrace.xyz(getPosition()),
                    "hp", getHealth(), "lastGoal", CFTrace.xyz(march.checkpoint()));
        }
        if (isDead() || isRemoved() || getInstance() == null) {
            CFTrace.event("UNIT_UPDATE_SKIP", "unit", getEntityId(),
                    "dead", isDead(), "removed", isRemoved(),
                    "instanceNull", getInstance() == null, "hp", getHealth());
            return;
        }
        CFTrace.unitTick(this);
        CFSoakTest.unitTick(this);
        // Once per second per unit, staggered across ticks: ~35k lines per 500-unit arena.
        // This is exclusively diagnostics; AI, pathfinding, combat and physics are unchanged.
        if (CFTrace.compactMovement() &&
                Math.floorMod(getAliveTicks() + (long) getEntityId(), 20L) == 0L) {
            final Pos now = getPosition();
            final Pos previous = motionPreviousPosition;
            final double moved = Math.hypot(now.x() - previous.x(), now.z() - previous.z());
            CFTrace.movementSample(this, march, moved, motionPushCalls,
                    motionPushX, motionPushZ, motionMaxOverlaps);
            motionPreviousPosition = now;
            motionPushCalls = 0;
            motionPushX = 0.0;
            motionPushZ = 0.0;
            motionMaxOverlaps = 0;
        }
        // Stagger HP checks: once per 4 ticks per mob; metadata sent only if HP changed.
        if (((getAliveTicks() + getEntityId()) & 3L) == 0) refreshHealthName();
        // Mirror the vanilla LivingEntity.pushEntities crowd pass after movement/AI.
        // Every living CFUnit processes its own neighbours each tick, including
        // stationary ranged units.  No direct refreshPosition() or escape probes.
        pushNeighbors();
    
        } finally {
            CFMegaPerf.end(CFMegaPerf.Metric.UNIT_UPDATE, _cfMegaUnit);
            CFDuelPerf.exitMethod("CFUnit.update", _cfPerfBegin);
        }
    }

    /**
     * Minecraft Java's Entity.push horizontal separation math for one living body.
     * Minestom stores velocity in blocks/SECOND; vanilla stores it per TICK.
     * Therefore multiply the vanilla impulse by the configured server TPS.
     *
     * Each unit writes ONLY its own velocity, so parallel entity updates cannot
     * overwrite another CFUnit's navigation/knockback state. Reciprocal contacts
     * are processed by the other entity's own update, just like living mob ticks.
     */
    private void pushNeighbors() {
        long begin = CFDuelPerf.enterMethod();
        long _cfMegaPush = CFMegaPerf.begin();
        try {
            if (getInstance() == null) return;
            if (!CFMegaPerf.pushOnThisTick(getAliveTicks(), getEntityId())) {
                CFMegaPerf.count(CFMegaPerf.Count.PUSH_SKIPPED, 1);
                return;
            }
            CFMegaPerf.count(CFMegaPerf.Count.PUSH_SCANS, 1);
            Pos here = getPosition();
            BoundingBox own = getBoundingBox();
            double tickPushX = 0, tickPushZ = 0;
            int contacts = 0;
            int candidates = 0;
            // 1.8 blocks covers the full width of all current Castle Fight mobs.
            // Do not apply a team filter: vanilla mobs push friend and foe alike.
            for (Entity candidate : getInstance().getNearbyEntities(here, 1.8)) {
                candidates++;
                if (!(candidate instanceof CFUnit other) || other == this || !other.combatAlive())
                    continue;
                Pos there = other.getPosition();
                BoundingBox theirs = other.getBoundingBox();
                if (!bodyIntersects(own, here, theirs, there)) continue;
                CFVanillaPushMath.Impulse impulse = CFVanillaPushMath.fromCenterDelta(
                        there.x() - here.x(), there.z() - here.z());
                if (impulse.isZero()) continue; // Vanilla: exact co-location has no direction.
                // Vanilla visits the same pair from BOTH living entities and
                // Entity.push adjusts both velocities in each visit. Because
                // this integration writes self velocity only (thread-safe),
                // account for the reciprocal visit with the second impulse.
                tickPushX -= 2.0 * impulse.x();
                tickPushZ -= 2.0 * impulse.z();
                contacts++;
            }
            CFMegaPerf.count(CFMegaPerf.Count.PUSH_CANDIDATES, candidates);
            CFMegaPerf.count(CFMegaPerf.Count.PUSH_CONTACTS, contacts);
            if (contacts == 0) return;
            final double tps = net.minestom.server.property.ServerProperties
                    .SERVER_TICKS_PER_SECOND.get();
            Vec prior = getVelocity();
            // Velocity is retained when an archer is firing: its combat goal
            // only stops active navigation, not external physical impulses.
            setVelocity(new Vec(prior.x() + tickPushX * tps, prior.y(),
                    prior.z() + tickPushZ * tps));
            if (CFTrace.compactMovement()) {
                motionPushCalls += contacts;
                motionPushX += tickPushX * tps;
                motionPushZ += tickPushZ * tps;
                motionMaxOverlaps = Math.max(motionMaxOverlaps, contacts);
            }
            // 1 summary per second per unit; per-contact logs only in detailed mode.
            if (CFTrace.detailed() || Math.floorMod(getAliveTicks() + (long) getEntityId(),20L) == 0L)
                CFTrace.event("VANILLA_PUSH", "unit", getEntityId(),
                        "team", team(), "contacts", contacts,
                        "deltaXPerTick", tickPushX, "deltaZPerTick", tickPushZ,
                        "ranged", isRangedLocked(), "pos", CFTrace.xyz(here));
        } finally {
            CFMegaPerf.end(CFMegaPerf.Metric.PUSH_SCAN, _cfMegaPush);
            CFDuelPerf.exitMethod("CFUnit.pushNeighbors", begin);
        }
    }

    private static boolean bodyIntersects(BoundingBox a, Pos pa, BoundingBox b, Pos pb) {
        // Vanilla LivingEntity.pushEntities selects neighbours with the local
        // bounding box inflated by 0.2 block horizontally.  Unlike the former
        // .76-body barrier this is only a push trigger, not a solid collision.
        final double selectionPadding = (double) 0.2F;
        return pa.x() + a.minX() - selectionPadding < pb.x() + b.maxX()
                && pb.x() + b.minX() < pa.x() + a.maxX() + selectionPadding
                && pa.y() + a.minY() < pb.y() + b.maxY()
                && pb.y() + b.minY() < pa.y() + a.maxY()
                && pa.z() + a.minZ() - selectionPadding < pb.z() + b.maxZ()
                && pb.z() + b.minZ() < pa.z() + a.maxZ() + selectionPadding;
    }

    void setHealthBarVisible(boolean visible) {
        if (this.healthBarVisible == visible) return;
        this.healthBarVisible = visible;
        this.lastDisplayedHealth10 = Integer.MIN_VALUE; // force a new name on enable
        this.lastDisplayedMaxHealth10 = Integer.MIN_VALUE;
        if (visible) {
            if (!isDead() && !isRemoved()) {
                setCustomNameVisible(true);
                refreshHealthName();
            }
        } else {
            // Полоска выключена: не подменять её именем и не показывать числа.
            setCustomNameVisible(false);
        }
    }

    private Component baseName() {
        NamedTextColor color = team.equals("red")
                ? NamedTextColor.RED : NamedTextColor.BLUE;
        return Component.text(team.toUpperCase() + " " + definition.name(), color);
    }

    private void refreshHealthName() {
        if (!healthBarVisible || isDead() || isRemoved()) return;

        int healthUnits = Math.max(0,health10());
        int maxHealthUnits = Math.max(1,maxHealth10());
        if (healthUnits==lastDisplayedHealth10 && maxHealthUnits==lastDisplayedMaxHealth10)return;
        int previouslyDisplayed10=lastDisplayedHealth10;
        lastDisplayedHealth10=healthUnits;
        lastDisplayedMaxHealth10=maxHealthUnits;
        if (CFCombatAudit.enabled())
            CFTrace.event("HEALTH_LABEL_REFRESH", "unit", getEntityId(),
                    "mob", contentId(), "team", team(),
                    "hp10", healthUnits, "hpLabel", Integer.toString(healthUnits),
                    "labelSchema", "int10-direct", "hpPreviouslyDisplayed10", previouslyDisplayed10,
                    "maxHealth10", maxHealthUnits, "ageTicks", getAliveTicks());

        // Even the visible HP bar uses integer math; zero decimal values in UI.
        int filled=(int)Math.min(HEALTH_CELLS,
                ((long)healthUnits*HEALTH_CELLS+maxHealthUnits-1)/maxHealthUnits);
        NamedTextColor barColor=(long)healthUnits*2>maxHealthUnits
                ? NamedTextColor.GREEN
                : (long)healthUnits*4>maxHealthUnits ? NamedTextColor.YELLOW : NamedTextColor.RED;

        // ВАЖНО: над мобом должна быть ТОЛЬКО ГОЛАЯ ПОЛОСКА HP.
        // Запрещено добавлять сюда имя, числа, скобки или проценты.
        // Имена и реальные характеристики теперь показываются отдельно по наведению.
        Component label = Component.text("■".repeat(filled), barColor)
                .append(Component.text("□".repeat(HEALTH_CELLS - filled), NamedTextColor.DARK_GRAY));
        setCustomName(label);
    }

    String team() { return team; }
    String kind() { return kind; }
    void updateDestination(Pos castle) { march.retarget(castle); }
    void assignValidatedLane(CFLanePath lane, List<Pos> journey) {
        march.assignValidatedLane(lane,journey);
        getNavigator().setNodeGenerator(() -> new CFLaneRuntimeGenerator(this));
        CFSkeletonMindLog.event(this,"LANE_BOUND","lane",lane.id,"team",team,
                "nodes",journey.size(),"decision","IMMUTABLE_LANE_ID");
    }
    int laneId() { return march.laneId(); }

    void assignLane(List<Pos> points, int roadWidth) {
        march.assignLane(points, roadWidth, motionSpawnPosition);
    }
}
