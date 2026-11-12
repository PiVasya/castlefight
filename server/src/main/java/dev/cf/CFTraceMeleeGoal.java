package dev.cf;

import net.minestom.server.coordinate.Pos;
import net.minestom.server.entity.Entity;
import net.minestom.server.entity.ai.GoalSelector;
import net.minestom.server.utils.time.Cooldown;
import java.time.Duration;

/**
 * Target memory is shared with the selector, trace and reverse links. No private
 * Minestom cachedTarget/stop flag can resurrect an outdated selection.
 * Attack cooldown and path-request interval retain the previous behavior.
 */
final class CFTraceMeleeGoal extends GoalSelector {
    private final CFUnit unit;
    private final double attackRangeSquared;
    private final Duration delay;
    private final Cooldown pathCooldown = new Cooldown(Duration.of(5, net.minestom.server.utils.time.TimeUnit.SERVER_TICK));
    private long lastHit;
    private long adjustedDelayMs=-1;
    private Duration adjustedDelay;
    private CFUnit navigationTarget;

    CFTraceMeleeGoal(CFUnit unit, double range, Duration delay) {
        super(unit); this.unit = unit; this.attackRangeSquared = range * range; this.delay = delay;
    }
    @Override public Entity findTarget() { return unit.targeting().resolve(); }
    @Override public boolean shouldStart() {
        return unit.combatAlive() && (findTarget() != null || unit.targeting().pending());
    }
    @Override public boolean shouldEnd() {
        // Resolve before handing control back to MarchGoal. A dead target can be
        // replaced here, keeping the same goal and the same attack cooldown.
        return !unit.combatAlive() || (findTarget() == null && !unit.targeting().pending());
    }
    @Override public void start() {
        unit.getNavigator().reset();
        navigationTarget = null;
        CFSkeletonMindLog.event(unit, "MELEE_START", "target", CFTrace.id(unit.traceTarget()));
    }
    @Override public void tick(long time) {
        if (!unit.combatAlive()) return;
        long began = CFDuelPerf.enterMethod();
        try {
            CFUnit target = unit.targeting().resolve();
            var nav = unit.getNavigator();
            if (target == null) { nav.reset(); navigationTarget = null; return; }
            if (!CFCombatHeight.meleeAllowed(unit.getPosition().y(), target.getPosition().y())) {
                // Do not chase or strike an enemy on a different floor.
                unit.setTarget(null);
                nav.reset(); navigationTarget = null;
                return;
            }
            // Stop a path to the previous enemy before any navigation tick.
            if (navigationTarget != target) { nav.reset(); navigationTarget = target; }
            if (unit.getDistanceSquared(target) <= attackRangeSquared) {
                if (nav.getGoalPosition() != null) nav.reset();
                unit.lookAt(target);
                long currentDelayMs=unit.attackCooldownMs();
                if(currentDelayMs!=adjustedDelayMs) {
                    adjustedDelayMs=currentDelayMs;
                    adjustedDelay=currentDelayMs==delay.toMillis()?delay:Duration.ofMillis(currentDelayMs);
                }
                if (!Cooldown.hasCooldown(time, lastHit, adjustedDelay)
                        && unit.targeting().peek() == target && unit.targeting().enemy(unit, target)) {
                    lastHit = time; // target changes NEVER reset this clock
                    unit.attack(target, true); // existing damage/ability pipeline
                }
            } else if (pathCooldown.isReady(time)) {
                Pos goalPoint=target.getPosition();
                if(unit.isCastleKing()) {
                    Pos home=unit.kingThrone();
                    // Keep the king on the nine throne blocks, even while fighting.
                    goalPoint=new Pos(Math.max(home.x()-1,Math.min(home.x()+1,goalPoint.x())),
                            home.y(),Math.max(home.z()-1,Math.min(home.z()+1,goalPoint.z())));
                }
                var goal = nav.getPathPosition();
                if (goal == null || !goal.samePoint(goalPoint)) {
                    pathCooldown.refreshLastUpdate(time);
                    nav.setPathTo(goalPoint);
                }
            }
        } finally { CFDuelPerf.exitMethod("CFTraceMeleeGoal.tick", began); }
    }
    @Override public void end() {
        unit.getNavigator().reset(); navigationTarget = null;
        CFSkeletonMindLog.event(unit, "MELEE_END", "target", CFTrace.id(unit.traceTarget()));
    }
}
