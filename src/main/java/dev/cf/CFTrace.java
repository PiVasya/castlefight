package dev.cf;

import net.minestom.server.coordinate.Point;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.coordinate.Vec;
import net.minestom.server.entity.Entity;
import net.minestom.server.entity.ai.EntityAIGroup;
import net.minestom.server.entity.ai.GoalSelector;
import net.minestom.server.entity.pathfinding.Navigator;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

final class CFTrace {
    // Unlimited by default for diagnostics. Optional -Dcf.trace.maxBytes=... cap.
    private static final long MAX_BYTES = Long.getLong("cf.trace.maxBytes", 0L);
    private static volatile Run active;
    private static volatile String lastFile = "none";
    private static final Map<Integer, String> LAST_GOAL = new ConcurrentHashMap<>();
    private static final Map<Integer, Integer> LAST_TARGET = new ConcurrentHashMap<>();
    private static final Map<Integer, String> UNIT_CASE = new ConcurrentHashMap<>();
    private static final Map<Integer, String> ARROW_CASE = new ConcurrentHashMap<>();
    private static final Map<String, Map<String, LongAdder>> CASE_COUNTS = new ConcurrentHashMap<>();

    static void tagUnit(CFUnit unit, String caseId) {
        UNIT_CASE.put(unit.getEntityId(), caseId);
        CFHitAudit.tagUnit(unit, caseId);
        CFMotionAudit.tagUnit(unit, caseId);
        event("UNIT_TAGGED", "unit", unit.getEntityId(), "caseId", caseId);
    }
    static void tagArrow(int arrowId, CFUnit shooter) {
        String label = UNIT_CASE.get(shooter.getEntityId());
        if (label != null) ARROW_CASE.put(arrowId, label);
    }
    static Path latestPath() {
        Run r = active;
        return r == null ? null : r.path;
    }
    static boolean awaitFinished(long timeoutMs) throws InterruptedException {
        long limit = System.nanoTime() + timeoutMs * 1_000_000L;
        while (System.nanoTime() < limit) {
            Run r = active;
            if (r != null && r.finished) return true;
            Thread.sleep(100);
        }
        return false;
    }
    static String scenarioCountersCsv() {
        StringBuilder out = new StringBuilder("scenario,event,count\n");
        CASE_COUNTS.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry ->
            entry.getValue().entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(stat ->
                out.append(entry.getKey()).append(',').append(stat.getKey())
                    .append(',').append(stat.getValue().sum()).append('\n')));
        return out.toString();
    }
    private static String resolveCase(Object... pairs) {
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            String key = String.valueOf(pairs[i]);
            Object value = pairs[i+1];
            if ("caseId".equals(key)) return String.valueOf(value);
            if (!(value instanceof Number number)) continue;
            int id = number.intValue();
            String label = switch (key) {
                case "unit", "attacker", "shooter" -> UNIT_CASE.get(id);
                case "arrow" -> ARROW_CASE.get(id);
                default -> null;
            };
            if (label != null) return label;
        }
        return null;
    }
    private CFTrace() {}

    static boolean on() {
        Run r = active;
        return r != null && r.open;
    }

    // Avoid expensive per-tick state scans during the compact hit investigation.
    // Only active for /cf duel hits, not normal gameplay or full AI tracing.
    static boolean compactMovement() {
        Run r = active;
        return r != null && r.open && !r.detailed;
    }

    static void movementSample(CFUnit u, MarchGoal march, double moved,
                               int pushes, double impulseX, double impulseZ,
                               int maxOverlaps) {
        if (!compactMovement()) return;
        Pos pos = u.getPosition();
        Navigator nav = u.getNavigator();
        Entity target = u.traceTarget();
        CFMotionAudit.sample(u, march, moved, pushes, impulseX, impulseZ, maxOverlaps);
        event("MOVE_SAMPLE", "unit", u.getEntityId(),
                "team", u.team(), "kind", u.kind(), "ageTicks", u.getAliveTicks(),
                "x", pos.x(), "y", pos.y(), "z", pos.z(),
                "vx", u.getVelocity().x(), "vy", u.getVelocity().y(), "vz", u.getVelocity().z(),
                "movedInSample", moved, "hp", u.getHealth(),
                "ground", u.isOnGround(),
                "goal", goal(u), "target", id(target),
                "targetDead", target instanceof CFUnit living ? living.isDead() : null,
                "targetDistance", target == null ? -1 : u.getDistance(target),
                "navState", nav.getState().name(),
                "navGoal", xyz(nav.getGoalPosition()),
                "navPathPos", xyz(nav.getPathPosition()),
                "navNodes", nav.getNodes() == null ? 0 : nav.getNodes().size(),
                "marchStep", march.checkpointIndex(),
                "marchTotal", march.checkpointCount(),
                "marchFinished", march.routeFinished(),
                "marchCheckpoint", xyz(march.checkpoint()),
                "pushCalls", pushes, "pushX", impulseX, "pushZ", impulseZ,
                "maxPushOverlaps", maxOverlaps);
    }

    static boolean detailed() {
        Run r = active;
        return r != null && r.open && r.detailed;
    }

    static synchronized String startCompact(int seconds) { return start(seconds, false); }
    static synchronized String start(int seconds) { return start(seconds, true); }

    private static synchronized String start(int seconds, boolean detailed) {
        if (on()) return "[CF] Trace is already running. Use /cf trace stop.";
        if (active != null && !active.finished) return "[CF] Previous trace is still flushing.";
        if (seconds < 2 || seconds > 3600) return "[CF] Choose 2-3600 seconds.";
        try {
            Files.createDirectories(Path.of("diagnostics"));
            String stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS")
                    .withZone(ZoneOffset.UTC).format(Instant.now());
            Run run = new Run(Path.of("diagnostics", "cftrace-" + stamp + ".jsonl"), detailed);
            LAST_GOAL.clear();
            LAST_TARGET.clear();
            UNIT_CASE.clear();
            ARROW_CASE.clear();
            CASE_COUNTS.clear();
            active = run;
            lastFile = run.path.toString();
            run.start();
            event("TRACE_BEGIN", "seconds", seconds, "format", "JSONL",
                    "mode", detailed ? "FULL_AI" : "HITS_AND_MOVEMENT_1HZ",
                    "engine", "Minestom-2026.10.07-26.2", "version", "CF0.15",
                    "note", "Observation only; no AI/combat fix applied");
            Thread.ofVirtual().name("cftrace-auto-stop").start(() -> {
                try { Thread.sleep(seconds * 1000L); } catch (InterruptedException ignored) { return; }
                synchronized (CFTrace.class) {
                    if (active == run && run.open) {
                        event("TRACE_END", "reason", "duration_expired");
                        run.open = false;
                        System.out.println("[CF] Trace auto-stopped: " + run.path);
                    }
                }
            });
            return "[CF] Recording " + (detailed ? "ALL unit ticks" : "HITS + MOVEMENT snapshots 1/sec")
                    + " for " + seconds + "s -> " + run.path;
        } catch (IOException e) {
            return "[CF] Trace start failed: " + e;
        }
    }

    static synchronized String stop() {
        Run r = active;
        if (r == null || !r.open) return "[CF] Trace not active. Last: " + lastFile;
        event("TRACE_END", "reason", "manual_stop");
        r.open = false;
        return "[CF] Trace stopped: " + r.path
                + "; wait ~2 seconds for disk flush, then run ./cf-pack-trace.sh";
    }

    static String status() {
        Run r = active;
        if (r == null) return "[CF] No trace yet.";
        return "[CF] trace=" + (r.open ? "ON" : (r.finished ? "SAVED" : "FLUSHING"))
                + " file=" + r.path + " bytes=" + r.bytes.get()
                + " queued=" + r.queue.size() + " dropped=" + r.dropped.sum()
                + " error=" + r.writeError;
    }

    static void event(String type, Object... pairs) {
        CFSoakTest.record(type, pairs);
        Run run = active;
        if (run == null || !run.open) return;
        if (!run.detailed && !compactEvent(type)) return;
        String scenario = resolveCase(pairs);
        if (scenario != null) CASE_COUNTS
                .computeIfAbsent(scenario, key -> new ConcurrentHashMap<>())
                .computeIfAbsent(type, key -> new LongAdder()).increment();
        long nano = System.nanoTime();
        StringBuilder b = new StringBuilder(320);
        b.append("{\"event\":");
        quote(b, type);
        if (scenario != null) {
            b.append(",\"scenario\":");
            quote(b, scenario);
        }
        b.append(",\"ns\":").append(nano);
        b.append(",\"millis\":").append(System.currentTimeMillis());
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            b.append(',');
            quote(b, String.valueOf(pairs[i]));
            b.append(':');
            Object value = pairs[i+1];
            if (value == null) b.append("null");
            else if (value instanceof Number n && Double.isFinite(n.doubleValue())) b.append(n);
            else if (value instanceof Boolean bool) b.append(bool);
            else quote(b, String.valueOf(value));
        }
        b.append('}');
        run.counters.computeIfAbsent(type, s -> new LongAdder()).increment();
        // Lossless mode: if the disk falls behind, slow the test instead of
        // silently dropping data. Never use this in a production battle.
        boolean queued = false;
        try {
            String line = b.toString();
            while (run.open && !queued) {
                queued = run.queue.offer(line, 200, TimeUnit.MILLISECONDS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (!queued) run.dropped.increment();
    }

    private static boolean compactEvent(String type) {
        return type.equals("MOVE_SAMPLE") || type.startsWith("ARROW_") || type.startsWith("CASE_")
                || type.startsWith("DUEL_") || type.startsWith("WAVE_")
                || type.equals("TRACE_BEGIN") || type.equals("TRACE_END")
                || type.equals("SHOOT_EVENT") || type.equals("UNIT_DIED")
                || type.equals("NON_ARROW_DAMAGE_EVENT")
                || type.equals("UNIT_TAGGED") || type.startsWith("MELEE_")
                || type.startsWith("DAMAGE_") || type.startsWith("POISON_")
                || type.startsWith("HEALTH_LABEL_") || type.startsWith("UNIT_DEATH_")
                || type.startsWith("DEAD_UNIT_") || type.startsWith("DEAD_ATTACK_")
                || type.startsWith("BURN_") || type.startsWith("CROWD_")
                || type.startsWith("TARGET_");
    }

    static String xyz(Point p) {
        if (p == null) return "null";
        return p.x() + "," + p.y() + "," + p.z();
    }

    static String vel(Vec v) { return v == null ? "null" : xyz(v); }
    static int id(Entity entity) { return entity == null ? -1 : entity.getEntityId(); }
    static String goal(CFUnit unit) {
        String result = "NONE";
        for (EntityAIGroup group : unit.getAIGroups()) {
            GoalSelector current = group.getCurrentGoalSelector();
            if (current != null) {
                result = current.getClass().getSimpleName();
                break;
            }
        }
        return result;
    }

    static void unitTick(CFUnit u) {
        if (!detailed()) return;
        // Actual selected goal target, NOT merely EntityCreature#getTarget().
        Entity t = u.traceTarget();
        Pos pos = u.getPosition();
        Navigator nav = u.getNavigator();
        String currentGoal = goal(u);
        String previous = LAST_GOAL.put(u.getEntityId(), currentGoal);
        if (!currentGoal.equals(previous))
            event("GOAL_CHANGE", "unit", u.getEntityId(), "team", u.team(),
                    "kind", u.kind(), "from", previous, "to", currentGoal,
                    "target", id(t), "nav", nav.getState().name());
        Integer prevTarget = LAST_TARGET.put(u.getEntityId(), id(t));
        if (prevTarget == null || prevTarget != id(t))
            event("TARGET_CHANGE", "unit", u.getEntityId(), "from", prevTarget,
                    "to", id(t), "goal", currentGoal,
                    "distance", t == null ? -1 : u.getDistance(t));

        int touching = 0, nearAllies = 0, nearEnemies = 0;
        double nearestEnemy = Double.POSITIVE_INFINITY;
        int nearestId = -1;
        // Diagnostic only. Extra scan can be expensive: restrict traces to short runs.
        if (u.getInstance() != null) {
            for (Entity e : u.getInstance().getNearbyEntities(pos, 3.0)) {
                if (!(e instanceof CFUnit other) || e == u || other.isRemoved()) continue;
                double dist = Math.hypot(pos.x()-e.getPosition().x(),pos.z()-e.getPosition().z());
                if (Math.abs(pos.y() - e.getPosition().y()) > 1.4) continue;
                if (dist < .76) touching++;
                if (u.team().equals(other.team())) nearAllies++;
                else {
                    nearEnemies++;
                    if (dist < nearestEnemy) { nearestEnemy = dist; nearestId = e.getEntityId(); }
                }
            }
        }
        // 'unit' ensures the scenario resolver attributes this tick to its lane.
        event("UNIT_TICK", "unit", u.getEntityId(), "id", u.getEntityId(), "ageTicks", u.getAliveTicks(),
                "team", u.team(), "kind", u.kind(), "x", pos.x(), "y", pos.y(),
                "z", pos.z(), "yaw", pos.yaw(), "pitch", pos.pitch(),
                "vx", u.getVelocity().x(), "vy", u.getVelocity().y(), "vz", u.getVelocity().z(),
                "hp", u.getHealth(), "ground", u.isOnGround(),
                "goal", currentGoal, "target", id(t),
                "creatureTarget", id(u.getTarget()),
                "targetDistance", t == null ? -1 : u.getDistance(t),
                "lineOfSight", t != null && u.hasLineOfSight(t),
                "navState", nav.getState().name(),
                "navGoal", xyz(nav.getGoalPosition()),
                "navPathPos", xyz(nav.getPathPosition()),
                "navNodes", nav.getNodes() == null ? 0 : nav.getNodes().size(), "touching", touching,
                "nearAllies", nearAllies, "nearEnemies", nearEnemies,
                "nearestEnemy", nearestId,
                "nearestDistance", Double.isInfinite(nearestEnemy) ? -1 : nearestEnemy,
                "boxW", u.getBoundingBox().width(),
                "boxH", u.getBoundingBox().height(), "boxD", u.getBoundingBox().depth());
    }

    private static void quote(StringBuilder b, String s) {
        b.append('"');
        for (int i=0; i<s.length(); i++) {
            char c=s.charAt(i);
            switch(c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                default -> { if(c<32) b.append(' '); else b.append(c); }
            }
        }
        b.append('"');
    }

    private static final class Run {
        final Path path;
        final boolean detailed;
        final ArrayBlockingQueue<String> queue = new ArrayBlockingQueue<>(20000);
        final Map<String,LongAdder> counters = new ConcurrentHashMap<>();
        final LongAdder dropped = new LongAdder();
        final AtomicLong bytes = new AtomicLong();
        volatile boolean open = true;
        volatile boolean finished = false;
        volatile String writeError = "none";
        Run(Path path, boolean detailed) { this.path = path; this.detailed = detailed; }
        void start() {
            Thread.ofVirtual().name("cftrace-writer").start(() -> {
                try (BufferedWriter writer = Files.newBufferedWriter(path,
                        StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                    long lastFlush = System.nanoTime();
                    while (open || !queue.isEmpty()) {
                        String line = queue.poll(150, TimeUnit.MILLISECONDS);
                        if (line != null) {
                            long size = bytes.addAndGet(line.length()+1L);
                            if (MAX_BYTES > 0 && size > MAX_BYTES) {
                                open=false;
                                dropped.increment();
                                System.err.println("[CF TRACE] Max bytes reached: " + MAX_BYTES);
                                break;
                            }
                            writer.write(line);
                            writer.newLine();
                        }
                        if (System.nanoTime() - lastFlush > 500_000_000L) {
                            writer.flush();
                            lastFlush = System.nanoTime();
                        }
                    }
                    writer.flush();
                } catch (Exception e) {
                    writeError = e.toString();
                    open = false;
                    System.err.println("[CF TRACE ERROR] " + e);
                } finally {
                    StringBuilder b = new StringBuilder();
                    b.append("CF TRACE SUMMARY\nfile=").append(path).append("\nbytes=")
                            .append(bytes.get()).append("\ndropped=").append(dropped.sum()).append('\n');
                    b.append("integrity=").append(dropped.sum() == 0 && "none".equals(writeError)
                            ? "COMPLETE" : "INCOMPLETE").append('\n');
                    b.append("writeError=").append(writeError).append('\n');
                    counters.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(e ->
                            b.append(e.getKey()).append('=').append(e.getValue().sum()).append('\n'));
                    try { Files.writeString(Path.of(path+".summary.txt"), b.toString()); }
                    catch(IOException e) { System.err.println("[CF TRACE] Summary write failed: "+e); }
                    finished = true;
                    System.out.println("[CF TRACE] Saved " + path + " (dropped=" + dropped.sum() + ")");
                }
            });
        }
    }
}
