package dev.cf.client;

import dev.cf.protocol.CFMobStatsWire;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.EntityHitResult;

/**
 * Только выбор моба под прицелом. В отличие от карты, статы меняются в бою.
 * При смене цели один короткий запрос ID; пока прицел на той же цели,
 * сервер сам присылает новые значения HP при их изменении.
 */
public final class CFMobHover {
    private static int aimedId = -1;
    private static CFMobStatsWire.Stats shown;
    private static int retryTicks;
    private static int requestsForTarget;
    private CFMobHover() { }

    public static CFMobStatsWire.Stats current() { return shown; }

    public static void tick(Minecraft client) {
        int target = -1;
        if (client.player != null && client.level != null && client.gui.screen() == null
                && client.hitResult instanceof EntityHitResult hit) {
            target = hit.getEntity().getId();
        }
        if (target != aimedId) {
            aimedId = target;
            shown = null;
            retryTicks = 0;
            requestsForTarget = 0;
            send(target);
        } else if (target >= 0 && shown == null && requestsForTarget < 3 && ++retryTicks >= 10) {
            // A dropped request may be retried twice, never spam a non-CF server.
            retryTicks = 0;
            send(target);
        }
    }

    private static void send(int target) {
        if (Minecraft.getInstance().getConnection() == null) return;
        if (target >= 0) requestsForTarget++;
        try { ClientPlayNetworking.send(new CFMobStatsRequestPayload(target)); }
        catch (RuntimeException ignored) { /* Non-CF server: no hover stats. */ }
    }

    public static void accept(CFMobStatsWire.Reply reply) {
        if (reply.entityId() != aimedId) return; // ignore delayed replies from another mob
        shown = reply.stats();
    }

    public static void clear() { aimedId = -1; shown = null; retryTicks = 0; requestsForTarget = 0; }
}
