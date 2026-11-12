package dev.cf;

import dev.cf.protocol.CFShotWire;
import net.minestom.server.MinecraftServer;
import net.minestom.server.entity.Player;
import net.minestom.server.event.player.PlayerDisconnectEvent;
import net.minestom.server.event.player.PlayerLoadedEvent;
import net.minestom.server.event.player.PlayerPluginMessageEvent;
import net.minestom.server.instance.Instance;
import net.minestom.server.instance.InstanceContainer;

import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Registers clients that support Fabric-only arrows.
 * Unmodded clients receive no arrow graphics; their battles still work server-side.
 */
final class CFShotSync {
    private static volatile CFShotSync active;
    private final InstanceContainer world;
    private final Set<UUID> visualClients = ConcurrentHashMap.newKeySet();

    CFShotSync(InstanceContainer world) {
        this.world = world;
        active = this;
        var events = MinecraftServer.getGlobalEventHandler();
        events.addListener(PlayerLoadedEvent.class, event -> {
            Player player = event.getPlayer();
            if (player.getInstance() == world)
                player.sendPluginMessage("minecraft:register",
                        CFShotWire.HELLO_CHANNEL.getBytes(StandardCharsets.UTF_8));
        });
        events.addListener(PlayerPluginMessageEvent.class, event -> {
            if (!CFShotWire.HELLO_CHANNEL.equals(event.getIdentifier())) return;
            Player player = event.getPlayer();
            if (player.getInstance() != world) return;
            if (CFShotWire.validHello(event.getMessage())) {
                if (visualClients.add(player.getUuid()))
                    System.out.println("[КФ СТРЕЛЫ] Fabric-визуализация включена: " + player.getUsername());
            }
        });
        events.addListener(PlayerDisconnectEvent.class,
                event -> visualClients.remove(event.getPlayer().getUuid()));
    }

    static boolean clientSupportsVisuals(Player player, Instance world) {
        CFShotSync sync = active;
        return sync != null && sync.world == world && sync.visualClients.contains(player.getUuid());
    }
}
