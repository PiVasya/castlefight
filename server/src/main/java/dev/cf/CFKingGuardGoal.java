package dev.cf;

import net.minestom.server.coordinate.Pos;
import net.minestom.server.entity.ai.GoalSelector;

/** Simplest possible king AI: return to the centre of his 3x3 throne. */
final class CFKingGuardGoal extends GoalSelector {
    private final CFUnit king;
    private final Pos throne;
    private long lastPathRequest;

    CFKingGuardGoal(CFUnit king,Pos throne) {
        super(king);
        this.king=king;
        this.throne=throne;
    }

    @Override public boolean shouldStart() {
        return king.combatAlive() && king.getPosition().distanceSquared(throne)>.36;
    }
    @Override public void start() { king.getNavigator().setPathTo(throne); }
    @Override public void tick(long time) {
        if(!king.combatAlive())return;
        if(time-lastPathRequest>1500) {
            lastPathRequest=time;
            king.getNavigator().setPathTo(throne);
        }
    }
    @Override public boolean shouldEnd() {
        return !king.combatAlive() || king.getPosition().distanceSquared(throne)<=.36;
    }
    @Override public void end() { king.getNavigator().reset(); }
}
