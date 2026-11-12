package dev.cf;

import net.minestom.server.entity.ai.GoalSelector;

/** Ranged lock ends only on death/lifecycle invalidity or leaving shot range. */
final class CFHoldRangedGoal extends GoalSelector {
    static final double ATTACK_RANGE = 16.0;
    private final CFUnit unit;

    private long lastShotNs;

    CFHoldRangedGoal(CFUnit unit) {
        super(unit); this.unit = unit;

    }
    @Override public boolean shouldStart() {
        // Unlocked archers also look every tick, not every fourth tick.
        return unit.combatAlive() && (unit.targeting().resolve() != null || unit.targeting().pending());
    }
    @Override public boolean shouldEnd() {
        return !unit.combatAlive() || (unit.targeting().resolve() == null && !unit.targeting().pending());
    }
    @Override public void start() {
        unit.setRangedLock(true); stopMotion();
        CFSkeletonMindLog.event(unit, "COMBAT_START", "target", CFTrace.id(unit.traceTarget()),
                "decision", "MARCH_INTERRUPTED");
    }
    @Override public void tick(long time) {
        if (!unit.combatAlive()) return;
        CFUnit enemy = unit.targeting().resolve();
        stopMotion();
        if (enemy == null) return;
        unit.lookAt(enemy);
        boolean sight = unit.hasLineOfSight(enemy);
        if (!sight) {
            CFSkeletonMindLog.event(unit, "SIGHT_LOST", "target", enemy.getEntityId(),
                    "decision", "RELEASE_TARGET_RESUME_LANE");
            // Keeping an unreachable target locked stopped both shooting AND marching.
            // Drop that target; ranged acquisition already requires line of sight.
            // Next AI evaluation leaves this goal and resumes the existing lane.
            unit.setTarget(null);
            return; // never shoot through walls
        }
        long now = System.nanoTime();
        if ((lastShotNs == 0 || now - lastShotNs >= unit.attackCooldownMs()*1_000_000L)
                && unit.targeting().peek() == enemy && unit.targeting().enemy(unit, enemy)
                && unit.targeting().inRange(unit, enemy)) {
            lastShotNs = now;
            CFSkeletonMindLog.event(unit, "SHOT_DECISION", "target", enemy.getEntityId(),
                    "distance", unit.getDistance(enemy), "lineOfSight", true, "decision", "FIRE_STATIONARY");
            CFClientShot.shoot(unit, enemy);
        }
    }
    @Override public void end() {
        unit.setRangedLock(false); unit.getNavigator().reset();
        CFSkeletonMindLog.event(unit, "COMBAT_END", "target", CFTrace.id(unit.traceTarget()),
                "decision", "RESUME_NEAREST_FORWARD_LANE");
        // Deliberately retain lastShotNs: reacquiring never gives a free shot.
    }
    private void stopMotion() {
        if (unit.getNavigator().getGoalPosition() != null) unit.getNavigator().reset();
        // Do not clear velocity: vanilla pushing and ability knockback still act.
    }
}
