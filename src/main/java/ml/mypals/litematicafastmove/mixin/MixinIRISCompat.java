package ml.mypals.litematicafastmove.mixin;

import fi.dy.masa.litematica.compat.iris.IrisCompat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = IrisCompat.class, remap = false)
public class MixinIRISCompat {

    @Inject(method = "registerPipelines", at = @At("HEAD"), cancellable = true)
    private static void supressLitematicaIrisCompatRegistration(CallbackInfo ci) {
        ci.cancel();
    }
}
