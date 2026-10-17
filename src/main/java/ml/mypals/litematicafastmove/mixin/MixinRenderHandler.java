package ml.mypals.litematicafastmove.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import fi.dy.masa.litematica.event.RenderHandler;
import ml.mypals.litematicafastmove.render.SchematicExit;
import net.minecraft.client.renderer.RenderBuffers;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.util.profiling.ProfilerFiller;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(value = RenderHandler.class, remap = false)
public class MixinRenderHandler {

    @WrapMethod(method = "onRenderWorldLast")
    private void routeBoxes(RenderTarget fb, CameraRenderState camera, Frustum frustum, RenderBuffers buffers,
                            GpuBufferSlice fog, Vector4f fogColor, ProfilerFiller profiler, Operation<Void> original) {
        SchematicExit.portal(() -> original.call(fb, camera, frustum, buffers, fog, fogColor, profiler));
    }
}
