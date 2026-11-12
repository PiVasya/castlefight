package dev.cf;

import net.minestom.server.coordinate.Pos;
import net.minestom.server.entity.ai.GoalSelector;
import net.minestom.server.entity.pathfinding.PPath;

/**
 * One authoritative goal for the castle king: guard the ceramic 3x3, fight
 * enemies reachable from that square, and return to its centre after combat.
 * A separate high-priority melee goal previously starved the return goal.
 */
final class CFKingGuardGoal extends GoalSelector {
    private static final double HOME_RADIUS_SQ = 0.25; // centre tolerance: 0.5 block
    private static final long PATH_RETRY_MS = 1500;
    private enum Mode { IDLE, FIGHT, RETURN }

    private final CFUnit king;
    private final Pos throne;
    private final double meleeRangeSq;
    private Mode mode = Mode.IDLE;
    private CFUnit chased;
    private Pos requestedGoal;
    private long lastPathRequest;
    private long lastHit;

    CFKingGuardGoal(CFUnit king, Pos throne) {
        super(king);
        this.king = king;
        this.throne = throne;
        this.meleeRangeSq = king.attackRange() * king.attackRange();
    }

    /** Is an enemy physically reachable while the king's feet remain on the 3x3? */
    static boolean reachableFromThrone(Pos throne, Pos enemy, double reach) {
        if (throne == null || enemy == null || reach <= 0) return false;
        double dx = Math.max(0, Math.abs(enemy.x() - throne.x()) - 1);
        double dz = Math.max(0, Math.abs(enemy.z() - throne.z()) - 1);
        double dy = Math.abs(enemy.y() - throne.y());
        return dx * dx + dz * dz + dy * dy <= reach * reach;
    }

    private Pos boundedGoal(Pos enemy) {
        // Quantise to ceramic block centres: moving enemies must not trigger
        // a full path recalculation for every tiny position change.
        double x = Math.floor(enemy.x()) + .5;
        double z = Math.floor(enemy.z()) + .5;
        return new Pos(Math.max(throne.x() - 1, Math.min(throne.x() + 1, x)),
                throne.y(),
                Math.max(throne.z() - 1, Math.min(throne.z() + 1, z)));
    }

    private boolean home() {
        Pos current = king.getPosition();
        double dx = current.x() - throne.x(), dz = current.z() - throne.z();
        return dx * dx + dz * dz <= HOME_RADIUS_SQ && Math.abs(current.y() - throne.y()) < .55;
    }

    @Override public boolean shouldStart() { return king.combatAlive(); }
    @Override public boolean shouldEnd() { return !king.combatAlive(); }

    @Override public void start() { mode = Mode.IDLE; requestedGoal = null; chased = null; }

    @Override public void tick(long time) {
        if (!king.combatAlive()) return;
        CFUnit enemy = king.targeting().resolve();
        // The target graph may retain a melee selection for its review period.
        // The throne guard must not wait for that period when an enemy leaves,
        // hides behind a wall or becomes impossible to hit from the 3x3.
        if (enemy != null && (!king.targeting().enemy(king, enemy)
                || !king.targeting().inRange(king, enemy)
                || !reachableFromThrone(throne, enemy.getPosition(), king.attackRange())
                || !king.hasLineOfSight(enemy))) {
            king.setTarget(null);
            enemy = null;
        }

        if (enemy == null) {
            chased = null;
            if (home()) {
                if (mode != Mode.IDLE) {
                    king.getNavigator().reset();
                    changeMode(Mode.IDLE);
                }
            } else {
                if (mode != Mode.RETURN) {
                    king.getNavigator().reset();
                    changeMode(Mode.RETURN);
                }
                navigate(throne, time);
            }
            return;
        }

        king.lookAt(enemy);
        if (enemy != chased) {
            chased = enemy;
            requestedGoal = null;
        }
        if (king.getDistanceSquared(enemy) <= meleeRangeSq) {
            if (mode != Mode.FIGHT || king.getNavigator().getGoalPosition() != null)
                king.getNavigator().reset();
            changeMode(Mode.FIGHT);
            if ((lastHit == 0 || time - lastHit >= king.attackCooldownMs())
                    && king.targeting().peek() == enemy
                    && king.targeting().enemy(king, enemy)) {
                lastHit = time;
                king.attack(enemy, true); // Existing EntityAttackEvent -> CFMeleeCombat.
            }
        } else {
            if (mode != Mode.FIGHT) changeMode(Mode.FIGHT);
            navigate(boundedGoal(enemy.getPosition()), time);
        }
    }

    private void navigate(Pos destination, long time) {
        var navigator = king.getNavigator();
        boolean changed = requestedGoal == null || !requestedGoal.samePoint(destination);
        boolean invalid = navigator.getState() == PPath.State.INVALID;
        if (!changed && !invalid && navigator.getGoalPosition() != null) return;
        if (!changed && time - lastPathRequest < PATH_RETRY_MS) return;
        requestedGoal = destination;
        lastPathRequest = time;
        navigator.setPathTo(destination);
    }

    /** Regression/diagnostic state, without granting the client authority over AI. */
    boolean returning() { return mode == Mode.RETURN; }
    boolean defending() { return mode == Mode.FIGHT; }
    Pos requestedDestination() { return requestedGoal; }

    private void changeMode(Mode next) {
        if (mode == next) return;
        Mode old = mode;
        mode = next;
        requestedGoal = null;
        if (next == Mode.RETURN)
            System.out.println("[КФ КОРОЛЬ] " + king.team() + ": возвращается к трону " + throne);
        else if (next == Mode.IDLE && old == Mode.RETURN)
            System.out.println("[КФ КОРОЛЬ] " + king.team() + ": вернулся на трон.");
        CFTrace.event("KING_GUARD_STATE", "king", king.getEntityId(),
                "team", king.team(), "from", old.name(), "to", next.name(),
                "pos", CFTrace.xyz(king.getPosition()), "throne", CFTrace.xyz(throne));
    }

    @Override public void end() {
        king.getNavigator().reset();
        chased = null;
        requestedGoal = null;
        mode = Mode.IDLE;
    }
}
