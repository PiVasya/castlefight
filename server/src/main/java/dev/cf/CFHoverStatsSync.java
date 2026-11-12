package dev.cf;

import dev.cf.protocol.CFMobStatsWire;
import net.minestom.server.MinecraftServer;
import net.minestom.server.entity.Player;
import net.minestom.server.event.player.PlayerDisconnectEvent;
import net.minestom.server.event.player.PlayerPluginMessageEvent;
import net.minestom.server.event.player.PlayerLoadedEvent;
import java.nio.charset.StandardCharsets;
import net.minestom.server.event.server.ServerTickMonitorEvent;
import net.minestom.server.instance.InstanceContainer;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Выбор моба: клиент присылает только ID, сервер проверяет мир/дистанцию и
 * возвращает настоящие статы CFUnit. После выбора сервер поддерживает HP
 * актуальным (не чаще раза в 4 тика) без постоянных запросов с клиента.
 * Никаких сведений об обычных сущностях и чужих мирах не выдаём.
 */
final class CFHoverStatsSync {
    private static final double MAX_DISTANCE_SQUARED = 16.0 * 16.0;
    private static final int UPDATE_EVERY_TICKS = 4;
    private final InstanceContainer world;
    private final Map<UUID, Watched> active = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastRequest = new ConcurrentHashMap<>();
    private long ticks;

    private static final class Watched {
        final int entityId;
        byte[] lastPacket;
        Watched(int entityId) { this.entityId = entityId; }
    }

    CFHoverStatsSync(InstanceContainer world) {
        this.world = world;
        var events = MinecraftServer.getGlobalEventHandler();
        // Fabric clients recognise the standard plugin-channel advertisement even
        // though the server itself is Minestom, not Fabric.
        events.addListener(PlayerLoadedEvent.class, event -> {
            Player player = event.getPlayer();
            if (player.getInstance() == world) {
                player.sendPluginMessage("minecraft:register",
                        CFMobStatsWire.REQUEST_CHANNEL.getBytes(StandardCharsets.UTF_8));
            }
        });
        events.addListener(PlayerPluginMessageEvent.class, event -> {
            if (!CFMobStatsWire.REQUEST_CHANNEL.equals(event.getIdentifier())) return;
            Player player = event.getPlayer();
            if (player.getInstance() != world) return;
            int requested;
            try { requested = CFMobStatsWire.readQuery(event.getMessage()); }
            catch (IOException ignored) { return; }
            UUID id = player.getUuid();
            // Clear requests must ALWAYS win, even during a rate-limit window.
            // Otherwise a fast glance-away could leave the old mob subscribed.
            if (requested == -1) {
                active.remove(id);
                return;
            }
            // Rate-limit a noisy or malicious client. Legitimate aim changes are rare.
            long last = lastRequest.getOrDefault(id, Long.MIN_VALUE / 2);
            if (ticks - last < 2) return;
            lastRequest.put(id, ticks);
            Watched watch = new Watched(requested);
            active.put(id, watch);
            update(player, watch);
        });
        events.addListener(PlayerDisconnectEvent.class, event -> {
            UUID id = event.getPlayer().getUuid();
            active.remove(id);
            lastRequest.remove(id);
        });
        events.addListener(ServerTickMonitorEvent.class, event -> {
            if (++ticks % UPDATE_EVERY_TICKS != 0) return;
            for (Player player : world.getPlayers()) {
                Watched watch = active.get(player.getUuid());
                if (watch != null) update(player, watch);
            }
        });
    }

    private void update(Player player, Watched watch) {
        if (active.get(player.getUuid()) != watch) return;
        var entity = world.getEntityById(watch.entityId);
        if (!(entity instanceof CFUnit unit) || unit.isDead() || unit.isRemoved()
                || unit.getInstance() != world
                || player.getPosition().distanceSquared(unit.getPosition()) > MAX_DISTANCE_SQUARED) {
            active.remove(player.getUuid(), watch);
            sendClear(player, watch.entityId);
            return;
        }
        // Lane zero is reserved for the castle king (not an army unit).
        if ((unit.isCastleKing() ? unit.laneId()!=0 : unit.laneId()<1 || unit.laneId()>4)
                || unit.health10() < 0) {
            active.remove(player.getUuid(), watch);
            sendClear(player, watch.entityId);
            return;
        }
        var stats = new CFMobStatsWire.Stats(unit.getEntityId(), unit.displayName(),
                unit.team(), unit.laneId(), Math.max(0, unit.health10()), unit.maxHealth10(),
                unit.attackDamage10(), unit.normalHit().type.id, unit.armorType().id,
                unit.criticalDamage10(), unit.criticalChancePercent(),
                unit.movementSpeed(), unit.attackRange(), unit.attackCooldownMs());
        try {
            byte[] packet = CFMobStatsWire.write(new CFMobStatsWire.Reply(watch.entityId, stats));
            if (java.util.Arrays.equals(packet, watch.lastPacket)) return;
            watch.lastPacket = packet;
            player.sendPluginMessage(CFMobStatsWire.RESPONSE_CHANNEL, packet);
        } catch (IOException e) {
            System.err.println("[КФ] Ошибка передачи характеристик моба: " + e.getMessage());
            active.remove(player.getUuid(), watch);
        }
    }

    private static void sendClear(Player player, int entityId) {
        try { player.sendPluginMessage(CFMobStatsWire.RESPONSE_CHANNEL, CFMobStatsWire.clear(entityId)); }
        catch (IOException ignored) { }
    }
}
