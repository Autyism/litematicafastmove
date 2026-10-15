package ml.mypals.litematicafastmove.mixin;

import fi.dy.masa.litematica.util.SchematicWorldRefresher;
import ml.mypals.litematicafastmove.mesh.PlacementMeshManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = SchematicWorldRefresher.class, remap = false)
public abstract class MixinSchematicWorldRefresher {

    @Inject(method = "updateAll", at = @At("HEAD"))
    private void onUpdateAll(CallbackInfo ci) {
        PlacementMeshManager.rebuildAll();
    }
}
