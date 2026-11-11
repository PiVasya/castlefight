package dev.cf;

import net.minestom.server.entity.EntityCreature;
import net.minestom.server.entity.Entity;
import net.minestom.server.entity.ai.goal.RangedAttackGoal;
import java.time.Duration;

final class CFTraceRangedGoal extends RangedAttackGoal {
    private final CFUnit unit;
    CFTraceRangedGoal(CFUnit u, Duration delay, int range, int desired, boolean comeClose,
                      double power, double spread) {
        super(u, delay, range, desired, comeClose, power, spread);
        unit=u;
    }
    @Override public Entity findTarget() {
        Entity chosen = super.findTarget();
        unit.traceTarget(chosen);
        if (CFTrace.detailed()) CFTrace.event("RANGED_TARGET_RESOLVE", "unit", unit.getEntityId(),
                "chosen", CFTrace.id(chosen),
                "distance", chosen == null ? -1 : unit.getDistance(chosen),
                "visible", chosen != null && unit.hasLineOfSight(chosen),
                "creatureTarget", CFTrace.id(unit.getTarget()));
        return chosen;
    }
    @Override public boolean shouldStart() {
        boolean yes=super.shouldStart();
        if (CFTrace.detailed()) CFTrace.event("RANGED_SHOULD_START", "unit", unit.getEntityId(), "yes", yes,
                "target", CFTrace.id(unit.traceTarget()), "goal", CFTrace.goal(unit),
                "distance", unit.traceTarget()==null?-1:unit.getDistance(unit.traceTarget()),
                "nav", unit.getNavigator().getState().name());
        return yes;
    }
    @Override public boolean shouldEnd() {
        boolean yes=super.shouldEnd();
        if (CFTrace.detailed()) CFTrace.event("RANGED_SHOULD_END", "unit", unit.getEntityId(), "yes", yes,
                "target", CFTrace.id(unit.traceTarget()));
        return yes;
    }
    @Override public void start() {
        if (CFTrace.detailed()) CFTrace.event("RANGED_START", "unit", unit.getEntityId(), "target", CFTrace.id(unit.traceTarget()));
        super.start();
    }
    @Override public void tick(long time) {
        long _cfPerfBegin = CFDuelPerf.enterMethod();
        try {
        if (CFTrace.detailed()) CFTrace.event("RANGED_TICK_BEGIN", "unit", unit.getEntityId(), "target", CFTrace.id(unit.traceTarget()),
                "pos", CFTrace.xyz(unit.getPosition()), "navGoal", CFTrace.xyz(unit.getNavigator().getGoalPosition()));
        Entity selected = unit.traceTarget();
        double distance = selected == null ? -1 : unit.getDistance(selected);
        if (CFTrace.detailed()) CFTrace.event("RANGED_DECISION_INPUT", "unit", unit.getEntityId(),
                "selected", CFTrace.id(selected), "distance", distance,
                "withinAttack16", distance >= 0 && distance <= 16,
                "withinDesired10", distance >= 0 && distance <= 10,
                "lineOfSight", selected != null && unit.hasLineOfSight(selected),
                "pathBefore", CFTrace.xyz(unit.getNavigator().getPathPosition()));
        super.tick(time);
        if (CFTrace.detailed()) CFTrace.event("RANGED_TICK_END", "unit", unit.getEntityId(),
                "selected", CFTrace.id(unit.traceTarget()),
                "navGoal", CFTrace.xyz(unit.getNavigator().getGoalPosition()),
                "navState", unit.getNavigator().getState().name(),
                "pathAfter", CFTrace.xyz(unit.getNavigator().getPathPosition()));
    
        } finally {
            CFDuelPerf.exitMethod("CFTraceRangedGoal.tick", _cfPerfBegin);
        }
    }
    @Override public void end() {
        if (CFTrace.detailed()) CFTrace.event("RANGED_END", "unit", unit.getEntityId(), "target", CFTrace.id(unit.traceTarget()));
        super.end();
    }
}
