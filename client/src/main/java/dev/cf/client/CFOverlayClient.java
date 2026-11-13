package dev.cf.client;

import dev.cf.client.render.CFOverlayMesh;
import dev.cf.client.render.CFArrowRenderer;
import dev.cf.client.render.CFOverlayRenderer;
import dev.cf.client.render.CFMobHud;
import dev.cf.protocol.CFMobStatsWire;
import dev.cf.protocol.CFShotWire;
import dev.cf.protocol.CFBuildWire;
import dev.cf.protocol.CFOverlayWire;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
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
    private static CFOverlayWire.Geometry currentGeometry;
    private static boolean visible;
    private static boolean shotHelloSent;

    public static CFOverlayMesh currentMesh() { return visible ? mesh : null; }
    static void rebuildCurrentMesh() {
        if (currentGeometry != null) mesh=CFOverlayMesh.build(currentGeometry,
                CFGraphicsSettings.current().routeWidth());
    }

    @Override public void onInitializeClient() {
        CFGraphicsSettings.initialize(FabricLoader.getInstance().getConfigDir()
                .resolve("castlefight-graphics.properties"));
        // Clientbound payload must be registered before the PLAY connection.
        PayloadTypeRegistry.clientboundPlay().register(CFOverlayPayload.TYPE, CFOverlayPayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(CFTowerCatalogPayload.TYPE, CFTowerCatalogPayload.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(CFBuildRequestPayload.TYPE, CFBuildRequestPayload.CODEC);
        ClientPlayNetworking.registerGlobalReceiver(CFTowerCatalogPayload.TYPE, (payload, context) -> {
            try {
                CFBuildWire.Catalog catalog=CFBuildWire.readCatalog(payload.bytes());
                context.client().execute(()->CFBuildClient.onCatalog(catalog));
            } catch(java.io.IOException ex) {
                System.err.println("[КФ КЛИЕНТ] Неверный каталог башен: "+ex.getMessage());
            }
        });
        PayloadTypeRegistry.serverboundPlay().register(CFMobStatsRequestPayload.TYPE, CFMobStatsRequestPayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(CFMobStatsResponsePayload.TYPE, CFMobStatsResponsePayload.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(CFShotHelloPayload.TYPE, CFShotHelloPayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(CFShotVisualPayload.TYPE, CFShotVisualPayload.CODEC);
        ClientPlayNetworking.registerGlobalReceiver(CFShotVisualPayload.TYPE, (payload, context) -> {
            try {
                CFShotWire.Shot shot = CFShotWire.read(payload.bytes());
                context.client().execute(() -> CFArrowRenderer.accept(shot));
            } catch (java.io.IOException exception) {
                System.err.println("[КФ КЛИЕНТ] Ошибка пакета стрелы: " + exception.getMessage());
            }
        });
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
                        currentGeometry=ready;
                        rebuildCurrentMesh();
                        CFBuildClient.onGeometry(ready);
                        if (context.client().player != null)
                            context.client().gui.hud.setOverlayMessage(
                                    Component.literal("Разметка загружена · G — показать зоны"),false);
                    }
                } catch (Exception e) {
                    RECEIVER.reset(); currentGeometry = null; mesh = null; visible = false;
                    System.err.println("[КФ КЛИЕНТ] Некорректные данные разметки: " + e);
                }
            });
        });

        KeyMapping.Category category = KeyMapping.Category.register(
                Identifier.fromNamespaceAndPath("castlefight", "controls"));
        KeyMapping key = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.castlefight.toggle_overlay", InputConstants.Type.KEYSYM,
                InputConstants.KEY_G, category));
        KeyMapping graphicsKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.castlefight.graphics", InputConstants.Type.KEYSYM,
                InputConstants.KEY_O, category));
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            CFMobHover.tick(client);
            CFBuildClient.tick(client);
            CFArrowRenderer.tick();
            // Minestom advertises the opt-in channel in PLAY, after the join.
            // On unrelated servers, do not send anything or claim mod support.
            if (!shotHelloSent && client.getConnection() != null) {
                try {
                    if (ClientPlayNetworking.canSend(CFShotHelloPayload.TYPE)) {
                        ClientPlayNetworking.send(new CFShotHelloPayload());
                        shotHelloSent = true;
                    }
                } catch (RuntimeException ignored) { /* Server does not support CF shots. */ }
            }
            while (graphicsKey.consumeClick()) {
                client.gui.setScreen(new CFGraphicsScreen());
            }
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
        CFBuildClient.register();
        CFOverlayRenderer.register();
        CFArrowRenderer.register();
        CFMobHud.register();
    }

    private static void clear() {
        RECEIVER.reset(); currentGeometry=null; mesh=null; visible=false;
        CFMobHover.clear();
        CFArrowRenderer.clear();
        CFBuildClient.clear();
        shotHelloSent = false;
    }
}
