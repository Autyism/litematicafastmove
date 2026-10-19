package ml.mypals.litematicafastmove.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.vertex.PoseStack;
import fi.dy.masa.litematica.render.IWorldSchematicRenderer;
import fi.dy.masa.litematica.render.LitematicaRenderer;
import ml.mypals.litematicafastmove.render.SchematicExit;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.chunk.ChunkSectionLayerGroup;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.state.LevelRenderState;
import net.minecraft.util.profiling.ProfilerFiller;
import org.jspecify.annotations.Nullable;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(value = LitematicaRenderer.class, remap = false)
public class MixinLitematicaRenderer {

    // Litematica 0.26 opens the passes on the group's own target (the main one while a shader pack is in use),
    // which EXIT's portal moves onto its target.
    @WrapMethod(method = "piecewiseDrawBlockLayerGroup")
    private void routeTerrain(ChunkSectionLayerGroup group, @Nullable GpuSampler sampler, Operation<Void> original) {
        SchematicExit.portal(() -> {
            // 1.21.11 submits the entities and block entities after the opaque terrain: draw them before the
            // translucent terrain, so their solid parts do not cover it
            if (group == ChunkSectionLayerGroup.TRANSLUCENT) {
                SchematicExit.flushBatch();
            }
            original.call(group, sampler);
        });
    }

    @ModifyVariable(method = "piecewiseRenderEntities", at = @At("HEAD"), argsOnly = true)
    private SubmitNodeCollector routeEntities(SubmitNodeCollector queue) {
        return SchematicExit.batch(queue);
    }

    // The block entities come as the game's SubmitNodeStorage here, so the batch goes in at the call instead.
    @WrapOperation(method = "piecewiseRenderBlockEntities", at = @At(value = "INVOKE",
            target = "Lfi/dy/masa/litematica/render/IWorldSchematicRenderer;renderBlockEntities"))
    private void routeBlockEntities(IWorldSchematicRenderer renderer, Camera camera, Frustum frustum, PoseStack matrices,
                                    LevelRenderState renderStates, SubmitNodeCollector queue, ProfilerFiller profiler,
                                    Operation<Void> original) {
        original.call(renderer, camera, frustum, matrices, renderStates, SchematicExit.batch(queue), profiler);
    }

    @WrapMethod(method = "piecewiseRenderOverlay")
    private void routeOverlay(Matrix4f posMatrix, Matrix4f projMatrix, ProfilerFiller profiler, Operation<Void> original) {
        SchematicExit.portal(() -> original.call(posMatrix, projMatrix, profiler));
    }
}
