package dev.cf.client.mixin;

import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Minecraft 26.2 keeps the inventory GUI origin as protected fields, not
 * getLeftPos()/getTopPos() methods. Read the actual origin so recipe-book
 * layout shifts and different GUI scales do not break inventory RMB.
 */
@Mixin(AbstractContainerScreen.class)
public interface CFContainerOriginAccessor {
    @Accessor("leftPos")
    int cf$getLeftPos();

    @Accessor("topPos")
    int cf$getTopPos();
}
