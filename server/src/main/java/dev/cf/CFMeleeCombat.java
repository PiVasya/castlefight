package dev.cf;

import net.minestom.server.entity.Entity;

/** Single entry point for actual melee damage. No cooldown loops and no work without an attack. */
final class CFMeleeCombat {
    private CFMeleeCombat() { }
    static boolean attack(CFUnit attacker, Entity selected) {
        long swing = CFCombatAudit.nextId();
        CFUnit target = selected instanceof CFUnit u ? u : null;
        String rejection = !attacker.combatAlive() ? "SOURCE_NOT_ALIVE"
                : !"melee".equals(attacker.behaviorId()) ? "SOURCE_NOT_MELEE"
                : target == null ? "NOT_CF_UNIT"
                : !target.combatAlive() ? "TARGET_NOT_ALIVE"
                : target.getInstance() != attacker.getInstance() ? "DIFFERENT_WORLD"
                : target.team().equals(attacker.team()) ? "FRIENDLY"
                : !CFCombatHeight.meleeAllowed(attacker.getPosition().y(), target.getPosition().y()) ? "HEIGHT_LIMIT"
                : attacker.getDistanceSquared(target) > Math.pow(attacker.attackRange()+.2,2) ? "OUT_OF_RANGE" : null;
        CFTrace.event("MELEE_ATTEMPT", "swingId", swing, "attacker", attacker.getEntityId(),
                "attackerMob", attacker.contentId(), "attackerTeam", attacker.team(),
                "attackerDeadAtAttempt", attacker.isDead(), "attackerRemovedAtAttempt", attacker.isRemoved(),
                "attackerHpAtAttempt", attacker.getHealth(), "target", CFTrace.id(selected),
                "targetHp", target == null ? null : target.getHealth(), "rejected", rejection);
        if (rejection != null) {
            CFTrace.event("MELEE_REJECTED", "swingId", swing, "attacker", attacker.getEntityId(),
                    "target", CFTrace.id(selected), "reason", rejection);
            if (!attacker.combatAlive()) CFTrace.event("DEAD_ATTACK_BLOCKED", "kind", "MELEE",
                    "swingId", swing, "attacker", attacker.getEntityId(), "target", CFTrace.id(selected));
            return false;
        }
        float before = target.getHealth();
        CFDamageTable.Hit profile=attacker.normalHit();
        if(attacker.criticalChancePercent()>0 &&
                java.util.concurrent.ThreadLocalRandom.current().nextInt(100)<attacker.criticalChancePercent())
            profile=attacker.criticalHit();
        int damage10=profile.raw10;
        final float hitDamage=damage10/10f;
        CFArenaAbilities abilities = CFArenaAbilities.active();
        boolean dodged = abilities != null && abilities.dodge(target);
        boolean applied = false;
        long action = -1;
        if (!dodged) {
            try (var audit = CFCombatAudit.begin("MELEE", attacker, target, hitDamage, swing, -1)) {
                action = audit.id();
                applied = target.damage(new CFTypedDamage(attacker,profile));
                audit.finish(applied);
            }
        }
        float primary = target.getHealth();
        // Reactions occur only after real HP loss, never after a dodge or rejected damage.
        if (applied && primary < before && abilities != null && !attacker.isCastleKing())
            abilities.afterSuccessfulHit(attacker, target, false, action);
        float after = target.getHealth();
        CFHitAudit.meleeDamage(target, before, primary);
        CFSkeletonMindLog.event(attacker, "MELEE_DAMAGE", "target", target.getEntityId(),
                "hpBefore", before, "hpAfterPrimary", primary, "hpAfterAbilities", after, "applied", applied);
        CFTrace.event("MELEE_DAMAGE", "swingId", swing, "actionId", action,
                "attacker", attacker.getEntityId(), "attackerMob", attacker.contentId(),
                "attackerDeadAtDamage", false, "attackerRemovedAtDamage", false,
                "attackerDeadAfterAbilities", attacker.isDead(), "target", target.getEntityId(),
                "requested", hitDamage, "damage10",damage10, "hpBefore", before, "hpAfterPrimary", primary,
                "hpAfterAbilities", after, "hpAfter", after, "applied", applied, "dodged", dodged,
                "primaryHpLost", CFHitAudit.hpLost(before,primary),
                "abilityHpLost", CFHitAudit.hpLost(primary,after), "distance", attacker.getDistance(target));
        return applied;
    }
}
