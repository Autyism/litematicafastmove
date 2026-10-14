package ml.mypals.litematicafastmove.mixin;

import fi.dy.masa.litematica.schematic.placement.PlacementManagerTaskRebuild;
import ml.mypals.litematicafastmove.sync.PendingMoveTracker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = PlacementManagerTaskRebuild.class, remap = false)
public abstract class MixinPlacementManagerTaskRebuild {

    /**
     * 推迟提交期间，base 区块里的数据仍处于移动前坐标，而区块索引 / overlay 范围也还是旧的；
     * 原版重建会按当前原点往这些区块写入错位数据。所有来源 (区块加载、FIXER、其他投影变更) 的重建
     * 都在这里统一推迟，提交时再补做。
     */
    @Inject(method = "run", at = @At("HEAD"), cancellable = true)
    private void onRun(CallbackInfo ci) {
        if (!PendingMoveTracker.hasAnyPending()) return;

        PlacementManagerTaskAccessor task = (PlacementManagerTaskAccessor) this;
        int cx = task.litematicafastmove$getChunkX();
        int cz = task.litematicafastmove$getChunkZ();

        if (PendingMoveTracker.isFrozenChunk(cx, cz)) {
            PendingMoveTracker.markSkippedRebuild(cx, cz);
            ci.cancel();
        }
    }
}
