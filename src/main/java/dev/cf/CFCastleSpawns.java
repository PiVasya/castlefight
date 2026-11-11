package dev.cf;

import net.minestom.server.coordinate.Pos;
import net.minestom.server.instance.Instance;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Map-independent staging grid near each team's castle, for diagnostic spawns only. */
final class CFCastleSpawns {
    private CFCastleSpawns() { }

    /** Columns to load/check; all coordinates are derived from this map's castles. */
    static List<Pos> candidates(Pos own, Pos enemy) {
        if (own == null || enemy == null) return List.of();
        double dx = enemy.x() - own.x(), dz = enemy.z() - own.z();
        double distance = Math.hypot(dx, dz);
        if (!Double.isFinite(distance) || distance < 0.001) return List.of();
        double ux = dx / distance, uz = dz / distance;
        double forwardLimit = Math.max(0.75, Math.min(12.0, (distance - 1.0) / 2.0));
        List<Pos> projected = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        for (int slot = 0; slot < 144; slot++) {
            double lateral = (slot % 12 - 5.5) * 0.95;
            double forward = Math.min(forwardLimit, 2.0 + (slot / 12) * 0.90);
            int x = (int) Math.floor(own.x() + ux * forward - uz * lateral);
            int z = (int) Math.floor(own.z() + uz * forward + ux * lateral);
            long key = ((long) x << 32) ^ (z & 0xffffffffL);
            if (seen.add(key)) projected.add(new Pos(x + .5, own.y(), z + .5));
        }
        // If the staging grid is obstructed, try the castle marker itself.
        int cx = own.blockX(), cz = own.blockZ();
        long key = ((long) cx << 32) ^ (cz & 0xffffffffL);
        if (seen.add(key)) projected.add(new Pos(cx + .5, own.y(), cz + .5));
        return List.copyOf(projected);
    }

    /** Reject walls, holes and unloaded chunks; do not assume any particular arena height. */
    static List<Pos> safe(Instance world, List<Pos> projected) {
        List<Pos> result = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        for (Pos p : projected) {
            Pos at = MapSurface.find(world, p.blockX(), p.blockZ(), p.y(), 3);
            if (at == null) continue;
            long key = ((long) at.blockX() << 32) ^ (at.blockZ() & 0xffffffffL);
            if (seen.add(key)) result.add(at);
        }
        return List.copyOf(result);
    }
}
