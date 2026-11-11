package dev.cf;

import net.minestom.server.collision.BoundingBox;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.instance.block.Block;
import java.nio.file.Path;
import java.util.AbstractCollection;
import java.util.Iterator;

/** Regression: no Castle Fight route/spawn gate may be keyed to mob dimensions. */
public final class CF270NoSizeSelfTest {
    private static int assertions;
    private static void ok(boolean value, String reason) {
        assertions++;
        if (!value) throw new AssertionError(reason);
    }
    public static void main(String[] args) {
        var routes = new CFThreeLanes(null, Path.of("cf270-test-map-unique"), null, () -> false);
        var trap = new AbstractCollection<CFContentRegistry.Mob>() {
            @Override public Iterator<CFContentRegistry.Mob> iterator() {
                throw new AssertionError("Route validator iterated mob sizes!");
            }
            @Override public int size() {
                throw new AssertionError("Route validator read mob collection!");
            }
        };
        routes.profile(trap);
        ok(routes.envelope() == CFThreeLanes.terrainPoint(), "routes use one terrain probe");
        ok(routes.envelope().width() < .1 && routes.envelope().height() < .1,
                "route point probe is not a mob hitbox");

        Block.Getter flat = (x, y, z, condition) -> y < 0 ? Block.STONE : Block.AIR;
        Pos start = new Pos(.5, 0, .5), finish = new Pos(8.5, 0, .5);
        var points = CFLaneSearch.find(flat, CFThreeLanes.terrainPoint(), start, finish,
                CFLaneSearch.tube(start, finish, 2));
        ok(!points.isEmpty(), "point-only route succeeds on dry ground");
        ok(points.getLast().distanceSquared(finish) < .0025, "point route connects both ends");

        // A ceiling within a tall mob's height must not reject a point on dry ground.
        Block.Getter lowCeiling = (x, y, z, condition) ->
                (y < 0 || y == 1) ? Block.STONE : Block.AIR;
        ok(CFLaneSearch.clear(lowCeiling, CFThreeLanes.terrainPoint(), start),
                "small terrain probe fits under low ceiling");
        ok(!CFLaneSearch.clear(lowCeiling, new BoundingBox(1.0, 2.4, 1.0), start),
                "size-dependent test would have rejected the same ground point");
        System.out.println("[CF270 NO MOB SIZE] PASS " + assertions + " assertions; routes ignore mob dimensions");
    }
}
