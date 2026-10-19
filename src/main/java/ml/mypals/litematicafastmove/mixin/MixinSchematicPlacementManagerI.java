package ml.mypals.litematicafastmove.mixin;

import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacementManager;
import ml.mypals.litematicafastmove.access.PlacementAccessorRegistry;
import ml.mypals.litematicafastmove.config.FastMoveConfig;
import ml.mypals.litematicafastmove.mesh.PlacementMeshManager;
import ml.mypals.litematicafastmove.sync.IFastMoveManager;
import ml.mypals.litematicafastmove.sync.MoveInterpolator;
import ml.mypals.litematicafastmove.sync.PendingMoveTracker;
import ml.mypals.litematicafastmove.sync.PlacementSnapshot;
import net.minecraft.world.level.ChunkPos;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.HashSet;
import java.util.Set;

@Mixin(value = SchematicPlacementManager.class, remap = false)
public abstract class MixinSchematicPlacementManagerI implements IFastMoveManager {

    @Shadow @Final protected Set<ChunkPos> chunksPreChange;

    @Shadow abstract void onPostPlacementChange(SchematicPlacement placement);

    @Shadow public abstract void markChunkForRebuild(int cx, int cz);

    @Unique private PlacementSnapshot litematicafastmove$preChange;
    @Unique private boolean litematicafastmove$committing;

    @Inject(method = "onPrePlacementChange", at = @At("HEAD"))
    private void onPrePlacementChangeHead(SchematicPlacement placement, CallbackInfo ci) {
        this.litematicafastmove$preChange = placement != null ? PlacementSnapshot.of(placement) : null;
    }

    @Inject(method = "onPostPlacementChange", at = @At("HEAD"), cancellable = true)
    private void onPostPlacementChangeHead(SchematicPlacement placement, CallbackInfo ci) {
        PlacementSnapshot pre = this.litematicafastmove$preChange;
        this.litematicafastmove$preChange = null;

        if (placement == null || this.litematicafastmove$committing) return;

        // 失效旧访问器缓存，重新根据新原点计算相对坐标
        PlacementAccessorRegistry.invalidate(placement);

        PendingMoveTracker.MoveState state = PendingMoveTracker.get(placement);
        boolean pureTranslation = pre != null && pre.isPureTranslationTo(placement);

        // 纯平移的大型投影：独立网格只需改变换 (见 PlacementMeshManager)，什么都不用重建；
        // WorldSchematic 的重写推迟到停止移动后一次性提交。
        // 首次加载 (addSchematicPlacement) 不经过这里，开关/旋转/镜像/子区域编辑都走原版路径并重建独立网格。
        if (FastMoveConfig.isFastMoveEnabled() && pureTranslation && FastMoveConfig.shouldDeferCommit(placement)) {
            // chunksPreChange 此刻 = 移动前占据的区块，即 WorldSchematic 中数据实际所在的区块
            PendingMoveTracker.onMoved(placement, pre.origin(), new HashSet<>(this.chunksPreChange));
            ci.cancel();
            return;
        }

        if (state != null) {
            this.chunksPreChange.addAll(state.baseChunks);
            PendingMoveTracker.remove(placement);
            this.litematicafastmove$rebuildSkipped();
        }

        if (!pureTranslation) {
            PlacementMeshManager.requestBuild(placement);
        } else {
            PlacementMeshManager.markPlacementDirty(placement);
        }
    }

    @Inject(method = "addSchematicPlacement", at = @At("RETURN"))
    private void onAddSchematicPlacement(SchematicPlacement placement, boolean printMessages, CallbackInfo ci) {
        PlacementMeshManager.requestBuild(placement);
    }

    @Inject(method = "removeSchematicPlacement(Lfi/dy/masa/litematica/schematic/placement/SchematicPlacement;Z)Z", at = @At("HEAD"))
    private void onRemoveSchematicPlacement(SchematicPlacement placement, boolean update, CallbackInfoReturnable<Boolean> cir) {
        if (placement != null) {
            // 先让区块索引回到当前原点，原版的移除逻辑才能卸载正确的区块
            this.litematicafastmove$commit(placement);
            PlacementAccessorRegistry.invalidate(placement);
            PlacementMeshManager.dispose(placement);
        }
    }

    @Inject(method = "onToggleMainRendering", at = @At("HEAD"))
    private void onToggleMainRendering(boolean toggle, CallbackInfo ci) {
        this.litematicafastmove$commitAll();
    }

    @Inject(method = "markAllPlacementsOfSchematicForRebuild", at = @At("HEAD"))
    private void onMarkAllPlacementsOfSchematicForRebuild(LitematicaSchematic schematic, CallbackInfo ci) {
        this.litematicafastmove$commitAll();
        PlacementMeshManager.rebuildAllOf(schematic);
    }

    @Inject(method = "clear", at = @At("HEAD"))
    private void onClear(CallbackInfo ci) {
        PendingMoveTracker.clear();
        PlacementAccessorRegistry.clear();
        PlacementMeshManager.disposeAll();
        MoveInterpolator.clear();
    }

    @Override
    public void litematicafastmove$commit(SchematicPlacement placement) {
        PendingMoveTracker.MoveState state = PendingMoveTracker.remove(placement);
        if (state == null) return;

        if (!placement.getOrigin().equals(state.baseOrigin)) {
            this.chunksPreChange.clear();
            this.chunksPreChange.addAll(state.baseChunks);
            this.litematicafastmove$committing = true;
            try {
                this.onPostPlacementChange(placement);
            } finally {
                this.litematicafastmove$committing = false;
                this.chunksPreChange.clear();
            }
        }

        this.litematicafastmove$rebuildSkipped();
        PlacementMeshManager.markPlacementDirty(placement);
    }

    @Override
    public void litematicafastmove$commitAll() {
        for (SchematicPlacement placement : PendingMoveTracker.getPending()) {
            this.litematicafastmove$commit(placement);
        }
    }

    @Override
    public void litematicafastmove$commitSettled(long nowMs) {
        if (!PendingMoveTracker.hasAnyPending()) return;
        for (SchematicPlacement placement : PendingMoveTracker.getDueForCommit(nowMs, FastMoveConfig.COMMIT_DELAY_MS.getIntegerValue())) {
            this.litematicafastmove$commit(placement);
        }
    }

    @Unique
    private void litematicafastmove$rebuildSkipped() {
        for (ChunkPos pos : PendingMoveTracker.drainUnfrozenSkipped()) {
            this.markChunkForRebuild(pos.x, pos.z);
        }
    }
}
