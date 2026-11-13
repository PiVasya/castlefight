package dev.cf.client;

import dev.cf.protocol.CFBuildWire;
import dev.cf.protocol.CFOverlayWire;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.entity.player.Player;
import java.io.IOException;

/**
 * Tower dossier: inventory right click only. Placement: world right click only.
 * Neither UI nor rejected preflight actions send a packet. The server validates
 * every accepted request independently; these checks are only traffic filters.
 */
public final class CFBuildClient {
    private static CFBuildWire.Catalog catalog;
    private static CFBuildPreview preview;
    private static PendingDetails pendingDetails;
    private static long lastBuildSent;

    private record PendingDetails(CFBuildWire.Tower tower, Screen previous) { }
    private CFBuildClient() { }

    static void onCatalog(CFBuildWire.Catalog c) { catalog=c; }
    static CFBuildWire.Tower tower(String id) {
        if (catalog!=null) for(var t:catalog.towers()) if(t.id().equals(id)) return t;
        return null;
    }
    static String team() { return catalog==null ? "blue" : catalog.team(); }
    static void onGeometry(CFOverlayWire.Geometry g) { preview=new CFBuildPreview(g); }
    static void clear() { catalog=null; preview=null; pendingDetails=null; lastBuildSent=0; }

    /** Pure item recognition: inventory hover and held item use the same catalog. */
    static boolean looksLikeTower(ItemStack stack) {
        if(stack.isEmpty())return false;
        String name=stack.getHoverName().getString();
        return name.startsWith("ТЬМА | ") || name.startsWith("СНЕГИ | ");
    }
    static CFBuildWire.Tower resolve(ItemStack stack) {
        if(stack.isEmpty() || catalog==null) return null;
        String name=stack.getHoverName().getString();
        for(var t:catalog.towers()) if(t.name().equals(name)) return t;
        return null;
    }
    private static CFBuildWire.Tower selected(Player player, InteractionHand hand) {
        if(hand!=InteractionHand.MAIN_HAND || player.isSpectator())return null;
        return resolve(player.getItemInHand(hand));
    }

    /** Called only from a right-click on an actual slot of the player's inventory. */
    static void queueInventoryDetails(ItemStack item, Screen inventoryScreen) {
        CFBuildWire.Tower definition=resolve(item);
        if(definition==null){notice("Данные башен ещё загружаются");return;}
        pendingDetails=new PendingDetails(definition,inventoryScreen);
    }
    /** Open after input processing; never change the screen while vanilla handles a click. */
    static void tick(Minecraft mc) {
        PendingDetails request=pendingDetails;
        if(request==null)return;
        pendingDetails=null;
        if(mc.player!=null && mc.gui.screen()==request.previous())
            mc.gui.setScreen(new CFTowerScreen(request.tower(),request.previous()));
    }

    public static void register() {
        CFInventoryTowerMenu.register();
        UseBlockCallback.EVENT.register((player,level,hand,hit)->{
            if(!level.isClientSide() || hand!=InteractionHand.MAIN_HAND || player.isSpectator())
                return InteractionResult.PASS;
            var held=player.getItemInHand(hand);
            var definition=selected(player,hand);
            if(definition==null){
                if(looksLikeTower(held)){
                    notice("Данные башен ещё загружаются");
                    return InteractionResult.FAIL;
                }
                return InteractionResult.PASS;
            }
            // A world click NEVER opens the description screen. It only tries
            // placement if ALL local checks pass. Invalid clicks send nothing.
            tryBuild(definition,hit);
            return InteractionResult.FAIL;
        });
        UseItemCallback.EVENT.register((player,level,hand)->{
            if(!level.isClientSide() || hand!=InteractionHand.MAIN_HAND || player.isSpectator())
                return InteractionResult.PASS;
            if(looksLikeTower(player.getItemInHand(hand))) {
                notice("Для постройки нажми ПКМ по центру площадки 3×3");
                return InteractionResult.FAIL;
            }
            return InteractionResult.PASS;
        });
    }

    private static void tryBuild(CFBuildWire.Tower definition, BlockHitResult hit) {
        Minecraft mc=Minecraft.getInstance();
        if(mc.player==null || mc.level==null || catalog==null){notice("Мир ещё загружается");return;}
        if(hit.getDirection()!=net.minecraft.core.Direction.UP){
            notice("Неверная позиция — нажми сверху на центр");return;
        }
        if(preview==null){notice("Разметка ещё загружается");return;}
        BlockPos center=hit.getBlockPos().above();
        if(!definition.race().equals(catalog.race())) {notice("Неверная раса");return;}
        if(mc.player.distanceToSqr(center.getX()+0.5,center.getY(),center.getZ()+0.5)>64) {
            notice("Слишком далеко — максимум 8 блоков");return;
        }
        CFBuildPreview.Result position=preview.check(catalog.team(),
                center.getX(),center.getY(),center.getZ());
        if(!position.valid()){notice(position.reason());return;}
        // Future economy hook: NOT_IMPLEMENTED is deliberately not a fake
        // positive balance check. Server-side currency must be added together
        // with a client balance snapshot before this can become authoritative.
        switch(CFBuildEconomy.check(definition)) {
            case NOT_AFFORDABLE -> {notice("Недостаточно ресурсов");return;}
            case BALANCE_UNKNOWN -> {notice("Баланс ещё загружается");return;}
            case NO_ECONOMY, AFFORDABLE -> { /* no economy implemented yet */ }
        }
        // All 9 foundation cells and the full 3-high footprint must be clear.
        for(int dx=-1;dx<=1;dx++)for(int dz=-1;dz<=1;dz++) {
            BlockPos floor=center.offset(dx,-1,dz);
            if(!mc.level.hasChunkAt(floor)) {notice("Участок мира ещё не загружен");return;}
            if(!mc.level.getBlockState(floor).isSolid()) {
                notice("Нужен твёрдый ровный пол");return;
            }
            for(int dy=0;dy<3;dy++)if(!mc.level.getBlockState(center.offset(dx,dy,dz)).isAir()) {
                notice("Место занято блоками");return;
            }
        }
        if(!mc.level.getBlockState(center.above(3)).isAir()) {
            notice("Освободи блок над башней");return;
        }
        if(!ClientPlayNetworking.canSend(CFBuildRequestPayload.TYPE)) {
            notice("Сервер не поддерживает этот режим строительства");return;
        }
        // Cheap anti-double-click gate. One packet only after all checks pass.
        long now=System.nanoTime();
        if(now-lastBuildSent<300_000_000L)return;
        try {
            CFBuildWire.Build request=new CFBuildWire.Build(definition.id(),
                    center.getX(),center.getY(),center.getZ());
            ClientPlayNetworking.send(new CFBuildRequestPayload(CFBuildWire.build(request)));
            lastBuildSent=now;
        } catch(IOException e) {notice("Не удалось подготовить запрос на строительство");}
    }
    static void notice(String message) {
        Minecraft mc=Minecraft.getInstance();
        if(mc.player!=null)mc.gui.hud.setOverlayMessage(Component.literal(message),false);
    }
}
