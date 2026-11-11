package dev.cf;

import net.minestom.server.coordinate.Pos;
import net.minestom.server.coordinate.Vec;
import net.minestom.server.entity.Entity;
import net.minestom.server.entity.EntityType;
import net.minestom.server.entity.Player;
import net.minestom.server.instance.Instance;
import net.minestom.server.network.packet.server.play.DestroyEntitiesPacket;
import net.minestom.server.network.packet.server.play.SpawnEntityPacket;
import net.minestom.server.timer.TaskSchedule;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * CF_ENGINE_11_SIMPLE_ARROWS
 *
 * Cosmetic arrows exist only on clients: one spawn packet, one removal packet.
 * Minecraft renders the vanilla-style falling arrow itself.
 * The server does NO projectile simulation, ray tracing, homing or per-tick updates.
 * Damage is resolved once for a selected target on a delayed callback.
 */
final class CFClientShot {
    // The only values to tune for the visual flight:
    private static final double VISUAL_SPEED = 1.5;          // blocks/client tick
    private static final double FIXED_UPWARD_BIAS = 0.17;    // blocks/client tick
    private static final int VISUAL_LIFETIME_TICKS = 23;     // ~1.15 s total
    private static final double SPAWN_ABOVE_EYES = 0.12;

    private static final double MAX_RANGE = 32.0;
    private static final double VIEW_RANGE_SQ = 96.0 * 96.0;
    private static final float DAMAGE = 3.0f;
    private static final AtomicLong SHOTS = new AtomicLong();
    // Unique shot identity even outside a legacy /cf duel audit session.
    private static final AtomicLong SHOT_IDS = new AtomicLong();
    private static final AtomicLong HITS = new AtomicLong();

    static long shots() { return SHOTS.get(); }
    static long hits() { return HITS.get(); }
    static float damagePerArrow() { return DAMAGE; }

    static void shoot(CFUnit shooter, Entity selected) {
        final CFDamageTable.Hit shotProfile = shooter.normalHit(); // immutable launch snapshot
        final float shotDamage = shotProfile.raw10/10f;
        final CFHitAudit.Shot audit = CFHitAudit.attempt(shooter, selected);
        final long shotId = SHOT_IDS.incrementAndGet();
        if (!(selected instanceof CFUnit target)) {
            CFHitAudit.notFired(audit, "NO_VALID_TARGET");
            return;
        }
        if (!shooter.combatAlive()) {
            CFHitAudit.notFired(audit, "SHOOTER_NOT_ALIVE");
            CFTrace.event("DEAD_ATTACK_BLOCKED", "kind", "RANGED_LAUNCH",
                    "attacker", shooter.getEntityId(), "hp", shooter.getHealth());
            return;
        }
        if (shooter.isRemoved()) { CFHitAudit.notFired(audit, "SHOOTER_REMOVED"); return; }
        if (target.isRemoved()) { CFHitAudit.notFired(audit, "TARGET_REMOVED"); return; }
        if (target.isDead()) { CFHitAudit.notFired(audit, "TARGET_DEAD"); return; }
        if (shooter.team().equals(target.team())) { CFHitAudit.notFired(audit, "FRIENDLY"); return; }
        final Instance world = shooter.getInstance();
        if (world == null || target.getInstance() != world) {
            CFHitAudit.notFired(audit, "DIFFERENT_WORLD");
            return;
        }

        // A single direction calculation on the server; no ballistic solver.
        final Pos from = shooter.getPosition().add(0,
                shooter.getEyeHeight() + SPAWN_ABOVE_EYES, 0);
        final Pos aim = target.getPosition().add(0,
                target.getBoundingBox().height() * 0.6, 0);
        final double dx = aim.x() - from.x();
        final double dy = aim.y() - from.y();
        final double dz = aim.z() - from.z();
        final double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (distance < 0.01 || distance > MAX_RANGE) {
            CFHitAudit.notFired(audit, "OUT_OF_RANGE");
            return;
        }

        final double factor = VISUAL_SPEED / distance;
        final Vec velocity = new Vec(dx * factor,
                dy * factor + FIXED_UPWARD_BIAS, dz * factor);
        final Pos spawn = from.withLookAt(from.add(velocity.x(), velocity.y(), velocity.z()));
        final int arrowId = Entity.generateId();
        final long shotStartNs = System.nanoTime();
        final boolean shooterDeadAtLaunch = shooter.isDead();

        // Spawn directly with velocity, no follow-up velocity packet.
        final SpawnEntityPacket spawnPacket = new SpawnEntityPacket(
                arrowId, new UUID(0L, Integer.toUnsignedLong(arrowId)),
                EntityType.ARROW, spawn, spawn.yaw(), shooter.getEntityId(), velocity);
        final List<Player> viewers = new ArrayList<>();
        for (Player player : world.getPlayers()) {
            if (player.getPosition().distanceSquared(from) <= VIEW_RANGE_SQ) {
                player.sendPacket(spawnPacket);
                viewers.add(player);
            }
        }

        SHOTS.incrementAndGet();
        CFTrace.tagArrow(arrowId, shooter);

        // Damage is resolved once after this delay. No projectile collision is simulated.
        final int hitDelayTicks = Math.min(VISUAL_LIFETIME_TICKS - 2,
                Math.max(1, (int) Math.ceil(distance / VISUAL_SPEED)));
        CFHitAudit.launched(audit, arrowId, distance, hitDelayTicks, viewers.size());
        CFTrace.event("ARROW_SHOOT", "arrow", arrowId, "shot", shotId,
                "duelAuditShot", CFHitAudit.id(audit),
                "shotStartNs", shotStartNs, "shooter", shooter.getEntityId(),
                "shooterDeadAtLaunch", shooterDeadAtLaunch,
                "shooterRemovedAtLaunch", shooter.isRemoved(),
                "shooterHpAtLaunch", shooter.getHealth(),
                "target", target.getEntityId(),
                "targetHpAtFire", target.getHealth(), "hitDelayTicks", hitDelayTicks,
                "damage", shotDamage, "mode", "CLIENT_FIXED_ARC", "from", CFTrace.xyz(from),
                "aim", CFTrace.xyz(aim), "velocity", CFTrace.vel(velocity),
                "speed", VISUAL_SPEED, "upwardBias", FIXED_UPWARD_BIAS,
                "visibleTicks", VISUAL_LIFETIME_TICKS, "viewers", viewers.size());

        world.scheduler().buildTask(() -> {
            final float hpBefore = target.getHealth();
            final boolean removedBefore = target.isRemoved();
            final boolean deadBefore = target.isDead();
            final boolean shooterDeadBeforeImpact = shooter.isDead();
            final boolean shooterRemovedBeforeImpact = shooter.isRemoved();
            final boolean invulnerableBefore = target.isInvulnerable();
            boolean applied = false;
            long impactActionId = -1;
            boolean impactDamageEventSeen = false;
            Boolean impactDamageCancelled = null;
            Float impactDamageEventAmount = null;
            float hpAfterPrimary = hpBefore;
            String reason;
            boolean dodged = false;
            if (removedBefore) reason = "TARGET_REMOVED_BEFORE_IMPACT";
            else if (deadBefore) reason = "TARGET_DEAD_BEFORE_IMPACT";
            else if (target.getInstance() != world) reason = "TARGET_CHANGED_WORLD";
            else {
                dodged=CFArenaAbilities.active()!=null && shooter.isTowerUnit()
                        && CFArenaAbilities.active().dodge(target);
                if(!dodged) {
                    CFHitAudit.beforeDamage(audit);
                    try (var forensic = CFCombatAudit.begin("CLIENT_ARROW_IMPACT", shooter, target,
                            shotDamage, arrowId, arrowId)) {
                        impactActionId = forensic.id();
                        try { applied = target.damage(new CFTypedDamage(shooter,shotProfile)); }
                        finally {
                            CFHitAudit.afterDamage();
                            impactDamageEventSeen = forensic.damageEventSeen();
                            impactDamageCancelled = forensic.damageCancelledFinal();
                            impactDamageEventAmount = forensic.damageAmountFinal();
                            forensic.finish(applied);
                        }
                    }
                    hpAfterPrimary = target.getHealth();
                    if(applied && hpAfterPrimary<hpBefore && CFArenaAbilities.active()!=null
                            && shooter.isTowerUnit())
                        CFArenaAbilities.active().afterSuccessfulHit(shooter,target,true,impactActionId);
                }
                if (applied) HITS.incrementAndGet();
                double usefulHp = CFHitAudit.hpLost(hpBefore, hpAfterPrimary);
                if (applied && usefulHp >= shotDamage - 0.0001) reason = "HIT_FULL";
                else if (applied && usefulHp > 0 && target.isDead()
                        && usefulHp >= Math.max(0, hpBefore) - 0.0001) reason = "HIT_PARTIAL_LETHAL";
                else if (applied && usefulHp > 0) reason = "HIT_REDUCED_DAMAGE";
                else if (applied) reason = "APPLIED_BUT_NO_HP_LOSS";
                else if (usefulHp > 0) reason = "HP_CHANGED_BUT_REJECTED";
                else if (invulnerableBefore) reason = "DAMAGE_IMMUNE";
                else if (audit != null && audit.eventCancelled) reason = "DAMAGE_EVENT_CANCELLED";
                else if(dodged) reason="DODGED";
                else reason = "DAMAGE_REJECTED_UNKNOWN";
            }
            final float hpAfter = target.getHealth();
            final double usefulHp = CFHitAudit.hpLost(hpBefore, hpAfterPrimary);
            CFHitAudit.resolved(audit, target, reason, applied,
                    hpBefore, hpAfterPrimary, invulnerableBefore);
            CFSkeletonMindLog.event(shooter, "SHOT_IMPACT", "target", target.getEntityId(),
                    "applied", applied, "reason", reason,
                    "hpBefore", hpBefore, "hpAfter", hpAfter,
                    "usefulHpLost", usefulHp, "travelTicks", hitDelayTicks);
            CFTrace.event("ARROW_DAMAGE", "arrow", arrowId, "shot", shotId,
                    "duelAuditShot", CFHitAudit.id(audit),
                    "actionId", impactActionId,
                    "shotStartNs", shotStartNs,
                    "elapsedSinceShotMs", (System.nanoTime()-shotStartNs)/1_000_000.0,
                    "shooter", shooter.getEntityId(),
                    "shooterDeadAtLaunch", shooterDeadAtLaunch,
                    "shooterDeadAtImpact", shooterDeadBeforeImpact,
                    "shooterRemovedAtImpact", shooterRemovedBeforeImpact,
                    "shooterDeadAfterImpact", shooter.isDead(),
                    "shooterHpAtImpact", shooter.getHealth(),
                    "target", target.getEntityId(),
                    "applied", applied, "reason", reason,
                    "hpBefore", hpBefore, "hpAfterPrimary", hpAfterPrimary,
                    "hpAfterAbilities", hpAfter, "hpAfter", hpAfter,
                    "primaryHpLost", usefulHp, "abilityHpLost", CFHitAudit.hpLost(hpAfterPrimary, hpAfter),
                    "usefulHpLost", usefulHp,
                    "targetRemovedBefore", removedBefore, "targetDeadBefore", deadBefore,
                    "targetDeadAfter", target.isDead(), "invulnerableBefore", invulnerableBefore,
                    "damageEventSeen", impactDamageEventSeen,
                    "damageEventCancelled", impactDamageCancelled,
                    "damageEventAmount", impactDamageEventAmount,
                    "otherShotsAtLaunch", audit != null ? audit.pendingAtLaunch : null,
                    "hitDelayTicks", hitDelayTicks);
        }).delay(TaskSchedule.tick(hitDelayTicks)).schedule();

        // We DON'T remove on hit: let the client see the arrow fall and lodge.
        // Fixed lifetime approximates 0.5s stuck in ground on an open flat map.
        world.scheduler().buildTask(() -> {
            final DestroyEntitiesPacket destroy = new DestroyEntitiesPacket(arrowId);
            for (Player viewer : viewers) {
                if (viewer.getInstance() == world) viewer.sendPacket(destroy);
            }
            CFTrace.event("ARROW_VISUAL_REMOVE", "arrow", arrowId,
                    "lifetimeTicks", VISUAL_LIFETIME_TICKS);
        }).delay(TaskSchedule.tick(VISUAL_LIFETIME_TICKS)).schedule();
    }

    private CFClientShot() { }
}
