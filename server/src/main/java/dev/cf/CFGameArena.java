package dev.cf;

import net.minestom.server.command.builder.Command;
import net.minestom.server.command.builder.arguments.ArgumentType;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.instance.InstanceContainer;

import java.nio.file.Path;
import java.util.Set;
import java.util.function.BooleanSupplier;

/** Gameplay-only arena: static map routes, match towers, and battle diagnostics. */
final class CFGameArena {
    private final CFThreeLanes routes;
    private final CFTowerSystem towers;

    CFGameArena(InstanceContainer world, Set<CFUnit> units,
                BooleanSupplier duel, BooleanSupplier healthBars,
                Path mapRoot, CFCarpetZones carpets, CFPathBeacons beacons, CFCastleMarkers castles) {
        routes = new CFThreeLanes(world, mapRoot, carpets, beacons, castles);
        towers = new CFTowerSystem(world, units, duel, healthBars, carpets, routes);
    }

    void register(Command cf) {
        cf.addSyntax((sender, ctx) -> CFPlayerNotice.send(sender,routes.status()),
                ArgumentType.Literal("routes"), ArgumentType.Literal("status"));
        cf.addSyntax((sender, ctx) -> CFPlayerNotice.send(sender,CFSkeletonMindLog.status()),
                ArgumentType.Literal("ai"), ArgumentType.Literal("status"));
        towers.register(cf);
    }

    Pos castle(String team) { return routes.castle(team); }
    java.util.concurrent.CompletableFuture<Void> routesReadyFuture() { return routes.readyFuture(); }
    java.util.Map<CFPlacementRules.Cell, CFMarkerPalette.Mark> carpetSnapshot() {
        return routes.carpetSnapshot();
    }
    java.util.Map<Integer, CFLanePath> pathSnapshot() { return routes.pathSnapshot(); }
    void start() { towers.start(); }
    CFArenaAbilities abilities() { return towers.abilities(); }
    String consoleSoakStart(int rounds,int seconds) { return towers.consoleSoakStart(rounds,seconds); }
    String consoleSoakVerify() { return towers.consoleSoakVerify(); }
    String consoleSoakNight(int rounds,int seconds) { return towers.consoleSoakNight(rounds,seconds); }
    String consoleSoakBurn(int rounds,int seconds) { return towers.consoleSoakBurn(rounds,seconds); }
    String consoleSoakStress(int rounds,int seconds) { return towers.consoleSoakStress(rounds,seconds); }
    String consoleSoakStatus() { return towers.consoleSoakStatus(); }
    String consoleSoakStop() { return towers.consoleSoakStop(); }
}
