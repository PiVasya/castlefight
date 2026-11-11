package dev.cf;

import jdk.jfr.Configuration;
import jdk.jfr.Recording;
import jdk.jfr.Event;
import jdk.jfr.Label;
import jdk.jfr.Name;

import java.io.IOException;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * CF_ENGINE_13_DUEL_BENCHMARK
 * JVM profiling (JFR) + one sample per server tick + CF method wall-time counters.
 * All detailed recording is OFF outside a /cf duel session.
 */
final class CFDuelPerf {
    private static volatile CFDuelPerf active;
    private final Recording recording;
    private final Path folder;
    private final long startedNs = System.nanoTime();
    private final Instant started = Instant.now();
    private final StringBuilder tickCsv = new StringBuilder(160_000);
    private final StringBuilder runtimeCsv = new StringBuilder(16_000);
    private final Map<String, MethodSample> methods = new ConcurrentHashMap<>();
    private final List<GarbageCollectorMXBean> gcBeans = ManagementFactory.getGarbageCollectorMXBeans();
    private final MemoryMXBean memory = ManagementFactory.getMemoryMXBean();
    private double[] tickTimes = new double[4096];
    private int tickCount;
    private long lastTickNs;
    private volatile boolean finished;
    private volatile String failure = "none";
    private volatile String stage = "setup";
    private final StringBuilder stageBoundaries = new StringBuilder("stage,elapsed_ms,epoch_ms\n");

    @Name("dev.cf.DuelStage")
    @Label("Castle Fight Benchmark Stage")
    static final class DuelStageEvent extends Event {
        @Label("Stage name") String stageName;
    }

    private final Map<String, StageSample> stages = new java.util.LinkedHashMap<>();

    private static final class StageSample {
        final java.util.ArrayList<Double> tickMs = new java.util.ArrayList<>();
        long totalAlive;
        int minAlive = Integer.MAX_VALUE;
        int maxAlive;
        void add(double duration, int alive) {
            tickMs.add(duration);
            totalAlive += alive;
            minAlive = Math.min(minAlive, alive);
            maxAlive = Math.max(maxAlive, alive);
        }
    }


    private static final class MethodSample {
        final LongAdder calls = new LongAdder();
        final LongAdder totalNs = new LongAdder();
        final AtomicLong maxNs = new AtomicLong();
        void add(long ns) {
            calls.increment();
            totalNs.add(ns);
            maxNs.accumulateAndGet(ns, Math::max);
        }
    }

    private CFDuelPerf(Path folder, Recording recording) {
        this.folder = folder;
        this.recording = recording;
        tickCsv.append("tick,elapsed_ms,tick_time_ms,acquisition_time_ms,interval_ms,alive_units,stage\n");
        runtimeCsv.append("elapsed_ms,heap_used_bytes,heap_committed_bytes,nonheap_used_bytes,thread_count,gc_count,gc_time_ms,process_cpu_ns\n");
    }

    static synchronized CFDuelPerf start() throws Exception {
        if (active != null) throw new IllegalStateException("Duel JFR profiler already running");
        String stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS")
                .withZone(ZoneOffset.UTC).format(Instant.now());
        Path folder = Path.of("diagnostics", "jfr-runs", "cfduel-" + stamp);
        Files.createDirectories(folder);
        Recording jfr = new Recording(Configuration.getConfiguration("profile"));
        jfr.setName("CF-Duel-" + stamp);
        jfr.setToDisk(true);
        // Avoid recording common sensitive initial environment / VM property events.
        // Logs, stacks, paths and user code can still contain private data: review ZIP.
        for (String type : List.of("jdk.InitialEnvironmentVariable", "jdk.InitialSystemProperty",
                "jdk.InitialSecurityProperty", "jdk.SystemProcess", "jdk.JVMInformation")) {
            jfr.disable(type);
        }
        try {
            jfr.start();
        } catch (Exception ex) {
            jfr.close();
            throw ex;
        }
        CFDuelPerf perf = new CFDuelPerf(folder, jfr);
        active = perf;
        System.out.println("[CF PERF] JFR profile STARTED -> " + folder.toAbsolutePath());
        return perf;
    }

    static boolean on() { return active != null; }

    static void setStage(String label) {
        CFDuelPerf perf = active;
        if (perf != null) perf.recordStage(label);
    }

    private synchronized void recordStage(String label) {
        this.stage = label;
        stageBoundaries.append(label).append(',')
                .append(format((System.nanoTime() - startedNs) / 1_000_000.0)).append(',')
                .append(System.currentTimeMillis()).append('\n');
        DuelStageEvent marker = new DuelStageEvent();
        marker.stageName = label;
        marker.commit(); // JFR can now filter CPU samples by the same stage boundaries.
    }


    static long enterMethod() {
        return active == null && !CFSoakTest.isRunning() ? 0L : System.nanoTime();
    }

    static void exitMethod(String name, long began) {
        if (began == 0L) return;
        long elapsed = System.nanoTime() - began;
        CFDuelPerf perf = active;
        if (perf != null && elapsed >= 0L)
            perf.methods.computeIfAbsent(name, ignored -> new MethodSample()).add(elapsed);
        CFSoakTest.methodMetric(name,elapsed);
    }

    static void tick(double tickMs, double acquisitionMs, int liveUnits) {
        CFDuelPerf perf = active;
        if (perf != null) perf.addTick(tickMs, acquisitionMs, liveUnits);
    }

    private synchronized void addTick(double tickMs, double acquisitionMs, int liveUnits) {
        if (finished) return;
        long now = System.nanoTime();
        double elapsedMs = (now - startedNs) / 1_000_000.0;
        double intervalMs = lastTickNs == 0L ? -1 : (now - lastTickNs) / 1_000_000.0;
        lastTickNs = now;
        final String currentStage = stage;
        stages.computeIfAbsent(currentStage, ignored -> new StageSample()).add(tickMs, liveUnits);
        if (tickCount >= tickTimes.length) tickTimes = Arrays.copyOf(tickTimes, tickTimes.length * 2);
        tickTimes[tickCount++] = tickMs;
        tickCsv.append(tickCount).append(',')
                .append(format(elapsedMs)).append(',')
                .append(format(tickMs)).append(',')
                .append(format(acquisitionMs)).append(',')
                .append(format(intervalMs)).append(',')
                .append(liveUnits).append(',').append(currentStage).append('\n');
        if (tickCount == 1 || tickCount % 20 == 0) sampleRuntime(elapsedMs);
    }

    private void sampleRuntime(double elapsedMs) {
        MemoryUsage heap = memory.getHeapMemoryUsage();
        MemoryUsage nonheap = memory.getNonHeapMemoryUsage();
        long gcCount = 0, gcMs = 0;
        for (GarbageCollectorMXBean bean : gcBeans) {
            if (bean.getCollectionCount() >= 0) gcCount += bean.getCollectionCount();
            if (bean.getCollectionTime() >= 0) gcMs += bean.getCollectionTime();
        }
        long cpu = ProcessHandle.current().info().totalCpuDuration()
                .map(Duration::toNanos).orElse(-1L);
        runtimeCsv.append(format(elapsedMs)).append(',')
                .append(heap.getUsed()).append(',').append(heap.getCommitted()).append(',')
                .append(nonheap.getUsed()).append(',')
                .append(ManagementFactory.getThreadMXBean().getThreadCount()).append(',')
                .append(gcCount).append(',').append(gcMs).append(',').append(cpu).append('\n');
    }

    private static String format(double n) {
        return String.format(Locale.ROOT, "%.3f", n);
    }

    /** Called from the duel ZIP virtual thread, never from the server tick. */
    synchronized Path finish() {
        if (finished) return folder;
        finished = true;
        if (active == this) active = null;
        try {
            recording.stop();
            recording.dump(folder.resolve("recording.jfr"));
        } catch (Exception ex) {
            failure = "JFR stop/dump: " + ex;
            System.err.println("[CF PERF] " + failure);
        } finally {
            recording.close();
        }
        try {
            Files.writeString(folder.resolve("tick-times.csv"), tickCsv.toString(), StandardCharsets.UTF_8);
            Files.writeString(folder.resolve("runtime-per-second.csv"), runtimeCsv.toString(), StandardCharsets.UTF_8);
            writeMethodStats();
            writeStageStatistics();
            Files.writeString(folder.resolve("stage-boundaries.csv"), stageBoundaries.toString(), StandardCharsets.UTF_8);
            writeSummary();
            writeJfrViews();
        } catch (Exception ex) {
            failure = "Profile report: " + ex;
            System.err.println("[CF PERF] " + failure);
            try { Files.writeString(folder.resolve("PROFILE_ERROR.txt"), failure); }
            catch (IOException ignored) { }
        }
        return folder;
    }

    String failure() { return failure; }

    private void writeMethodStats() throws IOException {
        StringBuilder sb = new StringBuilder("method,calls,total_ms,average_us,max_us\n");
        methods.entrySet().stream()
                .sorted(Comparator.<Map.Entry<String, MethodSample>>comparingLong(
                        e -> e.getValue().totalNs.sum()).reversed())
                .forEach(e -> {
                    MethodSample stat = e.getValue();
                    long calls = stat.calls.sum();
                    sb.append(e.getKey()).append(',').append(calls).append(',')
                            .append(format(stat.totalNs.sum() / 1e6)).append(',')
                            .append(format(calls == 0 ? 0 : stat.totalNs.sum() / (1000.0 * calls))).append(',')
                            .append(format(stat.maxNs.get() / 1000.0)).append('\n');
                });
        Files.writeString(folder.resolve("cf-method-times.csv"), sb.toString(), StandardCharsets.UTF_8);
    }

    private void writeStageStatistics() throws IOException {
        StringBuilder b = new StringBuilder("stage,sampled_ticks,mean_ms,p50_ms,p95_ms,p99_ms,max_ms,over_50ms,mean_alive,min_alive,max_alive\n");
        for (var e : stages.entrySet()) {
            StageSample st = e.getValue();
            double[] durations = new double[st.tickMs.size()];
            double sum = 0, worst = 0;
            int slow = 0;
            for (int i = 0; i < durations.length; i++) {
                double v = st.tickMs.get(i);
                durations[i] = v;
                sum += v;
                worst = Math.max(worst, v);
                if (v > 50) slow++;
            }
            Arrays.sort(durations);
            b.append(e.getKey()).append(',').append(durations.length).append(',')
                    .append(format(durations.length == 0 ? 0 : sum / durations.length)).append(',')
                    .append(format(pct(durations, 0.50))).append(',')
                    .append(format(pct(durations, 0.95))).append(',')
                    .append(format(pct(durations, 0.99))).append(',')
                    .append(format(worst)).append(',').append(slow).append(',')
                    .append(format(durations.length == 0 ? 0 : st.totalAlive / (double) durations.length)).append(',')
                    .append(st.minAlive == Integer.MAX_VALUE ? 0 : st.minAlive).append(',')
                    .append(st.maxAlive).append('\n');
        }
        Files.writeString(folder.resolve("stage-statistics.csv"), b.toString(), StandardCharsets.UTF_8);
    }

    private void writeSummary() throws IOException {
        double[] sorted = Arrays.copyOf(tickTimes, tickCount);
        Arrays.sort(sorted);
        double sum = 0, worst = 0;
        int slow = 0;
        for (double ms : sorted) { sum += ms; worst = Math.max(worst, ms); if (ms > 50) slow++; }
        double wall = (System.nanoTime() - startedNs) / 1e9;
        String summary = "CASTLE FIGHT AUTOMATIC DUEL BENCHMARK\n"
                + "Date UTC: " + started + "\n"
                + "JVM: " + System.getProperty("java.version") + "\n"
                + "Elapsed seconds (including finalization): " + format(wall) + "\n"
                + "Samples / monitored ticks: " + tickCount + "\n"
                + "Mean tick ms: " + format(tickCount == 0 ? 0 : sum / tickCount) + "\n"
                + "P50 tick ms: " + format(pct(sorted, 0.50)) + "\n"
                + "P95 tick ms: " + format(pct(sorted, 0.95)) + "\n"
                + "P99 tick ms: " + format(pct(sorted, 0.99)) + "\n"
                + "Worst tick ms: " + format(worst) + "\n"
                + "Ticks over 50 ms: " + slow + "\n"
                + "JFR status: " + failure + "\n"
                + "Tick time is Minestom's ServerTickMonitorEvent metric; "
                + "CPU method timings from JFR are SAMPLED (not exact calls).\n"
                + "CF method timings are inclusive wall-time, instrumented only during duel. "
                + "Nested method times MUST NOT be added together.\n"
                + "JFR and detailed trace add overhead; this is an instrumented benchmark.\n"
                + "Read stage-statistics.csv and stage-boundaries.csv for per-wave timings.\n"
                + "JFR also includes dev.cf.DuelStage marker events.\n"
                + "Read tick-times.csv, runtime-per-second.csv, cf-method-times.csv, "
                + "hot-methods.txt, allocation-by-site.txt and recording.jfr.\n";
        Files.writeString(folder.resolve("PERFORMANCE_SUMMARY.txt"), summary, StandardCharsets.UTF_8);
    }

    private static double pct(double[] sorted, double q) {
        if (sorted.length == 0) return 0;
        int index = Math.max(0, Math.min(sorted.length - 1, (int)Math.ceil(q * sorted.length) - 1));
        return sorted[index];
    }

    private void writeJfrViews() {
        Path jfr = folder.resolve("recording.jfr");
        if (!Files.isRegularFile(jfr)) return;
        Path command = Path.of(System.getProperty("java.home"), "bin", "jfr");
        if (!Files.isExecutable(command)) {
            try {Files.writeString(folder.resolve("jfr-tool-error.txt"), "JDK jfr binary not found: " + command);}
            catch (IOException ignored) { }
            return;
        }
        runJfr(command, "jfr-summary.txt", "summary", jfr.toString());
        for (String view : List.of("hot-methods", "cpu-time-hot-methods", "allocation-by-site", "gc-pauses"))
            runJfr(command, view + ".txt", "view", "--width", "180", view, jfr.toString());
    }

    private void runJfr(Path executable, String output, String... args) {
        Path file = folder.resolve(output);
        List<String> cmd = new ArrayList<>();
        cmd.add(executable.toString());
        cmd.addAll(List.of(args));
        try {
            Process process = new ProcessBuilder(cmd).redirectErrorStream(true)
                    .redirectOutput(file.toFile()).start();
            if (!process.waitFor(30, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                Files.writeString(file, "\nJFR report generation timed out. Raw recording.jfr is available.\n",
                        StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.APPEND);
            } else if (process.exitValue() != 0) {
                Files.writeString(file, "\nJFR view exited " + process.exitValue()
                                + "; use recording.jfr directly.\n",
                        StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.APPEND);
            }
        } catch (Exception ex) {
            try { Files.writeString(file, "JFR view error: " + ex); }
            catch (IOException ignored) { }
        }
    }
}
