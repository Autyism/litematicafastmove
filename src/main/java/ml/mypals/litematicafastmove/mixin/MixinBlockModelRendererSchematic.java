package ml.mypals.litematicafastmove.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import fi.dy.masa.litematica.render.schematic.BlockModelRendererSchematic;
import fi.dy.masa.malilib.util.LayerRange;
import ml.mypals.litematicafastmove.mesh.MeshSectionBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(value = BlockModelRendererSchematic.class, remap = false)
public abstract class MixinBlockModelRendererSchematic {

    @WrapOperation(method = "shouldRenderModelSide",
            at = @At(value = "INVOKE", target = "Lfi/dy/masa/malilib/util/LayerRange;isPositionAtRenderEdgeOnSide"))
    private static boolean skipRangeEdgeOnBuilderThread(LayerRange range, BlockPos pos, Direction side, Operation<Boolean> original) {
        if (MeshSectionBuilder.isBuilderThread()) return false;
        return original.call(range, pos, side);
    }
}
