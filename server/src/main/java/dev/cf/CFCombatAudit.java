package dev.cf;

import net.minestom.server.entity.Entity;
import net.minestom.server.entity.damage.Damage;
import net.minestom.server.event.entity.EntityDamageEvent;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Observation-only combat provenance for active SOAK runs and explicit /cf trace.
 * No combat decisions, damage amounts, invulnerability, AI or timers are changed.
 * A scoped source context ties DamageEvent/DeathEvent to the actual damage call.
 */
final class CFCombatAudit {
    private static final AtomicLong IDS = new AtomicLong();
    private static final ThreadLocal<Scope> CURRENT = new ThreadLocal<>();

    private CFCombatAudit() { }
    static boolean enabled() { return CFSoakTest.isRunning() || CFTrace.on(); }
    static long nextId() { return enabled() ? IDS.incrementAndGet() : -1L; }
    static long currentActionId() {
        Scope scope=CURRENT.get();return scope==null?-1L:scope.id;
    }

    static Scope begin(String cause, CFUnit source, CFUnit target, float requested,
                       long parentId, long relatedId) {
        if (!enabled()) return Scope.NOOP;
        return open(cause,source,target,requested,parentId,relatedId,
                source==null?-1:source.getEntityId(),source==null?"external":source.contentId(),
                source==null?"external":source.team(),-1);
    }
    static Scope beginDot(String cause,CFUnit target,float requested,long parentId,long relatedId,
                          int originUnit,String originMob,String originTeam,long altarId) {
        if (!enabled()) return Scope.NOOP;
        return open(cause,null,target,requested,parentId,relatedId,originUnit,originMob,originTeam,altarId);
    }
    private static Scope open(String cause,CFUnit source,CFUnit target,float requested,
                              long parentId,long relatedId,int originUnit,String originMob,
                              String originTeam,long altarId) {
        Scope previous = CURRENT.get();
        Scope scope = new Scope(nextId(), cause, source, target, requested,
                parentId, relatedId, previous,originUnit,originMob,originTeam,altarId);
        CURRENT.set(scope);
        CFTrace.event("DAMAGE_BEGIN", "actionId", scope.id, "parentId", parentId,
                "relatedId", relatedId, "cause", cause,
                "source", scope.originUnit, "sourceTeam", scope.originTeam, "sourceAltar", scope.altarId,
                "sourceMob", scope.originMob,
                "sourceDead", source == null ? null : source.isDead(),
                "sourceStateKnown", source != null,
                "sourceRemoved", source == null ? null : source.isRemoved(),
                "sourceHp", source == null ? null : source.getHealth(),
                "victim", target.getEntityId(), "victimMob", target.contentId(),
                "victimTeam", target.team(), "victimLane", target.laneId(),
                "requested", requested, "hpBefore", scope.hpBefore,
                "victimDead", target.isDead(), "victimRemoved", target.isRemoved(),
                "victimInvulnerable", target.isInvulnerable(),
                "thread", Thread.currentThread().getName());
        return scope;
    }

    /** Called by the existing Minestom EntityDamageEvent listener; read-only. */
    static void damageEvent(EntityDamageEvent event) {
        if (!enabled() || !(event.getEntity() instanceof CFUnit victim)) return;
        Scope scope = CURRENT.get();
        if(scope!=null && scope.victim==victim)scope.observedEvent=event;
        Damage damage = event.getDamage();
        CFTrace.event("DAMAGE_EVENT_OBSERVED",
                "actionId", scope == null ? -1 : scope.id,
                "cause", scope == null ? "UNATTRIBUTED" : scope.cause,
                "victim", victim.getEntityId(),
                "source", CFTrace.id(damage.getAttacker()),
                "sourceEntity", CFTrace.id(damage.getSource()),
                "damageType", String.valueOf(damage.getType()),
                "eventAmount", damage.getAmount(),
                "cancelledAtObservation", event.isCancelled(),
                "hpAtEvent", victim.getHealth(),
                "deadAtEvent", victim.isDead(), "removedAtEvent", victim.isRemoved(),
                "invulnerableAtEvent", victim.isInvulnerable(),
                "lastDamageType", victim.getLastDamageSource() == null ? null
                        : String.valueOf(victim.getLastDamageSource().getType()));
    }

    /** Captures death precisely while an active damage() context is on the call stack. */
    static void death(CFUnit unit) {
        if (!enabled()) return;
        Scope scope = CURRENT.get();
        Damage last = unit.getLastDamageSource();
        CFTrace.event("UNIT_DEATH_CAUSE", "unit", unit.getEntityId(),
                "mob", unit.contentId(), "team", unit.team(), "lane", unit.laneId(),
                "hpAtDeath", unit.getHealth(), "dead", unit.isDead(),
                "removed", unit.isRemoved(),
                "actionId", scope == null ? -1 : scope.id,
                "cause", scope == null ? "UNATTRIBUTED" : scope.cause,
                "parentId", scope == null ? -1 : scope.parentId,
                "source", scope == null ? -1 : scope.originUnit,
                "sourceMob", scope == null ? "external" : scope.originMob,
                "sourceTeam", scope == null ? "external" : scope.originTeam,
                "sourceAltar", scope == null ? -1 : scope.altarId,
                "relatedId", scope == null ? -1 : scope.relatedId,
                "lastDamageType", last == null ? null : String.valueOf(last.getType()),
                "lastDamageAttacker", last == null ? -1 : CFTrace.id(last.getAttacker()),
                "nano", System.nanoTime());
    }

    static final class Scope implements AutoCloseable {
        static final Scope NOOP = new Scope();
        final long id, parentId, relatedId, startNs;
        final String cause,originMob,originTeam;
        final int originUnit;
        final long altarId;
        final CFUnit source, victim;
        final float hpBefore, requested;
        final Scope previous;
        private EntityDamageEvent observedEvent;
        private boolean finished;
        private final boolean live;

        private Scope() {
            id = parentId = relatedId = startNs = -1;
            cause = originMob = originTeam = "off";originUnit=-1;altarId=-1; source = victim = null; hpBefore = requested = 0;
            previous = null; live = false;
        }
        private Scope(long id, String cause, CFUnit source, CFUnit victim,
                      float requested, long parentId, long relatedId, Scope previous, int originUnit, String originMob, String originTeam, long altarId) {
            this.originUnit=originUnit;this.originMob=originMob;this.originTeam=originTeam;this.altarId=altarId;
            this.id = id; this.cause = cause; this.source = source; this.victim = victim;
            this.requested = requested; this.parentId = parentId; this.relatedId = relatedId;
            this.previous = previous; this.hpBefore = victim.getHealth();
            this.startNs = System.nanoTime(); this.live = true;
        }
        long id() { return id; }
        boolean damageEventSeen() { return observedEvent != null; }
        Boolean damageCancelledFinal() { return observedEvent == null ? null : observedEvent.isCancelled(); }
        Float damageAmountFinal() { return observedEvent == null ? null : observedEvent.getDamage().getAmount(); }
        void finish(boolean applied) {
            if (!live || finished) return;
            finished = true;
            float after = victim.getHealth();
            CFTrace.event("DAMAGE_RESULT", "actionId", id,
                    "parentId", parentId, "relatedId", relatedId, "cause", cause,
                    "source", originUnit, "sourceMob", originMob, "sourceTeam", originTeam, "sourceAltar", altarId,
                    "sourceDeadAfter", source != null && source.isDead(),
                    "victim", victim.getEntityId(), "requested", requested,
                    "applied", applied, "hpBefore", hpBefore, "hpAfter", after,
                    "eventAmountFinal", observedEvent==null?null:observedEvent.getDamage().getAmount(),
                    "eventCancelledFinal", observedEvent==null?null:observedEvent.isCancelled(),
                    "hpLost", Math.max(0f, hpBefore - after),
                    "hpGained", Math.max(0f, after - hpBefore),
                    "victimDeadAfter", victim.isDead(),
                    "victimRemovedAfter", victim.isRemoved(),
                    "victimInvulnerableAfter", victim.isInvulnerable(),
                    "elapsedNs", System.nanoTime() - startNs);
        }
        @Override public void close() {
            if (!live) return;
            if (!finished) {
                CFTrace.event("DAMAGE_CONTEXT_UNFINISHED", "actionId", id,
                        "cause", cause, "victim", victim.getEntityId(),
                        "hpBefore", hpBefore, "hpAtExit", victim.getHealth());
            }
            if (previous == null) CURRENT.remove();
            else CURRENT.set(previous);
        }
    }
}
