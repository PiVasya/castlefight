package dev.cf;

import net.minestom.server.coordinate.Pos;

import java.util.HashSet;
import java.util.Set;

/** Pure geometry checks; no world and no installed arena required. */
public final class CFCastleSpawnsSelfTest {
    private static int checks;
    private static void check(boolean condition, String detail) {
        checks++;
        if (!condition) throw new AssertionError(detail);
    }

    public static void main(String[] args) {
        Pos red = new Pos(142.5, 72, -93.5);
        Pos blue = new Pos(-54.5, 68, 201.5);
        for (Pos[] castles : new Pos[][] {{red, blue}, {blue, red},
                {new Pos(-101.5, 8, -9.5), new Pos(90.5, 8, -9.5)},
                {new Pos(41.5, 64, 208.5), new Pos(41.5, 64, -79.5)}}) {
            Pos own = castles[0], enemy = castles[1];
            var projected = CFCastleSpawns.candidates(own, enemy);
            check(!projected.isEmpty(), "castle spawn candidates present");
            check(projected.size() <= 145, "bounded geometry");
            Set<String> seen = new HashSet<>();
            for (Pos pos : projected) {
                check(seen.add(pos.blockX() + "," + pos.blockZ()), "unique column");
                check(pos.y() == own.y(), "use this castle's height");
                check(pos.distance(own) < 22.0, "start near own castle");
            }
        }
        check(CFCastleSpawns.candidates(red, red).isEmpty(), "reject same castle");
        check(CFCastleSpawns.candidates(null, red).isEmpty(), "reject missing castle");
        check(CFCastleSpawns.candidates(red, null).isEmpty(), "reject missing enemy castle");
        System.out.println("[CF CASTLE SPAWNS] PASS " + checks +
                " geometry checks; no hardcoded map, dynamic castles, no CSV");
    }
}
