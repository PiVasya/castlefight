package dev.cf;

import net.minestom.server.entity.Entity;
import net.minestom.server.entity.damage.Damage;
import net.minestom.server.entity.damage.DamageType;

/** Explicit CF attack metadata. Never infer CF magic from Minestom MAGIC damage:
 * that native cause is also used by poison, frozen ticks and old test helpers.
 * This replaces the Damage object the attack already allocated, not adds a
 * context map, thread-local, per-victim table or per-tick job.
 */
final class CFTypedDamage extends Damage {
    final CFDamageTable.Hit hit;
    int armorDamage10, resolvedDamage10;
    boolean iceShield;
    CFTypedDamage(CFUnit attacker,CFDamageTable.Hit hit) {
        super(DamageType.MOB_ATTACK,attacker,attacker,attacker.getPosition(),hit.raw10/10f);
        this.hit=hit;
    }
    CFTypedDamage(CFUnit attacker,Entity projectile,CFDamageTable.Hit hit) {
        super(DamageType.MOB_PROJECTILE,projectile,attacker,null,hit.raw10/10f);
        this.hit=hit;
    }
    void resolve(CFDamageTable.Armor armor,CFArenaAbilities effects,CFUnit victim) {
        // Explicit king rule: a successful basic melee swing removes exactly 10
        // real HP (=100 int10), regardless of armor and dynamic shields. This is
        // ONLY for the king; normal damage table and status stages are unchanged.
        if (getAttacker() instanceof CFUnit attacker && attacker.isCastleKing()) {
            armorDamage10 = hit.raw10;
            resolvedDamage10 = hit.raw10;
            iceShield = false;
            setAmount(resolvedDamage10 / 10f);
            return;
        }
        // Armor lookup is precomputed and immutable. Shields are runtime stages.
        armorDamage10=hit.amount10(armor);
        iceShield=effects!=null && effects.hasIceShield(victim);
        resolvedDamage10=effects==null?armorDamage10:effects.postArmorDamage10(victim,armorDamage10);
        // Minestom event/network boundary only; resolve from original hit EVERY TIME.
        setAmount(resolvedDamage10/10f);
    }
}
