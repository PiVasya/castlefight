package dev.cf.client;

import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;
import dev.cf.client.mixin.CFContainerOriginAccessor;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.world.inventory.Slot;

/**
 * Only RMB on a CF tower item inside the player's open inventory opens the
 * dossier. The click is consumed BEFORE vanilla splits/picks up the stack;
 * ordinary left clicks, inventory movement and non-CF items are untouched.
 */
public final class CFInventoryTowerMenu {
    private CFInventoryTowerMenu() { }

    public static void register() {
        ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
            if(!(screen instanceof InventoryScreen inventory)) return;
            ScreenMouseEvents.allowMouseClick(screen).register((opened,event) -> {
                if(event.button()!=1 || client.player==null || client.player.isSpectator())return true;
                if(!inventory.getMenu().getCarried().isEmpty())return true;
                CFContainerOriginAccessor origin=(CFContainerOriginAccessor)inventory;
                int left=origin.cf$getLeftPos();
                int top=origin.cf$getTopPos();
                for(Slot slot:inventory.getMenu().slots) {
                    // Recompute the slot under the actual click instead of using
                    // last-frame hoveredSlot (which can be stale on mouse movement).
                    if(CFInventorySlotHit.contains(event.x(),event.y(),
                            left,top,slot.x,slot.y)) {
                        if(!CFBuildClient.looksLikeTower(slot.getItem()))return true;
                        CFBuildClient.queueInventoryDetails(slot.getItem(),inventory);
                        return false;
                    }
                }
                return true;
            });
        });
    }
}
