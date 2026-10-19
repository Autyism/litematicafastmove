package ml.mypals.litematicafastmove.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.vertex.PoseStack;
import fi.dy.masa.litematica.render.IWorldSchematicRenderer;
import fi.dy.masa.litematica.render.LitematicaRenderer;
import ml.mypals.litematicafastmove.render.IrisFormat;
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

    // Litematica 0.26 在方块层组自己的目标上开渲染通道（开着光影时就是主目标），EXIT 的 portal 会把它挪到 EXIT 的目标上
    @WrapMethod(method = "piecewiseDrawBlockLayerGroup")
    private void routeTerrain(ChunkSectionLayerGroup group, @Nullable GpuSampler sampler, Operation<Void> original) {
        SchematicExit.portal(() -> {
            // 1.21.11 在不透明地形之后才提交实体和方块实体：在半透明地形之前先把它们画掉，免得实心部分盖住半透明地形
            if (group == ChunkSectionLayerGroup.TRANSLUCENT) {
                SchematicExit.flushBatch();
            }
            // 方块网格是原版格式（见 IrisFormat）
            IrisFormat.plainDraw(() -> original.call(group, sampler));
        });
    }

    @ModifyVariable(method = "piecewiseRenderEntities", at = @At("HEAD"), argsOnly = true)
    private SubmitNodeCollector routeEntities(SubmitNodeCollector queue) {
        return SchematicExit.batch(queue);
    }

    // 这里拿到的方块实体队列是原版的 SubmitNodeStorage，所以在调用处换成 batch
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
