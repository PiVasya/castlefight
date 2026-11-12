package dev.cf.client.mixin;

import dev.cf.client.render.CFOverlayRenderer;
import dev.cf.client.render.CFArrowRenderer;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
public class GameRendererMixin {
    @Inject(method="close",at=@At("RETURN"))
    private void cfOverlayClose(CallbackInfo ci) { CFOverlayRenderer.close();
        CFArrowRenderer.close(); }
}
