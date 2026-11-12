package dev.cf;

import net.minestom.server.MinecraftServer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Locale;

/**
 * Unattended soak control over the local filesystem, polled once per second on the game thread.
 * Explicitly whitelists status, start-night, verify, stop, and never accepts arbitrary console commands.
 * All effects occur on the server tick thread, not in the external profiler process.
 */
final class CFNightControl {
    private static final Path ROOT = Path.of("diagnostics", "night-control");
    private static final Path REQUEST = ROOT.resolve("command.txt");
    private static int ticks;
    private CFNightControl() {}

    static void poll(CFGameArena tool) {
        if (++ticks % 20 != 0) return;
        if (!Files.isRegularFile(REQUEST) || Files.isSymbolicLink(REQUEST)) return;
        String raw;
        try {
            if (Files.size(REQUEST) > 256) {
                Files.deleteIfExists(REQUEST);
                return;
            }
            raw = Files.readString(REQUEST, StandardCharsets.UTF_8).trim();
            Files.deleteIfExists(REQUEST);
        } catch (IOException ex) {
            System.err.println("[CF NIGHT] Control read error: " + ex);
            return;
        }
        String[] args = raw.split("\\s+");
        if (args.length < 2 || !args[0].matches("[a-f0-9]{16,32}")) return;
        String id = args[0];
        boolean ok = false;
        String result;
        int players = MinecraftServer.getConnectionManager().getOnlinePlayers().size();
        try {
            String operation = args[1].toLowerCase(Locale.ROOT);
            switch (operation) {
                case "status" -> {
                    if (args.length != 2) throw new IllegalArgumentException("status syntax");
                    result = tool.consoleSoakStatus();
                    ok = true;
                }
                case "night" -> {
                    if (args.length != 4) throw new IllegalArgumentException("night syntax");
                    int rounds = Integer.parseInt(args[2]);
                    int seconds = Integer.parseInt(args[3]);
                    if (rounds < 10 || rounds > 100 || seconds < 30 || seconds > 900)
                        throw new IllegalArgumentException("night rounds/seconds out of bounds");
                    if (players != 0) throw new IllegalStateException("Players online: " + players);
                    if (CFSoakTest.isRunning()) throw new IllegalStateException("Soak already running");
                    result = tool.consoleSoakNight(rounds, seconds);
                    ok = result.contains("[CF SOAK] Started ");
                }
                case "burn" -> {
                    if (args.length != 4) throw new IllegalArgumentException("burn syntax");
                    int rounds = Integer.parseInt(args[2]);
                    int seconds = Integer.parseInt(args[3]);
                    if (rounds != 10 || seconds < 60 || seconds > 240)
                        throw new IllegalArgumentException("burn requires 10 rounds, 60..240 seconds");
                    if (players != 0) throw new IllegalStateException("Players online: " + players);
                    if (CFSoakTest.isRunning()) throw new IllegalStateException("Soak already running");
                    result = tool.consoleSoakBurn(rounds, seconds);
                    ok = result.contains("[CF SOAK] Started ");
                }
                case "verify" -> {
                    if (args.length != 2) throw new IllegalArgumentException("verify syntax");
                    if (players != 0) throw new IllegalStateException("Players online: " + players);
                    if (CFSoakTest.isRunning()) throw new IllegalStateException("Soak already running");
                    result = tool.consoleSoakVerify();
                    ok = result.contains("[CF SOAK] Started ");
                }
                case "stop" -> {
                    if (args.length != 2) throw new IllegalArgumentException("stop syntax");
                    result = tool.consoleSoakStop();
                    ok = true;
                }
                default -> throw new IllegalArgumentException("unsupported operation");
            }
        } catch (Exception ex) {
            result = ex.toString();
        }
        Path reply = ROOT.resolve("replies").resolve(id + ".txt");
        Path temporary = reply.resolveSibling(reply.getFileName() + ".tmp");
        try {
            if (Files.isSymbolicLink(reply) || Files.isSymbolicLink(temporary)) return;
            Files.createDirectories(reply.getParent());
            Path soakDir = CFSoakTest.lastDirectory();
            String payload = "ok=" + ok + "\nplayers=" + players + "\nsoak_running="
                    + CFSoakTest.isRunning() + "\nsoak_dir="
                    + (soakDir == null ? "" : soakDir.toString()) + "\nresult="
                    + result.replace('\n', ' ').replace('\r', ' ').substring(0, Math.min(1000,
                            result.replace('\n', ' ').replace('\r', ' ').length())) + "\n";
            Files.writeString(temporary, payload, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            try {
                Files.move(temporary, reply, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, reply);
            }
        } catch (IOException ex) {
            System.err.println("[CF NIGHT] Control response failed: " + ex);
            try { Files.deleteIfExists(temporary); } catch (IOException ignored) {}
        }
    }
}
