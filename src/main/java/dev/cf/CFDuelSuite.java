package dev.cf;

import net.minestom.server.coordinate.Pos;
import net.minestom.server.instance.InstanceContainer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/** Castle-based diagnostic duels on the selected map, up to 500 active CFUnits. */
final class CFDuelSuite {
    private static final int SECONDS_PER_WAVE = 70;
    private static final int BATCH_PER_TICK = 32;
    private static final int TRACE_TIMEOUT = 1800;
    private static final String RESULT_HEADER =
            "wave,scenario,title,red_zombies,red_skeletons,blue_zombies,blue_skeletons," +
            "requested_red,requested_blue,spawned_red,spawned_blue,not_spawned_red,not_spawned_blue," +
            "red_alive,blue_alive,red_hp,blue_hp,red_dead,blue_dead,reason\n";

    private record Case(String id, String title, int redZ, int redS,
                        int blueZ, int blueS) {
        int redTotal() { return redZ + redS; }
        int blueTotal() { return blueZ + blueS; }
        int total() { return redTotal() + blueTotal(); }
    }
    private record Wave(String id, List<Case> cases) {
        int units() { return cases.stream().mapToInt(Case::total).sum(); }
    }
    private record SpawnSpec(Case test, String team, String kind, Pos position,
                             Pos destination, List<CFUnit> owners) {}
    private record CastleSpawns(List<Pos> red, List<Pos> blue, Pos redCastle, Pos blueCastle) {}

    private static final List<Wave> WAVES = List.of(
            new Wave("W01_regression", List.of(
                    new Case("01_melee_1v1", "Zombie 1v1", 1,0,1,0),
                    new Case("02_archer_1v1", "Skeleton 1v1", 0,1,0,1),
                    new Case("03_archer_vs_zombie", "Archer vs zombie", 0,1,1,0),
                    new Case("04_zombie_vs_archer", "Zombie vs archer", 1,0,0,1),
                    new Case("05_archers_6v6", "Skeleton 6v6", 0,6,0,6),
                    new Case("06_mixed_5v5", "Mixed 5v5", 3,2,3,2),
                    new Case("07_archers_vs_ten", "6 archers vs 10 zombies", 0,6,10,0))),
            new Wave("W02_medium", List.of(
                    new Case("08_archers_50v50", "Skeleton 50v50", 0,50,0,50),
                    new Case("09_archers_vs_zombies_50", "50 archers vs 50 zombies", 0,50,50,0),
                    new Case("10_mixed_50v50", "Mixed 50v50", 25,25,25,25))),
            new Wave("W03_archers_vs_melee_200", List.of(
                    new Case("11_archers_vs_zombies_200", "100 archers vs 100 zombies", 0,100,100,0))),
            new Wave("W04_mixed_200", List.of(
                    new Case("12_mixed_200", "Mixed 100v100", 50,50,50,50))),
            new Wave("W05_melee_500", List.of(
                    new Case("13_zombies_500", "250 zombies vs 250 zombies", 250,0,250,0))),
            new Wave("W06_archers_500", List.of(
                    new Case("14_archers_500", "250 archers vs 250 archers", 0,250,0,250))),
            new Wave("W07_archers_vs_melee_500", List.of(
                    new Case("15_archers_vs_zombies_500", "250 archers vs 250 zombies", 0,250,250,0))),
            new Wave("W08_mixed_500", List.of(
                    new Case("16_mixed_500", "Mixed 250v250", 125,125,125,125)))
    );
    private static final List<Case> ALL_CASES = WAVES.stream().flatMap(w -> w.cases().stream()).toList();
    private static final List<Wave> SEQUENTIAL_WAVES = java.util.stream.IntStream.range(0, ALL_CASES.size())
            .mapToObj(i -> new Wave(String.format(Locale.ROOT, "W%02d", i + 1),
                    List.of(ALL_CASES.get(i)))).toList();

    private final InstanceContainer world;
    private final CFContentRegistry content;
    private final Set<CFUnit> gameUnits;
    private final Runnable clearExisting;
    private final Supplier<Pos> redCastle;
    private final Supplier<Pos> blueCastle;
    private final AtomicBoolean busy = new AtomicBoolean(false);
    private final AtomicInteger spawnErrors = new AtomicInteger();
    private final List<List<CFUnit>> participants = new ArrayList<>();
    private final List<CompletableFuture<?>> spawnFutures = new ArrayList<>();
    private final List<SpawnSpec> spawnPlan = new ArrayList<>();
    private final StringBuilder caseCsv = new StringBuilder(RESULT_HEADER);
    private final StringBuilder summary = new StringBuilder();

    private volatile String state = "IDLE";
    private volatile String zipPath = "none";
    private volatile String analysisZipPath = "none";
    private volatile String error = "none";
    private volatile long waveStartedNs;
    private volatile int waveIndex = -1;
    private volatile int finishedCases;
    private volatile boolean manualBattle;
    private volatile int serial;
    private volatile CFDuelPerf profiler;
    private volatile Path tracePath;
    private volatile Wave activeWave;
    private volatile boolean compactAudit;
    private CFMotionAudit.Report motionReport;
    private List<Wave> runWaves = SEQUENTIAL_WAVES;
    private CastleSpawns castleSpawns;
    private AtomicBoolean benchmarkGate;
    private List<Case> runCases = ALL_CASES;
    private CFHitAudit.Report hitReport;

    CFDuelSuite(InstanceContainer world, Set<CFUnit> gameUnits, Runnable clearExisting,
                CFContentRegistry content, Supplier<Pos> redCastle, Supplier<Pos> blueCastle) {
        this.content = content;
        this.world = world;
        this.gameUnits = gameUnits;
        this.clearExisting = clearExisting;
        this.redCastle = Objects.requireNonNull(redCastle);
        this.blueCastle = Objects.requireNonNull(blueCastle);
    }

    String start() { return startConfigured(false, SEQUENTIAL_WAVES); }
    String startHits() { return startConfigured(true, SEQUENTIAL_WAVES); }
    String startMotion() { return startConfigured(true, SEQUENTIAL_WAVES); }

    /** One 70s arena, no concurrent battles; /cf duel motion 20 10. */
    String startHits(int skeletons, int zombies) { return startMotion(skeletons, zombies); }
    String startMotion(int skeletons, int zombies) {
        if (skeletons < 1 || zombies < 1 || (long) skeletons + zombies > 500) {
            return "[CF] Need at least one skeleton and zombie; maximum 500 units together.";
        }
        Case c = new Case("hit_" + skeletons + "s_vs_" + zombies + "z",
                skeletons + " skeletons vs " + zombies + " zombies",
                0, skeletons, zombies, 0);
        return startConfigured(true, List.of(new Wave("W01_hit_custom", List.of(c))));
    }

    private String startConfigured(boolean compact, List<Wave> waves) {
        if (!busy.compareAndSet(false, true)) return "[CF] Benchmark is busy. /cf duel status";
        if (CFTrace.on()) {
            busy.set(false);
            return "[CF] Stop /cf trace before starting a duel.";
        }
        reset(false);
        compactAudit = compact;
        runWaves = waves;
        runCases = waves.stream().flatMap(w -> w.cases().stream()).toList();
        summary.append("Mode: ").append(compact ? "COMPACT_MOTION_HITS" : "FULL_AI")
                .append(" | scenarios=").append(runCases.size())
                .append(" | waves=").append(waves.size()).append("\n");
        state = "PREPARING";
        world.scheduler().scheduleNextTick(() -> {
            clearExisting.run();
            if (beginProfiling(TRACE_TIMEOUT)) prepareCastleSpawns(serial);
        });
        return "[CF] " + runCases.size() + " battle(s), " + waves.size() + " wave(s), " +
                "up to 500 units. " + (compact ? "MOTION + HIT AUDIT (small ZIP, no AI-tick log). "
                    : "Full AI trace (very large ZIP). ") +
                "70s per wave. /cf duel status | /cf duel finish";
    }

    private void reset(boolean manual) {
        manualBattle = manual;
        compactAudit = false;
        runWaves = SEQUENTIAL_WAVES;
        runCases = ALL_CASES;
        castleSpawns = null;
        hitReport = null;
        motionReport = null;
        waveIndex = -1;
        finishedCases = 0;
        waveStartedNs = 0L;
        spawnErrors.set(0);
        serial++;
        participants.clear();
        spawnFutures.clear();
        spawnPlan.clear();
        caseCsv.setLength(0);
        caseCsv.append(RESULT_HEADER);
        summary.setLength(0);
        summary.append("CASTLE FIGHT v1.5 MOTION + HIT AUDIT / STRESS BENCHMARK\n")
                .append("Started UTC: ").append(Instant.now()).append('\n')
                .append("JFR recording and per-arrow HP reconciliation included.\n")
                .append("Map: ").append(mapId()).append("; diagnostic units start near their own castles.\n")
                .append("Spawn points are shared if a castle has fewer safe cells than units.\n")
                .append("A balanced 500-unit test means 250 RED versus 250 BLUE.\n\n");
        zipPath = "none";
        analysisZipPath = "none";
        error = "none";
        tracePath = null;
        profiler = null;
        activeWave = null;
    }

    private boolean beginProfiling(int traceSeconds) {
        try {
            CFHitAudit.begin(manualBattle ? "manual" : (compactAudit ? "compact_motion_hits" : "full_ai"));
            CFMotionAudit.begin(manualBattle ? "manual" : (compactAudit ? "compact_motion_hits" : "full_ai"));
            profiler = CFDuelPerf.start();
            String started = compactAudit ? CFTrace.startCompact(traceSeconds) : CFTrace.start(traceSeconds);
            if (!CFTrace.on()) throw new IOException("Trace did not start: " + started);
            tracePath = CFTrace.latestPath();
            CFDuelPerf.setStage("setup");
            CFTrace.event("DUEL_SUITE_BEGIN", "version", "1.5", "mode",
                    manualBattle ? "manual" : (compactAudit ? "compact_motion_hits" : "full_ai"),
                    "waves", manualBattle ? 1 : runWaves.size(),
                    "cases", manualBattle ? 1 : runCases.size());
            return true;
        } catch (Exception ex) {
            CFHitAudit.discard();
            CFMotionAudit.discard();
            error = "Could not start profiler: " + ex;
            System.err.println("[CF13] " + error);
            if (CFTrace.on()) CFTrace.stop();
            CFDuelPerf p = profiler;
            if (p != null) Thread.ofVirtual().start(p::finish);
            state = "ERROR";
            busy.set(false);
            return false;
        }
    }

    /** Resolve castle positions from the selected map and validate nearby ground once per run. */
    private void prepareCastleSpawns(int token) {
        Pos redBase = redCastle.get(), blueBase = blueCastle.get();
        if (redBase == null || blueBase == null) {
            failArena("Selected map has no red/blue castle positions");
            return;
        }
        List<Pos> redCandidates = CFCastleSpawns.candidates(redBase, blueBase);
        List<Pos> blueCandidates = CFCastleSpawns.candidates(blueBase, redBase);
        if (redCandidates.isEmpty() || blueCandidates.isEmpty()) {
            failArena("Castle positions must be distinct to run diagnostic duels");
            return;
        }
        state = "LOADING_CHUNKS";
        List<CompletableFuture<?>> loads = new ArrayList<>();
        Set<Long> chunks = new HashSet<>();
        List<Pos> all = new ArrayList<>(redCandidates);
        all.addAll(blueCandidates);
        for (Pos pos : all) {
            int cx = Math.floorDiv(pos.blockX(), 16), cz = Math.floorDiv(pos.blockZ(), 16);
            for (int ox = -1; ox <= 1; ox++) for (int oz = -1; oz <= 1; oz++) {
                int x = cx + ox, z = cz + oz;
                long key = ((long) x << 32) ^ (z & 0xffffffffL);
                if (chunks.add(key)) loads.add(world.loadChunk(x, z));
            }
        }
        CompletableFuture.allOf(loads.toArray(new CompletableFuture<?>[0]))
                .whenComplete((unused, failure) -> world.scheduler().scheduleNextTick(() -> {
                    if (!validToken(token) || !state.equals("LOADING_CHUNKS")) return;
                    if (failure != null) {
                        failArena("Cannot load castle spawn chunks: " + failure);
                        return;
                    }
                    List<Pos> redSafe = CFCastleSpawns.safe(world, redCandidates);
                    List<Pos> blueSafe = CFCastleSpawns.safe(world, blueCandidates);
                    if (redSafe.isEmpty() || blueSafe.isEmpty()) {
                        failArena("No safe ground near " + (redSafe.isEmpty() ? "red" : "blue") +
                                " castle in selected map");
                        return;
                    }
                    castleSpawns = new CastleSpawns(redSafe, blueSafe, redBase, blueBase);
                    CFTrace.event("CASTLE_SPAWNS_READY", "map", mapId(),
                            "redCastle", CFTrace.xyz(redBase), "blueCastle", CFTrace.xyz(blueBase),
                            "redCells", redSafe.size(), "blueCells", blueSafe.size());
                    beginWave(0);
                }));
    }

    private static String mapId() {
        return System.getProperty("cf.map.id", "selected-map");
    }

    private void failArena(String message) {
        error = message;
        System.err.println("[CF] Arena error: " + message);
        summary.append("Arena error: ").append(message).append('\n');
        CFTrace.event("ARENA_ERROR", "error", message);
        completeSession("arena_error");
    }

    private void beginWave(int index) {
        if (!busy.get() || manualBattle) return;
        if (index >= runWaves.size()) {
            completeSession("all_waves_finished");
            return;
        }
        waveIndex = index;
        waveStartedNs = 0L;
        activeWave = runWaves.get(index);
        serial++;
        int token = serial;
        participants.clear();
        spawnFutures.clear();
        spawnPlan.clear();
        benchmarkGate = new AtomicBoolean(false);
        Case test = activeWave.cases().get(0);
        List<CFUnit> owners = new ArrayList<>();
        participants.add(owners);
        try { spawnPlan.addAll(buildPlan(test, owners)); }
        catch (IllegalStateException e) { failArena(e.getMessage()); return; }
        CFTrace.event("WAVE_PREPARE", "wave", activeWave.id(), "waveNumber", index + 1,
                "waves", runWaves.size(), "scenarios", 1,
                "units", activeWave.units(), "spawnPerTick", BATCH_PER_TICK,
                "map", mapId());
        System.out.println("[CF] Scenario " + (index + 1) + "/" + runWaves.size() +
                " " + test.id() + ": " + test.total() + " units near team castles on " + mapId());
        state = "SPAWNING";
        CFDuelPerf.setStage("spawn_" + activeWave.id());
        spawnBatch(token, 0);
    }

    private List<SpawnSpec> buildPlan(Case test, List<CFUnit> owners) {
        CastleSpawns spawns = Objects.requireNonNull(castleSpawns, "Castle spawn cells not loaded");
        List<Pos> redBase = spawns.red(), blueBase = spawns.blue();
        List<String> reds = teamKinds(test.redZ(), test.redS());
        List<String> blues = teamKinds(test.blueZ(), test.blueS());
        int redCount = test.redTotal(), blueCount = test.blueTotal();
        List<SpawnSpec> plan = new ArrayList<>(redCount + blueCount);
        for (int i = 0; i < Math.max(redCount, blueCount); i++) {
            if (i < redCount) plan.add(new SpawnSpec(test, "red", reds.get(i),
                    redBase.get(i % redBase.size()), spawns.blueCastle(), owners));
            if (i < blueCount) plan.add(new SpawnSpec(test, "blue", blues.get(i),
                    blueBase.get(i % blueBase.size()), spawns.redCastle(), owners));
        }
        return plan;
    }

    private static List<String> teamKinds(int zombies, int skeletons) {
        List<String> kinds = new ArrayList<>(zombies + skeletons);
        while (zombies > 0 || skeletons > 0) {
            if (zombies >= skeletons && zombies > 0) { kinds.add("grave_zombie"); zombies--; }
            else { kinds.add("risen"); skeletons--; }
        }
        return kinds;
    }

    private boolean validToken(int token) { return busy.get() && !manualBattle && serial == token; }

    private void spawnBatch(int token, int offset) {
        if (!validToken(token) || !state.equals("SPAWNING")) return;
        int end = Math.min(offset + BATCH_PER_TICK, spawnPlan.size());
        for (int i = offset; i < end; i++) {
            SpawnSpec spec = spawnPlan.get(i);
            Pos at = spec.position();
            Pos enemyCastle = spec.destination();
            try {
                CFContentRegistry.Mob type = content.mob(spec.kind());
                if(type == null) throw new IllegalStateException("Unknown benchmark mob " + spec.kind());
                CFUnit mob = new CFUnit(spec.team(), type, at, enemyCastle);
                mob.prepareBenchmark(benchmarkGate);
                CFTrace.tagUnit(mob, spec.test().id());
                spec.owners().add(mob);
                gameUnits.add(mob);
                CFTrace.event("CASE_SPAWN", "unit", mob.getEntityId(),
                        "team", spec.team(), "kind", spec.kind(),
                        "spawn", CFTrace.xyz(at), "destination", CFTrace.xyz(enemyCastle));
                CompletableFuture<?> future = mob.setInstance(world, at).whenComplete((result, failure) -> {
                    if (failure == null) mob.markSpawnCompleted();
                    if (failure != null) {
                        spawnErrors.incrementAndGet();
                        CFTrace.event("CASE_SPAWN_ERROR", "unit", mob.getEntityId(),
                                "reason", failure.toString());
                        gameUnits.remove(mob);
                    }
                    // A forced /cf duel finish may occur while an entity is still loading.
                    if (!validToken(token)) {
                        gameUnits.remove(mob);
                        if (!mob.isRemoved()) mob.remove();
                    }
                });
                spawnFutures.add(future);
            } catch (Exception ex) {
                spawnErrors.incrementAndGet();
                CFTrace.event("CASE_SPAWN_ERROR", "caseId", spec.test().id(), "reason", ex.toString());
            }
        }
        if (end < spawnPlan.size()) {
            world.scheduler().scheduleNextTick(() -> spawnBatch(token, end));
        } else {
            CompletableFuture.allOf(spawnFutures.toArray(new CompletableFuture<?>[0]))
                    .whenComplete((unused, failure) -> world.scheduler().scheduleNextTick(() -> {
                        if (!validToken(token) || !state.equals("SPAWNING")) return;
                        if (failure != null) {
                            CFTrace.event("CASE_SPAWN_FUTURE_ERROR", "wave", activeWave.id(),
                                    "reason", failure.toString());
                        }
                        startActiveWave(token);
                    }));
        }
    }

    private void startActiveWave(int token) {
        if (!validToken(token)) return;
        Wave w = activeWave;
        int aliveAtStart = 0;
        for (List<CFUnit> roster : participants) for (CFUnit mob : roster) {
            if (mob.wasSpawnCompleted() && !mob.isDead() && !mob.isRemoved()) aliveAtStart++;
        }
        // Units cannot attack while batches are being prepared. Activate all of them
        // on the world scheduler only after every setInstance future has finished.
        for (List<CFUnit> roster : participants) for (CFUnit mob : roster) {
            if (mob.wasSpawnCompleted() && !mob.isDead() && !mob.isRemoved())
                mob.releaseBenchmarkPhysics();
        }
        benchmarkGate.set(true);
        state = "RUNNING";
        waveStartedNs = System.nanoTime();
        CFDuelPerf.setStage(w.id());
        CFTrace.event("WAVE_ACTIVE", "wave", w.id(), "unitCount", w.units(),
                "aliveAtStart", aliveAtStart, "spawnErrorsTotal", spawnErrors.get(),
                "seconds", SECONDS_PER_WAVE);
        for (int i = 0; i < w.cases().size(); i++) {
            Case c = w.cases().get(i);
            CFTrace.event("CASE_BEGIN", "caseId", c.id(), "wave", w.id(),
                    "title", c.title(), "redZ", c.redZ(), "redS", c.redS(),
                    "blueZ", c.blueZ(), "blueS", c.blueS(),
                    "redCount", c.redTotal(), "blueCount", c.blueTotal(),
                    "map", mapId(), "redSpawn", CFTrace.xyz(castleSpawns.red().get(0)),
                    "blueSpawn", CFTrace.xyz(castleSpawns.blue().get(0)));
        }
        scheduleTimer(token);
    }

    private void scheduleTimer(int token) {
        Thread.ofVirtual().name("cf13-duel-timer").start(() -> {
            try { Thread.sleep(SECONDS_PER_WAVE * 1000L); }
            catch (InterruptedException ignored) { Thread.currentThread().interrupt(); return; }
            world.scheduler().scheduleNextTick(() -> {
                if (!busy.get() || !state.equals("RUNNING") || serial != token) return;
                if (manualBattle) endManual("time_limit");
                else endWave("time_limit", false);
            });
        });
    }

    private void endWave(String reason, boolean aborted) {
        Wave w = activeWave;
        if (w == null) return;
        state = "TRANSITION";
        CFDuelPerf.setStage("cleanup_" + w.id());
        CFTrace.event("WAVE_END", "wave", w.id(), "reason", reason,
                "durationMs", waveStartedNs == 0 ? 0 : (System.nanoTime()-waveStartedNs)/1_000_000L,
                "spawnErrors", spawnErrors.get());
        for (int i = 0; i < w.cases().size(); i++) {
            writeCase(w.id(), w.cases().get(i), participants.get(i), reason);
        }
        finishedCases += w.cases().size();
        clearParticipants();
        if (aborted) completeSession("user_stopped");
        else {
            int token = serial;
            int nextWave = waveIndex + 1;
            world.scheduler().scheduleNextTick(() -> {
                if (validToken(token) && state.equals("TRANSITION")) beginWave(nextWave);
            });
        }
    }

    private void writeCase(String wave, Case test, List<CFUnit> roster, String reason) {
        int redAlive = 0, blueAlive = 0, redSpawned = 0, blueSpawned = 0;
        double redHp = 0, blueHp = 0;
        for (CFUnit mob : roster) {
            if (!mob.wasSpawnCompleted()) continue;
            if (mob.team().equals("red")) redSpawned++;
            else blueSpawned++;
            if (mob.isDead() || mob.isRemoved()) continue;
            if (mob.team().equals("red")) { redAlive++; redHp += mob.getHealth(); }
            else { blueAlive++; blueHp += mob.getHealth(); }
            CFTrace.event("CASE_FINAL_UNIT", "unit", mob.getEntityId(),
                    "hp", mob.getHealth(), "pos", CFTrace.xyz(mob.getPosition()),
                    "goal", CFTrace.goal(mob), "target", CFTrace.id(mob.traceTarget()));
        }
        CFHitAudit.closeCase(test.id(), roster);
        CFTrace.event("CASE_END", "caseId", test.id(), "wave", wave,
                "redAlive", redAlive, "blueAlive", blueAlive, "redHp", redHp,
                "blueHp", blueHp, "reason", reason);
        caseCsv.append(wave).append(',').append(test.id()).append(',')
                .append('"').append(test.title()).append('"')
                .append(',').append(test.redZ()).append(',').append(test.redS())
                .append(',').append(test.blueZ()).append(',').append(test.blueS())
                .append(',').append(test.redTotal()).append(',').append(test.blueTotal())
                .append(',').append(redSpawned).append(',').append(blueSpawned)
                .append(',').append(test.redTotal()-redSpawned).append(',').append(test.blueTotal()-blueSpawned)
                .append(',').append(redAlive).append(',').append(blueAlive)
                .append(',').append(fmt(redHp)).append(',').append(fmt(blueHp))
                .append(',').append(redSpawned-redAlive)
                .append(',').append(blueSpawned-blueAlive)
                .append(',').append(reason).append('\n');
        summary.append(wave).append(" | ").append(test.id())
                .append(" | RED ").append(redAlive).append('/').append(test.redTotal())
                .append(" HP ").append(fmt(redHp))
                .append(" | BLUE ").append(blueAlive).append('/').append(test.blueTotal())
                .append(" HP ").append(fmt(blueHp))
                .append(" | spawned RED ").append(redSpawned).append(" BLUE ").append(blueSpawned)
                .append(" | not spawned RED ").append(test.redTotal()-redSpawned)
                .append(" BLUE ").append(test.blueTotal()-blueSpawned)
                .append(" | ").append(reason).append('\n');
    }

    private static String fmt(double n) { return String.format(Locale.ROOT, "%.1f", n); }

    private void clearParticipants() {
        for (List<CFUnit> roster : participants) {
            for (CFUnit unit : roster) {
                gameUnits.remove(unit);
                if (!unit.isRemoved()) unit.remove();
            }
        }
        participants.clear();
        spawnFutures.clear();
        spawnPlan.clear();
    }

    /** Legacy /cf duel N: N red zombies vs N blue zombies at the player's castles. */
    String startManual(int count) {
        if (!busy.compareAndSet(false, true)) return "[CF] Benchmark is busy. /cf duel status";
        if (CFTrace.on()) { busy.set(false); return "[CF] Stop /cf trace first."; }
        reset(true);
        Case manual = new Case("manual_" + count + "v" + count,
                "Manual zombie battle", count,0,count,0);
        activeWave = new Wave("manual", List.of(manual));
        participants.add(new ArrayList<>());
        waveIndex = 0;
        state = "PREPARING";
        if (!beginProfiling(180)) return "[CF] " + error;
        state = "RUNNING";
        waveStartedNs = System.nanoTime();
        CFDuelPerf.setStage("manual_" + count + "v" + count);
        CFTrace.event("CASE_BEGIN", "caseId", manual.id(), "redZ", count,
                "blueZ", count, "mode", "manual_castles");
        scheduleTimer(serial);
        return "[CF] Manual " + count + " vs " + count + " zombies. Full 70s benchmark started. " +
                "ZIP automatic; /cf duel status";
    }

    boolean isRunning() { return state.equals("RUNNING"); }
    boolean isBusy() { return busy.get(); }

    int tagManualSpawn(CFUnit unit) {
        if (!manualBattle || !busy.get() || !state.equals("RUNNING") || participants.isEmpty()) return -1;
        participants.get(0).add(unit);
        CFTrace.tagUnit(unit, activeWave.cases().get(0).id());
        CFTrace.event("CASE_SPAWN", "unit", unit.getEntityId(), "team", unit.team(),
                "kind", unit.kind(), "spawn", CFTrace.xyz(unit.getPosition()));
        return serial;
    }

    void manualSpawnFailed(int token, CFUnit unit, Throwable failure) {
        if (token < 0 || !manualBattle || !busy.get() || serial != token || !state.equals("RUNNING")) return;
        spawnErrors.incrementAndGet();
        CFTrace.event("CASE_SPAWN_ERROR", "unit", unit.getEntityId(), "reason", failure.toString());
    }

    private void endManual(String reason) {
        if (!manualBattle || !busy.get()) return;
        state = "TRANSITION";
        Case c = activeWave.cases().get(0);
        CFTrace.event("WAVE_END", "wave", "manual", "reason", reason);
        writeCase("manual", c, participants.get(0), reason);
        finishedCases = 1;
        CFDuelPerf.setStage("cleanup_manual");
        clearParticipants();
        // Invalidate any outstanding /cf duel N spawn batches.
        clearExisting.run();
        completeSession(reason);
    }

    /** User-facing forced finish: preserve results, JFR and FULL trace in a partial ZIP. */
    String finishNow() {
        if (!busy.get()) return "[CF] No duel running. " + status();
        if (state.equals("PACKING")) return "[CF] Already packing ZIP. /cf duel status";
        world.scheduler().scheduleNextTick(() -> {
            if (!busy.get() || state.equals("PACKING")) return;
            serial++; // Cancel pending async chunk/spawn/timer callbacks.
            if (manualBattle) {
                if (!participants.isEmpty()) endManual("user_stopped");
                else completeSession("user_stopped");
            } else if (activeWave != null && !participants.isEmpty()) {
                endWave("user_stopped", true);
            } else completeSession("user_stopped");
        });
        return "[CF] Finishing current test and creating a partial ZIP...";
    }

    private void completeSession(String reason) {
        if (!busy.get() || state.equals("PACKING")) return;
        state = "PACKING";
        CFDuelPerf.setStage("pack");
        CFTrace.event("DUEL_SUITE_END", "reason", reason,
                "casesFinished", finishedCases, "plannedCases", manualBattle ? 1 : runCases.size(),
                "spawnErrors", spawnErrors.get());
        CFTrace.stop();
        hitReport = CFHitAudit.finish();
        motionReport = CFMotionAudit.finish();
        summary.append("\nFinished UTC: ").append(Instant.now())
                .append("\nReason: ").append(reason)
                .append("\nCompleted scenarios: ").append(finishedCases).append('/')
                .append(manualBattle ? 1 : runCases.size())
                .append("\nSpawn errors: ").append(spawnErrors.get()).append('\n');
        final String humanText = summary.toString();
        final String resultText = caseCsv.toString();
        Thread.ofVirtual().name("cf13-duel-zip").start(() -> pack(humanText, resultText));
    }

    private String runPlan() {
        StringBuilder b = new StringBuilder("wave,scenario,red_zombies,red_skeletons,blue_zombies,blue_skeletons,total_units,seconds\n");
        for (Wave w : runWaves) for (Case c : w.cases())
            b.append(w.id()).append(',').append(c.id()).append(',')
                    .append(c.redZ()).append(',').append(c.redS()).append(',')
                    .append(c.blueZ()).append(',').append(c.blueS()).append(',')
                    .append(c.total()).append(',').append(SECONDS_PER_WAVE).append('\n');
        return b.toString();
    }

    private void pack(String humanText, String resultText) {
        Path pending = null;
        try {
            Path perfDir = profiler == null ? null : profiler.finish();
            if (!CFTrace.awaitFinished(600_000L))
                throw new IOException("Full trace writer did not finish in 600s. " + CFTrace.status());
            Path trace = tracePath;
            if (trace == null || !Files.isRegularFile(trace))
                throw new IOException("Trace file missing: " + trace);
            Path folder = Path.of("diagnostics", "reports");
            Files.createDirectories(folder);
            String name = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS")
                    .withZone(ZoneOffset.UTC).format(Instant.now());
            String prefix = compactAudit ? "cf-motion-" : "cf-stress-";
            Path zip = folder.resolve(prefix + name + ".zip");
            pending = folder.resolve(prefix + name + ".zip.part");
            try (OutputStream raw = Files.newOutputStream(pending, StandardOpenOption.CREATE_NEW);
                 ZipOutputStream out = new ZipOutputStream(raw)) {
                out.setLevel(5);
                String perfSummary = perfDir != null &&
                        Files.isRegularFile(perfDir.resolve("PERFORMANCE_SUMMARY.txt"))
                        ? Files.readString(perfDir.resolve("PERFORMANCE_SUMMARY.txt"))
                        : "PERFORMANCE PROFILE UNAVAILABLE\n";
                text(out, "SUMMARY.txt", humanText + "\n" + perfSummary +
                        "\nTRACE INTEGRITY: " + CFTrace.status() + "\n" +
                        "Note: JFR samples CPU methods; CF method times are inclusive.\n" +
                        "High-volume full AI logging changes the observed MSPT.\n");
                text(out, "TEST_PLAN.csv", runPlan());
                text(out, "scenario-results.csv", resultText);
                text(out, "scenario-events.csv", CFTrace.scenarioCountersCsv());
                CFHitAudit.Report audit = hitReport;
                if (audit != null) {
                    text(out, "hits/per-shot.csv", audit.shotsCsv());
                    text(out, "hits/per-scenario.csv", audit.casesCsv());
                    text(out, "hits/per-team-and-kind.csv", audit.teamsCsv());
                    text(out, "hits/reasons.csv", audit.reasonsCsv());
                    text(out, "hits/README.txt", audit.metadataTxt());
                }
                CFMotionAudit.Report motion = motionReport;
                if (motion != null) {
                    text(out, "motion/per-unit.csv", motion.perUnitCsv());
                    text(out, "motion/per-scenario.csv", motion.perScenarioCsv());
                    text(out, "motion/anomalies.csv", motion.anomaliesCsv());
                    text(out, "motion/README.txt", motion.metadataTxt());
                }
                text(out, "README.txt", "CF Engine 1.5, mode=" + (compactAudit ? "MOTION_HITS_1HZ" : "FULL_AI") +
                        ", scenarios=" + runCases.size() + ", waves=" + runWaves.size() + ".\n" +
                        "All scenarios run on the selected map near team castles, 70 seconds each.\n" +
                        "Spawn cells may repeat in very large stress tests.\n" +
                        "perf/stage-statistics.csv summarizes per-wave server ticks.\n" +
                        "perf/tick-times.csv has a stage column for each tick.\n" +
                        "trace/events.jsonl includes all events selected by mode, unsampled.\n" +
                        "hits/per-shot.csv is the authoritative per-attempt damage audit.\n" +
                        "motion/anomalies.csv flags stalled, jammed and drifting units from 1 Hz samples.\n" +
                        "The MOTION_HITS_1HZ mode excludes full AI-tick tracing and full server console log.\n" +
                        "Client-only arrows have no server entity/physics; see ARROW_SHOOT / ARROW_DAMAGE.\n" +
                        "The JVM CPU report is sampled, not exact per-method CPU time.\n" +
                        "Do not share ZIP if paths/logs contain sensitive data.\n");
                // Compact mode excludes huge console logs by design.
                Path console = CFConsoleCapture.logFile();
                if (!compactAudit && console != null && Files.isRegularFile(console))
                    file(out, "logs/server-full.log", console);
                if (Files.isRegularFile(Path.of("build.log"))) file(out, "logs/build.log", Path.of("build.log"));
                if (perfDir != null && Files.isDirectory(perfDir)) {
                    try (var stream = Files.walk(perfDir)) {
                        for (Path p : stream.filter(Files::isRegularFile).toList()) {
                            file(out, "perf/" + perfDir.relativize(p).toString().replace('\\', '/'), p);
                        }
                    }
                }
                file(out, "trace/events.jsonl", trace);
                Path traceSummary = Path.of(trace.toString() + ".summary.txt");
                if (Files.isRegularFile(traceSummary)) file(out, "trace/event-summary.txt", traceSummary);
                Path src = Path.of("src/main/java/dev/cf");
                for (String clazz : List.of("Main", "CFUnit", "MarchGoal", "CFTrace", "CFTraceArrow",
                        "CFTraceMeleeGoal", "CFTraceRangedGoal", "CFTraceTarget", "CFDuelSuite", "MapSurface",
                        "CFConsoleCapture", "CFClientShot", "CFDuelPerf", "CFHitAudit", "CFMotionAudit")) {
                    Path source = src.resolve(clazz + ".java");
                    if (Files.isRegularFile(source)) file(out, "source/" + clazz + ".java", source);
                }
                CastleSpawns locations = castleSpawns;
                if (locations != null) {
                    StringBuilder positions = new StringBuilder("team,x,y,z\n");
                    for (Pos p : locations.red()) positions.append("red,").append(p.x()).append(',')
                            .append(p.y()).append(',').append(p.z()).append('\n');
                    for (Pos p : locations.blue()) positions.append("blue,").append(p.x()).append(',')
                            .append(p.y()).append(',').append(p.z()).append('\n');
                    text(out, "map/generated-castle-spawns.csv", positions.toString());
                }
                if (Files.isRegularFile(Path.of("pom.xml")))
                    file(out, "source/pom.xml", Path.of("pom.xml"));
                if (Files.isRegularFile(Path.of("tools/analyze_hits.py")))
                    file(out, "tools/analyze_hits.py", Path.of("tools/analyze_hits.py"));
                if (Files.isRegularFile(Path.of("tools/analyze_motion.py")))
                    file(out, "tools/analyze_motion.py", Path.of("tools/analyze_motion.py"));
            }
            Files.move(pending, zip, StandardCopyOption.ATOMIC_MOVE);
            zipPath = zip.toAbsolutePath().toString();
            if (compactAudit) {
                // This archive is already compact; do not create a duplicate 500-MB ZIP.
                analysisZipPath = zipPath;
            } else {
                // Original exhaustive AI mode still emits its smaller second ZIP.
                Path compact = folder.resolve(prefix + name + "-analysis.zip");
                try {
                    compactReport(zip, compact);
                    analysisZipPath = compact.toAbsolutePath().toString();
                } catch (Exception compactError) {
                    System.err.println("[CF14] Analysis ZIP skipped: " + compactError);
                }
            }
            state = error.equals("none") ? "READY" : "ERROR";
            System.out.println("[CF13] COMPLETE ZIP (all logs): " + zipPath);
            System.out.println("[CF13] ANALYSIS ZIP (small): " + analysisZipPath);
        } catch (Exception ex) {
            error = ex.toString();
            state = "ERROR";
            System.err.println("[CF13] ZIP error: " + error);
            ex.printStackTrace();
            if (pending != null) try { Files.deleteIfExists(pending); } catch (IOException ignored) { }
        } finally {
            busy.set(false);
        }
    }

    private static void compactReport(Path fullZip, Path compact) throws IOException {
        try (ZipFile source = new ZipFile(fullZip.toFile());
             OutputStream raw = Files.newOutputStream(compact, StandardOpenOption.CREATE_NEW);
             ZipOutputStream out = new ZipOutputStream(raw)) {
            out.setLevel(5);
            var entries = source.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName();
                if (name.equals("trace/events.jsonl") || name.equals("logs/server-full.log")) continue;
                out.putNextEntry(new ZipEntry(name));
                try (InputStream in = source.getInputStream(entry)) { in.transferTo(out); }
                out.closeEntry();
            }
            text(out, "READ_ME_FIRST.txt", "This is the compact analysis ZIP.\n" +
                    "It includes the complete JFR recording, stage metrics, CSV results and source code.\n" +
                    "hits/ CSV files reconcile individual arrow attacks to HP lost.\n" +
                    "motion/ CSV files summarize stalled / jammed / drifting units.\n" +
                    "Full per-unit AI trace is only in the larger ZIP.\n" +
                    "Send this small ZIP for hit debugging.\n");
        }
    }

    private static void file(ZipOutputStream zip, String name, Path path) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        try (InputStream in = Files.newInputStream(path)) { in.transferTo(zip); }
        zip.closeEntry();
    }
    private static void text(ZipOutputStream zip, String name, String value) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(value.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    String status() {
        if (!busy.get()) return "[CF DUEL] state=" + state + " ZIP=" + zipPath + " ANALYSIS=" + analysisZipPath +
                (!error.equals("none") ? " ERROR=" + error : "");
        Wave w = activeWave;
        String wave = w == null ? "none" : w.id();
        String remaining = state.equals("RUNNING")
                ? String.valueOf(Math.max(0, SECONDS_PER_WAVE -
                        (int)((System.nanoTime()-waveStartedNs)/1_000_000_000L))) + "s"
                : "not-running";
        return "[CF DUEL] state=" + state + " wave=" + (waveIndex+1) + "/" +
                (manualBattle ? 1 : runWaves.size()) + " " + wave +
                " scenarios=" + finishedCases + "/" + (manualBattle ? 1 : runCases.size()) +
                " currentUnits=" + (w == null ? 0 : w.units()) +
                " remaining=" + remaining + " spawnErrors=" + spawnErrors.get() +
                " JFR=" + (CFDuelPerf.on() ? "ON" : "OFF") + " | /cf duel finish";
    }

    Pos camera(int number) {
        if (manualBattle || activeWave == null || number < 1 || number > runCases.size()) return null;
        Case picked = runCases.get(number-1);
        int lane = activeWave.cases().indexOf(picked);
        if (lane < 0) return null; // That scenario is not running in this wave.
        Pos r = redCastle.get(), b = blueCastle.get();
        if (r == null || b == null) return null;
        return new Pos((r.x() + b.x()) / 2.0,
                Math.max(r.y(), b.y()) + 12.0,
                (r.z() + b.z()) / 2.0);
    }
}
