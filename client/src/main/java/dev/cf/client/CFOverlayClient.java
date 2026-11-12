package dev.cf.client;

import dev.cf.client.render.CFOverlayMesh;
import dev.cf.client.render.CFOverlayRenderer;
import dev.cf.client.render.CFMobHud;
import dev.cf.protocol.CFMobStatsWire;
import dev.cf.protocol.CFOverlayWire;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.client.KeyMapping;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import com.mojang.blaze3d.platform.InputConstants;

/**
 * Castle Fight client-only overlay. The whole current-map snapshot is kept
 * in RAM and is discarded on disconnect. Absolutely no request is sent by G.
 */
public final class CFOverlayClient implements ClientModInitializer {
    private static final CFOverlayWire.Receiver RECEIVER = new CFOverlayWire.Receiver();
    private static CFOverlayMesh mesh;
    private static boolean visible;

    public static CFOverlayMesh currentMesh() { return visible ? mesh : null; }

    @Override public void onInitializeClient() {
        // Clientbound payload must be registered before the PLAY connection.
        PayloadTypeRegistry.clientboundPlay().register(CFOverlayPayload.TYPE, CFOverlayPayload.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(CFMobStatsRequestPayload.TYPE, CFMobStatsRequestPayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(CFMobStatsResponsePayload.TYPE, CFMobStatsResponsePayload.CODEC);
        ClientPlayNetworking.registerGlobalReceiver(CFMobStatsResponsePayload.TYPE, (payload, context) -> {
            try {
                CFMobStatsWire.Reply reply = CFMobStatsWire.readReply(payload.bytes());
                context.client().execute(() -> CFMobHover.accept(reply));
            } catch (java.io.IOException exception) {
                System.err.println("[КФ] Неверные характеристики моба: " + exception.getMessage());
            }
        });
        ClientPlayNetworking.registerGlobalReceiver(CFOverlayPayload.TYPE, (payload, context) -> {
            // Decode and update the overlay on the client thread, not Netty.
            context.client().execute(() -> {
                try {
                    var ready = RECEIVER.accept(payload.bytes());
                    if (ready != null) {
                        mesh = CFOverlayMesh.build(ready);
                        if (context.client().player != null)
                            context.client().player.sendSystemMessage(
                                    Component.literal("[КФ] Разметка карты «" + ready.mapId() +
                                            "» получена. Нажми G."));
                    }
                } catch (Exception e) {
                    RECEIVER.reset(); mesh = null; visible = false;
                    System.err.println("[КФ КЛИЕНТ] Некорректные данные разметки: " + e);
                }
            });
        });

        KeyMapping.Category category = KeyMapping.Category.register(
                Identifier.fromNamespaceAndPath("castlefight", "controls"));
        KeyMapping key = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "key.castlefight.toggle_overlay", InputConstants.Type.KEYSYM,
                InputConstants.KEY_G, category));
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            CFMobHover.tick(client);
            while (key.consumeClick()) {
                if (client.player == null) continue;
                if (mesh == null) {
                    client.gui.hud.setOverlayMessage(Component.literal(
                            "[КФ] Разметка ещё загружается или сервер её не прислал."), false);
                } else {
                    visible = !visible;
                    client.gui.hud.setOverlayMessage(Component.literal(
                            visible ? "[КФ] Зоны и маршруты: ВКЛ" : "[КФ] Зоны и маршруты: ВЫКЛ"), false);
                }
            }
        });
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> clear());
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> clear());
        CFOverlayRenderer.register();
        CFMobHud.register();
    }

    private static void clear() {
        RECEIVER.reset(); mesh=null; visible=false;
        CFMobHover.clear();
    }
}
