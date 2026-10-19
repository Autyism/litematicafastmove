package ml.mypals.litematicafastmove.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.llamalad7.mixinextras.sugar.ref.LocalIntRef;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.systems.RenderPass;
import fi.dy.masa.litematica.render.schematic.OverlayRenderType;
import fi.dy.masa.litematica.render.schematic.WorldRendererSchematic;
import ml.mypals.litematicafastmove.mesh.PlacementMeshManager;
import ml.mypals.litematicafastmove.mesh.PlacementMeshRenderer;
import ml.mypals.litematicafastmove.sync.PendingMoveTracker;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.DynamicUniforms;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.state.CameraRenderState;
import net.minecraft.client.renderer.state.LevelRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.util.profiling.ProfilerFiller;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;

@Mixin(value = WorldRendererSchematic.class, remap = false)
public class MixinWorldRendererSchematic {


    @ModifyVariable(method = "prepareBlockLayers", at = @At("LOAD"), name = "startedDrawing")
    private boolean appendDetachedPlacementDraws(boolean startedDrawing,
                                                 @Local(argsOnly = true) Matrix4fc modelView,
                                                 @Local(argsOnly = true, ordinal = 0) double cameraX,
                                                 @Local(argsOnly = true, ordinal = 1) double cameraY,
                                                 @Local(argsOnly = true, ordinal = 2) double cameraZ,
                                                 @Local(name = "transformValues") ArrayList<DynamicUniforms.Transform> transformValues,
                                                 @Local(name = "renderMap") EnumMap<ChunkSectionLayer, List<RenderPass.Draw<GpuBufferSlice[]>>> renderMap,
                                                 @Local(name = "colorMod") Vector4f colorMod,
                                                 @Local(name = "texMatrix") Matrix4f texMatrix,
                                                 @Local(name = "indexCount") LocalIntRef indexCount) {
        boolean added = PlacementMeshRenderer.appendDraws(renderMap, transformValues, modelView, colorMod, texMatrix,
                cameraX, cameraY, cameraZ, indexCount);
        return startedDrawing || added;
    }

    @Inject(method = "renderBlockOverlay", at = @At("RETURN"))
    private void drawDetachedPlacementOverlays(OverlayRenderType type, Camera camera, float lineWidth, ProfilerFiller profiler, CallbackInfo ci) {
        PlacementMeshRenderer.drawOverlays(type, camera);
    }

    @WrapOperation(method = "renderEntities", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/entity/EntityRenderDispatcher;submit(Lnet/minecraft/client/renderer/entity/state/EntityRenderState;Lnet/minecraft/client/renderer/state/CameraRenderState;DDDLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;)V",
            remap = true))
    private void shiftPendingEntity(EntityRenderDispatcher dispatcher, EntityRenderState state, CameraRenderState cameraState,
                                    double x, double y, double z, PoseStack matrices, SubmitNodeCollector queue, Operation<Void> original) {
        if (PendingMoveTracker.hasAnyObjectShift()) {
            Vec3 shift = PendingMoveTracker.getObjectShift(state.x, state.y, state.z);
            if (shift != null) {
                x += shift.x;
                y += shift.y;
                z += shift.z;
            }
        }
        original.call(dispatcher, state, cameraState, x, y, z, matrices, queue);
    }

    @Inject(method = "renderBlockEntities", at = @At(value = "INVOKE",
            target = "Lcom/mojang/blaze3d/vertex/PoseStack;translate(DDD)V", shift = At.Shift.AFTER, remap = true))
    private void shiftPendingBlockEntity(Camera camera, Frustum frustum, PoseStack matrices, LevelRenderState renderStates,
                                         SubmitNodeCollector queue, ProfilerFiller profiler, CallbackInfo ci,
                                         @Local BlockEntityRenderState state) {
        if (!PendingMoveTracker.hasAnyObjectShift()) return;

        BlockPos pos = state.blockPos;
        Vec3 shift = PendingMoveTracker.getObjectShift(pos.getX(), pos.getY(), pos.getZ());
        if (shift != null) {
            matrices.translate(shift.x, shift.y, shift.z);
        }
    }

    @Inject(method = "setupTerrain", at = @At("HEAD"))
    private void captureFrustum(Camera camera, Frustum frustum, int frameCount, boolean playerSpectator, ProfilerFiller profiler, CallbackInfo ci) {
        PlacementMeshRenderer.captureFrustum(frustum);
    }

    @Inject(method = "loadRenderers", at = @At("RETURN"))
    private void onLoadRenderers(ProfilerFiller profiler, CallbackInfo ci) {
        PlacementMeshManager.rebuildAll();
    }
}
