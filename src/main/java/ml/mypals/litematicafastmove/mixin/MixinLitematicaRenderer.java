package ml.mypals.litematicafastmove.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.vertex.PoseStack;
import fi.dy.masa.litematica.render.IWorldSchematicRenderer;
import fi.dy.masa.litematica.render.LitematicaRenderer;
import ml.mypals.litematicafastmove.render.SchematicExit;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.chunk.ChunkSectionLayerGroup;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.util.profiling.ProfilerFiller;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(value = LitematicaRenderer.class, remap = false)
public class MixinLitematicaRenderer {

    @WrapMethod(method = "piecewiseDrawBlockLayerGroup")
    private void routeTerrain(RenderTarget fb, ChunkSectionLayerGroup group, Operation<Void> original) {
        SchematicExit.portal(() -> {
            original.call(SchematicExit.redirect(fb), group);
            if (group == ChunkSectionLayerGroup.OPAQUE) {
                SchematicExit.flushBatch();
            }
        });
    }

    @ModifyVariable(method = "piecewiseRenderEntities", at = @At("HEAD"), argsOnly = true)
    private SubmitNodeCollector routeEntities(SubmitNodeCollector queue) {
        return SchematicExit.batch(queue);
    }

    @WrapOperation(method = "piecewiseRenderBlockEntities", at = @At(value = "INVOKE",
            target = "Lfi/dy/masa/litematica/render/IWorldSchematicRenderer;renderBlockEntities(Lnet/minecraft/client/Camera;Lnet/minecraft/client/renderer/culling/Frustum;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/state/level/LevelRenderState;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/util/profiling/ProfilerFiller;)V"))
    private void routeBlockEntities(IWorldSchematicRenderer renderer, Camera camera, Frustum frustum, PoseStack matrices,
                                    LevelRenderState renderStates, SubmitNodeCollector queue, ProfilerFiller profiler,
                                    Operation<Void> original) {
        original.call(renderer, camera, frustum, matrices, renderStates, SchematicExit.batch(queue), profiler);
    }

    @WrapMethod(method = "piecewiseRenderOverlay")
    private void routeOverlay(ProfilerFiller profiler, Operation<Void> original) {
        SchematicExit.portal(() -> original.call(profiler));
    }
}
