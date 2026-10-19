package ml.mypals.litematicafastmove.mixin;

import ml.mypals.litematicafastmove.mesh.PlacementMeshManager;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientLevel.class)
public abstract class MixinClientLevel {

    @Inject(method = "setServerVerifiedBlockState", at = @At("HEAD"))
    private void onServerBlockUpdate(BlockPos pos, BlockState blockState, int updateFlag, CallbackInfo ci) {
        PlacementMeshManager.markBlockDirty(pos);
    }
}
