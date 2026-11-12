package dev.cf;

import net.minestom.server.entity.ai.GoalSelector;
import java.time.Duration;
import java.util.Map;
import java.util.function.Function;

/** Reusable behavior factories, independent of entity skin, building and map. */
final class CFCombatBehaviors {
    private static final Map<String, Function<CFUnit, GoalSelector>> FACTORIES = Map.of(
        "ranged_hold", CFHoldRangedGoal::new,
        "melee", u -> new CFTraceMeleeGoal(u, u.attackRange(), Duration.ofMillis(u.attackCooldownMs()))
    );
    static boolean supports(String name) { return FACTORIES.containsKey(name); }
    static GoalSelector create(String name, CFUnit unit) {
        var factory = FACTORIES.get(name);
        if (factory == null) throw new IllegalArgumentException("Unknown combat behavior: " + name);
        return factory.apply(unit);
    }
    private CFCombatBehaviors() { }
}
