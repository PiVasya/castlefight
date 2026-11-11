package dev.cf;

import net.minestom.server.MinecraftServer;
import net.minestom.server.command.CommandSender;
import net.minestom.server.command.builder.Command;
import net.minestom.server.command.builder.arguments.ArgumentType;
import net.minestom.server.entity.Player;
import net.minestom.server.instance.Chunk;
import net.minestom.server.instance.ChunkLoader;
import net.minestom.server.instance.Instance;
import net.minestom.server.instance.InstanceContainer;
import net.minestom.server.instance.anvil.AnvilLoader;
import net.minestom.server.timer.TaskSchedule;
import net.kyori.adventure.key.Key;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Two explicit modes for a hand-built, persistent arena.
 * BUILD: writable AnvilLoader, saves world + editor layout on demand and periodically.
 * PLAY: read-only ChunkLoader; battle block changes and tower cubes never reach the map files.
 * Mode changes are applied after restart, so no PLAY changes can accidentally be saved in BUILD.
 */
final class CFWorldMode {
    enum Mode { BUILD, PLAY }

    private static final DateTimeFormatter BACKUP_DATE =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final int BUILD_AUTOSAVE_SECONDS = 120;

    private final CFCarpetZones carpets = new CFCarpetZones();
    CFCarpetZones carpets() { return carpets; }
    private final Path mapRoot;
    private final Path modeFile;
    private final Mode active;
    private volatile Mode next;
    private InstanceContainer world;
    private CFSingleLaneTool laneTool;
    private final AtomicBoolean gracefulStopSaved = new AtomicBoolean(false);
    private volatile CompletableFuture<Void> inFlight;
    private volatile long lastSavedAtMillis;
    private volatile String lastError = "none";
    private boolean firstSaveBackupDone;
    private Path backupLocation;

    CFWorldMode(Path mapRoot) {
        this.mapRoot = mapRoot.toAbsolutePath().normalize();
        String id = mapRoot.getFileName().toString().replaceAll("[^a-zA-Z0-9_-]", "_");
        this.modeFile = Path.of("navigation", id + "-server-mode.properties");
        // Game-only release cannot enable writable BUILD storage, even when a stale
        // mode file asks for it. The release launcher explicitly enables this flag.
        this.active = Boolean.getBoolean("cf.play.only") ? Mode.PLAY : loadMode();
        this.next = active;
        System.out.println("[CF SAVE] Active world mode=" + active + " map=" + this.mapRoot);
    }

    boolean isBuild() { return active == Mode.BUILD; }

    ChunkLoader loader() {
        AnvilLoader anvil = new AnvilLoader(mapRoot, Key.key("minecraft:overworld"));
        return isBuild() ? anvil : new ReadOnlyLoader(anvil, carpets);
    }

    void attach(InstanceContainer world, CFSingleLaneTool laneTool) {
        this.world = world;
        this.laneTool = laneTool;
    }

    void start() {
        if (!isBuild()) {
            System.out.println("[CF SAVE] PLAY: map storage is read-only. No autosave tasks scheduled.");
            return;
        }
        world.scheduler().buildTask(() -> saveBuild(null, "autosave"))
                .delay(TaskSchedule.seconds(BUILD_AUTOSAVE_SECONDS))
                .repeat(TaskSchedule.seconds(BUILD_AUTOSAVE_SECONDS)).schedule();
        System.out.println("[CF SAVE] BUILD: autosave every " + BUILD_AUTOSAVE_SECONDS +
                "s, /cf save, console stop, and SIGTERM shutdown hook.");
    }

    void register(Command cf) {
        if (Boolean.getBoolean("cf.play.only")) {
            cf.addSyntax((sender, ctx) -> status(sender),
                    ArgumentType.Literal("mode"), ArgumentType.Literal("status"));
            return; // No commands capable of selecting BUILD or saving the world.
        }
        cf.addSyntax((sender, ctx) -> status(sender),
                ArgumentType.Literal("mode"), ArgumentType.Literal("status"));
        cf.addSyntax((sender, ctx) -> status(sender),
                ArgumentType.Literal("save"), ArgumentType.Literal("status"));

        var modeArg = ArgumentType.Word("world-mode").from("build", "play");
        cf.addSyntax((sender, ctx) -> {
            if (!isAdmin(sender)) return;
            Mode requested = Mode.valueOf(ctx.get(modeArg).toUpperCase(Locale.ROOT));
            if (requested == active) {
                // Also cancel a previously staged mode change.
                try {
                    writeMode(requested);
                    sender.sendMessage("[CF] " + active + " remains active on next start.");
                } catch (IOException e) {
                    sender.sendMessage("[CF SAVE] Cannot update mode setting: " + e.getMessage());
                }
                return;
            }
            if (active == Mode.BUILD) {
                sender.sendMessage("[CF SAVE] Saving BUILD map before scheduling PLAY...");
                laneTool.validateForPlay(sender).thenCompose(ignored -> saveBuild(sender, "switch to PLAY"))
                        .whenComplete((ignored, failure) -> {
                    if (failure != null) {
                        sender.sendMessage("[CF SAVE] PLAY switch CANCELLED: route validation/save failed. " + rootMessage(failure));
                        return;
                    }
                    try {
                        writeMode(requested);
                        sender.sendMessage("[CF] PLAY selected for NEXT START. Restart the server.");
                        sender.sendMessage("[CF] BUILD is still active until restart.");
                    } catch (IOException e) {
                        sender.sendMessage("[CF SAVE] Cannot select PLAY: " + e.getMessage());
                    }
                });
            } else {
                try {
                    writeMode(requested);
                    sender.sendMessage("[CF] BUILD selected for NEXT START. Restart the server.");
                    sender.sendMessage("[CF] PLAY blocks/towers are still temporary until restart.");
                } catch (IOException e) {
                    sender.sendMessage("[CF SAVE] Cannot select BUILD: " + e.getMessage());
                }
            }
        }, ArgumentType.Literal("mode"), modeArg);

        cf.addSyntax((sender, ctx) -> {
            if (!isAdmin(sender)) return;
            if (!isBuild()) {
                sender.sendMessage("[CF SAVE] PLAY is read-only. Switch to BUILD and restart first.");
                return;
            }
            sender.sendMessage("[CF SAVE] Saving loaded world chunks and three-lane castle settings...");
            saveBuild(sender, "manual");
        }, ArgumentType.Literal("save"));
    }

    private boolean isAdmin(CommandSender sender) {
        if (sender instanceof Player p && p.getPermissionLevel() < 4) {
            sender.sendMessage("[CF] Operator permission required. Use console: op <nick>");
            return false;
        }
        return true; // server console
    }

    private Mode loadMode() {
        if (!Files.exists(modeFile)) {
            System.out.println("[CF SAVE] No mode file: defaulting to BUILD for safe map editing.");
            return Mode.BUILD;
        }
        Properties p = new Properties();
        try (InputStream in = Files.newInputStream(modeFile)) {
            p.load(in);
            return Mode.valueOf(p.getProperty("mode", "").trim().toUpperCase(Locale.ROOT));
        } catch (Exception e) {
            // Fail closed: a damaged mode file must never enable writable world storage.
            throw new IllegalStateException("Invalid mode file " + modeFile +
                    ". Restore mode=BUILD or mode=PLAY explicitly before starting.", e);
        }
    }

    private synchronized void writeMode(Mode mode) throws IOException {
        Properties props = new Properties();
        props.setProperty("mode", mode.name());
        props.setProperty("map", mapRoot.getFileName().toString());
        Files.createDirectories(modeFile.getParent());
        Path tmp = modeFile.resolveSibling(modeFile.getFileName() + ".tmp");
        try (OutputStream out = Files.newOutputStream(tmp)) {
            props.store(out, "Castle Fight world mode, takes effect after restart");
        }
        try {
            Files.move(tmp, modeFile, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, modeFile, StandardCopyOption.REPLACE_EXISTING);
        }
        next = mode;
    }

    synchronized CompletableFuture<Void> saveBuild(CommandSender sender, String reason) {
        if (!isBuild()) {
            return CompletableFuture.failedFuture(new IllegalStateException("PLAY is read-only"));
        }
        // Do not persist a half-finished multi-tick WorldEdit or an active route check.
        if (laneTool != null && laneTool.editorBusy()) {
            String msg = "BUILD editor/route check is active; save postponed. Retry after it finishes.";
            if (sender != null) sender.sendMessage("[CF SAVE] " + msg);
            if ("autosave".equals(reason)) {
                System.out.println("[CF SAVE] autosave deferred: editor/route check active");
                return CompletableFuture.completedFuture(null);
            }
            System.err.println("[CF SAVE] " + msg);
            return CompletableFuture.failedFuture(new IllegalStateException(msg));
        }
        if (inFlight != null && !inFlight.isDone()) {
            if (sender != null) sender.sendMessage("[CF SAVE] A save is already running; waiting for it.");
            return inFlight;
        }
        try {
            backupBeforeFirstSave();
            laneTool.saveLayout(); // atomic .tmp replacement; never includes match towers
            // Only save loaded chunks. Do NOT overwrite vanilla level.dat: the
            // current server attaches AnvilLoader after container construction,
            // so global Anvil tags have not been loaded into the instance.
            inFlight = world.saveChunksToStorage();
            CompletableFuture<Void> current = inFlight;
            current.whenComplete((ignored, failed) -> {
                if (failed == null) {
                    lastSavedAtMillis = System.currentTimeMillis();
                    lastError = "none";
                    System.out.println("[CF SAVE] " + reason + " COMPLETE (world + three-lane settings). " + mapRoot);
                    if (sender != null) sender.sendMessage("[CF SAVE] SUCCESS: map, carpets and castle settings saved.");
                } else {
                    lastError = rootMessage(failed);
                    System.err.println("[CF SAVE] " + reason + " FAILED: " + lastError);
                    if (sender != null) sender.sendMessage("[CF SAVE] ERROR: " + lastError);
                }
            });
            return current;
        } catch (Exception e) {
            lastError = rootMessage(e);
            System.err.println("[CF SAVE] " + reason + " FAILED: " + lastError);
            if (sender != null) sender.sendMessage("[CF SAVE] ERROR: " + lastError);
            return CompletableFuture.failedFuture(e);
        }
    }

    boolean saveOnConsoleStop() {
        if (!isBuild()) return true;
        try {
            // Console thread stays alive while scheduled WorldEdit batches finish.
            laneTool.whenWorldEditIdle().get(30, java.util.concurrent.TimeUnit.SECONDS);
            synchronized (this) {
                if (inFlight != null) inFlight.join();
            }
            saveBuild(null, "console stop").join();
            gracefulStopSaved.set(true);
            return true;
        } catch (Exception e) {
            System.err.println("[CF SAVE] Stop ABORTED; map save failed: " + rootMessage(e));
            return false;
        }
    }

    void saveOnShutdownHook() {
        if (!isBuild() || gracefulStopSaved.get()) return;
        try {
            laneTool.whenWorldEditIdle().get(10, java.util.concurrent.TimeUnit.SECONDS);
            synchronized (this) {
                if (inFlight != null) inFlight.join();
            }
            saveBuild(null, "JVM shutdown").join();
        } catch (Exception e) {
            System.err.println("[CF SAVE] Shutdown save failed: " + rootMessage(e));
        }
    }

    private void status(CommandSender sender) {
        sender.sendMessage("[CF SAVE] Active=" + active + ", next startup=" + next +
                (active == next ? "" : " (RESTART REQUIRED)"));
        sender.sendMessage("[CF SAVE] World writes=" + (isBuild() ? "ENABLED" : "DISABLED") +
                ", autosave=" + (isBuild() ? BUILD_AUTOSAVE_SECONDS + "s" : "OFF") +
                ", saving=" + (inFlight != null && !inFlight.isDone()));
        sender.sendMessage("[CF SAVE] Last success=" + (lastSavedAtMillis == 0 ? "not yet" :
                java.time.Instant.ofEpochMilli(lastSavedAtMillis)) + ", last error=" + lastError);
        if (backupLocation != null) sender.sendMessage("[CF SAVE] Backup=" + backupLocation);
    }

    private void backupBeforeFirstSave() throws IOException {
        if (firstSaveBackupDone) return;
        Path parent = Path.of("backups", "cf-build-pre-save-" +
                LocalDateTime.now().format(BACKUP_DATE) + "-" + ProcessHandle.current().pid());
        Files.createDirectories(parent);
        Path destination = parent.resolve("map");
        // The on-disk map is backed up before its first save of this BUILD session.
        try (var stream = Files.walk(mapRoot)) {
            for (Path source : stream.toList()) {
                Path target = destination.resolve(mapRoot.relativize(source));
                if (Files.isDirectory(source)) Files.createDirectories(target);
                else {
                    Files.createDirectories(target.getParent());
                    Files.copy(source, target, StandardCopyOption.COPY_ATTRIBUTES,
                            StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
        Path laneFile = laneTool.layoutFile();
        if (Files.isRegularFile(laneFile))
            Files.copy(laneFile, parent.resolve("lanes-v2.properties"), StandardCopyOption.REPLACE_EXISTING);
        Path castleFile = Path.of("castles-map.properties");
        if (Files.isRegularFile(castleFile))
            Files.copy(castleFile, parent.resolve("castles-map.properties"), StandardCopyOption.REPLACE_EXISTING);
        firstSaveBackupDone = true;
        backupLocation = parent;
        System.out.println("[CF SAVE] Pre-save backup created: " + parent);
    }

    private static String rootMessage(Throwable error) {
        Throwable t = error;
        while ((t instanceof CompletionException || t instanceof java.util.concurrent.ExecutionException)
                && t.getCause() != null) t = t.getCause();
        return t.getClass().getSimpleName() + ": " + t.getMessage();
    }

    /** PLAY reads .mca but refuses every chunk/instance write, even if called accidentally. */
    static final class ReadOnlyLoader implements ChunkLoader {
        private final AnvilLoader delegate;
        private final AtomicBoolean warned = new AtomicBoolean();
        private final CFCarpetZones carpets;
        ReadOnlyLoader(AnvilLoader delegate, CFCarpetZones carpets) { this.delegate = delegate; this.carpets = carpets; }
        @Override public void loadInstance(Instance instance) { delegate.loadInstance(instance); }
        @Override public Chunk loadChunk(Instance instance, int x, int z) {
            Chunk chunk = delegate.loadChunk(instance, x, z);
            carpets.preparePlayChunk(chunk);
            return chunk;
        }
        @Override public void saveChunk(Chunk chunk) { rejected(); }
        @Override public void saveInstance(Instance instance) { rejected(); }
        @Override public void saveChunks(java.util.Collection<Chunk> chunks) { rejected(); }
        @Override public void unloadChunk(Chunk chunk) {
            // Releases Anvil region file handles; it does not save chunk state.
            delegate.unloadChunk(chunk);
        }
        @Override public boolean supportsParallelLoading() { return delegate.supportsParallelLoading(); }
        @Override public boolean supportsParallelSaving() { return false; }
        private void rejected() {
            if (warned.compareAndSet(false, true))
                System.err.println("[CF SAVE] PLAY: ignored a world-storage write request.");
        }
    }
}
