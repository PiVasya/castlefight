package dev.cf;

import net.minestom.server.coordinate.Pos;
import net.minestom.server.entity.Entity;
import net.minestom.server.entity.ai.EntityAIGroup;
import net.minestom.server.entity.pathfinding.Navigator;
import net.minestom.server.instance.Instance;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Runtime tower skeleton brain recorder: 5Hz snapshots + every AI decision,
 * including navigation/target transitions. Non-blocking bounded queue;
 * a single daemon writer handles all skeletons. No synchronous disk IO on a tick.
 */
final class CFSkeletonMindLog {
    private static final int SAMPLE_TICKS = Math.max(1, Math.min(20,
            Integer.getInteger("cf.skeleton.ai.sampleTicks", 4)));
    private static final int QUEUE_SIZE = 65_536;
    private static final long ROTATE_BYTES = Math.max(1024,Long.getLong("cf.ai.rotateBytes",64L*1024*1024));
    private static final int KEEP_PARTS = 8;
    private static final Sink SINK = new Sink();
    private CFSkeletonMindLog() { }

    private record Flush(CountDownLatch done, long[] snapshot) { }
    static void beginCapture(Path runDir) throws IOException {
        Files.createDirectories(SINK.folder);
        SINK.pinned = true; // retention sees this immediately, even during writer startup
        Files.writeString(SINK.folder.resolve("cf-skeleton-ai-"+SINK.stamp+".keep"),
                "Pinned by soak run: "+runDir.toAbsolutePath()+"\n");
        // The writer publishes the segment number and logical byte offset as
        // ONE volatile 64-bit value. This cannot sample mismatched rotation state.
        long startPosition=SINK.writerPosition;
        SINK.captureStartPart=Math.max(1,(int)(startPosition >>> 32));
        SINK.captureStartBytes=startPosition & 0xFFFF_FFFFL;
        SINK.captureStartDropped=SINK.dropped.get();
        Files.writeString(runDir.resolve("ai-capture.properties"),
                "state=RECORDING\nprefix=cf-skeleton-ai-"+SINK.stamp+"-part\nfirstPart="+
                SINK.captureStartPart+"\nfirstBytes="+SINK.captureStartBytes+"\n");
    }
    static void finishCapture(Path runDir) {
        final int first=SINK.captureStartPart;
        final long firstBytes=SINK.captureStartBytes;
        final long droppedAtStart=SINK.captureStartDropped;
        Thread.ofVirtual().name("cf-ai-capture-flush").start(()->{
            boolean flushed=false;
            CountDownLatch done=new CountDownLatch(1);
            long[] snapshot=new long[4];
            try {
                if(SINK.queue.offer(new Flush(done,snapshot),10,TimeUnit.SECONDS))
                    flushed=done.await(15,TimeUnit.SECONDS);
                long dropped=(flushed?snapshot[3]:SINK.dropped.get())-droppedAtStart;
                String text="state="+(flushed && "none".equals(SINK.error)?"FLUSHED":"INCOMPLETE")+
                        "\nprefix=cf-skeleton-ai-"+SINK.stamp+"-part\nfirstPart="+first+
                        "\nfirstBytes="+firstBytes+
                        "\nlastPart="+(flushed?snapshot[0]:SINK.part)+"\nlastBytes="+snapshot[1]+"\ndropped="+dropped+"\nwriteError="+SINK.error+
                        "\ncoverage=per-run-byte-range; no reused prefix from previous block\n";
                Path tmp=runDir.resolve("ai-capture.properties.tmp");
                Files.writeString(tmp,text);
                Files.move(tmp,runDir.resolve("ai-capture.properties"),java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }catch(Exception e){System.err.println("[CF AI] Capture flush failed: "+e);}
        });
    }

    static long droppedCount(){return SINK.dropped.get();}
    static String writerError(){return SINK.error;}
    static String status() {
        return "[CF AI] log=" + SINK.lastPath + " queue=" + SINK.queue.size() +
                " written=" + SINK.written.get() + " dropped=" + SINK.dropped.get() +
                " error=" + SINK.error + " sampleTicks=" + SAMPLE_TICKS;
    }

    static void systemEvent(String type, Object... pairs) {
        StringBuilder out = new StringBuilder(256);
        out.append("{\"timestampMs\":").append(System.currentTimeMillis());
        out.append(",\"ns\":").append(System.nanoTime());
        out.append(",\"unit\":-1,\"event\":"); quote(out, type);
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            out.append(','); quote(out, String.valueOf(pairs[i])); out.append(':');
            appendValue(out, pairs[i+1]);
        }
        out.append('}'); SINK.offer(out.toString());
    }

    static void event(CFUnit u, String type, Object... pairs) {
        if (!u.isTowerUnit()) return;
        StringBuilder out = new StringBuilder(380);
        appendPrefix(out, u, type);
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            out.append(',');
            quote(out, String.valueOf(pairs[i]));
            out.append(':');
            appendValue(out, pairs[i + 1]);
        }
        out.append('}');
        SINK.offer(out.toString());
    }

    static boolean sample(CFUnit u, double distanceMoved) {
        int interval = CFSoakTest.highLoadSampling() ? 20 : SAMPLE_TICKS;
        if (!u.isTowerUnit() || u.getInstance() == null ||
                Math.floorMod(u.getAliveTicks() + (long) u.getEntityId(), interval) != 0) return false;
        Pos p = u.getPosition();
        Navigator nav = u.getNavigator();
        Entity t = u.traceTarget();
        Instance world = u.getInstance();
        String below, feet;
        try {
            below = world.getBlock(p.blockX(), p.blockY() - 1, p.blockZ()).name();
            feet = world.getBlock(p.blockX(), p.blockY(), p.blockZ()).name();
        } catch (Exception e) { below = "UNAVAILABLE"; feet = "UNAVAILABLE"; }
        event(u, "AI_SNAPSHOT",
                "decision", u.isRangedLocked() ? "HOLD_STILL_SHOOT" : CFTrace.goal(u),
                "x", p.x(), "y", p.y(), "z", p.z(),
                "yaw", p.yaw(), "onGround", u.isOnGround(),
                "dxzSinceLastSample", distanceMoved,
                "vx", u.getVelocity().x(), "vy", u.getVelocity().y(), "vz", u.getVelocity().z(),
                "floor", below, "feet", feet,
                "target", t == null ? -1 : t.getEntityId(),
                "targetHp", t instanceof CFUnit enemy ? enemy.getHealth() : null,
                "targetTeam", t instanceof CFUnit enemy ? enemy.team() : null,
                "targetDistance", t == null ? -1 : u.getDistance(t),
                "targetPolicy", u.targeting().brain.ranged() ? "RANGED_DEATH_OR_RADIUS" : "MELEE_PERIODIC",
                "targetFullScans", u.targeting().brain.scans(),
                "targetCacheHits", u.targeting().brain.cacheHits(),
                "targetNextReviewTick", u.targeting().brain.ranged() ? -1 : u.targeting().brain.nextReviewTick(),
                "targetInbound", u.targeting().brain.links.attackers(),
                "targetLineOfSight", t != null && u.hasLineOfSight(t),
                "navState", nav.getState().name(),
                "navGoal", CFTrace.xyz(nav.getGoalPosition()),
                "navNextNode", CFTrace.xyz(nav.getPathPosition()),
                "navNodes", nav.getNodes() == null ? 0 : nav.getNodes().size(),
                "marchNext", u.marchGoal().checkpointIndex() + 1,
                "marchTotal", u.marchGoal().checkpointCount(),
                "marchGoal", CFTrace.xyz(u.marchGoal().checkpoint()),
                "marchFinished", u.marchGoal().routeFinished(),
                "roadWidth", u.marchGoal().laneWidth(),
                "marchMode", u.marchGoal().movementMode(),
                "roadSide", u.marchGoal().lateralPosition(),
                "roadProgress", u.marchGoal().roadProgress(),
                "hp", u.getHealth());
        return true;
    }

    private static void appendPrefix(StringBuilder out, CFUnit u, String event) {
        out.append('{');
        out.append("\"timestampMs\":").append(System.currentTimeMillis());
        out.append(",\"ns\":").append(System.nanoTime());
        out.append(",\"event\":"); quote(out, event);
        out.append(",\"unit\":").append(u.getEntityId());
        out.append(",\"team\":"); quote(out, u.team());
        out.append(",\"mobType\":"); quote(out, u.contentId());
        out.append(",\"behavior\":"); quote(out, u.behaviorId());
        out.append(",\"assignedLane\":").append(u.laneId());
        out.append(",\"ageTicks\":").append(u.getAliveTicks());
    }

    private static void appendValue(StringBuilder b, Object v) {
        if (v == null) b.append("null");
        else if (v instanceof Number n && Double.isFinite(n.doubleValue())) b.append(v);
        else if (v instanceof Boolean bool) b.append(bool);
        else quote(b, String.valueOf(v));
    }

    private static void quote(StringBuilder b, String s) {
        b.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                default -> { if (c < 0x20) b.append(' '); else b.append(c); }
            }
        }
        b.append('"');
    }

    private static final class Sink implements Runnable {
        private final ArrayBlockingQueue<Object> queue = new ArrayBlockingQueue<>(QUEUE_SIZE);
        private final AtomicLong dropped = new AtomicLong();
        private final AtomicLong written = new AtomicLong();
        private final String stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS")
                .withZone(ZoneOffset.UTC).format(Instant.now());
        private final Path folder = Path.of("diagnostics", "skeleton-ai");
        private volatile String lastPath = "initializing";
        private volatile String error = "none";
        private volatile boolean closing;
        private volatile int part = 0;
        private volatile boolean pinned;
        private int captureStartPart;
        private long captureStartBytes;
        private long captureStartDropped;
        private final Thread thread;
        private BufferedWriter writer;
        private long bytes; // only accessed by the AI writer
        private volatile long writerPosition; // packed: upper32=part, lower32=byte offset

        Sink() {
            thread = Thread.ofPlatform().name("cf-skeleton-ai-writer").daemon(true).unstarted(this);
            thread.start();
            Runtime.getRuntime().addShutdownHook(Thread.ofPlatform()
                    .name("cf-skeleton-ai-flush").unstarted(() -> {
                        closing = true;
                        try { thread.join(10_000); }
                        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                    }));
        }

        void offer(String line) {
            if (closing || !queue.offer(line)) dropped.incrementAndGet();
        }

        @Override
        public void run() {
            try {
                Files.createDirectories(folder);
                nextPart();
                long lastFlush = System.nanoTime();
                while (!closing || !queue.isEmpty()) {
                    Object entry = queue.poll(200, TimeUnit.MILLISECONDS);
                    if (entry instanceof Flush flush) {
                        writer.flush();flush.snapshot()[0]=part;flush.snapshot()[1]=bytes;
                        flush.snapshot()[2]=written.get();flush.snapshot()[3]=dropped.get();
                        flush.done().countDown();continue;
                    }
                    if (entry instanceof String line) {
                        writer.write(line);
                        writer.newLine();
                        bytes += line.getBytes(StandardCharsets.UTF_8).length + 1L;
                        writerPosition=((long)part<<32)|(bytes & 0xFFFF_FFFFL);
                        written.incrementAndGet();
                        if (bytes >= ROTATE_BYTES) nextPart();
                    }
                    long now = System.nanoTime();
                    if (now - lastFlush >= 500_000_000L) {
                        writer.flush();
                        lastFlush = now;
                    }
                }
                writer.flush();
            } catch (Exception e) {
                error = e.toString();
                System.err.println("[CF AI] Brain log write failed: " + error);
            } finally {
                try { if (writer != null) writer.close(); } catch (IOException ignored) { }
            }
        }

        private static long mtime(Path p) {
            try { return Files.getLastModifiedTime(p).toMillis(); } catch(IOException e) { return 0; }
        }

        private void nextPart() throws IOException {
            if (writer != null) { writer.flush(); writer.close(); }
            part++;
            // Bound the recorder across sessions, not just within a single run.
            try (var stream = Files.list(folder)) {
                var old = stream.filter(p -> p.getFileName().toString().matches("cf-skeleton-ai-.*-part[0-9]+\\.jsonl"))
                        .sorted(java.util.Comparator.comparingLong(CFSkeletonMindLog.Sink::mtime).reversed()).toList();
                for (int i = KEEP_PARTS - 1; i < old.size(); i++) {
                    Path candidate=old.get(i);
                    String name=candidate.getFileName().toString();
                    String owner=name.substring(0,name.lastIndexOf("-part"));
                    if(pinned && owner.equals("cf-skeleton-ai-"+stamp))continue;
                    if(Files.exists(folder.resolve(owner+".keep")))continue;
                    Files.deleteIfExists(candidate);
                }
            }
            Path p = folder.resolve("cf-skeleton-ai-" + stamp + "-part" + part + ".jsonl");
            writer = Files.newBufferedWriter(p, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            lastPath = p.toString();
            bytes = 0;
            writerPosition=((long)part<<32);
            System.out.println("[CF AI] Recording tower skeleton decisions -> " + lastPath);
        }
    }
}
