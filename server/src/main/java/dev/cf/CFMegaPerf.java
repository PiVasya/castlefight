package dev.cf;

import java.io.IOException;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * On-demand, bounded, aggregate-only performance recorder.
 * DISABLED hot path: one volatile read. No per-unit JSON or disk IO.
 * Activate using diagnostics/mega-control.txt: "start 90 session-id".
 * The file is polled only once per 20 SERVER ticks, not once per entity.
 * Inclusive timings must not be summed: nested methods overlap.
 */
final class CFMegaPerf {
    enum Metric {
        UNIT_UPDATE, PUSH_SCAN, WIDE_MARCH_TICK, WIDE_PROJECTION,
        WIDE_GEOMETRY_NEAREST, WIDE_SAFE_STEP, WIDE_CLEAR_AHEAD,
        WIDE_TERRAIN_PATH, NATIVE_ASTAR_REQUEST, FOLLOWER_MOVE,
        FOLLOWER_BLOCK_COLLISION, TARGET_FULL_SCAN
    }
    enum Count {
        PUSH_SCANS, PUSH_SKIPPED, PUSH_CANDIDATES, PUSH_CONTACTS,
        WIDE_DIRECT_TICKS, WIDE_FALLBACK_TICKS,
        WIDE_PATH_REQUESTS, WIDE_PATH_ACCEPTED, WIDE_PATH_REJECTED,
        WIDE_BLOCKED_STEPS, WIDE_SAFE_DENIED,
        TARGET_REQUESTS, TARGET_CACHE_HITS, TARGET_SCANS_MARCH, TARGET_SCANS_REVIEW,
        TARGET_SCANS_LOST, TARGET_CANDIDATES, TARGET_CHANGES, TARGET_INVALIDATIONS
    }
    private static final Path ROOT = Path.of("diagnostics", "mega");
    private static final Path CONTROL = Path.of("diagnostics", "mega-control.txt");
    private static volatile Session active;
    private static volatile int pushPeriod = readInitialPeriod();
    private static long monitorTicks;
    private static boolean stateWritten;

    private static int readInitialPeriod() {
        int configured = Integer.getInteger("cf.push.periodTicks", 1);
        return configured >= 1 && configured <= 5 ? configured : 1;
    }

    private CFMegaPerf() { }
    static int pushPeriod() { return pushPeriod; }
    static boolean pushOnThisTick(long age, int entityId) {
        int period = pushPeriod;
        return period == 1 || Math.floorMod(age + (long) entityId, period) == 0;
    }
    static long begin() { return active == null ? 0L : System.nanoTime(); }
    static void end(Metric metric, long began) {
        if (began == 0L) return;
        Session s = active;
        if (s == null) return;
        long elapsed = System.nanoTime() - began;
        if (elapsed >= 0) s.method[metric.ordinal()].add(elapsed);
    }
    static void count(Count counter, long count) {
        if (count <= 0) return;
        Session s = active;
        if (s != null) s.counter[counter.ordinal()].add(count);
    }

    static void serverTick(double tickMs, int alive) {
        // Tick monitor is a single periodic source. No per-unit polling or filesystem IO.
        if (++monitorTicks % 20 == 0) pollControl();
        Session s = active;
        if (s == null) return;
        s.tick(tickMs, alive);
        if (System.nanoTime() >= s.endNs) finish(s, "duration");
    }

    private static void pollControl() {
        if (!stateWritten) {
            stateWritten = true;
            writePushState();
        }
        if (!Files.isRegularFile(CONTROL) || Files.isSymbolicLink(CONTROL)) return;
        String command;
        try {
            if (Files.size(CONTROL) > 200) {
                Files.deleteIfExists(CONTROL);
                return;
            }
            command = Files.readString(CONTROL).trim();
            Files.deleteIfExists(CONTROL);
        } catch (IOException ex) {
            System.err.println("[CF MEGA] Cannot read command: " + ex);
            return;
        }
        String[] parts = command.split("\\s+");
        try {
            if (parts.length == 3 && parts[0].equalsIgnoreCase("start")) {
                if (active != null) {
                    System.out.println("[CF MEGA] Recording already active; ignoring new start");
                    return;
                }
                int seconds = Integer.parseInt(parts[1]);
                if (seconds < 10 || seconds > 600 || !parts[2].matches("[A-Za-z0-9_-]{1,70}"))
                    throw new IllegalArgumentException("Expected start <10..600> <safe-session-id>");
                Path folder = ROOT.resolve(parts[2]);
                if (Files.exists(folder)) throw new IllegalArgumentException("Session already exists: " + folder);
                Files.createDirectories(folder);
                active = new Session(folder, seconds);
                Files.writeString(folder.resolve("ACTIVE.txt"), "started=" + Instant.now()
                        + "\nseconds=" + seconds + "\npushPeriod=" + pushPeriod + "\n");
                System.out.println("[CF MEGA] Started " + seconds + "s: " + folder);
            } else if (parts.length == 1 && parts[0].equalsIgnoreCase("stop")) {
                Session s = active;
                if (s != null) finish(s, "manual");
            } else if (parts.length == 2 && parts[0].equalsIgnoreCase("push")) {
                int requested = Integer.parseInt(parts[1]);
                if (requested < 1 || requested > 5)
                    throw new IllegalArgumentException("Push period must be 1..5 ticks");
                int old = pushPeriod;
                pushPeriod = requested;
                writePushState();
                System.out.println("[CF MEGA] Push neighbour scan period: " + old + " -> " + requested);
            } else {
                throw new IllegalArgumentException("Unknown command: " + command);
            }
        } catch (IOException | IllegalArgumentException e) {
            System.err.println("[CF MEGA] Rejected control command: " + e);
        }
    }

    private static void writePushState() {
        try {
            Files.createDirectories(ROOT);
            Files.writeString(ROOT.resolve("push-state.txt"), Integer.toString(pushPeriod) + "\n");
        } catch (IOException e) {
            System.err.println("[CF MEGA] Cannot write push state: " + e);
        }
    }

    private static void finish(Session s, String why) {
        if (active != s) return;
        active = null;
        Thread.ofVirtual().name("cf-mega-report").start(() -> {
            try {
                s.save(why);
                System.out.println("[CF MEGA] Report saved to " + s.folder);
            } catch (Exception e) {
                System.err.println("[CF MEGA] Failed to save report: " + e);
                try { Files.writeString(s.folder.resolve("ERROR.txt"), e.toString()); }
                catch (IOException ignored) { }
            }
        });
    }

    private static final class DurationMetric {
        final LongAdder calls = new LongAdder();
        final LongAdder nanos = new LongAdder();
        final AtomicLong maxNanos = new AtomicLong();
        void add(long ns) {
            calls.increment(); nanos.add(ns);
            // Avoid a contended compare-and-swap for the overwhelming majority of calls.
            if (ns > maxNanos.get()) maxNanos.accumulateAndGet(ns, Math::max);
        }
    }

    private static final class Session {
        final Path folder;
        final long startedNs = System.nanoTime();
        final long endNs;
        final int startPushPeriod = pushPeriod;
        final DurationMetric[] method;
        final LongAdder[] counter;
        final double[] times;
        int tickCount;
        long totalAlive;
        int minAlive = Integer.MAX_VALUE, maxAlive;
        final long cpuStart = cpuNs();
        final long gcStart = gcTime();
        Session(Path folder, int seconds) {
            this.folder = folder;
            this.endNs = startedNs + seconds * 1_000_000_000L;
            this.times = new double[seconds * 50 + 1024];
            method = Arrays.stream(Metric.values()).map(m -> new DurationMetric())
                    .toArray(DurationMetric[]::new);
            counter = Arrays.stream(Count.values()).map(m -> new LongAdder())
                    .toArray(LongAdder[]::new);
        }
        void tick(double ms, int alive) {
            if (tickCount < times.length) times[tickCount++] = ms;
            totalAlive += alive;
            minAlive = Math.min(minAlive, alive);
            maxAlive = Math.max(maxAlive, alive);
        }
        void save(String reason) throws IOException {
            double durationSeconds = (System.nanoTime() - startedNs) / 1e9;
            double[] sorted = Arrays.copyOf(times, tickCount);
            Arrays.sort(sorted);
            StringBuilder summary = new StringBuilder(2048);
            summary.append("Castle Fight MEGA CPU+tick diagnostics\n")
                    .append("completed=true\nreason=").append(reason)
                    .append("\nwall_seconds=").append(fmt(durationSeconds))
                    .append("\nticks=").append(tickCount)
                    .append("\nobserved_ticks_per_second=").append(fmt(tickCount / Math.max(.001, durationSeconds)))
                    .append("\ntick_p50_ms=").append(fmt(quantile(sorted, .50)))
                    .append("\ntick_p95_ms=").append(fmt(quantile(sorted, .95)))
                    .append("\ntick_p99_ms=").append(fmt(quantile(sorted, .99)))
                    .append("\ntick_p999_ms=").append(fmt(quantile(sorted, .999)))
                    .append("\ntick_max_ms=").append(fmt(sorted.length == 0 ? Double.NaN : sorted[sorted.length-1]))
                    .append("\nalive_min=").append(minAlive==Integer.MAX_VALUE ? 0 : minAlive)
                    .append("\nalive_max=").append(maxAlive)
                    .append("\nalive_mean=").append(fmt(tickCount==0 ? 0 : totalAlive / (double) tickCount))
                    .append("\nprocess_cpu_seconds=").append(fmt((cpuNs()-cpuStart)/1e9))
                    .append("\ngc_pause_ms=").append(gcTime()-gcStart)
                    .append("\npush_period_ticks_at_start=").append(startPushPeriod)
                    .append("\npush_period_ticks_at_end=").append(pushPeriod)
                    .append("\nNOTE=Method timings are inclusive and cannot be summed into tick time.\n")
                    .append("NOTE=This mode adds profiling overhead. Compare equal load and map states.\n");
            Files.writeString(folder.resolve("summary.txt"), summary.toString());
            StringBuilder timing = new StringBuilder("metric,calls,total_ms,average_us,max_us\n");
            for (Metric metric : Metric.values()) {
                DurationMetric m = method[metric.ordinal()];
                long calls = m.calls.sum(), nanos = m.nanos.sum();
                timing.append(metric).append(',').append(calls).append(',')
                        .append(fmt(nanos/1e6)).append(',')
                        .append(fmt(calls == 0 ? 0 : nanos / 1000.0 / calls)).append(',')
                        .append(fmt(m.maxNanos.get()/1000.0)).append('\n');
            }
            Files.writeString(folder.resolve("method-costs.csv"),timing);
            StringBuilder counters = new StringBuilder("event,count,per_second\n");
            for (Count c : Count.values()) {
                long n = counter[c.ordinal()].sum();
                counters.append(c).append(',').append(n).append(',')
                        .append(fmt(n / Math.max(.001, durationSeconds))).append('\n');
            }
            Files.writeString(folder.resolve("counters.csv"),counters);
            StringBuilder tickCsv = new StringBuilder("sample,tick_ms\n");
            for (int i=0;i<tickCount;i++) tickCsv.append(i+1).append(',').append(fmt(times[i])).append('\n');
            Files.writeString(folder.resolve("tick-times.csv"),tickCsv);
            Files.writeString(folder.resolve("COMPLETE.txt"), "complete=true\nreason="+reason+"\n");
        }
    }
    private static long cpuNs() {
        var os = ManagementFactory.getOperatingSystemMXBean();
        if (os instanceof com.sun.management.OperatingSystemMXBean sun) return sun.getProcessCpuTime();
        return 0;
    }
    private static long gcTime() {
        long sum=0;
        for(GarbageCollectorMXBean b : ManagementFactory.getGarbageCollectorMXBeans()) {
            long ms=b.getCollectionTime();if(ms>=0)sum+=ms;
        }
        return sum;
    }
    private static double quantile(double[] sorted,double p) {
        if(sorted.length==0)return Double.NaN;
        int i=(int)Math.ceil(sorted.length*p)-1;
        return sorted[Math.max(0,Math.min(sorted.length-1,i))];
    }
    private static String fmt(double x) { return String.format(Locale.ROOT,"%.4f",x); }
}
