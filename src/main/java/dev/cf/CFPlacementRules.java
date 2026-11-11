package dev.cf;

/** Pure footprint checks, deliberately independent of Minecraft and world storage. */
final class CFPlacementRules {
    record Cell(int x, int y, int z) { }
    @FunctionalInterface interface Lookup { String teamAt(int x, int y, int z); }
    static boolean allowed(Lookup lookup, String team, int x, int y, int z) {
        if (!"red".equals(team) && !"blue".equals(team)) return false;
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++)
            if (!team.equals(lookup.teamAt(x + dx, y, z + dz))) return false;
        return true;
    }
    private CFPlacementRules() { }
}
