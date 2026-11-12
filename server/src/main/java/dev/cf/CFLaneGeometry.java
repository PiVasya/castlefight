package dev.cf;

import net.minestom.server.coordinate.Pos;
import java.util.List;

/** Pure, constant-time-per-segment corridor math. No chunk reads or A*. */
final class CFLaneGeometry {
    private CFLaneGeometry() { }

    record Nearest(int segment, double t, double distanceSq) { }

    static Nearest nearest(List<Pos> nodes, Pos p, int firstSegment) {
        if (nodes.size() < 2) return new Nearest(0, 0, Double.POSITIVE_INFINITY);
        double best = Double.POSITIVE_INFINITY;
        int chosen = 0;
        double chosenT = 0;
        for (int i = Math.max(0, firstSegment); i < nodes.size() - 1; i++) {
            Pos a = nodes.get(i), b = nodes.get(i + 1);
            double dx = b.x() - a.x(), dz = b.z() - a.z();
            double lengthSq = dx * dx + dz * dz;
            double t = lengthSq < 1.0e-6 ? 0
                    : Math.max(0, Math.min(1, ((p.x() - a.x()) * dx + (p.z() - a.z()) * dz) / lengthSq));
            double x = a.x() + dx * t, z = a.z() + dz * t;
            // 2D projection only: ramps and slabs are handled by local navigator.
            double d2 = (p.x() - x) * (p.x() - x) + (p.z() - z) * (p.z() - z);
            if (d2 < best - 1.0e-8 || (Math.abs(d2 - best) < 1.0e-8 && i > chosen)) {
                best = d2;
                chosen = i;
                chosenT = t;
            }
        }
        return new Nearest(chosen, chosenT, best);
    }

    static int startCheckpoint(List<Pos> nodes, Pos spawn) {
        if (nodes.isEmpty()) return 0;
        if (nodes.size() == 1) return 0;
        return Math.min(nodes.size() - 1, nearest(nodes, spawn, 0).segment() + 1);
    }

    static boolean reached(Pos position, Pos goal, double radius) {
        double x = position.x() - goal.x(), z = position.z() - goal.z();
        return x * x + z * z <= radius * radius && Math.abs(position.y() - goal.y()) <= 2.25;
    }
}
