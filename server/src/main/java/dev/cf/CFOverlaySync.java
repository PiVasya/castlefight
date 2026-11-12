package dev.cf;

import dev.cf.protocol.CFOverlayWire;
import net.minestom.server.MinecraftServer;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.entity.Player;
import net.minestom.server.event.player.PlayerLoadedEvent;
import net.minestom.server.event.player.PlayerDisconnectEvent;
import net.minestom.server.instance.InstanceContainer;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * One map -> one snapshot -> one push per player connection.
 * There is NO C2S "G" packet, NO per-key network traffic and NO disk cache.
 * The client draws the exact authoritative carpet cells and compiled routes.
 */
final class CFOverlaySync {
    private final InstanceContainer world;
    private final CFGameArena arena;
    private final Set<UUID> readyPlayers = ConcurrentHashMap.newKeySet();
    private final Set<UUID> sent = ConcurrentHashMap.newKeySet();
    private volatile List<byte[]> payloadFrames;
    private final String mapId;

    CFOverlaySync(InstanceContainer world, CFGameArena arena) {
        this.world = world;
        this.arena = arena;
        this.mapId = System.getProperty("cf.map.id", "");
        var events = MinecraftServer.getGlobalEventHandler();
        // Loaded means the client is already in PLAY mode. This avoids an
        // overly early custom payload from the initial configuration phase.
        events.addListener(PlayerLoadedEvent.class, event -> {
            Player player = event.getPlayer();
            if (player.getInstance() != world) return;
            readyPlayers.add(player.getUuid());
            sendIfReady(player);
        });
        events.addListener(PlayerDisconnectEvent.class, event -> {
            UUID id = event.getPlayer().getUuid();
            readyPlayers.remove(id);
            sent.remove(id);
        });
    }

    void start() {
        CompletableFuture<Void> ready = arena.routesReadyFuture();
        ready.whenComplete((ok, error) -> world.scheduler().scheduleNextTick(() -> {
            if (error != null) {
                System.err.println("[КФ ОВЕРЛЕЙ] Геометрия не отправлена: ошибка компиляции карты: " + error);
                return;
            }
            try {
                var cells = new ArrayList<CFOverlayWire.Cell>();
                var marks = arena.carpetSnapshot();
                var byState = new HashMap<Integer,Integer>();
                for (int i = 0; i < CFMarkerPalette.ALL.size(); i++)
                    byState.put(CFMarkerPalette.ALL.get(i).block().stateId(), i);
                for (var entry : marks.entrySet()) {
                    var c = entry.getKey();
                    int color = byState.get(entry.getValue().block().stateId());
                    cells.add(new CFOverlayWire.Cell(c.x(), c.y(), c.z(), color));
                }
                cells.sort(Comparator.comparingInt(CFOverlayWire.Cell::y)
                        .thenComparingInt(CFOverlayWire.Cell::x)
                        .thenComparingInt(CFOverlayWire.Cell::z));
                var paths = new ArrayList<CFOverlayWire.Route>();
                for (var entry : new TreeMap<>(arena.pathSnapshot()).entrySet()) {
                    List<CFOverlayWire.Point> nodes = new ArrayList<>();
                    // Only BLUE is transmitted: RED is the same route reversed.
                    for (Pos p : entry.getValue().blue)
                        nodes.add(new CFOverlayWire.Point((float)p.x(), (float)p.y(), (float)p.z()));
                    paths.add(new CFOverlayWire.Route(entry.getKey(), nodes));
                }
                var geometry = new CFOverlayWire.Geometry(mapId, cells, paths);
                // Compress and fragment on a virtual thread, not in the game tick.
                Thread.ofVirtual().name("cf-overlay-encode").start(() -> {
                    try {
                        byte[] compressed = CFOverlayWire.pack(geometry);
                        var frames = CFOverlayWire.fragment(compressed, new Random().nextInt());
                        world.scheduler().scheduleNextTick(() -> {
                            payloadFrames = frames;
                            System.out.println("[КФ ОВЕРЛЕЙ] Разметка готова: карта="+mapId+
                                    ", клеток="+cells.size()+", путей="+paths.size()+
                                    ", сжато="+compressed.length+" байт, пакетов="+frames.size());
                            for (Player player : MinecraftServer.getConnectionManager().getOnlinePlayers())
                                sendIfReady(player);
                        });
                    } catch (Exception e) {
                        System.err.println("[КФ ОВЕРЛЕЙ] Ошибка упаковки разметки: " + e);
                    }
                });
            } catch (Exception e) {
                System.err.println("[КФ ОВЕРЛЕЙ] Ошибка формирования разметки: " + e);
            }
        }));
    }

    private void sendIfReady(Player player) {
        var frames = payloadFrames;
        if (frames == null || player.getInstance() != world ||
                !readyPlayers.contains(player.getUuid()) || !sent.add(player.getUuid())) return;
        for (byte[] bytes : frames) player.sendPluginMessage(CFOverlayWire.CHANNEL, bytes);
        System.out.println("[КФ ОВЕРЛЕЙ] Разметка отправлена игроку "+player.getUsername()+
                " однократно; клавиша G не требует сети.");
    }
}
