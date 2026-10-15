package ml.mypals.litematicafastmove.mixin;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import fi.dy.masa.litematica.render.schematic.FluidModelRendererSchematic;
import fi.dy.masa.malilib.util.position.LayerRange;
import ml.mypals.litematicafastmove.mesh.PlacementMeshManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
@Mixin(value = FluidModelRendererSchematic.class, remap = false)
public abstract class MixinFluidModelRendererSchematic {

    @WrapOperation(method = {"shouldRenderTopFace", "shouldRenderBottomFace"}, require = 2,
            at = @At(value = "INVOKE", target = "Lfi/dy/masa/malilib/util/position/LayerRange;isPositionAtRenderEdgeOnSide(Lnet/minecraft/core/BlockPos;Lnet/minecraft/core/Direction;)Z"))
    private boolean skipRangeEdgeOnBuilderThread(LayerRange range, BlockPos pos, Direction side, Operation<Boolean> original) {
        if (PlacementMeshManager.isBuilderThread()){
            return false;
        }
        return original.call(range, pos, side);
    }
}
