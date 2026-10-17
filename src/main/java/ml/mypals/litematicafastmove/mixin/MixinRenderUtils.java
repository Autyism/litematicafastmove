package ml.mypals.litematicafastmove.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.mojang.blaze3d.pipeline.RenderTarget;
import fi.dy.masa.malilib.render.RenderUtils;
import ml.mypals.litematicafastmove.render.SchematicExit;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(value = RenderUtils.class, remap = false)
public class MixinRenderUtils {

    @ModifyReturnValue(method = "mainTarget", at = @At("RETURN"))
    private static RenderTarget routeMainTarget(RenderTarget original) {
        return SchematicExit.redirect(original);
    }
}
