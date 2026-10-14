package ml.mypals.litematicafastmove.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.llamalad7.mixinextras.sugar.ref.LocalIntRef;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.RenderPass;
import fi.dy.masa.litematica.render.schematic.OverlayRenderType;
import fi.dy.masa.litematica.render.schematic.WorldRendererSchematic;
import ml.mypals.litematicafastmove.mesh.PlacementMeshManager;
import ml.mypals.litematicafastmove.sync.PendingMoveTracker;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.DynamicGpuData;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.util.profiling.ProfilerFiller;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector4fc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;
import java.util.Map;

@Mixin(value = WorldRendererSchematic.class, remap = false)
public class MixinWorldRendererSchematic {


    @ModifyVariable(method = "prepareBlockLayers", at = @At("LOAD"), name = "startedDrawing")
    private boolean appendDetachedPlacementDraws(boolean startedDrawing,
                                                 @Local(argsOnly = true) Matrix4fc modelView,
                                                 @Local(argsOnly = true, ordinal = 0) double cameraX,
                                                 @Local(argsOnly = true, ordinal = 1) double cameraY,
                                                 @Local(argsOnly = true, ordinal = 2) double cameraZ,
                                                 @Local(name = "transformValues") List<DynamicGpuData.Transform> transformValues,
                                                 @Local(name = "renderMap") Map<ChunkSectionLayer, List<RenderPass.Draw<GpuBufferSlice[]>>> renderMap,
                                                 @Local(name = "colorMod") Vector4fc colorMod,
                                                 @Local(name = "texMatrix") Matrix4f texMatrix,
                                                 @Local(name = "indexCount") LocalIntRef indexCount) {
        boolean added = PlacementMeshManager.appendDraws(renderMap, transformValues, modelView, colorMod, texMatrix,
                cameraX, cameraY, cameraZ, indexCount);
        return startedDrawing || added;
    }


    /**
     * 大型投影的 overlay 在各自的独立网格里，随投影当前原点绘制
     */
    @Inject(method = "renderBlockOverlay", at = @At("RETURN"))
    private void drawDetachedPlacementOverlays(OverlayRenderType type, Camera camera, float lineWidth, ProfilerFiller profiler, CallbackInfo ci) {
        PlacementMeshManager.drawOverlays(type, camera);
    }

    /**
     * 移动待提交期间：投影中的实体仍在 WorldSchematic 的旧位置，随区域一起平移
     */
    @WrapOperation(method = "renderEntities", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/entity/EntityRenderDispatcher;submit(Lnet/minecraft/client/renderer/entity/state/EntityRenderState;Lnet/minecraft/client/renderer/state/level/CameraRenderState;DDDLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;)V"))
    private void shiftPendingEntity(EntityRenderDispatcher dispatcher, EntityRenderState state, CameraRenderState cameraState,
                                    double x, double y, double z, PoseStack matrices, SubmitNodeCollector queue, Operation<Void> original) {
        if (PendingMoveTracker.hasAnyPending()) {
            Vec3 shift = PendingMoveTracker.getObjectShift(state.x, state.y, state.z);
            if (shift != null) {
                x += shift.x;
                y += shift.y;
                z += shift.z;
            }
        }
        original.call(dispatcher, state, cameraState, x, y, z, matrices, queue);
    }

    /**
     * 移动待提交期间：投影中的方块实体同样随区域一起平移
     */
    @Inject(method = "renderBlockEntities", at = @At(value = "INVOKE",
            target = "Lcom/mojang/blaze3d/vertex/PoseStack;translate(DDD)V", shift = At.Shift.AFTER))
    private void shiftPendingBlockEntity(Camera camera, Frustum frustum, PoseStack matrices, LevelRenderState renderStates,
                                         SubmitNodeCollector queue, ProfilerFiller profiler, CallbackInfo ci,
                                         @Local BlockEntityRenderState state) {
        if (!PendingMoveTracker.hasAnyPending()) return;

        BlockPos pos = state.blockPos;
        Vec3 shift = PendingMoveTracker.getObjectShift(pos.getX(), pos.getY(), pos.getZ());
        if (shift != null) {
            matrices.translate(shift.x, shift.y, shift.z);
        }
    }

    @Inject(method = "scheduleChunkRenders", at = @At("HEAD"))
    private void onScheduleChunkRenders(int chunkX, int chunkZ, boolean immediate, CallbackInfo ci) {
        PlacementMeshManager.markChunkDirty(chunkX, chunkZ);
    }

    @Inject(method = "loadRenderers", at = @At("RETURN"))
    private void onLoadRenderers(ProfilerFiller profiler, CallbackInfo ci) {
        PlacementMeshManager.rebuildAll();
    }
}
