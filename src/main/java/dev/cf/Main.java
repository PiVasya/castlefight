package dev.cf;

import net.kyori.adventure.text.Component;
import net.minestom.server.MinecraftServer;
import net.minestom.server.command.builder.Command;
import net.minestom.server.command.builder.arguments.ArgumentType;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.entity.Player;
import net.minestom.server.entity.EntityProjectile;
import net.minestom.server.event.entity.projectile.ProjectileCollideWithEntityEvent;
import net.minestom.server.event.entity.projectile.ProjectileCollideWithBlockEvent;
import net.minestom.server.entity.damage.Damage;
import net.minestom.server.entity.damage.DamageType;
import net.minestom.server.event.entity.EntityAttackEvent;
import net.minestom.server.event.entity.EntityDamageEvent;
import net.minestom.server.event.entity.EntityShootEvent;
import net.minestom.server.event.entity.EntityDeathEvent;
import net.minestom.server.event.player.AsyncPlayerConfigurationEvent;
import net.minestom.server.event.player.PlayerSpawnEvent;
import net.minestom.server.event.server.ServerTickMonitorEvent;
import net.minestom.server.instance.InstanceContainer;
import net.minestom.server.instance.LightingChunk;
import net.minestom.server.instance.block.Block;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

public final class Main {
    private final MinecraftServer server;
    private final InstanceContainer world;
    // The launcher must provide a spawn from the selected map's metadata.
    private static final Pos MAP_SPAWN = new Pos(
            requiredCoordinate("cf.spawn.x"),
            requiredCoordinate("cf.spawn.y"),
            requiredCoordinate("cf.spawn.z"));

    private static double requiredCoordinate(String key) {
        String value = System.getProperty(key);
        if (value == null || value.isBlank())
            throw new IllegalStateException("Missing map coordinate " + key + "; launch via dist/CastleFight/start.sh");
        double coordinate = Double.parseDouble(value);
        if (!Double.isFinite(coordinate)) throw new IllegalStateException("Invalid map coordinate: " + key);
        return coordinate;
    }
    private final Set<CFUnit> units = ConcurrentHashMap.newKeySet();
    private final Set<String> ops = ConcurrentHashMap.newKeySet();
    // Incremented when units are cleared: cancels queued spawn batches.
    private volatile long spawnEpoch = 0;
    private static final int SPAWN_PER_TICK = 32;
    private boolean healthBarsEnabled = true;
    private final CFDuelSuite duelSuite;
    private final CFContentRegistry combatContent;
    private final CFGameArena laneTool;
    private boolean manualDuel;
    // Reserve the sole profiling session before any asynchronous chunk loading.
    private CFDuelSuite activeDuel;
    private CFDuelSuite lastStartedDuel;
    private boolean duelReserved;
    private long reservationSerial;
    private long reservationId;
    // Totals since server startup: useful for diagnosing arrow misses.
    private final java.util.concurrent.atomic.AtomicLong arrowShots =
            new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong arrowHits =
            new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong arrowBlocks =
            new java.util.concurrent.atomic.AtomicLong();
    private Pos red;
    private Pos blue;

    private Main() {
        server = MinecraftServer.init();
        String configuredMap = System.getProperty("cf.map.path");
        if (configuredMap == null || configuredMap.isBlank())
            throw new IllegalStateException("Select a map with dist/CastleFight/start.sh <map-id>");
        Path mapPath = Path.of(configuredMap);
        if (!Files.isDirectory(mapPath.resolve("dimensions/minecraft/overworld/region"))) {
            throw new IllegalStateException("Castle map not found: " + mapPath.toAbsolutePath());
        }
        CFMapFormat.read(mapPath); // Отказ от запуска карты без schema=3.0.
        CFCarpetZones carpets = new CFCarpetZones();
        CFPathBeacons beacons = new CFPathBeacons();
        world = MinecraftServer.getInstanceManager().createInstanceContainer();
        world.setChunkSupplier(LightingChunk::new);
        world.setChunkLoader(new CFReadOnlyMapLoader(mapPath, carpets, beacons));
        world.setTime(6000);
        try { combatContent=new CFContentRegistry(Path.of("content")); }
        catch (IOException e) { throw new IllegalStateException("Invalid combat content",e); }
        duelSuite = new CFDuelSuite(world, units, this::removeAllUnits, combatContent,
                () -> getCastle("red"), () -> getCastle("blue"));
        // Real Minestom tick-duration samples: active only during duel benchmarks.
        MinecraftServer.getGlobalEventHandler().addListener(ServerTickMonitorEvent.class, event -> {
            if (CFDuelPerf.on()) CFDuelPerf.tick(
                    event.getTickMonitor().getTickTime(),
                    event.getTickMonitor().getAcquisitionTime(), units.size());
            CFMegaPerf.serverTick(event.getTickMonitor().getTickTime(), units.size());
            CFSoakTest.tickMetric(event.getTickMonitor().getTickTime(),
                    event.getTickMonitor().getAcquisitionTime(), units.size());
        });
        loadData();
        laneTool = new CFGameArena(world, units, () -> activeSuite() != null,
                () -> healthBarsEnabled, mapPath, carpets, beacons);
        MinecraftServer.getGlobalEventHandler().addListener(ServerTickMonitorEvent.class,
                event -> CFNightControl.poll(laneTool));
        // Map-scoped castle markers are authoritative; do not inherit another map's defaults.
        blue=laneTool.castle("blue");
        red=laneTool.castle("red");

        MinecraftServer.getGlobalEventHandler()
                .addListener(AsyncPlayerConfigurationEvent.class, event -> {
                    event.setSpawningInstance(world);
                    event.getPlayer().setRespawnPoint(MAP_SPAWN);
                });
        MinecraftServer.getGlobalEventHandler()
                .addListener(PlayerSpawnEvent.class, event -> {
                    Player player = event.getPlayer();
                    if (ops.contains(player.getUsername().toLowerCase(Locale.ROOT))) {
                        player.setPermissionLevel(4);
                    }
                });

        // Minestom's MeleeAttackGoal emits this event but does not apply damage.
        MinecraftServer.getGlobalEventHandler()
                .addListener(EntityAttackEvent.class, event -> {
                    if (event.getEntity() instanceof CFUnit attacker)
                        CFMeleeCombat.attack(attacker, event.getTarget());
                });
        // Observe damage-event cancellation without changing the damage pipeline.
        MinecraftServer.getGlobalEventHandler()
                .addListener(EntityDamageEvent.class, event -> {
                    CFHitAudit.damageEvent(event);
                    CFCombatAudit.damageEvent(event);
                });
        MinecraftServer.getGlobalEventHandler()
                .addListener(EntityDeathEvent.class, event -> {
                    if (event.getEntity() instanceof CFUnit unit) {
                        CFCombatAudit.death(unit);
                        CFTrace.event("UNIT_DIED", "unit", unit.getEntityId(),
                                "team", unit.team(), "kind", unit.kind(),
                                "position", CFTrace.xyz(unit.getPosition()),
                                "health", unit.getHealth(), "removed", unit.isRemoved(),
                                "nano", System.nanoTime());
                        units.remove(unit);
                        if(unit.isTowerUnit() && CFArenaAbilities.active()!=null)
                            CFArenaAbilities.active().onDeath(unit);
                        System.out.println("[CF] " + unit.team() + " " + unit.kind() + " died");
                    }
                });

        MinecraftServer.getGlobalEventHandler()
                .addListener(EntityShootEvent.class, event -> {
                    if (event.getEntity() instanceof CFUnit shooter
                            && shooter.behaviorId().equals("ranged_hold")) {
                        arrowShots.incrementAndGet();
                        CFHitAudit.aiShoot(shooter);
                        CFTrace.event("SHOOT_EVENT", "shooter", shooter.getEntityId(),
                                "target", CFTrace.id(shooter.getTarget()),
                                "pos", CFTrace.xyz(shooter.getPosition()));
                    }
                });

        // Arrow collisions: damage enemy CF units only. Friends and players are ignored.
        MinecraftServer.getGlobalEventHandler()
                .addListener(ProjectileCollideWithEntityEvent.class, event -> {
                    if (!(event.getEntity() instanceof EntityProjectile arrow)
                            || !(arrow.getShooter() instanceof CFUnit shooter)) return;
                    CFTrace.event("ARROW_ENTITY_CONTACT", "arrow", arrow.getEntityId(),
                            "shooter", shooter.getEntityId(),
                            "target", CFTrace.id(event.getTarget()),
                            "targetType", event.getTarget().getEntityType().toString(),
                            "arrowPos", CFTrace.xyz(arrow.getPosition()));
                    if (!(event.getTarget() instanceof CFUnit target)
                            || target.team().equals(shooter.team())
                            || target.isDead() || target.isRemoved()) {
                        event.setCancelled(true);
                        return;
                    }
                    float hpBefore = target.getHealth();
                    boolean applied = target.damage(new CFTypedDamage(shooter,arrow,shooter.normalHit()));
                    CFTrace.event("ARROW_DAMAGE", "arrow", arrow.getEntityId(),
                            "shooter", shooter.getEntityId(), "target", target.getEntityId(),
                            "hpBefore", hpBefore, "hpAfter", target.getHealth(),
                            "applied", applied);
                    if (applied) arrowHits.incrementAndGet();
                    arrow.remove();
                });

        // Don't leave thousands of arrows stuck in terrain.
        MinecraftServer.getGlobalEventHandler()
                .addListener(ProjectileCollideWithBlockEvent.class, event -> {
                    if (event.getEntity() instanceof EntityProjectile arrow
                            && arrow.getShooter() instanceof CFUnit) {
                        arrowBlocks.incrementAndGet();
                        CFTrace.event("ARROW_BLOCK", "arrow", arrow.getEntityId(),
                                "shooter", CFTrace.id(arrow.getShooter()),
                                "pos", CFTrace.xyz(arrow.getPosition()),
                                "velocity", CFTrace.vel(arrow.getVelocity()));
                        arrow.remove();
                    }
                });

        registerCommands();
        laneTool.start();
        Runtime.getRuntime().addShutdownHook(Thread.ofPlatform()
                .name("cf-soak-shutdown-flush").unstarted(CFSoakTest::shutdownActive));
        startConsole();
    }

    public static void main(String[] args) {
        System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", "debug");
        System.setProperty("org.slf4j.simpleLogger.showDateTime", "true");
        CFConsoleCapture.install();
        Main app = new Main();
        System.out.println("[CF] CF Engine v2.6.8.3 DAMAGE MATRIX / DARK+SNOW INT10 starting");
        app.server.start("0.0.0.0", 25565);
    }

    private void registerCommands() {
        Command cf = new Command("cf");
        cf.setDefaultExecutor((sender, context) ->
                sender.sendMessage("/cf routes status | /cf ai status | /cf kit | /cf tower catalog | " +
                        "/cf duel status | /cf clear | /cf status"));

        cf.addSyntax((sender, context) -> {
            sender.sendMessage("[CF] RED=" + red + " BLUE=" + blue);
            long alive = units.stream().filter(u -> !u.isDead() && !u.isRemoved()).count();
            sender.sendMessage("[CF] Living units: " + alive);
        }, ArgumentType.Literal("status"));

        var spawnTeam = ArgumentType.Word("spawn-team").from("red", "blue");
        var spawnCount = ArgumentType.Integer("count");
        var spawnKind = ArgumentType.Word("mob-kind").from(
                "grave_zombie","risen","vampire","hell_skeleton","banshee",
                "acolyte","mutated_zombie","risen_arsonist");

        // Manual diagnostic spawns use screenshot mobs only.
        cf.addSyntax((sender, context) -> {
            if (!hasBothCastles(sender)) return;
            int count = context.get(spawnCount);
            if (!validCount(sender, count)) return;
            spawnLater(sender, context.get(spawnTeam), "grave_zombie", count);
        }, ArgumentType.Literal("spawn"), spawnTeam, spawnCount);

        // /cf spawn blue risen 15
        cf.addSyntax((sender, context) -> {
            if (!hasBothCastles(sender)) return;
            int count = context.get(spawnCount);
            if (!validCount(sender, count)) return;
            spawnLater(sender, context.get(spawnTeam), context.get(spawnKind), count);
        }, ArgumentType.Literal("spawn"), spawnTeam, spawnKind, spawnCount);

        // /cf duel runs 16 sequential scenarios on the castle map, including four 500-mob stages.
        // /cf duel <number> remains the old manual zombie vs zombie mode.
        cf.addSyntax((sender, context) -> {
            sender.sendMessage(startSuite(duelSuite, duelSuite::start));
        }, ArgumentType.Literal("duel"));
        // Cheap, lossless movement + hit investigation. No multi-gigabyte per-AI-tick log.
        cf.addSyntax((sender, context) -> sender.sendMessage(startSuite(duelSuite, duelSuite::startMotion)),
                ArgumentType.Literal("duel"), ArgumentType.Literal("motion"));
        var motionSkeletons = ArgumentType.Integer("motion-skeletons");
        var motionZombies = ArgumentType.Integer("motion-zombies");
        cf.addSyntax((sender, context) ->
                        sender.sendMessage(startSuite(duelSuite, () -> duelSuite.startMotion(
                                context.get(motionSkeletons), context.get(motionZombies)))),
                ArgumentType.Literal("duel"), ArgumentType.Literal("motion"),
                motionSkeletons, motionZombies);
        // Backward-compatible alias: hits mode now also includes compact movement samples.
        cf.addSyntax((sender, context) -> sender.sendMessage(startSuite(duelSuite, duelSuite::startMotion)),
                ArgumentType.Literal("duel"), ArgumentType.Literal("hits"));
        var hitSkeletons = ArgumentType.Integer("hit-skeletons");
        var hitZombies = ArgumentType.Integer("hit-zombies");
        cf.addSyntax((sender, context) ->
                        sender.sendMessage(startSuite(duelSuite, () -> duelSuite.startMotion(
                                context.get(hitSkeletons), context.get(hitZombies)))),
                ArgumentType.Literal("duel"), ArgumentType.Literal("hits"),
                hitSkeletons, hitZombies);
        cf.addSyntax((sender, context) -> sender.sendMessage(duelStatus()),
                ArgumentType.Literal("duel"), ArgumentType.Literal("status"));
        cf.addSyntax((sender, context) -> sender.sendMessage(finishDuel()),
                ArgumentType.Literal("duel"), ArgumentType.Literal("finish"));
        var duelLane = ArgumentType.Integer("duel-lane");
        cf.addSyntax((sender, context) -> {
            if (!(sender instanceof Player player)) {
                sender.sendMessage("[CF] Only in-game players can teleport.");
                return;
            }
            Pos camera = activeSuite() == duelSuite && !manualDuel ? duelSuite.camera(context.get(duelLane)) : null;
            if (camera == null) {
                sender.sendMessage("[CF] Only a test active in the current wave can be viewed (1-16). /cf duel status");
                return;
            }
            (player.getInstance() == world ? player.teleport(camera)
                    : player.setInstance(world, camera)).exceptionally(error -> {
                sender.sendMessage("[CF] Could not move to the selected map.");
                error.printStackTrace();
                return null;
            });
            sender.sendMessage("[CF] View test " + context.get(duelLane) + "; /cf map to return.");
        }, ArgumentType.Literal("duel"), ArgumentType.Literal("goto"), duelLane);

        cf.addSyntax((sender, context) -> {
            if (!(sender instanceof Player player)) {
                sender.sendMessage("[CF] Only in-game players can return to the map.");
                return;
            }
            (player.getInstance() == world ? player.teleport(MAP_SPAWN)
                    : player.setInstance(world, MAP_SPAWN)).exceptionally(error -> {
                sender.sendMessage("[CF] Could not enter the castle map.");
                error.printStackTrace();
                return null;
            });
        }, ArgumentType.Literal("map"));

        var duelCount = ArgumentType.Integer("duel-count");
        cf.addSyntax((sender, context) -> {
            if (!hasBothCastles(sender)) return;
            int count = context.get(duelCount);
            if (!validCount(sender, count)) return;
            duelLater(sender, count);
        }, ArgumentType.Literal("duel"), duelCount);

        cf.addSyntax((sender, context) -> {
            synchronized (this) {
                if (activeSuite() != null) {
                    sender.sendMessage("[CF] Diagnostic duel in progress; cannot clear the tests.");
                    return;
                }
                removeAllUnits();
            }
            sender.sendMessage("[CF] Cleared all units.");
        }, ArgumentType.Literal("clear"));
        var hpbarMode = ArgumentType.Word("hpbar-mode").from("on", "off");
        cf.addSyntax((sender, context) -> {
            healthBarsEnabled = context.get(hpbarMode).equals("on");
            for (CFUnit unit : units) {
                if (!unit.isDead() && !unit.isRemoved()) {
                    unit.setHealthBarVisible(healthBarsEnabled);
                }
            }
            sender.sendMessage("[CF] Health bars " + (healthBarsEnabled ? "ON" : "OFF"));
        }, ArgumentType.Literal("hpbar"), hpbarMode);

        var traceDuration = ArgumentType.Integer("trace-seconds");
        cf.addSyntax((sender, context) -> sender.sendMessage(CFTrace.start(20)),
                ArgumentType.Literal("trace"), ArgumentType.Literal("start"));
        cf.addSyntax((sender, context) -> sender.sendMessage(CFTrace.start(context.get(traceDuration))),
                ArgumentType.Literal("trace"), ArgumentType.Literal("start"), traceDuration);
        cf.addSyntax((sender, context) -> sender.sendMessage(CFTrace.stop()),
                ArgumentType.Literal("trace"), ArgumentType.Literal("stop"));
        cf.addSyntax((sender, context) -> sender.sendMessage(CFTrace.status()),
                ArgumentType.Literal("trace"), ArgumentType.Literal("status"));

        cf.addSyntax((sender, context) -> {
            sender.sendMessage("[CF] Arrow stats since startup: fired=" + (arrowShots.get() + CFClientShot.shots())
                    + " enemyHits=" + (arrowHits.get() + CFClientShot.hits())
                    + " hitBlocks=" + arrowBlocks.get());
            sender.sendMessage("[CF] Other arrows missed, remain in flight, or expired.");
        }, ArgumentType.Literal("arrows"));

        laneTool.register(cf);
        MinecraftServer.getCommandManager().register(cf);

    }

    private Pos getCastle(String team) { return team.equals("red") ? red : blue; }

    private boolean hasBothCastles(net.minestom.server.command.CommandSender sender) {
        if (red != null && blue != null) return true;
        sender.sendMessage("[CF] Укажи координаты двух замков в карте cf/map.properties");
        return false;
    }

    private boolean validCount(net.minestom.server.command.CommandSender sender, int count) {
        if (count > 0) return true;
        sender.sendMessage("[CF] Count must be a positive integer.");
        return false;
    }

    // A reservation also covers asynchronous chunk loading before the suite becomes busy.
    private synchronized CFDuelSuite activeSuite() {
        if (activeDuel != null && !duelReserved && !activeDuel.isBusy()) activeDuel = null;
        return activeDuel;
    }

    private synchronized long reserveSuite(CFDuelSuite suite, boolean manual) {
        if (activeSuite() != null) return 0;
        activeDuel = suite;
        manualDuel = manual;
        duelReserved = true;
        reservationId = ++reservationSerial;
        return reservationId;
    }

    private synchronized void releaseReservation(CFDuelSuite suite, long id) {
        if (activeDuel == suite && duelReserved && reservationId == id) {
            activeDuel = null;
            duelReserved = false;
        }
    }

    private synchronized String beginReserved(CFDuelSuite suite, long id, java.util.function.Supplier<String> start) {
        if (activeDuel != suite || !duelReserved || reservationId != id) return "[CF] Duel preparation was cancelled.";
        try {
            return start.get();
        } finally {
            duelReserved = false;
            if (suite.isBusy()) lastStartedDuel = suite;
            else activeDuel = null;
        }
    }

    private synchronized String startSuite(CFDuelSuite suite, java.util.function.Supplier<String> start) {
        long id = reserveSuite(suite, false);
        if (id == 0) return "[CF] Benchmark is busy. /cf duel status";
        return beginReserved(suite, id, start);
    }

    private synchronized boolean isCurrentSuite(CFDuelSuite suite, long id) {
        return activeSuite() == suite && !duelReserved && reservationId == id;
    }

    private synchronized String duelStatus() {
        CFDuelSuite suite = activeSuite();
        if (suite == null) return (lastStartedDuel == null ? duelSuite : lastStartedDuel).status();
        if (duelReserved) return "[CF DUEL] Preparing route. /cf duel finish to cancel.";
        return suite.status();
    }

    private synchronized String finishDuel() {
        CFDuelSuite suite = activeSuite();
        if (suite == null) return "[CF] No duel running.";
        if (duelReserved) {
            spawnEpoch++;
            releaseReservation(suite, reservationId);
            return "[CF] Duel preparation cancelled.";
        }
        return suite.finishNow();
    }

    private void spawnLater(net.minestom.server.command.CommandSender sender,
                            String team, String kind, int count) {
        if (activeSuite() != null) {
            sender.sendMessage("[CF] Diagnostic duel in progress; do not spawn extra mobs.");
            return;
        }
        final long requestedEpoch = spawnEpoch;
        sender.sendMessage("[CF] Loading route, queued " + count + " " + kind + "(s)...");
        readyRoute().whenComplete((ignored, error) ->
                world.scheduler().scheduleNextTick(() -> {
                    if (requestedEpoch != spawnEpoch) return; // /cf clear happened
                    if (error != null) {
                        error.printStackTrace();
                        sender.sendMessage("[CF] Could not load route.");
                        return;
                    }
                    synchronized (this) {
                    if (requestedEpoch != spawnEpoch || activeSuite() != null) {
                        sender.sendMessage("[CF] A duel started while the route was loading; spawn cancelled.");
                        return;
                    }
                    List<Pos> positions = spawnPositions(team);
                    if (positions.isEmpty()) {
                        sender.sendMessage("[CF] No safe spawn positions near the " + team +
                                " castle. Check the map metadata and clear ground near the castle.");
                        return;
                    }
                    spawnTeam(team, kind, count, positions);
                    sender.sendMessage("[CF] Spawning " + count + " " + team
                            + " " + kind + "(s) in batches of " + SPAWN_PER_TICK + ".");
                    }
                }));
    }

    private void duelLater(net.minestom.server.command.CommandSender sender, int count) {
        final long reservation;
        final long requestedEpoch;
        synchronized (this) {
            reservation = reserveSuite(duelSuite, true);
            if (reservation == 0) {
                sender.sendMessage("[CF] Diagnostic duel in progress; cannot start a manual duel.");
                return;
            }
            removeAllUnits();
            requestedEpoch = spawnEpoch;
        }
        sender.sendMessage("[CF] Preparing " + count + " vs " + count + " grave zombies...");
        readyRoute().whenComplete((ignored, error) ->
                world.scheduler().scheduleNextTick(() -> {
                    if (requestedEpoch != spawnEpoch) {
                        releaseReservation(duelSuite, reservation);
                        return;
                    }
                    if (error != null) {
                        releaseReservation(duelSuite, reservation);
                        error.printStackTrace();
                        sender.sendMessage("[CF] Could not load route.");
                        return;
                    }
                    List<Pos> redPositions = spawnPositions("red");
                    List<Pos> bluePositions = spawnPositions("blue");
                    if (redPositions.isEmpty() || bluePositions.isEmpty()) {
                        releaseReservation(duelSuite, reservation);
                        sender.sendMessage("[CF] No safe spawn positions near " +
                                (redPositions.isEmpty() ? "red" : "blue") +
                                " castle. Prepare clear ground around the castle in the source map.");
                        return;
                    }
                    String report = beginReserved(duelSuite, reservation, () -> duelSuite.startManual(count));
                    sender.sendMessage(report);
                    if (!isCurrentSuite(duelSuite, reservation) || !duelSuite.isRunning()) return;
                    spawnTeam("red", "grave_zombie", count, redPositions);
                    spawnTeam("blue", "grave_zombie", count, bluePositions);
                    sender.sendMessage("[CF] Profiling " + count + " vs " + count
                            + " for 70 seconds. /cf duel status");
                }));
    }

    // The same map-derived staging grid is used by manual and automated duels.
    private List<Pos> spawnPositions(String team) {
        Pos own = getCastle(team);
        Pos enemy = getCastle(team.equals("red") ? "blue" : "red");
        return CFCastleSpawns.safe(world, CFCastleSpawns.candidates(own, enemy));
    }

    // Reuse the safe staging positions; batches avoid a single long server tick.
    private void spawnTeam(String team, String kind, int count, List<Pos> positions) {
        spawnBatch(team, kind, count, 0, spawnEpoch, positions);
    }

    private synchronized void spawnBatch(String team, String kind, int total, int from,
                            long epoch, List<Pos> positions) {
        if (epoch != spawnEpoch || red == null || blue == null) return;
        CFDuelSuite active = activeSuite();
        if (active != null && !manualDuel) return;
        Pos enemy = getCastle(team.equals("red") ? "blue" : "red");
        int end = (int) Math.min((long) total, (long) from + SPAWN_PER_TICK);
        for (int i = from; i < end; i++) {
            Pos spawn = positions.get(i % positions.size());
            CFContentRegistry.Mob type=combatContent.mob(kind);
            if(type==null) throw new IllegalArgumentException("Unknown screenshot mob: "+kind);
            CFUnit mob = new CFUnit(team, type, spawn, enemy);
            int manualToken = activeSuite() == duelSuite && manualDuel
                    ? duelSuite.tagManualSpawn(mob) : -1;
            CFTrace.event("UNIT_SPAWN", "unit", mob.getEntityId(),
                    "team", team, "kind", kind, "spawn", CFTrace.xyz(spawn),
                    "destination", CFTrace.xyz(enemy));
            mob.setHealthBarVisible(healthBarsEnabled);
            units.add(mob);
            mob.setInstance(world, spawn).whenComplete((ignored, failure) -> {
                if (failure == null) {
                    mob.markSpawnCompleted();
                    if (CFArenaAbilities.active()!=null) CFArenaAbilities.active().onUnitSpawn(mob);
                } else {
                    units.remove(mob);
                    world.scheduler().scheduleNextTick(() -> duelSuite.manualSpawnFailed(manualToken, mob, failure));
                    failure.printStackTrace();
                }
            });
        }
        if (end < total) {
            world.scheduler().scheduleNextTick(() ->
                    spawnBatch(team, kind, total, end, epoch, positions));
        }
    }

    private void removeAllUnits() {
        if(CFArenaAbilities.active()!=null)CFArenaAbilities.active().clearUnits();
        spawnEpoch++; // Also stops any unfinished spawn waves.
        for (CFUnit unit : units) {
            if (!unit.isRemoved()) unit.remove();
        }
        units.clear();
    }

    private CompletableFuture<Void> readyRoute() {
        Set<Long> seen = new HashSet<>();
        List<CompletableFuture<?>> futures = new ArrayList<>();
        int segments = Math.max(1, (int) Math.ceil(red.distance(blue) / 12));
        for (int i = 0; i <= segments; i++) {
            double fraction = i / (double) segments;
            int bx = (int) Math.floor(red.x() + (blue.x() - red.x()) * fraction);
            int bz = (int) Math.floor(red.z() + (blue.z() - red.z()) * fraction);
            int cx = Math.floorDiv(bx, 16);
            int cz = Math.floorDiv(bz, 16);
            for (int ox = -1; ox <= 1; ox++) for (int oz = -1; oz <= 1; oz++) {
                int x = cx + ox;
                int z = cz + oz;
                long key = ((long) x << 32) ^ (z & 0xffffffffL);
                if (seen.add(key)) futures.add(world.loadChunk(x, z));
            }
        }
        return CompletableFuture.allOf(futures.toArray(new CompletableFuture<?>[0]));
    }

    private void loadData() {
        try {
            Path opsFile = Path.of("ops.txt");
            if (Files.exists(opsFile)) {
                for (String line : Files.readAllLines(opsFile)) {
                    if (!line.isBlank()) ops.add(line.trim().toLowerCase(Locale.ROOT));
                }
            }
        } catch (IOException e) { throw new UncheckedIOException(e); }
    }

    private void startConsole() {
        Thread.ofVirtual().name("cf-console").start(() -> {
            System.out.println("[CF] Console: op <nick>, deop <nick>, soak start [rounds] [seconds], soak status, soak stop, stop");
            try (Scanner scanner = new Scanner(System.in)) {
                while (scanner.hasNextLine()) {
                    String[] parts = scanner.nextLine().trim().split("\\s+");
                    if (parts.length == 2 && (parts[0].equalsIgnoreCase("op") ||
                            parts[0].equalsIgnoreCase("deop"))) {
                        boolean grant = parts[0].equalsIgnoreCase("op");
                        String nick = parts[1];
                        if (grant) ops.add(nick.toLowerCase(Locale.ROOT));
                        else ops.remove(nick.toLowerCase(Locale.ROOT));
                        try { Files.write(Path.of("ops.txt"), ops.stream().sorted().toList()); }
                        catch (IOException ex) { ex.printStackTrace(); }
                        Player player = MinecraftServer.getConnectionManager().getOnlinePlayerByUsername(nick);
                        if (player != null) player.scheduler().scheduleNextTick(() ->
                                player.setPermissionLevel(grant ? 4 : 0));
                        System.out.println("[CF] " + (grant ? "OP: " : "DEOP: ") + nick);
                    } else if (parts.length>=2 && parts[0].equalsIgnoreCase("soak")) {
                        if(parts[1].equalsIgnoreCase("status") && parts.length==2) {
                            world.scheduler().scheduleNextTick(()->System.out.println(laneTool.consoleSoakStatus()));
                        } else if(parts[1].equalsIgnoreCase("stop") && parts.length==2) {
                            world.scheduler().scheduleNextTick(()->System.out.println(laneTool.consoleSoakStop()));
                        } else if(parts[1].equalsIgnoreCase("verify") && parts.length==2) {
                            world.scheduler().scheduleNextTick(()->System.out.println(laneTool.consoleSoakVerify()));
                        } else if(parts[1].equalsIgnoreCase("night") && parts.length<=4) {
                            try {
                                int rounds=parts.length>=3?Integer.parseInt(parts[2]):96;
                                int seconds=parts.length>=4?Integer.parseInt(parts[3]):300;
                                world.scheduler().scheduleNextTick(()->System.out.println(laneTool.consoleSoakNight(rounds,seconds)));
                            }catch(NumberFormatException bad){System.out.println("[CF NIGHT] Usage: soak night [rounds] [seconds]");}
                        } else if(parts[1].equalsIgnoreCase("stress") && parts.length<=4) {
                            try {
                                int rounds=parts.length>=3?Integer.parseInt(parts[2]):8;
                                int seconds=parts.length>=4?Integer.parseInt(parts[3]):180;
                                world.scheduler().scheduleNextTick(()->System.out.println(laneTool.consoleSoakStress(rounds,seconds)));
                            }catch(NumberFormatException bad){System.out.println("[CF STRESS] Usage: soak stress [rounds] [seconds]");}
                        } else if(parts[1].equalsIgnoreCase("start") && parts.length<=4) {
                            try {
                                int rounds=parts.length>=3?Integer.parseInt(parts[2]):128;
                                int seconds=parts.length>=4?Integer.parseInt(parts[3]):120;
                                world.scheduler().scheduleNextTick(()->System.out.println(
                                        laneTool.consoleSoakStart(rounds,seconds)));
                            }catch(NumberFormatException bad){
                                System.out.println("[CF SOAK] Usage: soak start [rounds] [seconds]");
                            }
                        } else System.out.println("[CF SOAK] soak night [rounds] [seconds] | soak stress [rounds] [seconds] | soak status | soak stop");
                    } else if (parts.length == 1 && parts[0].equalsIgnoreCase("stop")) {
                        CFSoakTest.shutdownActive();
                        MinecraftServer.stopCleanly();
                        return;
                    } else System.out.println("[CF] op <nick> | deop <nick> | soak night [rounds] [seconds] | soak stress [rounds] [seconds] | soak status | soak stop | stop");
                }
            }
        });
    }
}
