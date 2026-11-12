package dev.cf;

/** Minimum one empty block around every 3x3 footprint, even diagonally.
 * Tower centers differ by at least four in X or at least four in Z.
 * The other tower's team, lane and elevation do not matter.
 */
final class CFTowerSpacing {
    static boolean allows(int x, int z, int otherX, int otherZ) {
        return Math.abs((long)x - otherX) >= 4 || Math.abs((long)z - otherZ) >= 4;
    }
    private CFTowerSpacing() { }
}
