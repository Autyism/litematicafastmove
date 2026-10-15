package ml.mypals.litematicafastmove.mixin;

import fi.dy.masa.litematica.util.SchematicWorldRefresher;
import ml.mypals.litematicafastmove.mesh.PlacementMeshManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = SchematicWorldRefresher.class, remap = false)
public abstract class MixinSchematicWorldRefresher {

    // 渲染相关配置变化：独立网格按新配置完整重建
    @Inject(method = "updateAll", at = @At("HEAD"))
    private void onUpdateAll(CallbackInfo ci) {
        PlacementMeshManager.rebuildAll();
    }

    // 层范围变化会把范围内所有区块标记重绘；独立网格自己按区段裁剪，不跟随这些通知
    @Inject(method = {"updateBetweenX", "updateBetweenY", "updateBetweenZ"}, at = @At("HEAD"))
    private void suppressDirtyHead(int min, int max, CallbackInfo ci) {
        PlacementMeshManager.pushDirtySuppression();
    }

    @Inject(method = {"updateBetweenX", "updateBetweenY", "updateBetweenZ"}, at = @At("RETURN"))
    private void suppressDirtyReturn(int min, int max, CallbackInfo ci) {
        PlacementMeshManager.popDirtySuppression();
    }
}
